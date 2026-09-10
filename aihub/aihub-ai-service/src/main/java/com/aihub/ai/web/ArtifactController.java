package com.aihub.ai.web;

import com.aihub.ai.domain.spi.ArtifactStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产物预览：在线查看 Agent 生成的表格 / 图表 / HTML 页面。
 */
@RestController
@RequestMapping("/api/ai/artifact")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactStore artifactStore;

    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> preview(@PathVariable Long artifactId) {
        return artifactStore.load(currentTenantId(), artifactId)
                .<ResponseEntity<byte[]>>map(content -> {
                    MediaType type = content.mime() != null && content.mime().contains("html")
                            ? MediaType.TEXT_HTML
                            : MediaType.APPLICATION_OCTET_STREAM;
                    return ResponseEntity.ok()
                            .contentType(new MediaType(type, java.nio.charset.StandardCharsets.UTF_8))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(
                                            content.name() == null ? "artifact" : content.name(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .body(content.data());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private Long currentTenantId() {
        Long tenantId = com.aihub.common.tenant.TenantContext.getTenantId();
        return tenantId == null ? 0L : tenantId;
    }
}
