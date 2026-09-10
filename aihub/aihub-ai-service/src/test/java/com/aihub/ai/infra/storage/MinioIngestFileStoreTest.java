package com.aihub.ai.infra.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MinIO 入库文件存储单测：mock MinioClient 验证协议交互，
 * 重点钉住三件事——对象键布局与本地实现一致、桶懒建且只建一次、
 * 异常语义符合 SPI 契约（save/delete 吞异常，load 返回 empty）。
 */
class MinioIngestFileStoreTest {

    private MinioClient client;
    private MinioIngestFileStore store;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(MinioClient.class);
        store = new MinioIngestFileStore(client, new StorageProperties());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void savePutsObjectWithTenantKeyAndCreatesBucketOnce() throws Exception {
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        store.save(1L, 100L, bytes("课程数据内容"));
        store.save(1L, 101L, bytes("另一份"));

        // 桶懒建且只建一次：第二次 save 不再询问（ready 标记生效）
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
        verify(client, times(1)).makeBucket(any(MakeBucketArgs.class));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client, times(2)).putObject(captor.capture());
        PutObjectArgs first = captor.getAllValues().get(0);
        // 对象键与本地磁盘目录布局 {tenantId}/{docId}.bin 一致，人工排查时能对上
        assertThat(first.bucket()).isEqualTo("aihub-ingest");
        assertThat(first.object()).isEqualTo("1/100.bin");
        assertThat(captor.getAllValues().get(1).object()).isEqualTo("1/101.bin");
    }

    @Test
    void saveIgnoresNullBytes() {
        store.save(1L, 100L, null);
        verifyNoInteractions(client);
    }

    @Test
    void saveSwallowsFailure() throws Exception {
        // 暂存失败不阻断上传主流程（SPI 契约）：入库线程读不到会落 failed 可重试
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        doThrow(new RuntimeException("S3 超时")).when(client).putObject(any(PutObjectArgs.class));

        assertThatCode(() -> store.save(1L, 100L, bytes("x"))).doesNotThrowAnyException();
    }

    @Test
    void bucketCheckFailureRetriesNextTime() throws Exception {
        // 建桶确认失败不能把 ready 置位：第一次失败被吞，下一次操作要重新尝试
        when(client.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new RuntimeException("连接失败"))
                .thenReturn(true);

        store.save(1L, 100L, bytes("第一次（确认失败被吞）"));
        store.save(1L, 100L, bytes("第二次（成功）"));

        verify(client, times(2)).bucketExists(any(BucketExistsArgs.class));
        verify(client, times(1)).putObject(any(PutObjectArgs.class));
    }

    @Test
    void loadReturnsBytes() throws Exception {
        // 注意：必须返回真实的 GetObjectResponse（Mockito inline mock maker 会把
        // Answer 结果按方法声明的返回类型强转，返回裸 InputStream 会 ClassCastException）
        when(client.getObject(any(GetObjectArgs.class)))
                .thenAnswer(inv -> new GetObjectResponse(
                        Headers.of(), "aihub-ingest", "us-east-1", "1/100.bin",
                        new ByteArrayInputStream(bytes("重试用的原始内容"))));

        Optional<byte[]> loaded = store.load(1L, 100L);

        assertThat(loaded).isPresent();
        assertThat(new String(loaded.get(), StandardCharsets.UTF_8)).isEqualTo("重试用的原始内容");
    }

    @Test
    void loadReturnsEmptyWhenObjectMissing() throws Exception {
        // 对象不存在 / 网络故障一律 empty（SPI 契约），应用层据此给「请重新上传」
        when(client.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("NoSuchKey"));

        assertThat(store.load(1L, 404L)).isEmpty();
    }

    @Test
    void deleteSwallowsFailure() throws Exception {
        doThrow(new RuntimeException("boom")).when(client).removeObject(any(RemoveObjectArgs.class));

        assertThatCode(() -> store.delete(1L, 100L)).doesNotThrowAnyException();
    }
}
