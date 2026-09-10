package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.Chunk;
import com.aihub.ai.domain.model.DocumentInfo;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储 SPI。由 infra-persistence 实现（ai_document / ai_document_chunk）。
 */
public interface DocumentRepository {

    Long createDocument(Long tenantId, Long kbId, String name, String fileType, long size);

    void updateStatus(Long tenantId, Long docId, int status, String errorMsg);

    Optional<DocumentInfo> find(Long tenantId, Long docId);

    List<DocumentInfo> listByKb(Long tenantId, Long kbId);

    void saveChunks(Long tenantId, Long kbId, Long docId, List<Chunk> chunks, int vectorStatus);

    void updateChunkStatus(Long tenantId, Long docId, int vectorStatus);
}
