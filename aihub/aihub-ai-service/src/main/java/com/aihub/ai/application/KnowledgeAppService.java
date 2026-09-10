package com.aihub.ai.application;

import com.aihub.ai.domain.model.Chunk;
import com.aihub.ai.domain.model.DocumentInfo;
import com.aihub.ai.domain.model.RetrievedChunk;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.domain.spi.DocumentRepository;
import com.aihub.ai.domain.spi.KnowledgeBaseRepository;
import com.aihub.ai.domain.spi.KnowledgeIndexer;
import com.aihub.ai.domain.spi.KnowledgeRetriever;
import com.aihub.ai.domain.support.TextChunker;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 知识库编排服务（M2 · RAG 全链路）。
 *
 * <p>入库链路：上传 → 解析 → 分片 → 落库 → 向量化 → 状态回写
 * 检索链路：查询 → 向量召回（强制租户过滤）→ TenantGuard → 返回引用
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

    @Value("${aihub.rag.chunk-size:500}")
    private int chunkSize;

    @Value("${aihub.rag.chunk-overlap:100}")
    private int chunkOverlap;

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
     * 文档入库（同步执行；量大时改异步任务 + 进度上报，见 ai_ingest_task）。
     *
     * @return 入库的分片数量
     */
    public int ingest(Long tenantId, Long kbId, String filename, byte[] bytes) {
        requireKb(tenantId, kbId);

        String fileType = fileTypeOf(filename);
        if (!SUPPORTED.contains(fileType)) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "暂不支持该文件类型（当前支持 txt / md / pdf / docx）");
        }

        Long docId = documentRepository.createDocument(tenantId, kbId, filename, fileType, bytes.length);
        try {
            String text = parseText(filename, bytes);
            if (text.isBlank()) {
                throw new BizException(ResultCode.PARAM_ERROR, "文档内容为空");
            }
            List<Chunk> chunks = TextChunker.split(text, chunkSize, chunkOverlap);
            if (chunks.isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "文档切分后无有效内容");
            }
            documentRepository.saveChunks(tenantId, kbId, docId, chunks, 0);

            int indexed = knowledgeIndexer.index(tenantId, kbId, docId, chunks);
            documentRepository.updateChunkStatus(tenantId, docId, 1);
            documentRepository.updateStatus(tenantId, docId, DocumentInfo.STATUS_DONE, null);
            log.info("文档入库完成 tenant={} kb={} doc={} indexed={}", tenantId, kbId, docId, indexed);
            return indexed;
        } catch (BizException e) {
            documentRepository.updateStatus(tenantId, docId, DocumentInfo.STATUS_FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("文档入库失败 tenant={} doc={}", tenantId, docId, e);
            documentRepository.updateStatus(tenantId, docId, DocumentInfo.STATUS_FAILED,
                    "向量化失败：" + e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR, "文档入库失败");
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
