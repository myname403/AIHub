package com.aihub.ai.infra.storage;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * MinIO 真实端到端（类名 IT 结尾，日常构建不跑）。
 *
 * <p>运行前提：本机 MinIO 已就绪（docker compose up -d minio）：
 * <pre>
 *   mvnd -pl aihub-ai-service "-Dtest=MinioIngestFileStoreIT" test
 * </pre>
 *
 * <p>覆盖完整生命周期：懒建桶 → 存 → 读 → 覆盖 → 读缺失对象 → 删 → 删除幂等。
 * 使用独立测试桶 aihub-ingest-it，不污染开发数据。
 */
class MinioIngestFileStoreIT {

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @Test
    void fullCycleAgainstRealMinio() {
        MinioClient client = MinioClient.builder()
                .endpoint(env("AIHUB_MINIO_ENDPOINT", "http://127.0.0.1:9000"))
                .credentials(env("AIHUB_MINIO_ACCESS_KEY", "aihub"),
                        env("AIHUB_MINIO_SECRET_KEY", "aihub12345"))
                .build();
        StorageProperties properties = new StorageProperties();
        properties.getMinio().setBucket("aihub-ingest-it");
        MinioIngestFileStore store = new MinioIngestFileStore(client, properties);

        byte[] payload = "课程数据：多实例共享重试".getBytes(StandardCharsets.UTF_8);

        // 首次存取自动建桶（懒初始化）
        store.save(1L, 42L, payload);
        assertThat(store.load(1L, 42L)).isPresent().contains(payload);

        // 同租户同文档重复上传 → 覆盖
        byte[] v2 = "第二版内容".getBytes(StandardCharsets.UTF_8);
        store.save(1L, 42L, v2);
        assertThat(store.load(1L, 42L)).isPresent().contains(v2);

        // 不存在的对象 → empty（可重试提示「原始文件已丢失」的依据）
        assertThat(store.load(9L, 999L)).isEmpty();

        // 终态清理 + 幂等
        store.delete(1L, 42L);
        assertThat(store.load(1L, 42L)).isEmpty();
        assertThatCode(() -> store.delete(1L, 42L)).doesNotThrowAnyException();
    }
}
