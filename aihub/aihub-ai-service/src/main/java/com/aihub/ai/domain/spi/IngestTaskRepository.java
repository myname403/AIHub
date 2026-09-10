package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.IngestTask;

import java.util.Optional;

/**
 * 知识入库任务仓储 SPI（ai_ingest_task）。
 * 所有读写强制带 tenant_id。
 */
public interface IngestTaskRepository {

    /** 创建任务（同一文档重复提交时返回既有任务，见实现） */
    Long create(Long tenantId, Long kbId, Long docId);

    /** 按文档取任务（前端轮询进度用） */
    Optional<IngestTask> findByDoc(Long tenantId, Long docId);

    Optional<IngestTask> find(Long tenantId, Long taskId);

    /** 更新进度（status / stage / progress） */
    void updateProgress(Long tenantId, Long taskId, int status, String stage, int progress);

    /** 标记完成 */
    void markDone(Long tenantId, Long taskId);

    /** 标记失败（errorMsg 截断保存） */
    void markFailed(Long tenantId, Long taskId, String errorMsg);

    /** 重试前重置：retry_count +1，状态回到处理中 */
    void prepareRetry(Long tenantId, Long taskId);
}
