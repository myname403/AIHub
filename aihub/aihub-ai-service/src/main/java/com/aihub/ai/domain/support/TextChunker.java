package com.aihub.ai.domain.support;

import com.aihub.ai.domain.model.Chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 文本切分器（领域层，不依赖任何框架）。
 *
 * <p>策略：优先按段落（空行）切分；超长段落按固定字数二次切分；
 * 相邻分片保留 overlap 字符重叠，避免关键信息被切断。
 * （课程实践结论：按段落 + 重叠的检索效果明显优于固定字数硬切）
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<Chunk> split(String text, int maxChars, int overlap) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int size = Math.max(maxChars, 100);
        int ov = Math.max(0, Math.min(overlap, size / 2));

        List<String> paragraphs = splitParagraphs(text);
        StringBuilder buffer = new StringBuilder();
        int seq = 0;
        for (String paragraph : paragraphs) {
            if (paragraph.length() > size) {
                // 先把缓冲区刷出去
                if (!buffer.isEmpty()) {
                    chunks.add(new Chunk(seq++, buffer.toString()));
                    buffer.setLength(0);
                }
                // 超长段落滑动窗口切分
                for (String piece : slidingWindow(paragraph, size, ov)) {
                    chunks.add(new Chunk(seq++, piece));
                }
                continue;
            }
            if (buffer.length() + paragraph.length() + 1 > size) {
                String flushed = buffer.toString();
                chunks.add(new Chunk(seq++, flushed));
                // 保留尾部 overlap，衔接上下文
                String tail = flushed.length() > ov ? flushed.substring(flushed.length() - ov) : flushed;
                buffer.setLength(0);
                buffer.append(tail);
            }
            if (!buffer.isEmpty()) {
                buffer.append('\n');
            }
            buffer.append(paragraph);
        }
        if (!buffer.isEmpty()) {
            chunks.add(new Chunk(seq++, buffer.toString()));
        }
        return chunks;
    }

    private static List<String> splitParagraphs(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> slidingWindow(String text, int size, int overlap) {
        List<String> pieces = new ArrayList<>();
        int step = Math.max(size - overlap, 1);
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + size, text.length());
            pieces.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
        }
        return pieces;
    }
}
