package com.aihub.ai.infra.ai.tools;

import com.aihub.ai.domain.spi.ToolCallLogStore;
import com.aihub.ai.infra.ai.AiCallContext;
import com.aihub.ai.infra.metrics.AiMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 带审计的工具回调包装器（M3）。
 *
 * <p>在不侵入工具实现的前提下记录每一次调用：工具名、来源（local / mcp）、
 * 入参、出参、耗时与成功与否，写入 {@code ai_tool_call_log}；
 * 同时上报 Micrometer 指标（按工具名与成败计数）。
 *
 * <p>审计失败（如日志表不可用）只记 warn，绝不中断模型调用。
 */
@Slf4j
public class AuditedToolCallback implements ToolCallback {

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
        long start = System.currentTimeMillis();
        String output = null;
        String error = null;
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
            record(toolInput, output, error, cost);
            if (metrics != null) {
                metrics.recordToolCall(getToolDefinition().name(), source, error == null, cost);
            }
        }
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
