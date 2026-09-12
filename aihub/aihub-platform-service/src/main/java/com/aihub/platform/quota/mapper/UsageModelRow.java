package com.aihub.platform.quota.mapper;

import lombok.Data;

/**
 * 按模型聚合的用量行 —— @Select 查询的结果行对象（非数据库表）。
 * SQL 列别名（model_code 等）↔ 驼峰字段自动映射。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
public class UsageModelRow {

    /** 模型编码（NULL 归并为 unknown） */
    private String modelCode;

    /** 调用次数 */
    private Long calls;

    /** 输入 token 合计 */
    private Long tokenIn;

    /** 输出 token 合计 */
    private Long tokenOut;

    /** 平均耗时（毫秒） */
    private Double avgCostMs;
}
