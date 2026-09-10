package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.App;
import com.aihub.ai.domain.model.ModelEndpoint;
import com.aihub.ai.domain.model.ModelRoute;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.domain.spi.ModelConfigRepository;
import com.aihub.ai.domain.spi.ModelGateway;
import com.aihub.ai.domain.spi.ToolCallLogStore;
import com.aihub.ai.infra.ai.tools.AuditedToolCallback;
import com.aihub.ai.infra.ai.tools.KnowledgeTools;
import com.aihub.ai.infra.ai.tools.TimeTools;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 装配工厂（★ 扩展点）。
 *
 * <p>纯函数式装配：应用配置 -> ChatClient，按 (tenant, app, scene) 缓存，
 * 缓存带 TTL 以支持 Nacos / DB 配置变更后的自动刷新（无需重启）。
 */
@Slf4j
@Component
public class ChatClientFactory {

    private final ModelGateway modelGateway;
    private final ModelConfigRepository configRepository;
    private final AppRepository appRepository;
    private final OpenAiCompatibleChatModelResolver resolver;
    private final ObjectProvider<ChatModel> defaultChatModel;
    private final DbChatMemory chatMemory;
    private final ToolCallLogStore toolCallLogStore;
    /** 外部工具提供者（MCP Client starter 自动装配的 ToolCallbackProvider） */
    private final ObjectProvider<ToolCallbackProvider> externalToolProviders;
    private final KnowledgeTools knowledgeTools;

    @Value("${aihub.security.data-key:aihub-dev-data-key}")
    private String dataKey;

    @Value("${aihub.chat-client.cache-seconds:60}")
    private long cacheSeconds;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是 AIHub 智能助手，请用简体中文回答，回答需准确、简洁。";

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(ChatClient client, String modelCode, long createdAt) {
    }

    public ChatClientFactory(ModelGateway modelGateway,
                             ModelConfigRepository configRepository,
                             AppRepository appRepository,
                             OpenAiCompatibleChatModelResolver resolver,
                             ObjectProvider<ChatModel> defaultChatModel,
                             com.aihub.ai.infra.ai.DbChatMemory chatMemory,
                             ToolCallLogStore toolCallLogStore,
                             ObjectProvider<ToolCallbackProvider> externalToolProviders,
                             KnowledgeTools knowledgeTools) {
        this.modelGateway = modelGateway;
        this.configRepository = configRepository;
        this.appRepository = appRepository;
        this.resolver = resolver;
        this.defaultChatModel = defaultChatModel;
        this.chatMemory = chatMemory;
        this.toolCallLogStore = toolCallLogStore;
        this.externalToolProviders = externalToolProviders;
        this.knowledgeTools = knowledgeTools;
    }

    public ChatClient create(Long tenantId, Long appId, String scene) {
        String key = tenantId + ":" + appId + ":" + scene;
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.createdAt() > cacheSeconds * 1000) {
            synchronized (cache) {
                entry = cache.get(key);
                if (entry == null || now - entry.createdAt() > cacheSeconds * 1000) {
                    entry = build(tenantId, appId, scene);
                    cache.put(key, entry);
                }
            }
        }
        return entry.client();
    }

    /** 最近一次装配使用的模型编码（用于事件帧与用量记录） */
    public String resolvedModelCode(Long tenantId, Long appId, String scene) {
        CacheEntry entry = cache.get(tenantId + ":" + appId + ":" + scene);
        return entry == null ? "" : entry.modelCode();
    }

    private CacheEntry build(Long tenantId, Long appId, String scene) {
        ChatModel chatModel = resolveModel(tenantId, scene);
        String modelCode = currentModelCode(tenantId, scene);

        String systemPrompt = appRepository.find(tenantId, appId)
                .map(App::getSystemPrompt)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_SYSTEM_PROMPT);

        ChatClient client = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 审计 Advisor：call() 与 stream() 双路径都留痕（见 06 号文档 R9）
                        new AuditAdvisor())
                // 工具（M3）：内置工具 + MCP 外部工具，统一包装审计后挂载
                .defaultToolCallbacks(buildToolCallbacks())
                .build();
        log.info("ChatClient 装配完成 tenant={} app={} scene={} model={}", tenantId, appId, scene, modelCode);
        return new CacheEntry(client, modelCode, System.currentTimeMillis());
    }

    /**
     * 装配工具回调：内置 @Tool 工具 + MCP 外部工具，逐个套上审计包装。
     *
     * <p>MCP 侧：只依赖 {@link ToolCallbackProvider} 抽象，不直接引用 MCP 类型——
     * 未接入任何 MCP Server 时 orderedStream() 为空，功能自然降级为「仅内置工具」。
     */
    private List<ToolCallback> buildToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallback callback : MethodToolCallbackProvider.builder()
                .toolObjects(new TimeTools(), knowledgeTools)
                .build()
                .getToolCallbacks()) {
            callbacks.add(new AuditedToolCallback(callback, toolCallLogStore, "local"));
        }
        externalToolProviders.orderedStream().forEach(provider -> {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                callbacks.add(new AuditedToolCallback(callback, toolCallLogStore, "mcp"));
            }
        });
        if (!callbacks.isEmpty()) {
            log.info("工具挂载完成 count={}", callbacks.size());
        }
        return callbacks;
    }

    /** 主模型优先，失败自动降级到备用模型，两者皆缺则用 Spring Boot 自动装配的默认模型 */
    private ChatModel resolveModel(Long tenantId, String scene) {
        ModelRoute route = modelGateway.routeFor(tenantId, scene);
        ChatModel lastError = null;
        for (String code : new String[]{route.primaryModelCode(), route.fallbackModelCode()}) {
            if (!StringUtils.hasText(code)) {
                continue;
            }
            var endpoint = configRepository.findEndpoint(tenantId, code);
            if (endpoint.isEmpty()) {
                continue;
            }
            try {
                return resolver.build(endpoint.get(), dataKey);
            } catch (Exception e) {
                log.warn("模型构建失败，尝试降级 model={}", code, e);
            }
        }
        ChatModel fallback = defaultChatModel.getIfAvailable();
        if (fallback == null) {
            throw new BizException(ResultCode.MODEL_UNAVAILABLE, "无可用模型（请配置 ai_model / spring.ai.openai）");
        }
        return fallback;
    }

    private String currentModelCode(Long tenantId, String scene) {
        ModelRoute route = modelGateway.routeFor(tenantId, scene);
        if (StringUtils.hasText(route.primaryModelCode())
                && configRepository.findEndpoint(tenantId, route.primaryModelCode()).isPresent()) {
            return route.primaryModelCode();
        }
        if (StringUtils.hasText(route.fallbackModelCode())
                && configRepository.findEndpoint(tenantId, route.fallbackModelCode()).isPresent()) {
            return route.fallbackModelCode();
        }
        return route.primaryModelCode();
    }
}
