package com.aihub.ai.infra.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地磁盘暂存实现测试（真实读写磁盘，不打桩）。
 *
 * <p>重点：文件确实落盘了——这是「服务重启后仍能重试」的唯一依据。
 */
class LocalIngestFileStoreTest {

    @TempDir
    Path tempDir;

    private LocalIngestFileStore store() {
        return new LocalIngestFileStore(tempDir.toString());
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void savedFileSurvivesNewInstance() {
        LocalIngestFileStore writer = store();
        writer.save(1L, 100L, bytes("课程数据内容"));

        // 模拟服务重启：新建实例（之前的实例已不可用），只共享磁盘目录
        LocalIngestFileStore reader = store();
        Optional<byte[]> loaded = reader.load(1L, 100L);

        assertTrue(loaded.isPresent(), "重启后必须还能读到文件——这是重试的前提");
        assertArrayEquals(bytes("课程数据内容"), loaded.get());
    }

    @Test
    void loadReturnsEmptyWhenFileMissing() {
        assertTrue(store().load(1L, 999L).isEmpty(), "不存在的文件应返回 empty 而非抛异常");
    }

    @Test
    void deleteRemovesFile() {
        LocalIngestFileStore store = store();
        store.save(1L, 100L, bytes("x"));

        store.delete(1L, 100L);

        assertTrue(store.load(1L, 100L).isEmpty());
    }

    @Test
    void deleteIsIdempotentWhenFileMissing() {
        // 清理多次（如成功回调重入）不应抛异常
        LocalIngestFileStore store = store();
        store.delete(1L, 404L);
        store.delete(1L, 404L);

        assertTrue(store.load(1L, 404L).isEmpty());
    }

    /** 租户目录隔离：A 租户不能读到 B 租户的文件 */
    @Test
    void filesAreIsolatedByTenant() {
        LocalIngestFileStore store = store();
        store.save(1L, 100L, bytes("租户1的文档"));

        assertTrue(store.load(2L, 100L).isEmpty(), "同 docId 但不同租户不应互相可见");
    }

    @Test
    void sameDocIdInDifferentTenantsDoNotOverwrite() {
        LocalIngestFileStore store = store();
        store.save(1L, 100L, bytes("租户1"));
        store.save(2L, 100L, bytes("租户2"));

        assertArrayEquals(bytes("租户1"), store.load(1L, 100L).orElseThrow());
        assertArrayEquals(bytes("租户2"), store.load(2L, 100L).orElseThrow());
    }

    /** 重复上传同一文档应覆盖，而不是留下旧内容 */
    @Test
    void saveOverwritesExistingFile() {
        LocalIngestFileStore store = store();
        store.save(1L, 100L, bytes("旧版本"));
        store.save(1L, 100L, bytes("新版本内容"));

        assertArrayEquals(bytes("新版本内容"), store.load(1L, 100L).orElseThrow());
    }

    /** 原子写：不应留下 .tmp 残留文件 */
    @Test
    void saveLeavesNoTempFile() throws Exception {
        LocalIngestFileStore store = store();
        store.save(1L, 100L, bytes("内容"));

        Path tenantDir = tempDir.resolve("1");
        try (var entries = Files.list(tenantDir)) {
            assertFalse(entries.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "原子改名后不应残留临时文件");
        }
    }

    @Test
    void saveIgnoresNullBytes() {
        LocalIngestFileStore store = store();

        store.save(1L, 100L, null);

        assertTrue(store.load(1L, 100L).isEmpty());
    }

    @Test
    void handlesEmptyFile() {
        LocalIngestFileStore store = store();
        store.save(1L, 100L, new byte[0]);

        assertEquals(0, store.load(1L, 100L).orElseThrow().length);
    }
}
