package com.aihub.ai.domain.spi;

import java.util.function.Supplier;

/**
 * 指标埋点端口（domain 侧抽象）。
 *
 * <p>为什么需要它：编排层（application）想统计「入库各阶段耗时」这类指标，
 * 但指标实现（Micrometer）属于基础设施，application 依赖它会被 ArchUnit 拦下。
 * 因此在 domain 定义这个最小端口，实现放在 infra。
 *
 * <p>实现必须是<b>安全的</b>：指标上报失败绝不能影响主流程，
 * 且不得改变被包装动作的返回值与异常。
 */
public interface MetricsRecorder {

    /** 记录某阶段耗时，并原样透传返回值与异常 */
    <T> T timeStage(String stage, Supplier<T> action);

    /** 计数：如「某维度配额被拦截」 */
    void count(String name, String... tags);

    /**
     * 空实现：未接入指标系统（如单元测试、精简部署）时使用，
     * 让调用方无需到处判空。
     */
    MetricsRecorder NOOP = new MetricsRecorder() {
        @Override
        public <T> T timeStage(String stage, Supplier<T> action) {
            return action.get();
        }

        @Override
        public void count(String name, String... tags) {
            // 无操作
        }
    };
}
