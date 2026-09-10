package com.aihub.ai.domain.model;

/**
 * 一次对话请求的领域载体。
 *
 * <p>租户 ID 来自 TenantContext（唯一可信来源），绝不由前端传入。
 */
public record ChatTurn(
        Long tenantId,
        Long userId,
        Long appId,
        String conversationId,
        String userText,
        /** chat / rag / agent-plan */
        String scene
) {
    public static ChatTurn of(Long tenantId, Long userId, Long appId,
                              String conversationId, String userText) {
        return new ChatTurn(tenantId, userId, appId, conversationId, userText, "chat");
    }
}
