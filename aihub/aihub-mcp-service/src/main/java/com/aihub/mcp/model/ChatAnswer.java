package com.aihub.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 同步对话结果（对应 {@code POST /api/ai/chat} 的 data）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatAnswer {

    private String conversationId;
    private String content;
    private Long costMs;
}
