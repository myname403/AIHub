package com.aihub.platform.quota.mapper;

import lombok.Data;

/**
 * 按天聚合的用量行（用于趋势图）。
 */
@Data
public class UsageTrendRow {

    /** yyyy-MM-dd */
    private String day;
    private Long calls;
    private Long tokens;
}
