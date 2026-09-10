package com.aihub.ai.web;

import com.aihub.ai.application.KnowledgeAppService;
import com.aihub.ai.domain.model.DocumentInfo;
import com.aihub.ai.domain.model.IngestTask;
import com.aihub.ai.domain.model.RetrievedChunk;
import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库接口（M2 · RAG）。
 *
 * <p>租户 ID 一律取自上下文，绝不接受前端传参。
 */
@RestController
@RequestMapping("/api/ai/kb")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeAppService knowledgeAppService;

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody CreateKbRequest request) {
        Long kbId = knowledgeAppService.createKb(
                TenantContext.requireTenantId(), request.getName(), request.getVectorDim());
        return R.ok(Map.of("kbId", kbId));
    }

    @GetMapping
    public R<List<DocumentInfo>> list() {
        return R.ok(knowledgeAppService.listKb(TenantContext.requireTenantId()));
    }

    @GetMapping("/{kbId}/documents")
    public R<List<DocumentInfo>> documents(@PathVariable Long kbId) {
        return R.ok(knowledgeAppService.listDocuments(TenantContext.requireTenantId(), kbId));
    }

    /**
     * 文档上传并入库（异步）。
     *
     * <p>支持 txt / md / pdf / docx；立即返回 taskId，前端轮询
     * {@code GET /api/ai/kb/ingest/{taskId}} 获取进度。
     */
    @PostMapping("/{kbId}/documents")
    public R<Map<String, Object>> upload(@PathVariable Long kbId,
                                         @RequestParam("file") MultipartFile file) {
        try {
            Long taskId = knowledgeAppService.submitIngest(
                    TenantContext.requireTenantId(), kbId,
                    file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename(),
                    file.getBytes());
            return R.ok(Map.of("taskId", taskId));
        } catch (Exception e) {
            if (e instanceof com.aihub.common.exception.BizException biz) {
                throw biz;
            }
            throw new com.aihub.common.exception.BizException(
                    com.aihub.common.result.ResultCode.SYSTEM_ERROR, "文件读取失败");
        }
    }

    /** 入库进度（前端轮询） */
    @GetMapping("/ingest/{taskId}")
    public R<IngestTask> ingestProgress(@PathVariable Long taskId) {
        return knowledgeAppService.ingestProgress(TenantContext.requireTenantId(), taskId)
                .map(R::ok)
                .orElseGet(() -> R.fail(
                        com.aihub.common.result.ResultCode.NOT_FOUND.getCode(), "入库任务不存在"));
    }

    /** 重试失败的入库任务 */
    @PostMapping("/ingest/{taskId}/retry")
    public R<Map<String, Object>> retryIngest(@PathVariable Long taskId) {
        boolean accepted = knowledgeAppService.retryIngest(TenantContext.requireTenantId(), taskId);
        return R.ok(Map.of("accepted", accepted));
    }

    /** 应用绑定知识库（绑定后对话自动走 RAG） */
    @PostMapping("/bind")
    public R<Void> bind(@RequestBody BindRequest request) {
        knowledgeAppService.bindAppKb(TenantContext.requireTenantId(),
                request.getAppId(), request.getKbId());
        return R.ok();
    }

    /** 检索调试台：查看命中分片与相似度 */
    @PostMapping("/search")
    public R<List<RetrievedChunk>> search(@RequestBody SearchRequest request) {
        return R.ok(knowledgeAppService.search(TenantContext.requireTenantId(),
                request.getKbId(), request.getQuery(), request.getTopK(), request.getThreshold()));
    }

    @Data
    public static class CreateKbRequest {
        @NotBlank(message = "知识库名称不能为空")
        private String name;
        /** Embedding 维度，须与模型一致（如 1536 / 1024） */
        private int vectorDim = 1536;
    }

    @Data
    public static class BindRequest {
        private Long appId;
        private Long kbId;
    }

    @Data
    public static class SearchRequest {
        private Long kbId;
        @NotBlank(message = "查询内容不能为空")
        private String query;
        private int topK = 5;
        private Double threshold;
    }
}
