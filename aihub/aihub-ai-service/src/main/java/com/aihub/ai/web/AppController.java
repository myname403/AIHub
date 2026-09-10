package com.aihub.ai.web;

import com.aihub.ai.application.AppAdminService;
import com.aihub.ai.domain.model.App;
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
 * 应用配置接口（管理端）。
 *
 * <p>租户一律取自上下文（网关从 JWT / API Key 注入），绝不接受前端传参。
 */
@RestController
@RequestMapping("/api/ai/app")
@RequiredArgsConstructor
public class AppController {

    private final AppAdminService appAdminService;

    @GetMapping
    public R<List<App>> list() {
        return R.ok(appAdminService.list(TenantContext.requireTenantId()));
    }

    @GetMapping("/{appId}")
    public R<App> get(@PathVariable Long appId) {
        return R.ok(appAdminService.get(TenantContext.requireTenantId(), appId));
    }

    @PostMapping
    public R<Map<String, Object>> create(@Valid @RequestBody AppRequest request) {
        Long appId = appAdminService.create(TenantContext.requireTenantId(), request.toDomain());
        return R.ok(Map.of("appId", appId));
    }

    @PutMapping("/{appId}")
    public R<Void> update(@PathVariable Long appId, @Valid @RequestBody AppRequest request) {
        appAdminService.update(TenantContext.requireTenantId(), appId, request.toDomain());
        return R.ok();
    }

    @DeleteMapping("/{appId}")
    public R<Void> delete(@PathVariable Long appId) {
        appAdminService.delete(TenantContext.requireTenantId(), appId);
        return R.ok();
    }

    /** 应用已绑定的知识库（管理端用来渲染多选框的回显） */
    @GetMapping("/{appId}/knowledge-bases")
    public R<List<Long>> knowledgeBases(@PathVariable Long appId) {
        return R.ok(appAdminService.boundKnowledgeBases(TenantContext.requireTenantId(), appId));
    }

    @DeleteMapping("/{appId}/knowledge-bases/{kbId}")
    public R<Void> unbind(@PathVariable Long appId, @PathVariable Long kbId) {
        appAdminService.unbindKnowledgeBase(TenantContext.requireTenantId(), appId, kbId);
        return R.ok();
    }

    @Data
    public static class AppRequest {
        @NotBlank(message = "应用名称不能为空")
        private String name;
        private String systemPrompt;
        private String agentStrategy;
        private String memoryPolicy;
        private Long modelRouteId;
        private Double temperature;
        private Integer status;

        App toDomain() {
            App app = new App();
            app.setName(name);
            app.setSystemPrompt(systemPrompt);
            app.setAgentStrategy(agentStrategy);
            app.setMemoryPolicy(memoryPolicy);
            app.setModelRouteId(modelRouteId);
            app.setTemperature(temperature);
            app.setStatus(status);
            return app;
        }
    }
}
