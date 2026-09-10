package com.aihub.ai.infra.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_model_route 表数据对象（场景选模与主备降级）。
 */
@Data
@TableName("ai_model_route")
public class AiModelRouteDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    /** chat / rag / embed / agent-plan */
    private String scene;
    private Long primaryModelId;
    private Long fallbackModelId;

    @TableLogic
    private Integer deleted;
}
