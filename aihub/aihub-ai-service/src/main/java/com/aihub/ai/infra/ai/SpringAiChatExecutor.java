package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.ChatTurn;
import com.aihub.ai.domain.model.MessageReference;
import com.aihub.ai.domain.model.RetrievedChunk;
import com.aihub.ai.domain.model.SequencedSink;
import com.aihub.ai.domain.model.StreamBridge;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.domain.spi.ChatExecutor;
import com.aihub.ai.domain.spi.KnowledgeRetriever;
import com.aihub.ai.domain.spi.MessageReferenceStore;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.infra.metrics.AiMetrics;
import com.aihub.ai.infra.ai.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI 的对话执行器（ChatExecutor 的 infra 实现）—— 对话的"引擎室"。
 *
 * <p><b>在架构中的位置：</b>domain 定义了 ChatExecutor 接口（"能对话"），
 * 本类用 Spring AI 实现它。Spring AI 的所有类型被限制在本包内——业务层只依赖
 * domain 的 ChatExecutor 接口，将来升级 Spring AI 2.0 时只需改这里（防腐层思想）。
 *
 * <p><b>同步（call）与流式（stream）的差异：</b>
 * <ul>
 *   <li>call()：一次请求等完整响应，适合后端间调用；</li>
 *   <li>stream()：响应是 Flux（事件流），doOnNext 对每个到达的 token 增量做处理
 *       （拼全文 + 发 StreamEvent.token 给前端），blockLast() 阻塞等流结束。
 *       这是"响应式数据源 + 阻塞消费"的混合用法——因为本服务是 MVC（Servlet 线程模型），
 *       在自建线程池里阻塞是安全的。</li>
 * </ul>
 *
 * <p><b>RAG 是什么（零基础必读）：</b>Retrieval-Augmented Generation，检索增强生成。
 * 模型不知道你的私有资料，所以在提问前先去向量库"检索"最相关的知识片段，
 * 拼进提示词让模型"开卷考试"，并用防幻觉约束命令它只依据资料作答。
 * 见 augment() 方法：查应用绑定的知识库 → 检索 topK 片段 → 拼装受限上下文
 * → 发 rag.sources 事件（前端渲染引用角标）。
 *
 * <p><b>AiCallContext / AuditAdvisor 的 param 传递：</b>租户/应用/场景通过
 * advisors(a -> a.param(...)) 传给 Advisor 链 —— Advisor 在请求线程里读这些参数写审计日志。
 * 详见学习文档《05-AI服务-aihub-ai-service.md》。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiChatExecutor implements ChatExecutor {

    private final ChatClientFactory chatClientFactory;
    private final AppRepository appRepository;
    private final KnowledgeRetriever knowledgeRetriever;
    private final MessageReferenceStore referenceStore;
    private final AiMetrics aiMetrics;

    /** RAG 检索参数（@ConfigurationProperties，Nacos 配置变更自动重绑定，改配置无需重启） */
    private final RagProperties ragProperties;

    @Override
    public StreamResult call(ChatTurn turn) {
        AiCallContext.set(turn.tenantId(), turn.appId(), turn.scene(), turn.conversationId());
        long start = System.currentTimeMillis();
        try {
            ChatClient client = chatClientFactory.create(turn.tenantId(), turn.appId(), turn.scene(), turn.modelCode());
            Augmented augmented = augment(turn, null);
            org.springframework.ai.chat.model.ChatResponse response = client.prompt()
                    .user(augmented.prompt())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID,
                                    DbChatMemory.key(turn.tenantId(), turn.conversationId()))
                            .param(AuditAdvisor.CTX_TENANT, turn.tenantId())
                            .param(AuditAdvisor.CTX_APP, turn.appId())
                            .param(AuditAdvisor.CTX_SCENE, turn.scene()))
                    .call()
                    .chatResponse();
            long cost = System.currentTimeMillis() - start;
            String content = response == null || response.getResult() == null
                    ? "" : response.getResult().getOutput().getText();
            referenceStore.save(turn.tenantId(), turn.conversationId(), augmented.references());
            String modelCode = chatClientFactory.resolvedModelCode(
                    turn.tenantId(), turn.appId(), turn.scene(), turn.modelCode());
            int tokenIn = tokenOf(response, true);
            int tokenOut = tokenOf(response, false);
            aiMetrics.recordChat(turn.scene(), true, cost, modelCode, tokenIn, tokenOut);
            return new StreamResult(content == null ? "" : content, modelCode, cost, tokenIn, tokenOut);
        } catch (RuntimeException e) {
            // 失败也要计数与计时，否则成功率与耗时分位数都是失真的
            aiMetrics.recordChat(turn.scene(), false, System.currentTimeMillis() - start,
                    chatClientFactory.resolvedModelCode(turn.tenantId(), turn.appId(), turn.scene(), turn.modelCode()),
                    0, 0);
            throw e;
        } finally {
            AiCallContext.clear();
        }
    }

    /** 从模型响应中取 token 用量；模型未返回 usage 时返回 0（配额与计费按 0 处理，不阻断主流程） */
    private Integer tokenOf(org.springframework.ai.chat.model.ChatResponse response, boolean prompt) {
        if (response == null || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return 0;
        }
        var usage = response.getMetadata().getUsage();
        Integer value = prompt ? usage.getPromptTokens() : usage.getCompletionTokens();
        return value == null ? 0 : value;
    }

    @Override
    public StreamResult stream(ChatTurn turn, StreamSink sink) {
        AiCallContext.set(turn.tenantId(), turn.appId(), turn.scene(), turn.conversationId());
        // 序号化 sink：本会话所有事件帧（含工具事件）由它统一编号，全局唯一递增
        SequencedSink seq = SequencedSink.of(sink);
        // 桥设置给同步链兜底；真正的跨线程通道靠 prompt 级 toolContext（见下）
        StreamBridge.set(seq);
        ChatClient client = chatClientFactory.create(turn.tenantId(), turn.appId(), turn.scene(), turn.modelCode());
        String memoryKey = DbChatMemory.key(turn.tenantId(), turn.conversationId());
        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();

        seq.emit(StreamEvent.MSG_START, java.util.Map.of("conversationId", turn.conversationId()));

        // RAG：检索 + 注入 + 引用事件（在 msg.start 之后、token 之前发出）
        Augmented augmented = augment(turn, seq);

        // 流式最后一个 ChatResponse 携带 usage，用作本次调用的 token 统计依据
        final org.springframework.ai.chat.model.ChatResponse[] last = {null};
        try {
            client.prompt()
                    .user(augmented.prompt())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey)
                            .param(AuditAdvisor.CTX_TENANT, turn.tenantId())
                            .param(AuditAdvisor.CTX_APP, turn.appId())
                            .param(AuditAdvisor.CTX_SCENE, turn.scene()))
                    // ★ 工具事件跨线程主通道：.stream() 的工具执行发生在 Reactor 调度线程，
                    // ThreadLocal 不跨线程——ToolContext 随 prompt 携带桥，与线程无关。
                    // 没有它，普通对话里模型调用知识检索/时间工具时前端完全无感知。
                    .toolContext(StreamBridge.toolContextMap())
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        last[0] = response;
                        if (response != null && response.getResult() != null
                                && response.getResult().getOutput() != null) {
                            var output = response.getResult().getOutput();
                            // 推理模型的“思考”增量：Spring AI 把 reasoning_content 放进 AssistantMessage 的 metadata，
                            // key 为 reasoningContent（智谱等兼容供应商字段名一致；Ollama 用 reasoning 不会被解析）。
                            Object reasoning = output.getMetadata() == null
                                    ? null : output.getMetadata().get("reasoningContent");
                            if (reasoning != null && !reasoning.toString().isEmpty()) {
                                seq.emit(StreamEvent.REASONING,
                                        java.util.Map.of("content", reasoning.toString()));
                            }
                            String token = output.getText();
                            if (token != null) {
                                content.append(token);
                                seq.emit(StreamEvent.TOKEN, java.util.Map.of("content", token));
                            }
                        }
                    })
                    .blockLast();

            long cost = System.currentTimeMillis() - start;
            String modelCode = chatClientFactory.resolvedModelCode(
                    turn.tenantId(), turn.appId(), turn.scene(), turn.modelCode());
            // 记忆 Advisor 已在流结束时把助手消息落库，此刻才能把引用挂到该消息上
            referenceStore.save(turn.tenantId(), turn.conversationId(), augmented.references());
            int tokenIn = tokenOf(last[0], true);
            int tokenOut = tokenOf(last[0], false);
            aiMetrics.recordChat(turn.scene(), true, cost, modelCode, tokenIn, tokenOut);
            seq.emit(StreamEvent.MSG_END, java.util.Map.of("costMs", cost, "model", modelCode));
            return new StreamResult(content.toString(), modelCode, cost, tokenIn, tokenOut);
        } catch (Exception e) {
            log.error("流式对话失败 tenant={} conv={}", turn.tenantId(), turn.conversationId(), e);
            aiMetrics.recordChat(turn.scene(), false, System.currentTimeMillis() - start,
                    chatClientFactory.resolvedModelCode(turn.tenantId(), turn.appId(), turn.scene(), turn.modelCode()),
                    0, 0);
            seq.emit(StreamEvent.ERROR,
                    java.util.Map.of("code", String.valueOf(com.aihub.common.result.ResultCode.MODEL_UNAVAILABLE.getCode()),
                            "message", "模型调用失败，请稍后重试"));
            throw e;
        } finally {
            AiCallContext.clear();
            StreamBridge.clear();
            sink.close();
        }
    }

    /**
     * RAG 增强：检索知识库 → 拼装受限上下文 → 发出 rag.sources 事件。
     * 未绑定知识库或未命中时返回原始问题、引用列表为空。
     */
    private Augmented augment(ChatTurn turn, SequencedSink seq) {
        try {
            List<Long> kbIds = appRepository.knowledgeBaseIds(turn.tenantId(), turn.appId());
            if (kbIds == null || kbIds.isEmpty()) {
                return Augmented.plain(turn.userText());
            }
            List<Map<String, Object>> sources = new ArrayList<>();
            List<MessageReference> references = new ArrayList<>();
            StringBuilder context = new StringBuilder();
            int ref = 1;
            for (Long kbId : kbIds) {
                List<RetrievedChunk> chunks = retrieveWithTimeout(turn, kbId);
                for (RetrievedChunk chunk : chunks) {
                    context.append('[').append(ref).append("] ")
                            .append(chunk.content()).append("\n\n");
                    sources.add(Map.of(
                            "index", ref,
                            "kbId", chunk.kbId() == null ? 0L : chunk.kbId(),
                            "docId", chunk.docId() == null ? 0L : chunk.docId(),
                            "docName", StringUtils.hasText(chunk.docName()) ? chunk.docName() : "",
                            "score", chunk.score()));
                    references.add(MessageReference.of(ref, chunk));
                    ref++;
                }
            }
            if (sources.isEmpty()) {
                return Augmented.plain(turn.userText());
            }
            if (seq != null) {
                seq.emit(StreamEvent.RAG_SOURCES, Map.of("sources", sources));
            }
            // 防幻觉约束（源自课程天机助手提示词）：只依据知识库内容作答
            return new Augmented("请仅依据下方知识库内容回答用户问题；"
                    + "若内容不足以回答，请明确说明未检索到相关信息，禁止编造。"
                    + "回答末尾以 [n] 标注引用来源。\n\n"
                    + "【知识库内容】\n" + context
                    + "\n【用户问题】\n" + turn.userText(), references);
        } catch (Exception e) {
            log.warn("RAG 检索失败，退回普通对话 conv={}", turn.conversationId(), e);
            return Augmented.plain(turn.userText());
        }
    }

    /** RAG 增强结果：注入模型的提示词 + 本次命中的引用来源 */
    private record Augmented(String prompt, List<MessageReference> references) {
        static Augmented plain(String prompt) {
            return new Augmented(prompt, List.of());
        }
    }

    /**
     * 带超时保护的检索（3 秒）。超时/失败一律返回空列表 → 上层降级为普通对话。
     *
     * <p><b>为什么要超时（本期真实事故）：</b>检索前需要先调用<b>嵌入模型</b>把问题向量化；
     * 未配置嵌入模型时 Spring AI 默认连 api.openai.com，网络不通会无限阻塞，
     * 导致绑定过知识库的应用（如种子应用 9001）所有对话都卡在"思考中"。
     * AI 主链路绝不能被可选能力（RAG）拖死——降级放行是硬性要求。
     */
    private List<RetrievedChunk> retrieveWithTimeout(ChatTurn turn, Long kbId) {
        try {
            return java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> knowledgeRetriever.retrieve(
                            turn.tenantId(), kbId, turn.userText(),
                            ragProperties.getTopK(), ragProperties.getSimilarityThreshold()))
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("RAG 检索超时（3s），降级为普通对话 kb={}", kbId);
            return List.of();
        } catch (Exception e) {
            log.warn("RAG 检索失败，降级为普通对话 kb={} err={}", kbId, e.getMessage());
            return List.of();
        }
    }
}
