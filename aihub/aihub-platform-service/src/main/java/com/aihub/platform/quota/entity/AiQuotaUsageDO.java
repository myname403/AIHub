package com.aihub.platform.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_quota_usage 配额用量（按 租户+应用+维度+周期 唯一）。
 */
@Data
@TableName("ai_quota_usage")
public class AiQuotaUsageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long appId;
    private String dimension;
    /** day: yyyy-MM-dd；month: yyyy-MM */
    private String periodKey;
    private Long usedValue;
}
