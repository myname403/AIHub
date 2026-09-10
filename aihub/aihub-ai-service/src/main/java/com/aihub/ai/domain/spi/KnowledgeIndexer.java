package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.Chunk;

import java.util.List;

/**
 * 知识向量化索引 SPI（★ 扩展点）。
 *
 * <p>由 infra-ai 实现（内部使用 Spring AI VectorStore + EmbeddingModel）。
 * 实现必须强制写入 tenant_id / kb_id / doc_id 元数据，检索侧据此做租户过滤。
 */
public interface KnowledgeIndexer {

    /** 将分片向量化并写入向量库，返回成功写入数量 */
    int index(Long tenantId, Long kbId, Long docId, List<Chunk> chunks);
}
