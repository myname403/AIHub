package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.do_.AiMessageReferenceDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiMessageReferenceMapper extends BaseMapper<AiMessageReferenceDO> {

    @Select("""
            select * from ai_message_reference
            where tenant_id = #{tenantId} and conv_key = #{conversationId}
            order by message_id asc, seq asc
            """)
    List<AiMessageReferenceDO> selectByConvKey(@Param("tenantId") Long tenantId,
                                               @Param("conversationId") String conversationId);
}
