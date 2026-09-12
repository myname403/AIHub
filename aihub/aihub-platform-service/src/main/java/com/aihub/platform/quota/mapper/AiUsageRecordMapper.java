package com.aihub.platform.quota.mapper;

import com.aihub.platform.quota.entity.AiUsageRecordDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量明细 Mapper。
 *
 * <p>写入侧（{@code reportUsage}）与读取侧（管理端用量看板）都走这里。
 * 所有聚合查询<b>强制带 tenant_id</b>——这是多租户隔离的第一道防线。
 *
 * <p><b>SQL 聚合小课堂：</b>
 * count(*) 计数、sum() 求和、avg() 平均、count(distinct x) 去重计数；
 * coalesce(x, 0) 把 NULL 兜底成 0（空窗口时前端不用判空）；
 * group by 按列分组聚合；text block（三引号）保持 SQL 可读缩进。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
public interface AiUsageRecordMapper extends BaseMapper<AiUsageRecordDO> {

    /**
     * 概览聚合：指定时间窗内的调用次数 / Token / 平均耗时 / 涉及模型数。
     *
     * <p>用 {@code coalesce} 兜底 NULL，避免空窗口时前端拿到 null 需要额外判空。
     * 返回值 UsageOverviewRow 是"查询结果行"对象（列别名 ↔ 字段名自动映射）。
     */
    @Select("""
            select count(*)                                  as calls,
                   coalesce(sum(token_in), 0)                as token_in,
                   coalesce(sum(token_out), 0)               as token_out,
                   coalesce(avg(cost_ms), 0)                 as avg_cost_ms,
                   count(distinct model_code)                as model_count
            from ai_usage_record
            where tenant_id = #{tenantId}
              and create_time >= #{from}
            """)
    UsageOverviewRow selectOverview(@Param("tenantId") Long tenantId,
                                    @Param("from") LocalDateTime from);

    /**
     * 按天聚合趋势。
     *
     * <p>在 SQL 里格式化成字符串，避免 JDBC 日期类型在各驱动下的差异。
     * 注意：<b>没有数据的日期不会出现在结果里</b>，补零由服务层完成
     * （见 UsageQueryService.trend，前端折线图需要连续日期轴）。
     */
    @Select("""
            select date_format(create_time, '%Y-%m-%d')                     as day,
                   count(*)                                                 as calls,
                   coalesce(sum(token_in), 0) + coalesce(sum(token_out), 0)  as tokens
            from ai_usage_record
            where tenant_id = #{tenantId}
              and create_time >= #{from}
            group by date_format(create_time, '%Y-%m-%d')
            order by day
            """)
    List<UsageTrendRow> selectTrend(@Param("tenantId") Long tenantId,
                                   @Param("from") LocalDateTime from);

    /** 按模型聚合（调用量倒序，取前 20 个，防止模型过多撑爆图例） */
    @Select("""
            select coalesce(model_code, 'unknown')   as model_code,
                   count(*)                          as calls,
                   coalesce(sum(token_in), 0)        as token_in,
                   coalesce(sum(token_out), 0)       as token_out,
                   coalesce(avg(cost_ms), 0)         as avg_cost_ms
            from ai_usage_record
            where tenant_id = #{tenantId}
              and create_time >= #{from}
            group by coalesce(model_code, 'unknown')
            order by calls desc
            limit 20
            """)
    List<UsageModelRow> selectByModel(@Param("tenantId") Long tenantId,
                                      @Param("from") LocalDateTime from);
}
