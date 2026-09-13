package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_knowledge_base 表数据对象。
 * vector_dim 必须与所选 Embedding 模型维度一致（强校验，建索引后不可改）。
 */
@Data
@TableName("ai_knowledge_base")
public class AiKnowledgeBaseDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private String name;
    private Long embeddingModelId;
    private Integer vectorDim;
    private String indexName;
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
}
