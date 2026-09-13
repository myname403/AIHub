package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiMessageReferenceDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ai_message_reference 表 Mapper —— 消息引用来源（RAG 命中片段挂到回答消息上）。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；{@code @Select} 按会话键批量取引用，
 * 带 tenant_id 条件做租户隔离。
 */
public interface AiMessageReferenceMapper extends BaseMapper<AiMessageReferenceDO> {

    @Select("""
            select * from ai_message_reference
            where tenant_id = #{tenantId} and conv_key = #{conversationId}
            order by message_id asc, seq asc
            """)
    List<AiMessageReferenceDO> selectByConvKey(@Param("tenantId") Long tenantId,
                                               @Param("conversationId") String conversationId);
}
