package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.App;

import java.util.List;
import java.util.Optional;

/**
 * 应用（助手）仓储 SPI。由 infra-persistence 实现（读 ai_app 表）。
 */
public interface AppRepository {

    Optional<App> find(Long tenantId, Long appId);

    /** 应用绑定的知识库 ID 列表（RAG 检索范围，按租户隔离） */
    List<Long> knowledgeBaseIds(Long tenantId, Long appId);

    /** 将应用绑定到知识库（RAG 生效前提） */
    void bindKnowledgeBase(Long tenantId, Long appId, Long kbId);
}
