package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.ChatMessage;
import com.aihub.ai.domain.spi.ConversationMemoryStore;
import com.aihub.ai.infra.persistence.do_.AiMessageDO;
import com.aihub.ai.infra.persistence.mapper.AiMessageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话记忆存储实现（持久化到 ai_message，服务重启不丢上下文）。
 */
@Component
@RequiredArgsConstructor
public class DbConversationMemoryStore implements ConversationMemoryStore {

    private final AiMessageMapper messageMapper;

    @Override
    public void append(Long tenantId, Long conversationId, List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            AiMessageDO ddo = new AiMessageDO();
            ddo.setTenantId(tenantId);
            ddo.setConversationId(conversationId);
            ddo.setRole(message.role());
            ddo.setContent(message.content());
            ddo.setStatus(1);
            messageMapper.insert(ddo);
        }
    }

    @Override
    public List<ChatMessage> history(Long tenantId, Long conversationId) {
        return messageMapper.selectHistory(tenantId, conversationId).stream()
                .map(row -> new ChatMessage(row.getRole(), row.getContent()))
                .toList();
    }

    @Override
    public void clear(Long tenantId, Long conversationId) {
        messageMapper.delete(Wrappers.<AiMessageDO>lambdaQuery()
                .eq(AiMessageDO::getTenantId, tenantId)
                .eq(AiMessageDO::getConversationId, conversationId));
    }
}
