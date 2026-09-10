package com.aihub.ai.web;

import com.aihub.ai.application.ModelAdminService;
import com.aihub.ai.domain.model.ModelCommand;
import com.aihub.ai.domain.model.ModelInfo;
import com.aihub.ai.domain.model.ModelProvider;
import com.aihub.ai.domain.model.ModelRouteInfo;
import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型管理接口（管理端）。
 *
 * <p>安全约定：
 * <ul>
 *   <li>响应<b>永不包含</b> apiKey（明文或密文），只回 {@code hasApiKey} 布尔值；</li>
 *   <li>请求体可带明文 apiKey，写入时由 infra 立即 AES-GCM 加密；</li>
 *   <li>编辑时 apiKey 留空表示保持原值，管理端无需也无法回填。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelAdminService modelAdminService;

    /** 供应商字典（下拉框数据源，平台级） */
    @GetMapping("/provider")
    public R<List<ModelProvider>> providers() {
        return R.ok(modelAdminService.providers());
    }

    @GetMapping
    public R<List<ModelInfo>> models() {
        return R.ok(modelAdminService.models(TenantContext.requireTenantId()));
    }

    @PostMapping
    public R<Map<String, Object>> create(@Valid @RequestBody ModelRequest request) {
        Long modelId = modelAdminService.createModel(
                TenantContext.requireTenantId(), request.toCommand());
        return R.ok(Map.of("modelId", modelId));
    }

    @PutMapping("/{modelId}")
    public R<Void> update(@PathVariable Long modelId, @Valid @RequestBody ModelRequest request) {
        modelAdminService.updateModel(
                TenantContext.requireTenantId(), modelId, request.toCommand());
        return R.ok();
    }

    @DeleteMapping("/{modelId}")
    public R<Void> delete(@PathVariable Long modelId) {
        modelAdminService.deleteModel(TenantContext.requireTenantId(), modelId);
        return R.ok();
    }

    /* ---------------- 场景路由 ---------------- */

    @GetMapping("/route")
    public R<List<ModelRouteInfo>> routes() {
        return R.ok(modelAdminService.routes(TenantContext.requireTenantId()));
    }

    @PutMapping("/route")
    public R<Void> saveRoute(@Valid @RequestBody RouteRequest request) {
        modelAdminService.saveRoute(TenantContext.requireTenantId(),
                request.getScene(), request.getPrimaryModelId(), request.getFallbackModelId());
        return R.ok();
    }

    @Data
    public static class ModelRequest {
        @NotBlank(message = "providerCode 不能为空")
        private String providerCode;
        @NotBlank(message = "modelCode 不能为空")
        private String modelCode;
        /** 明文密钥；编辑时留空表示保持原值 */
        private String apiKey;
        private String baseUrl;
        /** 嵌入模型必填，须与实际模型维度一致 */
        private Integer vectorDim;
        private Boolean defaultModel;
        private Integer status;

        ModelCommand toCommand() {
            return new ModelCommand(providerCode, modelCode, apiKey,
                    baseUrl, vectorDim, Boolean.TRUE.equals(defaultModel), status);
        }
    }

    @Data
    public static class RouteRequest {
        @NotBlank(message = "scene 不能为空")
        private String scene;
        private Long primaryModelId;
        private Long fallbackModelId;
    }
}
