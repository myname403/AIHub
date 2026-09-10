package com.aihub.ai.application;

import com.aihub.ai.domain.model.Chunk;
import com.aihub.ai.domain.model.DocumentInfo;
import com.aihub.ai.domain.model.IngestTask;
import com.aihub.ai.domain.model.RetrievedChunk;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.domain.spi.DocumentRepository;
import com.aihub.ai.domain.spi.IngestFileStore;
import com.aihub.ai.domain.spi.IngestTaskRepository;
import com.aihub.ai.domain.spi.KnowledgeBaseRepository;
import com.aihub.ai.domain.spi.KnowledgeIndexer;
import com.aihub.ai.domain.spi.KnowledgeRetriever;
import com.aihub.ai.domain.spi.MetricsRecorder;
import com.aihub.ai.domain.support.TextChunker;
import com.aihub.api.client.PlatformClient;
import com.aihub.api.client.QuotaDimensions;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.common.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 知识库编排服务（M2 · RAG 全链路）。
 *
 * <p>入库链路：上传 → 解析 → 分片 → 落库 → 向量化 → 状态回写
 * 检索链路：查询 → 向量召回（强制租户过滤）→ TenantGuard → 返回引用
 *
 * <p>入库为<b>异步</b>：上传接口只落文档与任务记录并立即返回 taskId，
 * 真正的解析/向量化在独立线程池执行，进度写 ai_ingest_task 供前端轮询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAppService {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeIndexer knowledgeIndexer;
    private final KnowledgeRetriever knowledgeRetriever;
    private final AppRepository appRepository;
    private final IngestTaskRepository ingestTaskRepository;
    private final IngestFileStore ingestFileStore;
    private final PlatformClient platformClient;
    /**
     * 指标埋点端口（domain SPI，infra 提供实现）。
     * 用 setter 注入而非构造器：未接入指标时保持 NOOP，单测无需构造它。
     */
    private MetricsRecorder metrics = MetricsRecorder.NOOP;

    // 入库参数保留 @Value 而非复用 infra 侧的 RagProperties：架构规则禁止 application 层
    // 依赖 infra，@Value 字段在启动时绑定一次，这些低频参数改后需重启生效
    // （哪些键热更新见 docs/nacos/ 与 README 的「Nacos 动态配置」章节）
    @Value("${aihub.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${aihub.rag.chunk-overlap:100}")
    private int chunkOverlap;

    /** 入库失败自动重试次数上限 */
    @Value("${aihub.rag.ingest-max-retry:2}")
    private int ingestMaxRetry;

    /** 入库线程池：解析与向量化都是 IO 密集，并发不宜过高以免压垮向量库 */
    private final ExecutorService ingestExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "aihub-ingest-" + System.nanoTime() % 100000);
        t.setDaemon(true);
        return t;
    });

    /** M2+ 支持 txt / md / pdf / docx */
    private static final Set<String> SUPPORTED = Set.of("txt", "md", "markdown", "pdf", "docx");

    /** 解析文档为纯文本（按类型分发；解析失败抛出可读错误） */
    private String parseText(String filename, byte[] bytes) {
        String type = fileTypeOf(filename);
        try {
            return switch (type) {
                case "pdf" -> parsePdf(bytes);
                case "docx" -> parseDocx(bytes);
                default -> new String(bytes, StandardCharsets.UTF_8);
            };
        } catch (Exception e) {
            log.warn("文档解析失败 type={} err={}", type, e.getMessage());
            throw new BizException(ResultCode.PARAM_ERROR, "文档解析失败：" + type);
        }
    }

    private String parsePdf(byte[] bytes) throws Exception {
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
        }
    }

    private String parseDocx(byte[] bytes) throws Exception {
        try (var in = new java.io.ByteArrayInputStream(bytes);
             var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(in);
             var extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    public Long createKb(Long tenantId, String name, int vectorDim) {
        String indexName = "aihub:kb:" + tenantId;
        return kbRepository.create(tenantId, name, "default", vectorDim, indexName);
    }

    public List<DocumentInfo> listKb(Long tenantId) {
        return kbRepository.list(tenantId);
    }

    public List<DocumentInfo> listDocuments(Long tenantId, Long kbId) {
        requireKb(tenantId, kbId);
        return documentRepository.listByKb(tenantId, kbId);
    }

    /**
     * 提交文档入库（异步）：落文档 + 建任务，立即返回任务 ID。
     *
     * <p>原实现是同步阻塞——大文件解析加向量化会占满请求线程，
     * 现在改为提交即返回，前端凭 taskId 轮询进度。
     *
     * @return 入库任务 ID
     */
    public Long submitIngest(Long tenantId, Long kbId, String filename, byte[] bytes) {
        requireKb(tenantId, kbId);

        String fileType = fileTypeOf(filename);
        if (!SUPPORTED.contains(fileType)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "暂不支持该文件类型（当前支持 txt / md / pdf / docx）");
        }
        checkDocQuota(tenantId, kbId);

        Long docId = documentRepository.createDocument(tenantId, kbId, filename, fileType, bytes.length);
        Long taskId = ingestTaskRepository.create(tenantId, kbId, docId);
        // 原始文件落盘暂存：服务重启后仍能重试（早先用进程内 Map，重启即丢）
        ingestFileStore.save(tenantId, docId, bytes);
        // wrap：把提交请求的链路 ID 带到入库工作线程，否则入库日志孤立无链路
        ingestExecutor.submit(TraceContext.wrap(
                () -> runWithRetry(tenantId, kbId, docId, taskId, filename, bytes)));
        log.info("文档入库已提交 tenant={} kb={} doc={} task={}", tenantId, kbId, docId, taskId);
        return taskId;
    }

    /** 查询入库进度（前端轮询） */
    public Optional<IngestTask> ingestProgress(Long tenantId, Long taskId) {
        return ingestTaskRepository.find(tenantId, taskId);
    }

    /**
     * doc 维度配额扣减（M5）：入库是重操作（解析 + 向量化），单独限额。
     * 平台服务不可用时降级放行——能力不因统计故障而不可用。
     */
    private void checkDocQuota(Long tenantId, Long kbId) {
        try {
            var resp = platformClient.consume(new PlatformClient.QuotaConsumeRequest(
                    java.util.UUID.randomUUID().toString(), tenantId, String.valueOf(kbId),
                    List.of(new PlatformClient.QuotaItem(QuotaDimensions.DOC, 1))));
            var result = resp == null ? null : resp.getData();
            if (result != null && !result.allowed()) {
                throw new BizException(ResultCode.QUOTA_EXCEEDED, result.reason());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("doc 配额校验失败（已降级放行）tenant={} err={}", tenantId, e.getMessage());
        }
    }

    /**
     * 手动重试失败的入库任务。
     *
     * @return 是否受理（任务不存在、非失败态或超过重试上限时返回 false）
     */
    public boolean retryIngest(Long tenantId, Long taskId) {
        IngestTask task = ingestTaskRepository.find(tenantId, taskId)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "入库任务不存在"));
        if (!task.retryable(ingestMaxRetry)) {
            return false;
        }
        // 从暂存介质重读原始文件（本地磁盘/对象存储），服务重启后依然可用
        byte[] bytes = ingestFileStore.load(tenantId, task.docId()).orElse(null);
        if (bytes == null) {
            ingestTaskRepository.markFailed(tenantId, taskId, "原始文件已丢失，请重新上传");
            return false;
        }
        // 文件名从文档记录取——它随文档持久化，不像内存 Map 会随重启丢失
        String filename = documentRepository.find(tenantId, task.docId())
                .map(DocumentInfo::name)
                .orElse("document");
        ingestTaskRepository.prepareRetry(tenantId, taskId);
        // 必须走 ingest：它负责写终态（成功 markDone / 失败 markFailed 并保留暂存文件）
        ingestExecutor.submit(TraceContext.wrap(() -> {
            try {
                ingest(tenantId, task.kbId(), task.docId(), taskId, filename, bytes);
            } catch (Exception e) {
                // 失败态已由 ingest 落库，此处只记日志——重试是后台任务，异常无处可抛
                log.warn("手动重试入库失败 tenant={} doc={} task={} err={}",
                        tenantId, task.docId(), taskId, e.getMessage());
            }
        }));
        return true;
    }

    /**
     * 文档入库执行体（异步任务内部调用；测试也可直接调用）。
     *
     * @return 入库的分片数量
     */
    public int ingest(Long tenantId, Long kbId, Long docId, Long taskId, String filename, byte[] bytes) {
        try {
            int indexed = execute(tenantId, kbId, docId, taskId, filename, bytes);
            ingestTaskRepository.markDone(tenantId, taskId);
            documentRepository.updateStatus(tenantId, docId, DocumentInfo.STATUS_DONE, null);
            // 成功后才清理暂存文件：失败态必须保留文件以便重试
            ingestFileStore.delete(tenantId, docId);
            log.info("文档入库完成 tenant={} kb={} doc={} indexed={}", tenantId, kbId, docId, indexed);
            return indexed;
        } catch (Exception e) {
            String reason = e instanceof BizException ? e.getMessage() : "向量化失败：" + e.getMessage();
            ingestTaskRepository.markFailed(tenantId, taskId, reason);
            documentRepository.updateStatus(tenantId, docId, DocumentInfo.STATUS_FAILED, reason);
            log.error("文档入库失败 tenant={} doc={}", tenantId, docId, e);
            if (e instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(ResultCode.SYSTEM_ERROR, "文档入库失败");
        }
    }

    /** 指标实现由 infra 注入；未注入时保持 NOOP，指标缺失不影响业务 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(MetricsRecorder metrics) {
        this.metrics = metrics == null ? MetricsRecorder.NOOP : metrics;
    }

    /** 入库阶段计时包装：统一走 domain 端口，未接入时直接执行 */
    private <T> T timed(String stage, java.util.function.Supplier<T> action) {
        return metrics.timeStage(stage, action);
    }

    /** 真正干活：解析 → 分片 → 落库 → 向量化，各阶段上报进度与耗时指标 */
    private int execute(Long tenantId, Long kbId, Long docId, Long taskId, String filename, byte[] bytes) {
        ingestTaskRepository.updateProgress(tenantId, taskId,
                IngestTask.STATUS_RUNNING, IngestTask.STAGE_PARSE, 10);
        documentRepository.updateStatus(tenantId, docId, DocumentInfo.STATUS_PROCESSING, null);
        // 分阶段计时：能直接看出瓶颈是解析、分片还是向量化
        String text = timed(IngestTask.STAGE_PARSE, () -> parseText(filename, bytes));
        if (text.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "文档内容为空");
        }

        ingestTaskRepository.updateProgress(tenantId, taskId,
                IngestTask.STATUS_RUNNING, IngestTask.STAGE_SPLIT, 30);
        List<Chunk> chunks = timed(IngestTask.STAGE_SPLIT,
                () -> TextChunker.split(text, chunkSize, chunkOverlap));
        if (chunks.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "文档切分后无有效内容");
        }

        ingestTaskRepository.updateProgress(tenantId, taskId,
                IngestTask.STATUS_RUNNING, IngestTask.STAGE_SAVE, 50);
        timed(IngestTask.STAGE_SAVE, () -> {
            documentRepository.saveChunks(tenantId, kbId, docId, chunks, 0);
            return null;
        });

        ingestTaskRepository.updateProgress(tenantId, taskId,
                IngestTask.STATUS_RUNNING, IngestTask.STAGE_VECTOR, 70);
        int indexed = timed(IngestTask.STAGE_VECTOR,
                () -> knowledgeIndexer.index(tenantId, kbId, docId, chunks));
        documentRepository.updateChunkStatus(tenantId, docId, 1);
        return indexed;
    }

    /** 带重试的执行：失败后按 ingestMaxRetry 自动重试，指数退避 */
    private void runWithRetry(Long tenantId, Long kbId, Long docId, Long taskId,
                              String filename, byte[] bytes) {
        for (int attempt = 0; attempt <= ingestMaxRetry; attempt++) {
            try {
                if (attempt > 0) {
                    ingestTaskRepository.prepareRetry(tenantId, taskId);
                    backoff(attempt);
                }
                ingest(tenantId, kbId, docId, taskId, filename, bytes);
                return;
            } catch (Exception e) {
                if (attempt == ingestMaxRetry) {
                    log.error("文档入库重试耗尽 tenant={} doc={} attempts={}", tenantId, docId, attempt + 1);
                    return; // 失败状态已由 ingest 写入
                }
                log.warn("文档入库第 {} 次失败，准备重试 doc={} err={}", attempt + 1, docId, e.getMessage());
            }
        }
    }

    private void backoff(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(Math.min(200L * (1L << (attempt - 1)), 2000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 检索调试台：输入问题查看命中分片与相似度 */
    public List<RetrievedChunk> search(Long tenantId, Long kbId, String query, int topK, Double threshold) {
        requireKb(tenantId, kbId);
        return knowledgeRetriever.retrieve(tenantId, kbId, query,
                topK <= 0 ? 5 : topK,
                threshold == null ? 0.5 : threshold);
    }

    /** 将应用绑定到知识库（RAG 生效的前提） */
    public void bindAppKb(Long tenantId, Long appId, Long kbId) {
        requireKb(tenantId, kbId);
        appRepository.bindKnowledgeBase(tenantId, appId, kbId);
    }

    private void requireKb(Long tenantId, Long kbId) {
        if (!kbRepository.exists(tenantId, kbId)) {
            throw new BizException(ResultCode.NOT_FOUND, "知识库不存在");
        }
    }

    private String fileTypeOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
