package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.AgentTask;

/**
 * Agent 任务与步骤的持久化 SPI（M4）。
 *
 * <p>落库的价值：任务可追溯（谁在什么时候跑了什么）、步骤可回放、
 * 以及为 checkpoint / 断点续跑留出数据基础。
 */
public interface AgentTaskRepository {

    /** 创建任务记录，返回任务 ID */
    Long create(AgentTask task, String strategy);

    /** 记录一个执行步骤（think / act / observe） */
    void appendStep(Long tenantId, Long taskId, int seq, String type, String agentName,
                    String content, long costMs);

    /** 结束任务：status = done / budget_exceeded / canceled / failed */
    void finish(Long taskId, String status, String answer, long costMs, long usedTokens);
}
