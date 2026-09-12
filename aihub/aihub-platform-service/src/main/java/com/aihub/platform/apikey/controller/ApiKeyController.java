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
 * 开放 API Key 管理接口（M5）—— 租户自助管理自己的密钥。
 *
 * <p><b>REST 风格示范（本类是最标准的 Controller 写法，值得模仿）：</b>
 * <pre>
 *   POST   /api/platform/apikey        签发（创建）
 *   GET    /api/platform/apikey        列表（查询）
 *   DELETE /api/platform/apikey/{id}   吊销（删除）
 * </pre>
 * HTTP 方法表达"对资源做什么"，URL 表达"哪个资源"，没有动词出现在 URL 里。
 *
 * <p><b>安全设计：</b>租户一律取自上下文（网关从 JWT 注入 → TenantContext），
 * 绝不接受前端传参。这些接口面向「租户管理员自助管理」，
 * 因此走用户 JWT 而非 API Key 自身（用 Key 管理 Key 本身会鸡生蛋问题）。
 *
 * <p>注解说明：
 * <ul>
 *   <li>{@code @GetMapping/@PostMapping/@DeleteMapping}：HTTP 方法 + 路径的组合简写；</li>
 *   <li>{@code @PathVariable}：URL 路径变量（/{id} 里的 {id} 注入参数）；</li>
 *   <li>{@code @Valid}：触发参数校验 —— IssueRequest 里 @NotBlank 失败时抛
 *       MethodArgumentNotValidException，由 common 的全局异常处理器转成 PARAM_ERROR；</li>
 *   <li>{@code @RequiredArgsConstructor}：final 字段构造器注入。</li>
 * </ul>
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@RestController
@RequestMapping("/api/platform/apikey")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /**
     * 签发新 Key。
     *
     * <p><b>明文只在本响应里出现一次</b>，之后系统无法找回（库里只有哈希）——
     * 这是与用户沟通的关键交互：前端必须弹出"请立即保存"提示。
     */
    @PostMapping
    public R<Map<String, Object>> issue(@Valid @RequestBody IssueRequest request) {
        // 租户从 ThreadLocal 上下文取（网关写入的 X-Tenant-Id → TenantResolveInterceptor → 这里）
        Long tenantId = TenantContext.requireTenantId();
        ApiKeyService.IssuedKey issued = apiKeyService.issue(
                tenantId, request.getName(), request.getAppId(), request.getExpireAt());
        // Map.of（Java 9+）：创建不可变 Map，适合这种固定几个字段的返回
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

    /** 吊销 Key（软删，立即失效）。注意网关侧的 ApiKeyCache 有 60 秒 TTL，吊销最迟 60 秒后全网生效 */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> revoke(@PathVariable Long id) {
        boolean ok = apiKeyService.revoke(TenantContext.requireTenantId(), id);
        if (!ok) {
            // 明确区分"不存在"（可能是别人的 Key，也可能是真的没有）
            throw new BizException(ResultCode.NOT_FOUND, "密钥不存在");
        }
        return R.ok(Map.of("revoked", true));
    }

    /**
     * 签发请求体。static 嵌套类：请求模型只服务于本 Controller。
     */
    @Data
    public static class IssueRequest {
        @NotBlank(message = "密钥名称不能为空")
        private String name;

        /** 绑定应用；留空表示可访问该租户全部应用 */
        private Long appId;

        /** 过期时间；留空表示长期有效。
         * @DateTimeFormat：告诉 Spring 前端传的是 ISO 格式字符串（如 2026-12-31T23:59:59），自动转 LocalDateTime */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime expireAt;
    }
}
