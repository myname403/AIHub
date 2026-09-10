package com.aihub.ai.infra.metrics;

import com.aihub.ai.domain.spi.MetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * AI 服务指标中心（Micrometer）。
 *
 * <p>命名规范：{@code aihub.<域>.<指标>}，单位用规范后缀
 * （{@code _seconds} 计时、{@code _total} 计数）。
 *
 * <p><b>基数控制（重要）</b>：标签只放低基数的枚举值（scene / status / tool 名），
 * <b>绝不放 tenantId / userId / conversationId</b>——它们基数极高，
 * 会让指标序列爆炸并拖垮 Prometheus。租户维度的用量统计走
 * {@code ai_usage_record} 表 + 用量看板，不走指标系统。
 *
 * <p>Meter 对象本身有创建开销，故按标签组合缓存复用。
 */
@Component
public class AiMetrics implements MetricsRecorder {

    /** 对话调用：label scene=chat|rag|agent, status=ok|error */
    private static final String CHAT_CALLS = "aihub.chat.calls";
    /** 对话耗时：label scene */
    private static final String CHAT_LATENCY = "aihub.chat.latency";
    /** Token 消耗：label model, direction=in|out */
    private static final String TOKENS = "aihub.tokens";
    /** 工具调用：label tool, type=local|mcp, status=ok|error */
    private static final String TOOL_CALLS = "aihub.tool.calls";
    /** Agent 任务：label status=done|canceled|failed */
    private static final String AGENT_TASKS = "aihub.agent.tasks";
    /** 文档入库耗时：label stage=parse|split|save|vector */
    private static final String INGEST_LATENCY = "aihub.ingest.latency";
    /** 配额拦截次数：label dimension */
    private static final String QUOTA_REJECTED = "aihub.quota.rejected";

    private final MeterRegistry registry;
    /** 缓存已创建的计量器，避免每次调用都走注册表查找 */
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /* ---------------- 对话 ---------------- */

    /** 记录一次对话调用（含耗时与 token 用量） */
    public void recordChat(String scene, boolean success, long costMs,
                           String modelCode, int tokenIn, int tokenOut) {
        String sceneLabel = safe(scene, "unknown");
        counter(CHAT_CALLS, "scene", sceneLabel, "status", success ? "ok" : "error").increment();
        timer(CHAT_LATENCY, "scene", sceneLabel).record(costMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (tokenIn > 0) {
            counter(TOKENS, "model", safe(modelCode, "unknown"), "direction", "in").increment(tokenIn);
        }
        if (tokenOut > 0) {
            counter(TOKENS, "model", safe(modelCode, "unknown"), "direction", "out").increment(tokenOut);
        }
    }

    /* ---------------- 工具 ---------------- */

    public void recordToolCall(String toolName, String type, boolean success, long costMs) {
        String tool = safe(toolName, "unknown");
        counter(TOOL_CALLS, "tool", tool, "type", safe(type, "local"),
                "status", success ? "ok" : "error").increment();
    }

    /* ---------------- Agent ---------------- */

    public void recordAgentTask(String status) {
        counter(AGENT_TASKS, "status", safe(status, "unknown")).increment();
    }

    /* ---------------- 入库 ---------------- */

    /** 记录入库某阶段耗时，便于定位是解析慢还是向量化慢 */
    @Override
    public <T> T timeStage(String stage, Supplier<T> action) {
        return timer(INGEST_LATENCY, "stage", safe(stage, "unknown")).record(action);
    }

    /** 通用计数（实现 {@link MetricsRecorder} 端口） */
    @Override
    public void count(String name, String... tags) {
        counter(name, tags).increment();
    }

    /* ---------------- 配额 ---------------- */

    public void recordQuotaRejected(String dimension) {
        counter(QUOTA_REJECTED, "dimension", safe(dimension, "unknown")).increment();
    }

    /* ---------------- 内部 ---------------- */

    private Counter counter(String name, String... tags) {
        String key = name + java.util.Arrays.toString(tags);
        return counters.computeIfAbsent(key, k -> Counter.builder(name)
                .tags(tags)
                .description(descriptionOf(name))
                .register(registry));
    }

    private Timer timer(String name, String... tags) {
        String key = name + java.util.Arrays.toString(tags);
        return timers.computeIfAbsent(key, k -> Timer.builder(name)
                .tags(tags)
                .description(descriptionOf(name))
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    private String descriptionOf(String name) {
        return switch (name) {
            case CHAT_CALLS -> "对话调用次数";
            case CHAT_LATENCY -> "对话耗时";
            case TOKENS -> "Token 消耗量";
            case TOOL_CALLS -> "工具调用次数";
            case AGENT_TASKS -> "Agent 任务数";
            case INGEST_LATENCY -> "文档入库各阶段耗时";
            case QUOTA_REJECTED -> "配额拦截次数";
            default -> name;
        };
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
