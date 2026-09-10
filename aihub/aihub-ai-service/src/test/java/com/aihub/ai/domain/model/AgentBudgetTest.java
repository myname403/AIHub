package com.aihub.ai.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBudgetTest {

    @Test
    void tokensAccumulateAcrossSubTasks() {
        AgentBudget budget = new AgentBudget(3, 100, 60_000);
        budget.addTokens(40, 30);
        assertEquals(70, budget.usedTokens());
        assertFalse(budget.tokenExceeded());

        budget.addTokens(20, 20);
        assertEquals(110, budget.usedTokens());
        assertTrue(budget.tokenExceeded(), "超出 maxTokens 后应判定为预算耗尽");
    }

    @Test
    void tokenLimitDisabledWhenNotPositive() {
        AgentBudget budget = new AgentBudget(3, 0, 60_000);
        budget.addTokens(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
        assertFalse(budget.tokenExceeded(), "maxTokens<=0 表示不限制");
    }

    @Test
    void timeoutIsTriggeredAfterDeadline() {
        AgentBudget budget = new AgentBudget(3, 0, 30);
        assertFalse(budget.timeout());
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(budget.timeout(), "超过 timeoutMs 后应判定超时");
    }

    @Test
    void nonPositiveValuesFallBackToUnlimited() {
        AgentBudget budget = new AgentBudget(0, 0, 0);
        assertEquals(Integer.MAX_VALUE, budget.maxSubTasks());
        assertEquals(Long.MAX_VALUE, budget.timeoutMs());
        assertFalse(budget.timeout());
    }

    @Test
    void subTaskSharesTheSameBudget() {
        AgentBudget budget = new AgentBudget(2, 500, 60_000);
        AgentTask root = new AgentTask(1001L, 1L, 9001L, "conv-1", "目标", budget);
        AgentTask sub = root.subTask("子任务");

        sub.budget().addTokens(10, 10);
        assertEquals(20, root.budget().usedTokens(),
                "子任务与父任务必须共享预算，否则三重预算会失效");
    }
}
