package com.aihub.platform.quota.mapper;

import lombok.Data;

/**
 * 用量概览聚合行（单行）—— @Select 聚合查询的结果行对象（非数据库表）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
public class UsageOverviewRow {

    /** 调用总次数 */
    private Long calls;

    /** 输入 token 合计 */
    private Long tokenIn;

    /** 输出 token 合计 */
    private Long tokenOut;

    /** 平均耗时（毫秒） */
    private Double avgCostMs;

    /** 涉及的不同模型数 */
    private Long modelCount;
}
