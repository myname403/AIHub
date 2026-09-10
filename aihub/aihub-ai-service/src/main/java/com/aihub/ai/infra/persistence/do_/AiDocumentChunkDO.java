package com.aihub.ai.infra.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_document_chunk 表数据对象。
 */
@Data
@TableName("ai_document_chunk")
public class AiDocumentChunkDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long kbId;
    private Long docId;
    private Integer seq;
    private String content;
    private Integer tokenCount;
    /** 0待向量化 1已入库 2失败 */
    private Integer vectorStatus;
    private String metadataJson;
    private LocalDateTime createTime;
}
