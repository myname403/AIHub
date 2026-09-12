package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.App;
import com.aihub.ai.domain.model.ModelEndpoint;
import com.aihub.ai.domain.model.ModelRoute;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.domain.spi.ModelConfigRepository;
import com.aihub.ai.domain.spi.ModelGateway;
import com.aihub.ai.domain.spi.ToolCallLogStore;
import com.aihub.ai.infra.ai.config.ChatClientProperties;
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
 * ChatClient 装配工厂（★ 扩展点）—— 本服务最核心的"装配车间"。
 *
 * <p><b>Spring AI 核心概念速成（读本类前先懂这些）：</b>
 * <ul>
 *   <li><b>ChatModel</b>：一次模型调用的底层封装（指向哪家 API、什么密钥）——相当于"电话线"；</li>
 *   <li><b>ChatClient</b>：构建在 ChatModel 之上的高级客户端，可挂系统提示词、记忆 Advisor、
 *       工具 —— 相当于"装好了通讯录和助理的电话机"；</li>
 *   <li><b>Advisor</b>：请求/响应的拦截器链（AOP 思想）。本项目挂了两个：
 *       MessageChatMemoryAdvisor（自动把历史对话注入上下文）+ AuditAdvisor（审计留痕）；</li>
 *   <li><b>ToolCallback</b>：给模型注册的"可调用函数"（function calling）——模型判断需要时
 *       会让框架执行这个函数并把结果喂回模型。@Tool 注解的方法会被 MethodToolCallbackProvider 收集。</li>
 * </ul>
 *
 * <p><b>为什么要工厂 + 缓存：</b>每个 (租户, 应用, 场景) 组合的模型配置、系统提示词、
 * 挂载的工具都可能不同，ChatClient 不能全局共享一个；但每次对话都重新装配又太贵
 * （要查库、要构建对象图），所以按 key 缓存 + TTL 过期（默认重装，配置改了自动生效）。
 *
 * <p>纯函数式装配：应用配置 -> ChatClient，按 (tenant, app, scene) 缓存，
 * 缓存带 TTL 以支持 Nacos / DB 配置变更后的自动刷新（无需重启）。
 * 详见学习文档《05-AI服务-aihub-ai-service.md》。
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
    /** 浏览器工具（可选）：aihub.browser.enabled=false 时容器里没有这个 bean，自动缺席 */
    private final ObjectProvider<com.aihub.ai.infra.ai.tools.BrowserTools> browserToolsProvider;
    private final com.aihub.ai.infra.metrics.AiMetrics aiMetrics;
    /** 装配缓存 TTL（@ConfigurationProperties，Nacos 配置变更自动重绑定，改 TTL 无需重启） */
    private final ChatClientProperties chatClientProperties;

    // 敏感凭据保留 @Value：它是部署期注入的密钥而非可热调参数，不应随 Nacos 动态变化
    @Value("${aihub.security.data-key:aihub-dev-data-key}")
    private String dataKey;

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
                             KnowledgeTools knowledgeTools,
                             ObjectProvider<com.aihub.ai.infra.ai.tools.BrowserTools> browserToolsProvider,
                             com.aihub.ai.infra.metrics.AiMetrics aiMetrics,
                             ChatClientProperties chatClientProperties) {
        this.modelGateway = modelGateway;
        this.configRepository = configRepository;
        this.appRepository = appRepository;
        this.resolver = resolver;
        this.defaultChatModel = defaultChatModel;
        this.chatMemory = chatMemory;
        this.toolCallLogStore = toolCallLogStore;
        this.externalToolProviders = externalToolProviders;
        this.knowledgeTools = knowledgeTools;
        this.browserToolsProvider = browserToolsProvider;
        this.aiMetrics = aiMetrics;
        this.chatClientProperties = chatClientProperties;
    }

    /**
     * 获取（或装配）ChatClient。
     * <p>双重检查锁（double-checked locking）经典范式：
     * 先无锁查缓存（绝大多数请求走这条快路径）→ 未命中才 synchronized → 再查一次
     * （防止两个线程同时通过第一次检查后重复装配）→ 装配并放入缓存。
     * 注意缓存 key 是 (tenant, app, scene) 三元组拼接的字符串。
     */
    public ChatClient create(Long tenantId, Long appId, String scene) {
        String key = tenantId + ":" + appId + ":" + scene;
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        // 每次都读当前 TTL：Nacos 重绑定后，下一次过期判断即用新值（惰性生效）
        long ttlMillis = chatClientProperties.getCacheSeconds() * 1000;
        if (entry == null || now - entry.createdAt() > ttlMillis) {
            synchronized (cache) {
                entry = cache.get(key);
                if (entry == null || now - entry.createdAt() > ttlMillis) {
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
        // 内置工具：时间、知识库固定挂载；浏览器工具仅在能力开启时存在
        List<Object> toolObjects = new ArrayList<>(List.of(new TimeTools(), knowledgeTools));
        com.aihub.ai.infra.ai.tools.BrowserTools browserTools = browserToolsProvider.getIfAvailable();
        if (browserTools != null) {
            toolObjects.add(browserTools);
        }
        for (ToolCallback callback : MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks()) {
            callbacks.add(new AuditedToolCallback(callback, toolCallLogStore, "local", aiMetrics));
        }
        externalToolProviders.orderedStream().forEach(provider -> {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                callbacks.add(new AuditedToolCallback(callback, toolCallLogStore, "mcp", aiMetrics));
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
