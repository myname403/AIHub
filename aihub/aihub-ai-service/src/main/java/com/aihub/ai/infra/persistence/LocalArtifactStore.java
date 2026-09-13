package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.ArtifactInfo;
import com.aihub.ai.domain.spi.ArtifactStore;
import com.aihub.ai.infra.persistence.dataobject.AiArtifactDO;
import com.aihub.ai.infra.persistence.mapper.AiArtifactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 本地磁盘产物存储（ArtifactStore 实现，可替换为 OSS / MinIO）。
 *
 * <p>产物文件按租户分目录存放，路径形如
 * {@code {artifact-dir}/{tenantId}/{yyyyMM}/{uuid}.html}，元数据落 ai_artifact 表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalArtifactStore implements ArtifactStore {

    private final AiArtifactMapper artifactMapper;

    @Value("${aihub.artifact-dir:./data/artifacts}")
    private String artifactDir;

    @Value("${aihub.artifact-url-prefix:/api/ai/artifact}")
    private String urlPrefix;

    @Override
    public ArtifactInfo save(Long tenantId, Long taskId, String name, String mime, byte[] content) {
        try {
            Path dir = Paths.get(artifactDir, String.valueOf(tenantId));
            Files.createDirectories(dir);
            String ext = mime != null && mime.contains("html") ? "html" : "txt";
            String fileName = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "." + ext;
            Path file = dir.resolve(fileName);
            Files.write(file, content);

            AiArtifactDO ddo = new AiArtifactDO();
            ddo.setTenantId(tenantId);
            ddo.setTaskId(taskId);
            ddo.setName(name);
            ddo.setMime(mime);
            ddo.setFilePath(file.toAbsolutePath().toString());
            ddo.setSize((long) content.length);
            ddo.setPreviewUrl(urlPrefix + "/" + ddo.getId());
            artifactMapper.insert(ddo);

            ddo.setPreviewUrl(urlPrefix + "/" + ddo.getId());
            artifactMapper.updateById(ddo);

            log.info("产物已保存 tenant={} name={} size={}", tenantId, name, content.length);
            return new ArtifactInfo(ddo.getId(), name, mime, ddo.getPreviewUrl());
        } catch (IOException e) {
            throw new IllegalStateException("产物保存失败", e);
        }
    }

    @Override
    public java.util.Optional<ArtifactContent> load(Long tenantId, Long artifactId) {
        AiArtifactDO ddo = artifactMapper.selectById(artifactId);
        if (ddo == null || !tenantId.equals(ddo.getTenantId())) {
            return java.util.Optional.empty(); // 出口校验：非本租户产物不可见
        }
        try {
            return java.util.Optional.of(new ArtifactContent(
                    ddo.getName(), ddo.getMime(), Files.readAllBytes(Paths.get(ddo.getFilePath()))));
        } catch (IOException e) {
            log.warn("产物读取失败 id={}", artifactId, e);
            return java.util.Optional.empty();
        }
    }
}
