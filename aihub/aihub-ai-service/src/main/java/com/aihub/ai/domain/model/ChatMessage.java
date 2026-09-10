package com.aihub.ai.domain.model;

/**
 * 会话消息（领域层），只保留记忆所需的最小字段。
 */
public record ChatMessage(String role, String content) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
}
