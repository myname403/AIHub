package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.ChatMessage;

import java.util.List;

/**
 * 会话记忆存储 SPI（★ 扩展点）。
 *
 * <p>由 infra-persistence 实现（持久化到 ai_message）。
 * infra-ai 的 Spring AI 适配层通过它读写记忆，从而满足
 * 「infra 模块间禁止横向依赖」的架构卡口；将来可换 Redis / 向量化摘要实现。
 */
public interface ConversationMemoryStore {

    void append(Long tenantId, Long conversationId, List<ChatMessage> messages);

    /** 按时间正序返回全部历史（窗口截取由适配层完成） */
    List<ChatMessage> history(Long tenantId, Long conversationId);

    void clear(Long tenantId, Long conversationId);
}
