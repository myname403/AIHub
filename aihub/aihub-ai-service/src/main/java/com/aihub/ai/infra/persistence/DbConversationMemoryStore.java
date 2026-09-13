package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.ChatMessage;
import com.aihub.ai.domain.spi.ConversationMemoryStore;
import com.aihub.ai.infra.persistence.dataobject.AiMessageDO;
import com.aihub.ai.infra.persistence.mapper.AiMessageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话记忆存储实现（持久化到 ai_message，服务重启不丢上下文）。
 *
 * <p>会话标识落在 conv_key 列：会话 ID 是字符串，不能被 BIGINT 列无损承载。
 */
@Component
@RequiredArgsConstructor
public class DbConversationMemoryStore implements ConversationMemoryStore {

    private final AiMessageMapper messageMapper;

    @Override
    public void append(Long tenantId, String conversationId, List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            AiMessageDO entity = new AiMessageDO();
            entity.setTenantId(tenantId);
            entity.setConvKey(conversationId);
            entity.setRole(message.role());
            entity.setContent(message.content());
            entity.setStatus(1);
            messageMapper.insert(entity);
        }
    }

    @Override
    public List<ChatMessage> history(Long tenantId, String conversationId) {
        return messageMapper.selectHistoryByKey(tenantId, conversationId).stream()
                .map(row -> new ChatMessage(row.getId(), row.getRole(), row.getContent()))
                .toList();
    }

    @Override
    public void clear(Long tenantId, String conversationId) {
        messageMapper.delete(Wrappers.<AiMessageDO>lambdaQuery()
                .eq(AiMessageDO::getTenantId, tenantId)
                .eq(AiMessageDO::getConvKey, conversationId));
    }
}
