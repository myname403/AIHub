package com.aihub.ai.domain.model;

/**
 * 会话消息（领域层）。
 *
 * @param id 落库后的消息 ID，用于关联引用来源（新消息尚未落库时为 null）
 */
public record ChatMessage(Long id, String role, String content) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /** 新建（尚未落库）的消息 */
    public static ChatMessage of(String role, String content) {
        return new ChatMessage(null, role, content);
    }
}
