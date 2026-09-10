package com.aihub.ai.infra.ai.tools;

import com.aihub.ai.domain.model.RetrievedChunk;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.domain.spi.KnowledgeRetriever;
import com.aihub.ai.infra.ai.AiCallContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具（M3 · 让模型能自主检索，而��只能被动接收注入的上下文）。
 *
 * <p>RAG 的常规形态是「先检索再拼上下文」，但面对多轮追问或需要查证的场景，
 * 让模型自己决定「什么时候查、查什么」效果更好——即 Agentic RAG。
 *
 * <p>租户安全：检索入口仍是 {@link KnowledgeRetriever}（租户隔离唯一入口），
 * 工具方法不接收任何租户参数，全部从调用上下文解析。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeTools {

    private final KnowledgeRetriever knowledgeRetriever;
    private final AppRepository appRepository;

    @Tool(description = "检索知识库：当用户问到需要依据文档内容回答的问题时调用，返回最相关的原文片段")
    public String searchKnowledge(
            @ToolParam(description = "检索关键词或用户的原始问题") String query) {
        Long tenantId = AiCallContext.tenantId();
        Long appId = AiCallContext.appId();
        if (tenantId == null || appId == null) {
            return "当前调用上下文缺少租户或应用信息，无法检索知识库";
        }
        List<Long> kbIds = appRepository.knowledgeBaseIds(tenantId, appId);
        if (kbIds == null || kbIds.isEmpty()) {
            return "当前应用未绑定知识库";
        }
        StringBuilder answer = new StringBuilder();
        int ref = 1;
        for (Long kbId : kbIds) {
            List<RetrievedChunk> chunks = knowledgeRetriever.retrieve(tenantId, kbId, query, 4, 0.3);
            for (RetrievedChunk chunk : chunks) {
                answer.append('[').append(ref++).append("] ")
                        .append(chunk.content())
                        .append('\n');
            }
        }
        if (answer.length() == 0) {
            return "未检索到相关内容";
        }
        return answer.toString();
    }
}
