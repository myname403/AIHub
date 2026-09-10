package com.aihub.ai.domain.model;

/**
 * 产物（Agent 生成的表格 / 图表 / HTML 页面）。
 */
public record ArtifactInfo(
        Long id,
        String name,
        String mime,
        String previewUrl
) {
}
