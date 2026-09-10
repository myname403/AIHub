package com.aihub.platform.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_quota_policy 配额策略。
 */
@Data
@TableName("ai_quota_policy")
public class AiQuotaPolicyDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    /** request / token / doc / task */
    private String dimension;
    /** day / month */
    private String period;
    private Long limitValue;

    @TableLogic
    private Integer deleted;
}
