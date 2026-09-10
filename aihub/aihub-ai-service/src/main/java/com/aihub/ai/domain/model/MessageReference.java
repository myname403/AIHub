package com.aihub.ai.domain.model;

/**
 * 引用来源（RAG 溯源）。
 *
 * @param seq     引用序号，对应正文中的 [n]
 * @param kbId    知识库 ID
 * @param docId   文档 ID
 * @param docName 文档名（展示用）
 * @param score   相似度得分
 * @param content 命中分片原文
 */
public record MessageReference(
        int seq,
        Long kbId,
        Long docId,
        String docName,
        double score,
        String content
) {

    /** 由检索命中的分片构造引用 */
    public static MessageReference of(int seq, RetrievedChunk chunk) {
        return new MessageReference(
                seq,
                chunk.kbId(),
                chunk.docId(),
                chunk.docName(),
                chunk.score(),
                chunk.content());
    }
}
