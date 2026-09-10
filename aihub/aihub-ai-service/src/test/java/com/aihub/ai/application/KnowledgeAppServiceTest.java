package com.aihub.ai.application;

import com.aihub.ai.domain.model.DocumentInfo;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.domain.spi.DocumentRepository;
import com.aihub.ai.domain.spi.IngestFileStore;
import com.aihub.ai.domain.spi.IngestTaskRepository;
import com.aihub.ai.domain.spi.KnowledgeBaseRepository;
import com.aihub.ai.domain.spi.KnowledgeIndexer;
import com.aihub.ai.domain.spi.KnowledgeRetriever;
import com.aihub.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步入库编排测试（M2）。
 *
 * <p>重点验证三件事：<br>
 * 1) 提交立即返回 taskId，不被解析/向量化阻塞；<br>
 * 2) 各阶段进度按 parse→split→save→vector 顺序上报；<br>
 * 3) 失败后按配置自动重试，耗尽后落 failed。
 */
class KnowledgeAppServiceTest {

    private KnowledgeBaseRepository kbRepository;
    private DocumentRepository documentRepository;
    private KnowledgeIndexer knowledgeIndexer;
    private KnowledgeRetriever knowledgeRetriever;
    private AppRepository appRepository;
    private IngestTaskRepository ingestTaskRepository;
    private IngestFileStore ingestFileStore;
    private com.aihub.api.client.PlatformClient platformClient;
    private KnowledgeAppService service;

    /** 记录进度上报顺序 */
    private final List<String> stages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kbRepository = mock(KnowledgeBaseRepository.class);
        documentRepository = mock(DocumentRepository.class);
        knowledgeIndexer = mock(KnowledgeIndexer.class);
        knowledgeRetriever = mock(KnowledgeRetriever.class);
        appRepository = mock(AppRepository.class);
        ingestTaskRepository = mock(IngestTaskRepository.class);
        ingestFileStore = mock(IngestFileStore.class);
        platformClient = mock(com.aihub.api.client.PlatformClient.class);

        service = new KnowledgeAppService(kbRepository, documentRepository, knowledgeIndexer,
                knowledgeRetriever, appRepository, ingestTaskRepository, ingestFileStore, platformClient);
        ReflectionTestUtils.setField(service, "chunkSize", 500);
        ReflectionTestUtils.setField(service, "chunkOverlap", 100);
        ReflectionTestUtils.setField(service, "ingestMaxRetry", 2);

        when(kbRepository.exists(anyLong(), anyLong())).thenReturn(true);
        when(documentRepository.createDocument(anyLong(), anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(100L);
        when(ingestTaskRepository.create(anyLong(), anyLong(), anyLong())).thenReturn(999L);
        doAnswer(inv -> {
            stages.add(inv.getArgument(3));
            return null;
        }).when(ingestTaskRepository).updateProgress(anyLong(), anyLong(), anyInt(), anyString(), anyInt());
    }

    private byte[] longText() {
        String body = "AIHub 是一个通用 AI 能力中台，提供对话、知识库与 Agent 编排能力。".repeat(80);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void submitShouldReturnTaskIdImmediately() throws Exception {
        CountDownLatch indexLatch = new CountDownLatch(1);
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> {
                    indexLatch.countDown();
                    return 3;
                });

        Long taskId = service.submitIngest(1L, 10L, "手册.md", longText());

        assertEquals(999L, taskId);
        assertTrue(indexLatch.await(5, TimeUnit.SECONDS), "异步线程应最终完成向量化");
    }

    @Test
    void shouldReportStagesInOrder() throws Exception {
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any())).thenReturn(3);

        service.submitIngest(1L, 10L, "手册.md", longText());

