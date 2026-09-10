package com.aihub.ai.domain.support;

import com.aihub.ai.domain.model.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void emptyTextReturnsNoChunk() {
        assertTrue(TextChunker.split("", 500, 100).isEmpty());
        assertTrue(TextChunker.split(null, 500, 100).isEmpty());
        assertTrue(TextChunker.split("   \n  ", 500, 100).isEmpty());
    }

    @Test
    void shortTextKeepsSingleChunk() {
        List<Chunk> chunks = TextChunker.split("你好，AIHub。", 500, 100);
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).seq());
    }

    @Test
    void longTextIsSplitIntoMultipleChunks() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("第").append(i).append("行内容，用于验证切分效果。\n\n");
        }
        List<Chunk> chunks = TextChunker.split(text.toString(), 300, 60);
        assertTrue(chunks.size() > 1, "长文本必须被切分为多片");
        for (Chunk chunk : chunks) {
            assertTrue(chunk.content().length() <= 300 + 60,
                    "单个分片不应显著超过 size + overlap");
        }
    }

    @Test
    void seqIsContinuousFromZero() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("段落").append(i).append("：这是一段用于测试的文本。\n\n");
        }
        List<Chunk> chunks = TextChunker.split(text.toString(), 200, 40);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).seq(), "seq 必须从 0 连续递增");
        }
    }

    @Test
    void overlapKeepsTailOfPreviousChunk() {
        // 两段各自都不超长、但合起来超限，第二段分片应带上第一段结尾的一部分
        String first = "A".repeat(150);
        String second = "B".repeat(150);
        List<Chunk> chunks = TextChunker.split(first + "\n\n" + second, 200, 50);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(1).content().contains("A"),
                "第二个分片应携带上一分片的尾部重叠内容");
    }
}
