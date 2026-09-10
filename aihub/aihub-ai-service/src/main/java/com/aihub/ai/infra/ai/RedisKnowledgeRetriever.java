package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.RetrievedChunk;
import com.aihub.ai.domain.spi.DocumentRepository;
import com.aihub.ai.domain.spi.KnowledgeRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识检索实现（Spring AI VectorStore）。
 *
 * <p>★ 租户隔离的实现位置：
 * <ol>
 *   <li>过滤器强制注入 tenant_id（调用方无法绕过）——防线之二</li>
 *   <li>返回后逐条校验元数据租户，不匹配即丢弃并告警——防线之三（TenantGuard）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKnowledgeRetriever implements KnowledgeRetriever {

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;

    @Override
    public List<RetrievedChunk> retrieve(Long tenantId, Long kbId, String query, int topK, double threshold) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        StringBuilder filter = new StringBuilder("tenant_id == '").append(tenantId).append("'");
        if (kbId != null) {
            filter.append(" && kb_id == '").append(kbId).append("'");
        }

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1))
                .similarityThreshold(Math.max(0d, Math.min(threshold, 1d)))
                .filterExpression(filter.toString())
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);
        List<RetrievedChunk> result = new ArrayList<>();
        if (docs == null) {
            return result;
        }
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            // ★ TenantGuard：出口逐条校验，不匹配即丢弃并告警
            if (!String.valueOf(tenantId).equals(String.valueOf(meta.get("tenant_id")))) {
                log.warn("TenantGuard 拦截跨租户向量! expect={} actual={} docId={}",
                        tenantId, meta.get("tenant_id"), meta.get("doc_id"));
                continue;
            }
            Long docId = parseLong(meta.get("doc_id"));
            Long chunkKbId = parseLong(meta.get("kb_id"));
            result.add(new RetrievedChunk(
                    doc.getText() == null ? "" : doc.getText(),
                    doc.getScore() == null ? 0d : doc.getScore(),
                    chunkKbId,
                    docId,
                    docName(tenantId, docId)));
        }
        return result;
    }

    private String docName(Long tenantId, Long docId) {
        if (docId == null) {
            return "";
        }
        return documentRepository.find(tenantId, docId)
                .map(info -> info.name())
                .orElse("");
    }

    private Long parseLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
