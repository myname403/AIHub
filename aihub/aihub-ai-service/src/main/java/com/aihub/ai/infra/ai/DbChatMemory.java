package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.ChatMessage;
import com.aihub.ai.domain.spi.ConversationMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI ChatMemory 适配器（位于 infra-ai，存储走 domain SPI）。
 *
 * <p>会话键格式固定为 {@code t{tenantId}:{conversationId}}，
 * 租户隔离由键本身保证；窗口策略在本层实现。
 */
@Component
@RequiredArgsConstructor
public class DbChatMemory implements ChatMemory {

    private final ConversationMemoryStore store;

    /** 记忆窗口大小：每次取最近 N 条作为上下文 */
    @Value("${aihub.memory.window-size:20}")
    private int windowSize;

    public static String key(Long tenantId, String conversationId) {
        return "t" + tenantId + ":" + conversationId;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Long tenantId = tenantOf(conversationId);
        Long convId = convOf(conversationId);
        List<ChatMessage> toSave = new ArrayList<>();
        for (Message message : messages) {
            String role = roleOf(message);
            if (role == null) {
                continue; // 系统提示词不作为记忆持久化
            }
            toSave.add(new ChatMessage(role, message.getText()));
        }
        if (!toSave.isEmpty()) {
            store.append(tenantId, convId, toSave);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        Long tenantId = tenantOf(conversationId);
        Long convId = convOf(conversationId);
        List<ChatMessage> history = store.history(tenantId, convId);
        int from = Math.max(0, history.size() - Math.max(windowSize, 1));
        List<Message> messages = new ArrayList<>();
        for (ChatMessage m : history.subList(from, history.size())) {
            if (ChatMessage.ROLE_USER.equals(m.role())) {
                messages.add(new UserMessage(m.content() == null ? "" : m.content()));
            } else if (ChatMessage.ROLE_ASSISTANT.equals(m.role())) {
                messages.add(new AssistantMessage(m.content() == null ? "" : m.content()));
            }
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        store.clear(tenantOf(conversationId), convOf(conversationId));
    }

    private String roleOf(Message message) {
        return switch (message.getMessageType()) {
            case USER -> ChatMessage.ROLE_USER;
            case ASSISTANT -> ChatMessage.ROLE_ASSISTANT;
            default -> null;
        };
    }

    private Long tenantOf(String key) {
        int end = key.indexOf(':');
        return Long.parseLong(key.substring(1, end));
    }

    private Long convOf(String key) {
        int end = key.indexOf(':');
        try {
            return Long.parseLong(key.substring(end + 1));
        } catch (NumberFormatException e) {
            // 非数字会话 ID 时退化为 0，仅用于分组
            return 0L;
        }
    }
}
