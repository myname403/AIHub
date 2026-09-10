package com.aihub.ai.infra.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_message 表数据对象。同时充当会话记忆的持久化存储（ChatMemory 实现）。
 */
@Data
@TableName("ai_message")
public class AiMessageDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long conversationId;
    /** user / assistant / tool */
    private String role;
    private String content;
    private Integer tokenIn;
    private Integer tokenOut;
    private String modelCode;
    private Long costMs;
    private Integer status;
    private LocalDateTime createTime;
}
