package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.ChatMessage;
import com.aihub.ai.domain.model.MessageReference;
import com.aihub.ai.domain.spi.MessageReferenceStore;
import com.aihub.ai.infra.persistence.do_.AiMessageReferenceDO;
import com.aihub.ai.infra.persistence.mapper.AiMessageMapper;
import com.aihub.ai.infra.persistence.mapper.AiMessageReferenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 引用来源落库实现。
 *
 * <p>消息本身由 Spring AI 的 ChatMemory Advisor 写入，业务侧拿不到消息 ID，
 * 因此这里按 (tenant_id, conv_key, role=assistant) 取最近一条消息作为归属。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbMessageReferenceStore implements MessageReferenceStore {

    private final AiMessageReferenceMapper referenceMapper;
    private final AiMessageMapper messageMapper;

    @Override
    public void save(Long tenantId, String conversationId, List<MessageReference> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        Long messageId = messageMapper.selectLastIdByRole(
                tenantId, conversationId, ChatMessage.ROLE_ASSISTANT);
        if (messageId == null) {
            log.warn("引用落库跳过：未找到归属消息 conv={} refs={}", conversationId, references.size());
            return;
        }
        for (MessageReference reference : references) {
            AiMessageReferenceDO entity = new AiMessageReferenceDO();
            entity.setTenantId(tenantId);
            entity.setMessageId(messageId);
            entity.setConvKey(conversationId);
            entity.setSeq(reference.seq());
            entity.setKbId(reference.kbId());
            entity.setDocId(reference.docId());
            entity.setDocName(reference.docName());
            entity.setScore(reference.score());
            entity.setContent(reference.content());
            referenceMapper.insert(entity);
        }
    }

    @Override
    public List<MessageReference> list(Long tenantId, String conversationId) {
        return referenceMapper.selectByConvKey(tenantId, conversationId).stream()
                .map(row -> new MessageReference(
                        row.getSeq() == null ? 0 : row.getSeq(),
                        row.getKbId(),
                        row.getDocId(),
                        row.getDocName(),
                        row.getScore() == null ? 0d : row.getScore(),
                        row.getContent()))
                .toList();
    }
}
