package com.aihub.platform.quota.mapper;

import lombok.Data;

/**
 * 按天聚合的用量行（用于趋势图）—— @Select 查询的结果行对象（非数据库表）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
public class UsageTrendRow {

    /** 日期（yyyy-MM-dd 字符串，SQL 里 date_format 生成） */
    private String day;

    /** 当天调用次数 */
    private Long calls;

    /** 当天 token 合计（输入+输出） */
    private Long tokens;
}
