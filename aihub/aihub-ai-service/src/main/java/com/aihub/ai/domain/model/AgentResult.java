package com.aihub.ai.domain.model;

import java.util.List;

/**
 * Agent 执行结果。
 */
public record AgentResult(
        /** done / budget_exceeded / canceled / failed */
        String status,
        String answer,
        List<ArtifactInfo> artifacts
) {
    public static final String DONE = "done";
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    /** 用户主动中断（M4 DoD：停止后后续步骤真正取消） */
    public static final String CANCELED = "canceled";
    public static final String FAILED = "failed";

    public static AgentResult done(String answer, List<ArtifactInfo> artifacts) {
        return new AgentResult(DONE, answer, artifacts);
    }
}
