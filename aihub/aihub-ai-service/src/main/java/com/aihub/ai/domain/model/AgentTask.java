package com.aihub.ai.domain.model;

/**
 * Agent 任务（对齐课程 MyManus：入口任务可拆解为子任务链）。
 *
 * @param budget 整条子任务链共享的预算，可为 null（由 PlanningAgent 在入口填充）
 */
public record AgentTask(
        Long tenantId,
        Long userId,
        Long appId,
        String conversationId,
        String goal,
        AgentBudget budget
) {

    public AgentTask(Long tenantId, Long userId, Long appId, String conversationId, String goal) {
        this(tenantId, userId, appId, conversationId, goal, null);
    }

    /** 派生子任务（复用租户、会话上下文与同一份预算） */
    public AgentTask subTask(String subGoal) {
        return new AgentTask(tenantId, userId, appId, conversationId, subGoal, budget);
    }

    /** 携带预算的副本（入口 Agent 填充预算后继续传递） */
    public AgentTask withBudget(AgentBudget newBudget) {
        return new AgentTask(tenantId, userId, appId, conversationId, goal, newBudget);
    }
}
