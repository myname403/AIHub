package com.aihub.ai.domain.model;

import java.util.List;

/**
 * Agent 执行结果。
 *
 * <p><b>modelCode 为什么在这里：</b>Agent 链路的最终答案不走模型流式返回，
 * 而是编排层拿到 {@code AgentResult.answer} 后一次性补发 token——
 * msg.end 事件帧里的模型编码只能从结果对象带回。此前该字段缺失，
 * ChatAppService 只能传空串，前端"本次回答所用模型"标签永远不显示
 * （修这个 bug 不能让编排层反向依赖 infra 的模型工厂，所以让结果对象捎带，
 * 由 BaseAgent 在 LLM 调用时通过 StreamBridge 记录、PlanningAgent 汇总时填入）。
 */
public record AgentResult(
        /** done / budget_exceeded / canceled / failed */
        String status,
        String answer,
        List<ArtifactInfo> artifacts,
        /** 本次任务实际使用的模型编码（可能为 null：无 LLM 调用即失败/取消的任务） */
        String modelCode
) {
    public static final String DONE = "done";
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    /** 用户主动中断（M4 DoD：停止后后续步骤真正取消） */
    public static final String CANCELED = "canceled";
    public static final String FAILED = "failed";

    /** 三参兼容构造（无模型信息：取消/预算终止/未走到 LLM 的失败路径） */
    public AgentResult(String status, String answer, List<ArtifactInfo> artifacts) {
        this(status, answer, artifacts, null);
    }

    public static AgentResult done(String answer, List<ArtifactInfo> artifacts) {
        return new AgentResult(DONE, answer, artifacts);
    }
}
