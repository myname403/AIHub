package com.aihub.ai.infra.storage;

import com.aihub.ai.domain.spi.IngestFileStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 入库原始文件的本地磁盘存储。
 *
 * <p>目录布局：{@code {baseDir}/{tenantId}/{docId}.bin}
 * ——按租户分目录，既便于人工排查，也避免单目录文件过多。
 *
 * <p>写入用「临时文件 + 原子改名」：中途失败不会留下半截文件，
 * 保证 {@link #load} 读到的要么是完整内容、要么什么都没有。
 *
 * <p>已知限制：本实现面向单机部署。多实例部署时各节点的本地盘不共享，
 * 重试请求若落到另一台机器会读不到文件——那时应换 MinIO / OSS 实现。
 */
@Slf4j
@Component
public class LocalIngestFileStore implements IngestFileStore {

    private static final String SUFFIX = ".bin";

    private final Path baseDir;

    public LocalIngestFileStore(@Value("${aihub.rag.ingest-file-dir:.aihub/ingest}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
            log.info("入库文件暂存目录：{}", this.baseDir);
        } catch (IOException e) {
            // 不阻断启动：真正写入时会再次报错并留下可读日志
            log.warn("创建入库文件目录失败 {} err={}", this.baseDir, e.getMessage());
        }
    }

    @Override
    public void save(Long tenantId, Long docId, byte[] bytes) {
        if (bytes == null) {
            return;
        }
        Path target = pathOf(tenantId, docId);
        try {
            Files.createDirectories(target.getParent());
            // 先写临时文件再原子改名，避免读到写了一半的内容
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("暂存入库文件失败 tenant={} doc={}", tenantId, docId, e);
        }
    }

    @Override
    public Optional<byte[]> load(Long tenantId, Long docId) {
        Path target = pathOf(tenantId, docId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            log.warn("读取入库文件失败 tenant={} doc={} err={}", tenantId, docId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(Long tenantId, Long docId) {
        try {
            Files.deleteIfExists(pathOf(tenantId, docId));
        } catch (IOException e) {
            // 清理失败只是留了个文件，不影响业务，记日志即可
            log.warn("清理入库文件失败 tenant={} doc={} err={}", tenantId, docId, e.getMessage());
        }
    }

    private Path pathOf(Long tenantId, Long docId) {
        // tenantId / docId 都是 Long，天然不含路径分隔符，无需防目录穿越
        return baseDir.resolve(String.valueOf(tenantId)).resolve(docId + SUFFIX);
    }
}
