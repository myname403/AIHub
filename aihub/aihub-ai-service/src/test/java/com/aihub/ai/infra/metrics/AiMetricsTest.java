package com.aihub.ai.infra.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标组件测试：用 SimpleMeterRegistry 真实累积，断言指标确实被记录。
 */
class AiMetricsTest {

    private MeterRegistry registry;
    private AiMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiMetrics(registry);
    }

    private double counterValue(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? -1 : counter.count();
    }

    @Test
    void recordChatCountsSuccess() {
        metrics.recordChat("rag", true, 120L, "deepseek-v3", 100, 50);

        assertEquals(1.0, counterValue("aihub.chat.calls", "scene", "rag", "status", "ok"));
        assertNotNull(registry.find("aihub.chat.latency").tags("scene", "rag").timer());
    }

    @Test
    void recordChatCountsFailureSeparately() {
        metrics.recordChat("chat", true, 10L, "m", 1, 1);
        metrics.recordChat("chat", false, 10L, "m", 0, 0);

        assertEquals(1.0, counterValue("aihub.chat.calls", "scene", "chat", "status", "ok"));
        assertEquals(1.0, counterValue("aihub.chat.calls", "scene", "chat", "status", "error"));
    }

    @Test
    void tokenCountsSplitByDirection() {
        metrics.recordChat("chat", true, 10L, "gpt-4o-mini", 300, 120);

        assertEquals(300.0, counterValue("aihub.tokens",
                "model", "gpt-4o-mini", "direction", "in"));
        assertEquals(120.0, counterValue("aihub.tokens",
                "model", "gpt-4o-mini", "direction", "out"));
    }

    /** 零 token 不应产生计数器：避免无意义的时间序列 */
    @Test
    void zeroTokensAreNotRecorded() {
        metrics.recordChat("chat", true, 10L, "m", 0, 0);

        assertNull(registry.find("aihub.tokens").tags("model", "m", "direction", "in").counter());
    }

    @Test
    void chatLatencyAccumulates() {
        metrics.recordChat("chat", true, 100L, "m", 1, 1);
        metrics.recordChat("chat", true, 300L, "m", 1, 1);

        var timer = registry.find("aihub.chat.latency").tags("scene", "chat").timer();
        assertNotNull(timer);
        assertEquals(2, timer.count());
        assertEquals(400.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void recordToolCallTagsByToolAndSource() {
        metrics.recordToolCall("searchKnowledge", "local", true, 20L);
        metrics.recordToolCall("searchKnowledge", "local", false, 20L);
        metrics.recordToolCall("fetchDoc", "mcp", true, 20L);

        assertEquals(1.0, counterValue("aihub.tool.calls",
                "tool", "searchKnowledge", "type", "local", "status", "ok"));
        assertEquals(1.0, counterValue("aihub.tool.calls",
                "tool", "searchKnowledge", "type", "local", "status", "error"));
        assertEquals(1.0, counterValue("aihub.tool.calls",
                "tool", "fetchDoc", "type", "mcp", "status", "ok"));
    }

    @Test
    void recordAgentTaskByStatus() {
        metrics.recordAgentTask("done");
        metrics.recordAgentTask("done");
        metrics.recordAgentTask("canceled");

        assertEquals(2.0, counterValue("aihub.agent.tasks", "status", "done"));
        assertEquals(1.0, counterValue("aihub.agent.tasks", "status", "canceled"));
    }

    @Test
    void timeStageRecordsDurationAndReturnsValue() {
        String result = metrics.timeStage("vector", () -> {
            sleepQuietly(15);
            return "indexed";
        });

        assertEquals("indexed", result, "计时包装不得改变返回值");
        var timer = registry.find("aihub.ingest.latency").tags("stage", "vector").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) > 0);
    }

    /** 计时包装必须让异常原样穿透，否则入库失败会被吞掉 */
    @Test
    void timeStagePropagatesException() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> metrics.timeStage("parse", () -> {
                    throw new IllegalStateException("解析失败");
                }));

        assertEquals("解析失败", e.getMessage());
        assertEquals(1, registry.find("aihub.ingest.latency").tags("stage", "parse").timer().count(),
                "异常路径同样应记录耗时，便于统计失败率");
    }

    @Test
    void recordQuotaRejectedByDimension() {
        metrics.recordQuotaRejected("token");
        metrics.recordQuotaRejected("token");

        assertEquals(2.0, counterValue("aihub.quota.rejected", "dimension", "token"));
    }

    /** 空/缺失标签要落到兜底值，不能产生 null 标签（会导致指标注册失败） */
    @Test
    void blankTagsFallBackToPlaceholder() {
        metrics.recordChat(null, true, 10L, null, 1, 1);
        metrics.recordChat("  ", true, 10L, "", 1, 1);

        assertEquals(2.0, counterValue("aihub.chat.calls", "scene", "unknown", "status", "ok"));
        assertEquals(2.0, counterValue("aihub.tokens",
                "model", "unknown", "direction", "in"));
    }

    /** 相同标签组合应复用同一个计量器，而不是每次调用都新建 */
    @Test
    void sameTagsReuseMeter() {
        metrics.recordChat("chat", true, 10L, "m", 1, 0);
        metrics.recordChat("chat", true, 10L, "m", 1, 0);

        long okCounters = registry.find("aihub.chat.calls")
                .tag("status", "ok").counters().size();
        assertEquals(1, okCounters, "同标签只应注册一个计数器，计数靠累加");
        assertEquals(2.0, counterValue("aihub.chat.calls", "scene", "chat", "status", "ok"));
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
