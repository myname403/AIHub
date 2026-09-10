package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.ArtifactInfo;

import java.util.Optional;

/**
 * 产物存储 SPI（★ 扩展点）：Agent 生成的表格 / 图表 / HTML 落盘并支持预览。
 * 本地磁盘实现可替换为 OSS / MinIO。
 */
public interface ArtifactStore {

    ArtifactInfo save(Long tenantId, Long taskId, String name, String mime, byte[] content);

    /** 读取产物（含租户校验：非本租户产物不可见） */
    Optional<ArtifactContent> load(Long tenantId, Long artifactId);

    record ArtifactContent(String name, String mime, byte[] data) {
    }
}
