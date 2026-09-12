package com.aihub.platform.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * ai_quota_policy 表数据对象：配额策略（限额规则）。
 *
 * <p>一行 = "某租户某维度在一个周期内最多能用多少"。示例：
 * <pre>
 *   tenantId=1001, dimension=token, period=day,   limitValue=100000   ← 每天最多 10 万 token
 *   tenantId=1001, dimension=token, period=month, limitValue=2000000  ← 每月最多 200 万 token
 * </pre>
 * 同一维度可同时配 day 和 month 两条；扣减时 day 优先（更细的先扣，详见 QuotaService）。
 * 维度取值见 aihub-api 的 QuotaDimensions 常量（两端共享，防口径漂移）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
@TableName("ai_quota_policy")
public class AiQuotaPolicyDO {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属租户 */
    private Long tenantId;

    /** 配额维度：request / token / doc / task（QuotaDimensions 常量） */
    private String dimension;

    /** 周期类型：day / month */
    private String period;

    /** 周期内限额值（token 维度是 token 数，request 维度是次数） */
    private Long limitValue;

    /** 逻辑删除标记（@TableLogic：删除=置 1，查询自动过滤） */
    @TableLogic
    private Integer deleted;
}
