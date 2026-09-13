package com.aihub.ai.infra.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_agent_step Agent 执行步骤（think / act / observe）。
 */
@Data
@TableName("ai_agent_step")
public class AiAgentStepDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long taskId;
    private Integer seq;
    private String type;
    private String agentName;
    private String contentJson;
    private Long costMs;
    private LocalDateTime createTime;
}
