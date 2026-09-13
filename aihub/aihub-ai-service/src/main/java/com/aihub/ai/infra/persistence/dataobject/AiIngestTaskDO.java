package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_ingest_task 表数据对象：知识入库任务。
 */
@Data
@TableName("ai_ingest_task")
public class AiIngestTaskDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long kbId;
    private Long docId;
    private Integer status;
    private Integer progress;
    private String errorMsg;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 当前阶段：parse / split / save / vector */
    private String stage;
    private Integer retryCount;
    private Integer chunkTotal;
    private Integer chunkDone;
}
