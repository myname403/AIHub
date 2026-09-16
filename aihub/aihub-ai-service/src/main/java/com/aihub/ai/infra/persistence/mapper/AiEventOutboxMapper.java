package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiEventOutboxDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * ai_event_outbox 表 Mapper —— 跨服务上报失败后的兜底投递。
 *
 * <p>表在 V1 迁移里就建好了（原设计为"预留"），这里补齐读写：
 * 写入（待投递）→ 轮询取待投递 → 标记成功 / 累加重试次数。
 */
public interface AiEventOutboxMapper extends BaseMapper<AiEventOutboxDO> {

    /** 落一条待投递事件（status=0） */
    @Insert("insert into ai_event_outbox (id, tenant_id, event_type, payload_json, status, retry_count, create_time) "
            + "values (#{id}, #{tenantId}, #{eventType}, #{payloadJson}, 0, 0, now())")
    int insertEvent(@Param("id") Long id, @Param("tenantId") Long tenantId,
                    @Param("eventType") String eventType, @Param("payloadJson") String payloadJson);

    /**
     * 取一批待投递事件（按时间先后，先失败先重投）。
     * <p>只取 status=0；投递失败时若重试次数已达上限会置为 2（放弃），不再被本查询捞出来。
     */
    @Select("select id, tenant_id, event_type, payload_json, status, retry_count, create_time, published_at "
            + "from ai_event_outbox where status = 0 order by create_time asc limit #{limit}")
    List<AiEventOutboxDO> selectPending(@Param("limit") int limit);

    /** 投递成功 */
    @Update("update ai_event_outbox set status = 1, published_at = now() where id = #{id}")
    int markPublished(@Param("id") Long id);

    /**
     * 投递失败：重试次数 +1；达到上限则置为「已放弃」（status=2），
     * 避免坏数据在轮询里无限打转。
     */
    @Update("update ai_event_outbox set retry_count = retry_count + 1, "
            + "status = case when retry_count + 1 >= #{maxRetry} then 2 else 0 end "
            + "where id = #{id}")
    int markRetry(@Param("id") Long id, @Param("maxRetry") int maxRetry);
}
