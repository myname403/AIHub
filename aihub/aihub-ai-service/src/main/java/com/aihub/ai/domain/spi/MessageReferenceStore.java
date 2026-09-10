package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.MessageReference;

import java.util.List;

/**
 * 引用来源落库（RAG 溯源持久化）。
 *
 * <p>实现方负责把引用挂到「该会话最近一条 assistant 消息」上——
 * 因为消息由 ChatMemory Advisor 在模型调用前后写入，业务层拿不到消息 ID。
 */
public interface MessageReferenceStore {

    /**
     * @param tenantId      租户
     * @param conversationId 会话 ID（conv_key）
     * @param references    引用列表，为空时直接返回
     */
    void save(Long tenantId, String conversationId, List<MessageReference> references);

    /** 按会话查询引用（按消息与序号排序，供历史回看） */
    List<MessageReference> list(Long tenantId, String conversationId);
}
