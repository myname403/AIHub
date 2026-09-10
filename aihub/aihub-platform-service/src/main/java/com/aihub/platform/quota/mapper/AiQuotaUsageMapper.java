package com.aihub.platform.quota.mapper;

import com.aihub.platform.quota.entity.AiQuotaUsageDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface AiQuotaUsageMapper extends BaseMapper<AiQuotaUsageDO> {

    /**
     * 原子扣减：不存在则插入，存在则累加（依赖 uk_usage 唯一键）。
     * 生产高并发场景可替换为 Redis 计数 + 定时回写。
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
