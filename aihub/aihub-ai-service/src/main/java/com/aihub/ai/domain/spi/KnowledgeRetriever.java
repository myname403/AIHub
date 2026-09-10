package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.RetrievedChunk;

import java.util.List;

/**
 * 知识检索 SPI（★ 扩展点 + ★ 租户隔离唯一入口）。
 *
 * <p>四道防线之一：业务代码禁止直接注入 VectorStore，
 * 所有检索必须经过本接口，由实现强制注入租户过滤（见 03 号文档 8.2 节）。
 */
public interface KnowledgeRetriever {

    /**
     * @param tenantId  租户 ID（来自上下文，绝不接受前端传参）
     * @param kbId      知识库 ID
     * @param query     查询文本
     * @param topK      召回条数
     * @param threshold 相似度阈值 0~1
     */
    List<RetrievedChunk> retrieve(Long tenantId, Long kbId, String query, int topK, double threshold);
}