        verify(ingestTaskRepository, timeout(5000)).markDone(eq(1L), eq(999L));
        assertEquals(List.of("parse", "split", "save", "vector"), stages,
                "阶段必须按解析→分片→落库→向量化顺序上报");
    }

    @Test
    void shouldMarkDoneAndPersistChunks() throws Exception {
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any())).thenReturn(7);

        service.submitIngest(1L, 10L, "手册.md", longText());

        verify(documentRepository, timeout(5000)).saveChunks(eq(1L), eq(10L), eq(100L), any(), eq(0));
        verify(documentRepository, timeout(5000)).updateChunkStatus(eq(1L), eq(100L), eq(1));
        verify(documentRepository, timeout(5000))
                .updateStatus(eq(1L), eq(100L), eq(DocumentInfo.STATUS_DONE), eq(null));
    }

    @Test
    void shouldRetryThenSucceedAfterTransientFailure() throws Exception {
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("向量库抖动"))
                .thenReturn(5);

        service.submitIngest(1L, 10L, "手册.md", longText());

        // 第一次失败 → 记录失败态并重试一次 → 成功
        verify(ingestTaskRepository, timeout(8000)).prepareRetry(1L, 999L);
        verify(ingestTaskRepository, timeout(8000)).markDone(1L, 999L);
        // 中途的失败会先落 markFailed（让前端能立刻看到原因），但最终状态必须是完成
        verify(ingestTaskRepository, timeout(8000)).markFailed(eq(1L), eq(999L), anyString());
    }

    @Test
    void shouldMarkFailedAfterRetriesExhausted() throws Exception {
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("向量库不可用"));

        service.submitIngest(1L, 10L, "手册.md", longText());

        verify(ingestTaskRepository, timeout(15000)).markFailed(eq(1L), eq(999L), anyString());
        verify(documentRepository, timeout(15000))
                .updateStatus(eq(1L), eq(100L), eq(DocumentInfo.STATUS_FAILED), anyString());
        verify(ingestTaskRepository, never()).markDone(anyLong(), anyLong());
    }

    @Test
    void shouldRejectUnsupportedFileType() {
        BizException e = assertThrows(BizException.class,
                () -> service.submitIngest(1L, 10L, "报表.xlsx", longText()));

        assertTrue(e.getMessage().contains("暂不支持该文件类型"));
        verify(documentRepository, never())
                .createDocument(anyLong(), anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldRejectWhenKbMissing() {
        when(kbRepository.exists(anyLong(), anyLong())).thenReturn(false);

        assertThrows(BizException.class, () -> service.submitIngest(1L, 10L, "手册.md", longText()));
        verify(ingestTaskRepository, never()).create(anyLong(), anyLong(), anyLong());
    }

    @Test
    void retryShouldBeRejectedWhenNotFailed() {
        when(ingestTaskRepository.find(1L, 999L)).thenReturn(Optional.of(
                new com.aihub.ai.domain.model.IngestTask(
                        999L, 1L, 10L, 100L, com.aihub.ai.domain.model.IngestTask.STATUS_DONE,
                        "vector", 100, 0, null)));

        assertFalse(service.retryIngest(1L, 999L), "已完成的任务不应被重试");
    }

    @Test
    void retryShouldBeRejectedWhenRetryLimitReached() {
        when(ingestTaskRepository.find(1L, 999L)).thenReturn(Optional.of(
                new com.aihub.ai.domain.model.IngestTask(
                        999L, 1L, 10L, 100L, com.aihub.ai.domain.model.IngestTask.STATUS_FAILED,
                        "vector", 70, 2, "向量库不可用")));

        assertFalse(service.retryIngest(1L, 999L), "超过重试上限应拒绝");
    }

    @Test
    void retryShouldFailWhenOriginalFileMissing() {
        when(ingestTaskRepository.find(1L, 999L)).thenReturn(Optional.of(
                new com.aihub.ai.domain.model.IngestTask(
                        999L, 1L, 10L, 100L, com.aihub.ai.domain.model.IngestTask.STATUS_FAILED,
                        "vector", 70, 0, "向量库不可用")));
        // 暂存介质里没有该文档（例如文件被人工删除）
        when(ingestFileStore.load(1L, 100L)).thenReturn(Optional.empty());

        assertFalse(service.retryIngest(1L, 999L));
        verify(ingestTaskRepository).markFailed(eq(1L), eq(999L), anyString());
    }

    /* ---------------- 文件暂存生命周期 ---------------- */

    @Test
    void shouldPersistOriginalFileOnSubmit() {
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);

        service.submitIngest(1L, 10L, "手册.md", longText());

        // 提交时必须落暂存：这是「重启后仍可重试」的前提
        verify(ingestFileStore, timeout(5000))
                .save(eq(1L), eq(100L), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    /** 成功入库后应清理暂存文件，避免磁盘无限增长 */
    @Test
    void shouldDeleteStagedFileAfterSuccess() throws Exception {
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any())).thenReturn(3);

        service.submitIngest(1L, 10L, "手册.md", longText());

        verify(ingestFileStore, timeout(5000)).delete(1L, 100L);
    }

    /**
     * 关键约束：失败时<b>不能</b>删暂存文件，否则重试就没有原料了。
     */
    @Test
    void shouldKeepStagedFileAfterFailure() throws Exception {
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("向量库不可用"));

        service.submitIngest(1L, 10L, "手册.md", longText());

        verify(ingestTaskRepository, timeout(15000)).markFailed(eq(1L), eq(999L), anyString());
        verify(ingestFileStore, never()).delete(anyLong(), anyLong());
    }

    /**
     * 【回归】服务重启后仍能重试：只要暂存介质里有文件，
     * 就应按文档记录里的文件名重新执行，而不是报「原始文件已释放」。
     */
    @Test
    void retryShouldWorkAfterServiceRestart() throws Exception {
        when(ingestTaskRepository.find(1L, 999L)).thenReturn(Optional.of(
                new com.aihub.ai.domain.model.IngestTask(
                        999L, 1L, 10L, 100L, com.aihub.ai.domain.model.IngestTask.STATUS_FAILED,
                        "vector", 70, 0, "向量库不可用")));
        when(ingestFileStore.load(1L, 100L)).thenReturn(Optional.of(longText()));
        // 文件名来自持久化的文档记录，而非内存 Map
        when(documentRepository.find(1L, 100L)).thenReturn(Optional.of(
                new DocumentInfo(100L, 10L, "手册.md", "md", DocumentInfo.STATUS_FAILED, "向量库不可用")));
        when(knowledgeIndexer.index(anyLong(), anyLong(), anyLong(), any())).thenReturn(4);

        boolean accepted = service.retryIngest(1L, 999L);

        assertTrue(accepted, "暂存文件仍在时应受理重试");
        verify(ingestTaskRepository).prepareRetry(1L, 999L);
        verify(ingestTaskRepository, timeout(5000)).markDone(1L, 999L);
    }

    @Test
    void shouldExposeProgress() {
        when(ingestTaskRepository.find(1L, 999L)).thenReturn(Optional.of(
                new com.aihub.ai.domain.model.IngestTask(
                        999L, 1L, 10L, 100L, com.aihub.ai.domain.model.IngestTask.STATUS_RUNNING,
                        "vector", 70, 0, null)));

        var progress = service.ingestProgress(1L, 999L);

        assertTrue(progress.isPresent());
        assertEquals("vector", progress.get().stage());
        assertEquals(70, progress.get().progress());
    }
}
