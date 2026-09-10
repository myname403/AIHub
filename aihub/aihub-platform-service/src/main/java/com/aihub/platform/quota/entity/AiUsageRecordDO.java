package com.aihub.platform.quota.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ai_usage_record 调用用量明细。
 */
@Data
@TableName("ai_usage_record")
public class AiUsageRecordDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long appId;
    private Long userId;
    private String modelCode;
    private Integer tokenIn;
    private Integer tokenOut;
    private Long costMs;
    private BigDecimal costAmount;
    private LocalDateTime createTime;
}
