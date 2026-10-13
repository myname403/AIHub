package com.aihub.ai.infra.ai.tools;

import com.aihub.ai.domain.model.SequencedSink;
import com.aihub.ai.domain.model.StreamBridge;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.ToolCallLogStore;
import com.aihub.ai.infra.ai.AiCallContext;
import com.aihub.ai.infra.metrics.AiMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.HashMap;
import java.util.Map;

/**
 * 带审计的工具回调包装器（M3）。
 *
 * <p>在不侵入工具实现的前提下记录每一次调用：工具名、来源（local / mcp）、
 * 入参、出参、耗时与成功与否，写入 {@code ai_tool_call_log}；
 * 同时上报 Micrometer 指标（按工具名与成败计数）。
 *
 * <p><b>前端工具调用事件（tool.start / tool.end）同层发出：</b>包装器是所有工具
 * （内置 + MCP）的必经之路，在这里广播事件即可让前端看到"模型正在调用什么工具、
 * 耗时多少、成功与否"（对齐 ChatGPT 的工具调用卡片），工具实现类零改动。
 * 事件走 {@link StreamBridge} 双通道：ToolContext 优先（chat/rag 流式链路，
 * 工具在 Reactor 线程执行），ThreadLocal 兜底（Agent 同步链）。两处都取不到
 * 桥（如同步调试路径 /chat）就只落审计不发事件——事件是增强，绝不因它中断调用。
 *
 * <p>审计失败（如日志表不可用）只记 warn，绝不中断模型调用。
 */
@Slf4j
public class AuditedToolCallback implements ToolCallback {

    /** 事件里入参/出参摘要的截断长度：够前端展示，避免大出参（如浏览器快照）撑爆事件帧 */
    private static final int SUMMARY_LIMIT = 500;

    private final ToolCallback delegate;
    private final ToolCallLogStore logStore;
    private final String source;
    /** 可为 null：指标是增强项，缺失时跳过而不影响工具调用 */
    private final AiMetrics metrics;

    public AuditedToolCallback(ToolCallback delegate, ToolCallLogStore logStore, String source) {
        this(delegate, logStore, source, null);
    }

    public AuditedToolCallback(ToolCallback delegate, ToolCallLogStore logStore, String source,
                               AiMetrics metrics) {
        this.delegate = delegate;
        this.logStore = logStore;
        this.source = source;
        this.metrics = metrics;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        SequencedSink bridge = resolveBridge(toolContext);
        long start = System.currentTimeMillis();
        String output = null;
        String error = null;
        if (bridge != null) {
            bridge.emit(StreamEvent.TOOL_START, Map.of(
                    "name", name,
                    "source", source == null ? "" : source,
                    "input", abbreviate(toolInput)));
        }
        try {
            output = toolContext == null
                    ? delegate.call(toolInput)
                    : delegate.call(toolInput, toolContext);
            return output;
        } catch (Exception e) {
            error = e.getMessage();
            throw e;
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (bridge != null) {
                // 失败时把错误信息放进 output 位（前端同一处展示"结果/错误"）
                String summary = error != null ? error : abbreviate(output);
                Map<String, Object> data = new HashMap<>();
                data.put("name", name);
                data.put("source", source == null ? "" : source);
                data.put("success", error == null);
                data.put("costMs", cost);
                data.put("output", summary == null ? "" : summary);
                bridge.emit(StreamEvent.TOOL_END, data);
            }
            record(toolInput, output, error, cost);
            if (metrics != null) {
                metrics.recordToolCall(name, source, error == null, cost);
            }
        }
    }

    /**
     * 桥解析：ToolContext 优先（流式链路跨线程），ThreadLocal 兜底（Agent 同步链）。
     * 两处都没有 → null，调用方跳过事件发送。
     */
    private SequencedSink resolveBridge(ToolContext toolContext) {
        Object value = toolContext == null || toolContext.getContext() == null
                ? null : toolContext.getContext().get(StreamBridge.CTX_KEY);
        if (value instanceof SequencedSink sink) {
            return sink;
        }
        return StreamBridge.get();
    }

    /** 入参/出参摘要：超长截断（大出参如浏览器快照/知识片段，事件帧塞全文没意义） */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() > SUMMARY_LIMIT ? t.substring(0, SUMMARY_LIMIT) + "…" : t;
    }

    private void record(String input, String output, String error, long costMs) {
        try {
            logStore.record(new ToolCallLogStore.ToolCallLog(
                    AiCallContext.tenantId(),
                    AiCallContext.appId(),
                    source,
                    getToolDefinition().name(),
                    input,
                    output,
                    error == null,
                    error,
                    costMs));
        } catch (Exception e) {
            log.warn("工具调用审计失败 tool={} err={}", getToolDefinition().name(), e.getMessage());
        }
    }
}
