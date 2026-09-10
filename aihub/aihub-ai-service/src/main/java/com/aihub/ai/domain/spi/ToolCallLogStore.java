package com.aihub.ai.domain.spi;

/**
 * 工具调用日志 SPI（M3）。
 *
 * <p>定义在领域层，实现在 infra-persistence；infra-ai 的 {@code AuditedToolCallback}
 * 只依赖本接口，因此不破坏「infra.ai 不得依赖 infra.persistence」的架构卡口。
 */
public interface ToolCallLogStore {

    /** 记录一次工具调用（实现方负责脱敏与截断，且不得因写失败影响主流程） */
    void record(ToolCallLog log);

    record ToolCallLog(Long tenantId, Long appId, String source, String toolName,
                       String inputJson, String outputJson, boolean success,
                       String errorMsg, long costMs) {
    }
}
