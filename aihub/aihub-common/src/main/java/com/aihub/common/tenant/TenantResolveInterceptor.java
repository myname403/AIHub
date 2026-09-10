package com.aihub.common.tenant;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户解析拦截器（下游服务使用）。
 *
 * <p>租户 ID 来自网关解析令牌后写入的 {@code X-Tenant-Id} 请求头。
 * 网关已强制剥离客户端伪造的同名头，因此此处可信任该头。
 *
 * <p>防御：头缺失即拒绝，避免"漏传租户"演变成越权。
 */
public class TenantResolveInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenant = request.getHeader(TenantContext.HEADER_TENANT);
        String user = request.getHeader(TenantContext.HEADER_USER);

        if (tenant == null || tenant.isBlank()) {
            throw new BizException(ResultCode.TENANT_MISMATCH, "请求缺少租户上下文");
        }
        TenantContext.set(Long.valueOf(tenant), user == null || user.isBlank() ? null : Long.valueOf(user));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
