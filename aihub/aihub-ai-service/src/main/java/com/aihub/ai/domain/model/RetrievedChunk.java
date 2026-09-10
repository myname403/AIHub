package com.aihub.ai.domain.model;

/**
 * 检索命中的分片（带引用溯源信息）。
 */
public record RetrievedChunk(
        String content,
        double score,
        Long kbId,
        Long docId,
        String docName
) {
}
