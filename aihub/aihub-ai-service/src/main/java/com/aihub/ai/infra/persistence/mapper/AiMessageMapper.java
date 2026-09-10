package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.do_.AiMessageDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiMessageMapper extends BaseMapper<AiMessageDO> {

    @Select("""
            select * from ai_message
            where tenant_id = #{tenantId} and conversation_id = #{conversationId}
            order by create_time asc, id asc
            """)
    List<AiMessageDO> selectHistory(@Param("tenantId") Long tenantId,
                                    @Param("conversationId") Long conversationId);
}
