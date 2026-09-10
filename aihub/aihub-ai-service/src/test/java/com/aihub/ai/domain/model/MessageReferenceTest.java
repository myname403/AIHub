package com.aihub.ai.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 引用来源模型测试：序号对齐与空值透传。
 */
class MessageReferenceTest {

    @Test
    void shouldBuildFromRetrievedChunk() {
        RetrievedChunk chunk = new RetrievedChunk("分片正文", 0.83, 7L, 42L, "产品手册.md");

        MessageReference ref = MessageReference.of(3, chunk);

        assertEquals(3, ref.seq(), "引用序号应原样保留，供正文 [n] 对应");
        assertEquals(7L, ref.kbId());
        assertEquals(42L, ref.docId());
        assertEquals("产品手册.md", ref.docName());
        assertEquals(0.83, ref.score(), 1e-6);
        assertEquals("分片正文", ref.content());
    }

    @Test
    void shouldTolerateNullFieldsFromChunk() {
        RetrievedChunk chunk = new RetrievedChunk(null, 0d, null, null, null);

        MessageReference ref = MessageReference.of(1, chunk);

        assertEquals(1, ref.seq());
        assertNull(ref.kbId());
        assertNull(ref.docName());
        assertNull(ref.content());
    }
}
