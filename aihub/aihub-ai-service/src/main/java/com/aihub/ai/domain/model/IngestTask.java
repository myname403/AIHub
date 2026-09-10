package com.aihub.ai.domain.model;

/**
 * 知识入库任务（异步执行 + 进度可见 + 可重试）。
 *
 * @param id         任务 ID
 * @param tenantId   租户
 * @param kbId       知识库
 * @param docId      文档
 * @param status     0待处理 1处理中 2完成 3失败 4已取消
 * @param stage      当前阶段（parse / split / save / vector）
 * @param progress   进度百分比 0-100
 * @param retryCount 已重试次数
 * @param errorMsg   失败原因
 */
public record IngestTask(
        Long id,
        Long tenantId,
        Long kbId,
        Long docId,
        int status,
        String stage,
        int progress,
        int retryCount,
        String errorMsg
) {
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELED = 4;

    public static final String STAGE_PARSE = "parse";
    public static final String STAGE_SPLIT = "split";
    public static final String STAGE_SAVE = "save";
    public static final String STAGE_VECTOR = "vector";

    /** 是否处于终态（终态任务不再被重试） */
    public boolean finished() {
        return status == STATUS_DONE || status == STATUS_CANCELED;
    }

    /** 是否可重试（失败且未超过上限） */
    public boolean retryable(int maxRetry) {
        return status == STATUS_FAILED && retryCount < maxRetry;
    }
}
