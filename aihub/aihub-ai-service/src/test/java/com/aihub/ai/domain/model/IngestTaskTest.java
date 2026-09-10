package com.aihub.ai.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入库任务状态机测试：终态判定与重试准入。
 */
class IngestTaskTest {

    private IngestTask task(int status, int retryCount) {
        return new IngestTask(1L, 1L, 10L, 100L, status, IngestTask.STAGE_PARSE, 0, retryCount, null);
    }

    @Test
    void doneAndCanceledAreFinished() {
        assertTrue(task(IngestTask.STATUS_DONE, 0).finished());
        assertTrue(task(IngestTask.STATUS_CANCELED, 1).finished());
    }

    @Test
    void runningAndFailedAreNotFinished() {
        assertFalse(task(IngestTask.STATUS_RUNNING, 0).finished());
        assertFalse(task(IngestTask.STATUS_FAILED, 0).finished());
        assertFalse(task(IngestTask.STATUS_PENDING, 0).finished());
    }

    @Test
    void failedTaskIsRetryableBelowLimit() {
        assertTrue(task(IngestTask.STATUS_FAILED, 0).retryable(2), "首次失败应可重试");
        assertTrue(task(IngestTask.STATUS_FAILED, 1).retryable(2), "未达上限应可重试");
    }

    @Test
    void failedTaskIsNotRetryableAtLimit() {
        assertFalse(task(IngestTask.STATUS_FAILED, 2).retryable(2), "达到上限后不得再重试");
        assertFalse(task(IngestTask.STATUS_FAILED, 5).retryable(2));
    }

    @Test
    void nonFailedTaskIsNeverRetryable() {
        assertFalse(task(IngestTask.STATUS_RUNNING, 0).retryable(2), "处理中重复提交会打架");
        assertFalse(task(IngestTask.STATUS_DONE, 0).retryable(2), "已完成不应重试");
        assertFalse(task(IngestTask.STATUS_CANCELED, 0).retryable(2));
    }
}
