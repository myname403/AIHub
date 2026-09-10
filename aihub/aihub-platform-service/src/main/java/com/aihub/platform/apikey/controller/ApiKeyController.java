package com.aihub.platform.apikey.controller;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.R;
import com.aihub.common.result.ResultCode;
import com.aihub.common.tenant.TenantContext;
import com.aihub.platform.apikey.service.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 开放 API Key 管理接口（M5）。
 *
 * <p>租户一律取自上下文（网关从 JWT 注入），绝不接受前端传参。
 * 这些接口面向「租户管理员自助管理」，因此走用户 JWT 而非 API Key 自身。
 */
@RestController
@RequestMapping("/api/platform/apikey")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /**
     * 签发新 Key。
     *
     * <p><b>明文只在本响应里出现一次</b>，之后系统无法找回。
     */
    @PostMapping
    public R<Map<String, Object>> issue(@Valid @RequestBody IssueRequest request) {
        Long tenantId = TenantContext.requireTenantId();
        ApiKeyService.IssuedKey issued = apiKeyService.issue(
                tenantId, request.getName(), request.getAppId(), request.getExpireAt());
        return R.ok(Map.of(
                "id", issued.id(),
                "apiKey", issued.plainKey(),
                "prefix", issued.prefix(),
                "notice", "请立即保存，此密钥不会再次显示"));
    }

    /** 列出本租户的 Key（不含明文与哈希） */
    @GetMapping
    public R<List<ApiKeyService.ApiKeyView>> list() {
        return R.ok(apiKeyService.list(TenantContext.requireTenantId()));
    }

    /** 吊销 Key（软删，立即失效） */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> revoke(@PathVariable Long id) {
        boolean ok = apiKeyService.revoke(TenantContext.requireTenantId(), id);
        if (!ok) {
            throw new BizException(ResultCode.NOT_FOUND, "密钥不存在");
        }
        return R.ok(Map.of("revoked", true));
    }

    @Data
    public static class IssueRequest {
        @NotBlank(message = "密钥名称不能为空")
        private String name;
        /** 绑定应用；留空表示可访问该租户全部应用 */
        private Long appId;
        /** 过期时间；留空表示长期有效 */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime expireAt;
    }
}
