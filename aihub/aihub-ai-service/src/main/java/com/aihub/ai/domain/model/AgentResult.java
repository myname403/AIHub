package com.aihub.ai.domain.model;

import java.util.List;

/**
 * Agent 执行结果。
 */
public record AgentResult(
        /** done / budget_exceeded / failed */
        String status,
        String answer,
        List<ArtifactInfo> artifacts
) {
    public static final String DONE = "done";
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    public static final String FAILED = "failed";

    public static AgentResult done(String answer, List<ArtifactInfo> artifacts) {
        return new AgentResult(DONE, answer, artifacts);
    }
}
