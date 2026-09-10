package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.DocumentInfo;
import com.aihub.ai.domain.spi.KnowledgeBaseRepository;
import com.aihub.ai.infra.persistence.do_.AiKnowledgeBaseDO;
import com.aihub.ai.infra.persistence.mapper.AiKnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库仓储实现（ai_knowledge_base）。
 */
@Repository
@RequiredArgsConstructor
public class DbKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final AiKnowledgeBaseMapper kbMapper;

    @Override
    public Long create(Long tenantId, String name, String embeddingModelCode, int vectorDim, String indexName) {
        AiKnowledgeBaseDO ddo = new AiKnowledgeBaseDO();
        ddo.setTenantId(tenantId);
        ddo.setName(name);
        ddo.setEmbeddingModelId(0L); // M2 使用默认 Embedding 模型，M5 支持按 KB 选型
        ddo.setVectorDim(vectorDim);
        ddo.setIndexName(indexName);
        ddo.setStatus(1);
        ddo.setCreateTime(LocalDateTime.now());
        kbMapper.insert(ddo);
        return ddo.getId();
    }

    @Override
    public List<DocumentInfo> list(Long tenantId) {
        return kbMapper.selectList(Wrappers.<AiKnowledgeBaseDO>lambdaQuery()
                        .eq(AiKnowledgeBaseDO::getTenantId, tenantId))
                .stream()
                .map(d -> new DocumentInfo(d.getId(), null, d.getName(), "kb", d.getStatus(), null))
                .toList();
    }

    @Override
    public boolean exists(Long tenantId, Long kbId) {
        return kbMapper.selectCount(Wrappers.<AiKnowledgeBaseDO>lambdaQuery()
                .eq(AiKnowledgeBaseDO::getTenantId, tenantId)
                .eq(AiKnowledgeBaseDO::getId, kbId)) > 0;
    }
}
