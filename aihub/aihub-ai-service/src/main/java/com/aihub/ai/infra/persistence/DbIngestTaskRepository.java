package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.IngestTask;
import com.aihub.ai.domain.spi.IngestTaskRepository;
import com.aihub.ai.infra.persistence.dataobject.AiIngestTaskDO;
import com.aihub.ai.infra.persistence.mapper.AiIngestTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 入库任务仓储实现。所有查询强制带 tenant_id（多租户第三道防线）。
 */
@Repository
@RequiredArgsConstructor
public class DbIngestTaskRepository implements IngestTaskRepository {

    private static final int ERROR_MAX = 500;

    private final AiIngestTaskMapper taskMapper;

    @Override
    public Long create(Long tenantId, Long kbId, Long docId) {
        // 同一文档只保留一条任务：重试时原地复用，避免任务记录堆积
        AiIngestTaskDO existing = selectLatest(tenantId, docId);
        if (existing != null) {
            return existing.getId();
        }
        AiIngestTaskDO entity = new AiIngestTaskDO();
        entity.setTenantId(tenantId);
        entity.setKbId(kbId);
        entity.setDocId(docId);
        entity.setStatus(IngestTask.STATUS_PENDING);
        entity.setProgress(0);
        entity.setStage(IngestTask.STAGE_PARSE);
        entity.setRetryCount(0);
        entity.setStartedAt(LocalDateTime.now());
        taskMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public Optional<IngestTask> findByDoc(Long tenantId, Long docId) {
        return Optional.ofNullable(selectLatest(tenantId, docId)).map(this::toModel);
    }

    @Override
    public Optional<IngestTask> find(Long tenantId, Long taskId) {
        AiIngestTaskDO entity = taskMapper.selectById(taskId);
        if (entity == null || !tenantId.equals(entity.getTenantId())) {
            return Optional.empty(); // 出口校验：非本租户数据不外泄
        }
        return Optional.of(toModel(entity));
    }

    @Override
    public void updateProgress(Long tenantId, Long taskId, int status, String stage, int progress) {
        AiIngestTaskDO patch = new AiIngestTaskDO();
        patch.setStatus(status);
        patch.setStage(stage);
        patch.setProgress(Math.max(0, Math.min(100, progress)));
        taskMapper.update(patch, Wrappers.<AiIngestTaskDO>lambdaUpdate()
                .eq(AiIngestTaskDO::getId, taskId)
                .eq(AiIngestTaskDO::getTenantId, tenantId));
    }

    @Override
    public void markDone(Long tenantId, Long taskId) {
        AiIngestTaskDO patch = new AiIngestTaskDO();
        patch.setStatus(IngestTask.STATUS_DONE);
        patch.setProgress(100);
        patch.setErrorMsg(null);
        patch.setFinishedAt(LocalDateTime.now());
        taskMapper.update(patch, Wrappers.<AiIngestTaskDO>lambdaUpdate()
                .eq(AiIngestTaskDO::getId, taskId)
                .eq(AiIngestTaskDO::getTenantId, tenantId));
    }

    @Override
    public void markFailed(Long tenantId, Long taskId, String errorMsg) {
        AiIngestTaskDO patch = new AiIngestTaskDO();
        patch.setStatus(IngestTask.STATUS_FAILED);
        patch.setErrorMsg(truncate(errorMsg));
        patch.setFinishedAt(LocalDateTime.now());
        taskMapper.update(patch, Wrappers.<AiIngestTaskDO>lambdaUpdate()
                .eq(AiIngestTaskDO::getId, taskId)
                .eq(AiIngestTaskDO::getTenantId, tenantId));
    }

    @Override
    public void prepareRetry(Long tenantId, Long taskId) {
        AiIngestTaskDO current = taskMapper.selectById(taskId);
        if (current == null || !tenantId.equals(current.getTenantId())) {
            return;
        }
        AiIngestTaskDO patch = new AiIngestTaskDO();
        patch.setStatus(IngestTask.STATUS_RUNNING);
        patch.setProgress(0);
        patch.setStage(IngestTask.STAGE_PARSE);
        patch.setErrorMsg(null);
        patch.setRetryCount((current.getRetryCount() == null ? 0 : current.getRetryCount()) + 1);
        patch.setStartedAt(LocalDateTime.now());
        patch.setFinishedAt(null);
        taskMapper.update(patch, Wrappers.<AiIngestTaskDO>lambdaUpdate()
                .eq(AiIngestTaskDO::getId, taskId)
                .eq(AiIngestTaskDO::getTenantId, tenantId));
    }

    private AiIngestTaskDO selectLatest(Long tenantId, Long docId) {
        List<AiIngestTaskDO> rows = taskMapper.selectList(Wrappers.<AiIngestTaskDO>lambdaQuery()
                .eq(AiIngestTaskDO::getTenantId, tenantId)
                .eq(AiIngestTaskDO::getDocId, docId)
                .orderByDesc(AiIngestTaskDO::getId)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() <= ERROR_MAX ? msg : msg.substring(0, ERROR_MAX);
    }

    private IngestTask toModel(AiIngestTaskDO entity) {
        return new IngestTask(
                entity.getId(),
                entity.getTenantId(),
                entity.getKbId(),
                entity.getDocId(),
                entity.getStatus() == null ? IngestTask.STATUS_PENDING : entity.getStatus(),
                entity.getStage(),
                entity.getProgress() == null ? 0 : entity.getProgress(),
                entity.getRetryCount() == null ? 0 : entity.getRetryCount(),
                entity.getErrorMsg());
    }
}
