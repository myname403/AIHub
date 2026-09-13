package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.Chunk;
import com.aihub.ai.domain.model.DocumentInfo;
import com.aihub.ai.domain.spi.DocumentRepository;
import com.aihub.ai.infra.persistence.dataobject.AiDocumentChunkDO;
import com.aihub.ai.infra.persistence.dataobject.AiDocumentDO;
import com.aihub.ai.infra.persistence.mapper.AiDocumentChunkMapper;
import com.aihub.ai.infra.persistence.mapper.AiDocumentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 文档仓储实现（ai_document / ai_document_chunk）。
 * 所有查询强制带 tenant_id。
 */
@Repository
@RequiredArgsConstructor
public class DbDocumentRepository implements DocumentRepository {

    private final AiDocumentMapper documentMapper;
    private final AiDocumentChunkMapper chunkMapper;

    @Override
    public Long createDocument(Long tenantId, Long kbId, String name, String fileType, long size) {
        AiDocumentDO ddo = new AiDocumentDO();
        ddo.setTenantId(tenantId);
        ddo.setKbId(kbId);
        ddo.setName(name);
        ddo.setFileType(fileType);
        ddo.setSize(size);
        ddo.setStatus(DocumentInfo.STATUS_PROCESSING);
        documentMapper.insert(ddo);
        return ddo.getId();
    }

    @Override
    public void updateStatus(Long tenantId, Long docId, int status, String errorMsg) {
        AiDocumentDO ddo = documentMapper.selectById(docId);
        if (ddo == null || !tenantId.equals(ddo.getTenantId())) {
            return; // 出口校验：非本租户文档不允许修改
        }
        ddo.setStatus(status);
        ddo.setErrorMsg(errorMsg);
        documentMapper.updateById(ddo);
    }

    @Override
    public Optional<DocumentInfo> find(Long tenantId, Long docId) {
        AiDocumentDO ddo = documentMapper.selectById(docId);
        if (ddo == null || !tenantId.equals(ddo.getTenantId())) {
            return Optional.empty();
        }
        return Optional.of(toInfo(ddo));
    }

    @Override
    public List<DocumentInfo> listByKb(Long tenantId, Long kbId) {
        return documentMapper.selectList(Wrappers.<AiDocumentDO>lambdaQuery()
                        .eq(AiDocumentDO::getTenantId, tenantId)
                        .eq(AiDocumentDO::getKbId, kbId)
                        .orderByDesc(AiDocumentDO::getCreateTime))
                .stream().map(this::toInfo).toList();
    }

    @Override
    public void saveChunks(Long tenantId, Long kbId, Long docId, List<Chunk> chunks, int vectorStatus) {
        for (Chunk chunk : chunks) {
            AiDocumentChunkDO cdo = new AiDocumentChunkDO();
            cdo.setTenantId(tenantId);
            cdo.setKbId(kbId);
            cdo.setDocId(docId);
            cdo.setSeq(chunk.seq());
            cdo.setContent(chunk.content());
            cdo.setTokenCount(chunk.content().length() / 2); // 粗估
            cdo.setVectorStatus(vectorStatus);
            cdo.setCreateTime(LocalDateTime.now());
            chunkMapper.insert(cdo);
        }
    }

    @Override
    public void updateChunkStatus(Long tenantId, Long docId, int vectorStatus) {
        AiDocumentChunkDO patch = new AiDocumentChunkDO();
        patch.setVectorStatus(vectorStatus);
        chunkMapper.update(patch, Wrappers.<AiDocumentChunkDO>lambdaUpdate()
                .eq(AiDocumentChunkDO::getTenantId, tenantId)
                .eq(AiDocumentChunkDO::getDocId, docId));
    }

    private DocumentInfo toInfo(AiDocumentDO ddo) {
        return new DocumentInfo(ddo.getId(), ddo.getKbId(), ddo.getName(),
                ddo.getFileType(), ddo.getStatus(), ddo.getErrorMsg());
    }
}
