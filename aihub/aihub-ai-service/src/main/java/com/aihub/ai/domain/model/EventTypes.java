package com.aihub.ai.domain.model;

/**
 * 领域事件类型常量（发件箱 event_type 列取值）。
 *
 * <p>与 {@link AgentNames} 同样的理由：常量集中放在 domain，
 * 避免 application / infra 各自硬编码字符串导致口径漂移
 * （而且 {@code static final String} 会被编译期内联，分散定义时 ArchUnit 也查不出来）。
 */
public final class EventTypes {

    /** 用量上报：AI 服务 → 平台服务（token 消耗、耗时、模型），失败时由发件箱兜底重投 */
    public static final String USAGE_REPORT = "usage.report";

    private EventTypes() {
    }
}
