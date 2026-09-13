package com.aihub.ai.domain.model;

/**
 * 一次对话请求的领域载体。
 *
 * <p>租户 ID 来自 TenantContext（唯一可信来源），绝不由前端传入。
 *
 * <p>{@code modelCode} 是<b>可选</b>的模型覆盖：前端模型切换器指定时优使用该模型，
 * 为空则走管理端配置的场景路由（ai_model_route）。这样"管理员配默认、用户可选切换"两层并存。
 */
public record ChatTurn(
        Long tenantId,
        Long userId,
        Long appId,
        String conversationId,
        String userText,
        /** chat / rag / agent-plan */
        String scene,
        /** 可选：指定模型编码（如 qwen3.5:0.8b），空 = 走场景路由 */
        String modelCode
) {
    public static ChatTurn of(Long tenantId, Long userId, Long appId,
                              String conversationId, String userText) {
        return new ChatTurn(tenantId, userId, appId, conversationId, userText, "chat", null);
    }
}
