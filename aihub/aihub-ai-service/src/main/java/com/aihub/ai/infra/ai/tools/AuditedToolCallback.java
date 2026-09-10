package com.aihub.ai.infra.ai.tools;

import com.aihub.ai.domain.spi.ToolCallLogStore;
import com.aihub.ai.infra.ai.AiCallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 带审计的工���回调包装器（M3）。
 *
 * <p>在不侵入工具实现的前提下记录每一次调用：工具名、来源（local / mcp / http）、
 * 入参、出参、耗时与成功与否，写入 {@code ai_tool_call_log}。
 *
 * <p>审计失败（如日志表不可用）只记录 warn，绝不中断模型调用。
 */
@Slf4j
public class AuditedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolCallLogStore logStore;
    private final String source;

    public AuditedToolCallback(ToolCallback delegate, ToolCallLogStore logStore, String source) {
        this.delegate = delegate;
        this.logStore = logStore;
        this.source = source;
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
            record(toolInput, output, error, System.currentTimeMillis() - start);
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
