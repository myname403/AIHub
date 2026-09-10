package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.App;

import java.util.List;
import java.util.Optional;

/**
 * 应用（助手）仓储 SPI。由 infra-persistence 实现（读 ai_app 表）。
 *
 * <p>所有方法都要求显式传 tenantId：仓储层不做「猜租户」，
 * 避免某处忘了注入上下文就悄悄读到别人的数据。
 */
public interface AppRepository {

    Optional<App> find(Long tenantId, Long appId);

    /** 列出本租户的全部应用（管理端用，按 id 倒序即新建在前） */
    List<App> list(Long tenantId);

    /** 新建应用，返回新 ID */
    Long create(App app);

    /** 按 (tenantId, appId) 更新，命名空间不匹配时返回 false（越权防护） */
    boolean update(Long tenantId, App app);

    /** 软删，命名空间不匹配时返回 false */
    boolean delete(Long tenantId, Long appId);

    /** 应用绑定的知识库 ID 列表（RAG 检索范围，按租户隔离） */
    List<Long> knowledgeBaseIds(Long tenantId, Long appId);

    /** 将应用绑定到知识库（RAG 生效前提） */
    void bindKnowledgeBase(Long tenantId, Long appId, Long kbId);

    /** 解除绑定；返回是否确实删掉了一条 */
    boolean unbindKnowledgeBase(Long tenantId, Long appId, Long kbId);
}
