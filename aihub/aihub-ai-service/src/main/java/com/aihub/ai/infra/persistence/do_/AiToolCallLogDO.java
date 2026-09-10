package com.aihub.ai.infra.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ai_tool_call_log 工具调用日志。
 */
@Data
@TableName("ai_tool_call_log")
public class AiToolCallLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long appId;
    /** local / mcp / http */
    private String source;
    private String toolName;
    private String inputJson;
    private String outputJson;
    private Integer success;
    private String errorMsg;
    private Long costMs;
    private LocalDateTime createTime;
}
