package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.Chunk;
import com.aihub.ai.domain.spi.KnowledgeIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识向量化索引实现（Spring AI VectorStore）。
 *
 * <p>入库时强制写入租户元数据——这是租户隔离的数据源头，
 * 缺失该元数据的向量在检索侧会被 TenantGuard 丢弃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKnowledgeIndexer implements KnowledgeIndexer {

    private final VectorStore vectorStore;

    @Override
    public int index(Long tenantId, Long kbId, Long docId, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        List<Document> documents = chunks.stream()
                .map(chunk -> new Document(
                        chunk.content(),
                        Map.of(
                                "tenant_id", String.valueOf(tenantId),
                                "kb_id", String.valueOf(kbId),
                                "doc_id", String.valueOf(docId))))
                .toList();
        vectorStore.add(documents);
        log.info("知识入库完成 tenant={} kb={} doc={} chunks={}", tenantId, kbId, docId, documents.size());
        return documents.size();
    }
}
