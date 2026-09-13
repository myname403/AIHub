package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_document 表数据对象。
 */
@Data
@TableName("ai_document")
public class AiDocumentDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long kbId;
    private String name;
    private String fileType;
    private Long size;
    private String filePath;
    private Integer status;
    private String errorMsg;
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
