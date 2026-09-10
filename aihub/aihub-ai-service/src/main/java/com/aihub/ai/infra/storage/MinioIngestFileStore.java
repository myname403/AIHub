package com.aihub.ai.infra.storage;

import com.aihub.ai.domain.spi.IngestFileStore;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

/**
 * 入库原始文件的 MinIO / S3 对象存储实现。
 *
 * <p>对象键与本地磁盘实现的目录布局一致：{@code {tenantId}/{docId}.bin}
 * ——按租户分前缀，控制台人工排查时一眼能对上。
 *
 * <p>异常语义与 {@link LocalIngestFileStore} 对齐（SPI 契约见
 * {@link IngestFileStore}）：save / delete 失败只记日志不抛（调用方主流程
 * 不因暂存介质故障中断，读不到时会落 failed 可重试）；load 失败返回 empty，
 * 由应用层给出「请重新上传」这类可读提示。
 *
 * <p>桶是<b>懒初始化</b>的：构造期不做任何网络 IO，因此无 MinIO 环境也能
 * 安全创建 bean（Spring 上下文测试不受影响）；首次使用时确认桶存在，
 * 不存在则创建。确认失败不置 ready 标记，下次操作自动重试。
 */
@Slf4j
public class MinioIngestFileStore implements IngestFileStore {

    private static final String SUFFIX = ".bin";
    private static final String CONTENT_TYPE = "application/octet-stream";

    private final MinioClient client;
    private final StorageProperties properties;

    /** 桶是否已确认存在（懒初始化标记，双检锁防并发重复建桶） */
    private volatile boolean bucketReady;

    public MinioIngestFileStore(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void save(Long tenantId, Long docId, byte[] bytes) {
        if (bytes == null) {
            return;
        }
        try {
            ensureBucket();
            // putObject 是原子语义：读到的一定是完整对象，无需本地实现的临时文件改名
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket())
                        .object(keyOf(tenantId, docId))
                        .contentType(CONTENT_TYPE)
                        .stream(in, bytes.length, -1)
                        .build());
            }
        } catch (Exception e) {
            log.error("暂存入库文件失败(MinIO) tenant={} doc={}", tenantId, docId, e);
        }
    }

    @Override
    public Optional<byte[]> load(Long tenantId, Long docId) {
        try {
            ensureBucket();
            try (InputStream in = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket())
                    .object(keyOf(tenantId, docId))
                    .build())) {
                return Optional.of(in.readAllBytes());
            }
        } catch (Exception e) {
            log.warn("读取入库文件失败(MinIO) tenant={} doc={} err={}", tenantId, docId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(Long tenantId, Long docId) {
        try {
            // 不做 ensureBucket：清理属垃圾回收性质，桶都没建过自然也谈不上清理
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket())
                    .object(keyOf(tenantId, docId))
                    .build());
        } catch (Exception e) {
            log.warn("清理入库文件失败(MinIO) tenant={} doc={} err={}", tenantId, docId, e.getMessage());
        }
    }

    private String keyOf(Long tenantId, Long docId) {
        // tenantId / docId 都是 Long，天然不含路径分隔符，无对象键注入风险
        return tenantId + "/" + docId + SUFFIX;
    }

    private String bucket() {
        return properties.getMinio().getBucket();
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket()).build());
                log.info("已创建 MinIO 桶 {}", bucket());
            }
            bucketReady = true;
        }
    }
}
