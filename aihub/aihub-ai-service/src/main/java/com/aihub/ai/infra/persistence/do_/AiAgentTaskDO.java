package com.aihub.ai.infra.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_agent_task Agent 任务。
 */
@Data
@TableName("ai_agent_task")
public class AiAgentTaskDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long appId;
    private Long userId;
    private String conversationId;
    private String goal;
    private String strategy;
    /** 0运行 1完成 2预算超限 3取消 4失败 */
    private Integer status;
    private Integer currentStep;
    /** 预算与消耗快照 */
    private String budgetJson;
    private Long usedTokens;
    private Long costMs;
    private String answer;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
