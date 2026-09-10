package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.DocumentInfo;

import java.util.List;

/**
 * 知识库仓储 SPI。由 infra-persistence 实现（ai_knowledge_base）。
 */
public interface KnowledgeBaseRepository {

    Long create(Long tenantId, String name, String embeddingModelCode, int vectorDim, String indexName);

    List<DocumentInfo> list(Long tenantId);

    boolean exists(Long tenantId, Long kbId);
}
