package com.aihub.ai.domain.model;

/**
 * Agent 任务（对齐课程 MyManus：入口任务可拆解为子任务链）。
 */
public record AgentTask(
        Long tenantId,
        Long userId,
        Long appId,
        String conversationId,
        String goal
) {

    /** 派生子任务（复用租户与会话上下文） */
    public AgentTask subTask(String subGoal) {
        return new AgentTask(tenantId, userId, appId, conversationId, subGoal);
    }
}
