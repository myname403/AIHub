package com.aihub.platform.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ai_usage_record 表数据对象：调用用量明细（流水账）。
 *
 * <p>AI 服务每完成一次模型调用就报一条（InternalController.reportUsage → QuotaService.reportUsage）。
 * 它是<b>只增不改的审计/统计流水</b>：用量看板的所有聚合（概览/趋势/按模型）
 * 都从这张表算出；配额扣减不读它（读的是 ai_quota_usage 计数器）。
 *
 * <p>costAmount 用 BigDecimal 而非 double：金额计算绝不能用浮点
 * （0.1 + 0.2 != 0.3 的经典精度问题），BigDecimal 精确但慢，仅用于金额。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
@TableName("ai_usage_record")
public class AiUsageRecordDO {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户 ID（隔离维度） */
    private Long tenantId;

    /** 应用 ID */
    private Long appId;

    /** 用户 ID */
    private Long userId;

    /** 模型编码（如 deepseek-chat），看板"按模型聚合"的分组键 */
    private String modelCode;

    /** 输入 token 数 */
    private Integer tokenIn;

    /** 输出 token 数 */
    private Integer tokenOut;

    /** 调用耗时（毫秒），看板平均耗时的来源 */
    private Long costMs;

    /** 计费金额（BigDecimal 精确小数，金额字段专用） */
    private BigDecimal costAmount;

    /** 创建时间（看板时间窗过滤的字段） */
    private LocalDateTime createTime;
}
