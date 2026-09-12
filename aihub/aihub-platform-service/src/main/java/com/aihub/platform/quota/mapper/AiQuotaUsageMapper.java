package com.aihub.platform.quota.mapper;

import com.aihub.platform.quota.entity.AiQuotaUsageDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 配额用量计数器 Mapper。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
public interface AiQuotaUsageMapper extends BaseMapper<AiQuotaUsageDO> {

    /**
     * 原子扣减：不存在则插入，存在则累加（依赖 uk_usage 唯一键）。
     * 生产高并发场景可替换为 Redis 计数 + 定时回写。
     *
     * <p><b>upsert 语法讲解：</b>{@code INSERT ... ON DUPLICATE KEY UPDATE} 是 MySQL 特有的
     * "存在即更新"原子操作 —— 并发下不会出现"先查发现没有、再插撞唯一键"的竞态，
     * 也不需要显式加锁。text block（三引号，Java 15+）让 SQL 保持可读缩进。
     * #{xxx} 预编译占位符，@Param 声明参数名供 SQL 引用。
     */
    @Insert("""
            insert into ai_quota_usage (id, tenant_id, app_id, dimension, period_key, used_value)
            values (#{id}, #{tenantId}, #{appId}, #{dimension}, #{periodKey}, #{amount})
            on duplicate key update used_value = used_value + #{amount}
            """)
    int upsertConsume(@Param("id") long id,
                      @Param("tenantId") Long tenantId,
                      @Param("appId") Long appId,
                      @Param("dimension") String dimension,
                      @Param("periodKey") String periodKey,
                      @Param("amount") long amount);
}
