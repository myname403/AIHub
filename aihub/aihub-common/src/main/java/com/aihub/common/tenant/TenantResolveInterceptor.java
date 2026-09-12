package com.aihub.common.tenant;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户解析拦截器（下游服务使用：platform / ai 服务）。
 *
 * <p><b>拦截器是什么？</b>HandlerInterceptor 是 Spring MVC 的"请求过滤器"，
 * 在 Controller 方法执行前后插入逻辑：
 * <pre>
 *   preHandle（进 Controller 前）→ Controller/Service → afterCompletion（响应完成后，无论成败）
 * </pre>
 * 本类在 preHandle 里把请求头中的租户信息写入 {@link TenantContext}，
 * 在 afterCompletion 里清理 —— 一进一出正好配对。
 *
 * <p><b>信任链：</b>租户 ID 来自网关解析令牌后写入的 {@code X-Tenant-Id} 请求头。
 * 网关已强制剥离客户端伪造的同名头（见 gateway 的 AuthGlobalFilter），
 * 因此<b>此处可信任该头</b>——这就是"边界统一鉴权"模式：
 * 鉴权只做一次（网关），内网服务信任内网流量。
 *
 * <p><b>防御：头缺失即拒绝</b>，避免"漏传租户"演变成越权
 * （fail-fast 原则：宁可拒绝请求，也不带着空租户执行业务）。
 *
 * <p><b>注意事项：</b>下游服务必须在 WebMvcConfig 里注册本拦截器才会生效
 * （{@code registry.addInterceptor(new TenantResolveInterceptor()).addPathPatterns("/**")}）；
 * 健康检查 / 内部探活路径通常排除在外。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
public class TenantResolveInterceptor implements HandlerInterceptor {

    /**
     * Controller 执行前：解析租户头并写入上下文。
     *
     * @return true 放行继续执行 Controller；false 表示拦截（本实现用抛异常代替返回 false，
     *         异常会被 GlobalExceptionHandler 转成统一响应体，信息更明确）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 从请求头读取网关写入的租户/用户 ID（客户端伪造的同名头已在网关被剥离）
        String tenant = request.getHeader(TenantContext.HEADER_TENANT);
        String user = request.getHeader(TenantContext.HEADER_USER);

        // fail-fast：租户头缺失直接拒绝，绝不让"无主请求"进入业务层
        if (tenant == null || tenant.isBlank()) {
            throw new BizException(ResultCode.TENANT_MISMATCH, "请求缺少租户上下文");
        }
        // 用户头可缺省（如纯 API Key 调用没有用户概念），租户头必须存在
        TenantContext.set(Long.valueOf(tenant), user == null || user.isBlank() ? null : Long.valueOf(user));
        return true;
    }

    /**
     * 请求完全结束后回调（成功、失败、异常都会执行）：
     * 清理 ThreadLocal，防止线程复用时租户信息串台。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
