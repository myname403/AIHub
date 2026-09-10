package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.ChatTurn;
import com.aihub.ai.domain.model.MessageReference;
import com.aihub.ai.domain.model.RetrievedChunk;
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
 * 基于 Spring AI 的对话执行器（ChatExecutor 的 infra 实现）。
 *
 * <p>Spring AI 的所有类型被限制在本包内——业务层只依赖 domain 的 ChatExecutor 接口，
 * 将来升级 Spring AI 2.0 时只需改这里。
 *
 * <p>RAG 集成：应用绑定知识库后，检索 → 注入受限上下文 → 发出 rag.sources 引用事件。
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
            ChatClient client = chatClientFactory.create(turn.tenantId(), turn.appId(), turn.scene());
            Augmented augmented = augment(turn, null, new int[]{0});
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
                    turn.tenantId(), turn.appId(), turn.scene());
            int tokenIn = tokenOf(response, true);
            int tokenOut = tokenOf(response, false);
            aiMetrics.recordChat(turn.scene(), true, cost, modelCode, tokenIn, tokenOut);
            return new StreamResult(content == null ? "" : content, modelCode, cost, tokenIn, tokenOut);
        } catch (RuntimeException e) {
            // 失败也要计数与计时，否则成功率与耗时分位数都是失真的
            aiMetrics.recordChat(turn.scene(), false, System.currentTimeMillis() - start,
                    chatClientFactory.resolvedModelCode(turn.tenantId(), turn.appId(), turn.scene()),
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
        ChatClient client = chatClientFactory.create(turn.tenantId(), turn.appId(), turn.scene());
        String memoryKey = DbChatMemory.key(turn.tenantId(), turn.conversationId());
        long start = System.currentTimeMillis();
        int[] index = {0};
        StringBuilder content = new StringBuilder();

        sink.emit(StreamEvent.of(index[0]++, StreamEvent.MSG_START,
                Map.of("conversationId", turn.conversationId())));

        // RAG：检索 + 注入 + 引用事件（在 msg.start 之后、token 之前发出）
        Augmented augmented = augment(turn, sink, index);

        // 流式最后一个 ChatResponse 携带 usage，用作本次调用的 token 统计依据
        final org.springframework.ai.chat.model.ChatResponse[] last = {null};
        try {
            client.prompt()
                    .user(augmented.prompt())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, memoryKey)
                            .param(AuditAdvisor.CTX_TENANT, turn.tenantId())
                            .param(AuditAdvisor.CTX_APP, turn.appId())
                            .param(AuditAdvisor.CTX_SCENE, turn.scene()))
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        last[0] = response;
                        if (response != null && response.getResult() != null
                                && response.getResult().getOutput() != null) {
                            String token = response.getResult().getOutput().getText();
                            if (token != null) {
                                content.append(token);
                                sink.emit(StreamEvent.token(index[0]++, token));
                            }
                        }
                    })
                    .blockLast();

            long cost = System.currentTimeMillis() - start;
            String modelCode = chatClientFactory.resolvedModelCode(
                    turn.tenantId(), turn.appId(), turn.scene());
            // 记忆 Advisor 已在流结束时把助手消息落库，此刻才能把引用挂到该消息上
            referenceStore.save(turn.tenantId(), turn.conversationId(), augmented.references());
            int tokenIn = tokenOf(last[0], true);
            int tokenOut = tokenOf(last[0], false);
            aiMetrics.recordChat(turn.scene(), true, cost, modelCode, tokenIn, tokenOut);
            sink.emit(StreamEvent.end(index[0]++, cost, modelCode));
            return new StreamResult(content.toString(), modelCode, cost, tokenIn, tokenOut);
        } catch (Exception e) {
            log.error("流式对话失败 tenant={} conv={}", turn.tenantId(), turn.conversationId(), e);
            aiMetrics.recordChat(turn.scene(), false, System.currentTimeMillis() - start,
                    chatClientFactory.resolvedModelCode(turn.tenantId(), turn.appId(), turn.scene()),
                    0, 0);
            sink.emit(StreamEvent.error(index[0]++,
                    String.valueOf(com.aihub.common.result.ResultCode.MODEL_UNAVAILABLE.getCode()),
                    "模型调用失败，请稍后重试"));
            throw e;
        } finally {
            AiCallContext.clear();
            sink.close();
        }
    }

    /**
     * RAG 增强：检索知识库 → 拼装受限上下文 → 发出 rag.sources 事件。
     * 未绑定知识库或未命中时返回原始问题、引用列表为空。
     */
    private Augmented augment(ChatTurn turn, StreamSink sink, int[] index) {
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
                List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(
                        turn.tenantId(), kbId, turn.userText(),
                        ragProperties.getTopK(), ragProperties.getSimilarityThreshold());
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
            if (sink != null) {
                sink.emit(StreamEvent.of(index[0]++, StreamEvent.RAG_SOURCES, Map.of("sources", sources)));
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
}
