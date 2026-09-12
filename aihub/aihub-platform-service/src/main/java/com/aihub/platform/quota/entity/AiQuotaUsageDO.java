package com.aihub.platform.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_quota_usage 表数据对象：配额用量计数器。
 *
 * <p>一行 = "某租户某维度某周期已用多少"（按 租户+应用+维度+周期 唯一）。
 * 与策略表（ai_quota_policy）的区别：策略是"规则"，这个是"计数器"。
 * 扣减走 upsertConsume 的原子累加（见 AiQuotaUsageMapper），不经过本 DO 的 update。
 *
 * <p>periodKey 说明：day → "2026-09-11"，month → "2026-09" —— 用字符串做周期键，
 * 新的一天/月自然落到新行，无需定时任务清零。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
@TableName("ai_quota_usage")
public class AiQuotaUsageDO {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户 ID */
    private Long tenantId;

    /** 应用 ID；NULL 表示租户级汇总口径（当前扣减与看板都用这个口径） */
    private Long appId;

    /** 配额维度（QuotaDimensions 常量） */
    private String dimension;

    /** 周期键：day: yyyy-MM-dd；month: yyyy-MM */
    private String periodKey;

    /** 已用量 */
    private Long usedValue;
}
