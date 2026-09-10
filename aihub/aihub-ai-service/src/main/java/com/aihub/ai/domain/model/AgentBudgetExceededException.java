package com.aihub.ai.domain.model;

/**
 * 预算耗尽（步数 / Token / 超时）时抛出，由 Agent 转为 {@link AgentResult#BUDGET_EXCEEDED}。
 */
public class AgentBudgetExceededException extends RuntimeException {

    public AgentBudgetExceededException(String message) {
        super(message);
    }
}
