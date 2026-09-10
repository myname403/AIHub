package com.aihub.ai.domain.model;

/**
 * Agent 名称常量（★ 定义在领域层）。
 *
 * <p>为什么必须有这个类：名称是编排层（application）按名派发 Agent 的唯一凭据，
 * 若把常量定义在 infra 的 Agent 实现类上，application 就必须 import infra —— 这违反
 * 「application 只依赖 domain」的架构卡口。放在 domain 后，编排层与 infra 各自引用同一份常量，
 * 依赖方向始终朝内。
 */
public final class AgentNames {

    /** 任务规划 Agent：拆解目标并派发 */
    public static final String PLANNING = "planning";

    /** 表格生成 Agent */
    public static final String TABLE = "table";

    /** 图表生成 Agent（ECharts） */
    public static final String CHART = "chart";

    /** 网页 / 文档生成 Agent（默认兜底） */
    public static final String HTML = "html";

    private AgentNames() {
    }
}
