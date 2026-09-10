package com.aihub.platform.quota.mapper;

import lombok.Data;

/**
 * 用量概览聚合行（单行）。
 */
@Data
public class UsageOverviewRow {

    /** 调用总次数 */
    private Long calls;
    private Long tokenIn;
    private Long tokenOut;
    /** 平均耗时（毫秒） */
    private Double avgCostMs;
    /** 涉及的不同模型数 */
    private Long modelCount;
}
