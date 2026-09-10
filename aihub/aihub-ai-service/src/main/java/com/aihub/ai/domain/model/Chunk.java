package com.aihub.ai.domain.model;

/**
 * 文档分片（领域层）。由纯 Java 切分器产生，与任何框架无关。
 */
public record Chunk(int seq, String content) {
}
