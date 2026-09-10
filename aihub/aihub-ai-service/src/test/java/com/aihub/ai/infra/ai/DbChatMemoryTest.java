package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.ChatMessage;
import com.aihub.ai.domain.spi.ConversationMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话记忆适配器测试（★ 会话隔离回归防线）。
 *
 * <p>历史缺陷：非数字会话 ID 被退化成 {@code 0L}，同一租户下所有会话共用一份记忆。
 */
class DbChatMemoryTest {

    /** 记录调用参数的假存储 */
    private static class RecordingStore implements ConversationMemoryStore {
        private final List<String> appendedConvIds = new ArrayList<>();
        private final List<String> queriedConvIds = new ArrayList<>();
        private final List<ChatMessage> history = new ArrayList<>();

        @Override
        public void append(Long tenantId, String conversationId, List<ChatMessage> messages) {
            appendedConvIds.add(conversationId);
            history.addAll(messages);
        }

        @Override
        public List<ChatMessage> history(Long tenantId, String conversationId) {
            queriedConvIds.add(conversationId);
            return history;
        }

        @Override
        public void clear(Long tenantId, String conversationId) {
            queriedConvIds.add("clear:" + conversationId);
        }
    }

    private DbChatMemory memoryWith(RecordingStore store) {
        DbChatMemory memory = new DbChatMemory(store);
        ReflectionTestUtils.setField(memory, "windowSize", 20);
        return memory;
    }

    @Test
    void shouldKeepStringConversationIdIntact() {
        RecordingStore store = new RecordingStore();
        DbChatMemory memory = memoryWith(store);
        String convId = "a3f2c1e09b8d4a7f";

        memory.add(DbChatMemory.key(1L, convId), List.of(new UserMessage("你好")));

        // 关键断言：字符串会话 ID 不得被改写成 0 或丢弃
        assertEquals(List.of(convId), store.appendedConvIds,
                "会话 ID 必须原样透传，否则同租户会话会互相串台");
    }

    @Test
    void shouldQueryHistoryByOriginalConversationId() {
        RecordingStore store = new RecordingStore();
        DbChatMemory memory = memoryWith(store);
        memory.add(DbChatMemory.key(1L, "conv-A"), List.of(new UserMessage("A 的问题")));

        memory.get(DbChatMemory.key(1L, "conv-A"));

        assertEquals(List.of("conv-A"), store.queriedConvIds);
    }

    @Test
    void shouldNotPersistSystemPrompt() {
        RecordingStore store = new RecordingStore();
        DbChatMemory memory = memoryWith(store);

        memory.add(DbChatMemory.key(1L, "conv-A"), List.of(
                new SystemMessage("你是助手"),
                new UserMessage("用户问题")));

        assertEquals(1, store.history.size(), "系统提示词不应写入记忆");
        assertEquals(ChatMessage.ROLE_USER, store.history.get(0).role());
    }

    @Test
    void shouldReturnMessagesInWindow() {
        RecordingStore store = new RecordingStore();
        DbChatMemory memory = memoryWith(store);
        ReflectionTestUtils.setField(memory, "windowSize", 2);
        memory.add(DbChatMemory.key(1L, "conv-A"), List.of(
                new UserMessage("第 1 问"), new AssistantMessage("第 1 答"),
                new UserMessage("第 2 问"), new AssistantMessage("第 2 答")));

        var messages = memory.get(DbChatMemory.key(1L, "conv-A"));

        // 窗口只保留最近 2 条，且 user/assistant 归位正确
        assertEquals(2, messages.size());
        assertEquals("第 2 问", messages.get(0).getText());
        assertEquals("第 2 答", messages.get(1).getText());
    }

    @Test
    void shouldClearByConversationId() {
        RecordingStore store = new RecordingStore();
        DbChatMemory memory = memoryWith(store);

        memory.clear(DbChatMemory.key(9L, "conv-Z"));

        assertEquals(List.of("clear:conv-Z"), store.queriedConvIds);
    }

    @Test
    void shouldNotThrowWhenConversationIdMissing() {
        RecordingStore store = new RecordingStore();
        DbChatMemory memory = memoryWith(store);

        // 键缺省会话 ID 时不应抛异常，退化为空串会话
        assertDoesNotThrow(() -> memory.add(DbChatMemory.key(1L, null), List.of(new UserMessage("hi"))));
        assertTrue(store.appendedConvIds.contains(""));
    }
}
