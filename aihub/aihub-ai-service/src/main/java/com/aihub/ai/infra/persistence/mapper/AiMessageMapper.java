package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiMessageDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ai_message 表 Mapper —— 对话消息历史。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD；下方 {@code @Select} 方法
 * 为文本块（text block）写法的原生 SQL，全部带 tenant_id 条件做租户隔离。
 */
public interface AiMessageMapper extends BaseMapper<AiMessageDO> {

    /** 按字符串会话键查询（推荐路径） */
    @Select("""
            select * from ai_message
            where tenant_id = #{tenantId} and conv_key = #{conversationId}
            order by id asc
            """)
    List<AiMessageDO> selectHistoryByKey(@Param("tenantId") Long tenantId,
                                         @Param("conversationId") String conversationId);

    /** 取该会话最近一条指定角色的消息 ID（用于把引用挂到刚落库的回答上） */
    @Select("""
            select id from ai_message
            where tenant_id = #{tenantId} and conv_key = #{conversationId} and role = #{role}
            order by id desc
            limit 1
            """)
    Long selectLastIdByRole(@Param("tenantId") Long tenantId,
                            @Param("conversationId") String conversationId,
                            @Param("role") String role);

    /** 兼容旧数据（conversation_id 为数字的场景） */
    @Select("""
            select * from ai_message
            where tenant_id = #{tenantId} and conversation_id = #{conversationId}
            order by create_time asc, id asc
            """)
    List<AiMessageDO> selectHistory(@Param("tenantId") Long tenantId,
                                    @Param("conversationId") Long conversationId);
}
