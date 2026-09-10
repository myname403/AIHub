package com.aihub.platform.quota.mapper;

import lombok.Data;

/**
 * 按模型聚合的用量行。
 */
@Data
public class UsageModelRow {

    private String modelCode;
    private Long calls;
    private Long tokenIn;
    private Long tokenOut;
    private Double avgCostMs;
}
