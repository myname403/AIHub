package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_app_kb 表数据对象（应用-知识库绑定）。
 */
@Data
@TableName("ai_app_kb")
public class AiAppKbDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long appId;
    private Long kbId;
}
