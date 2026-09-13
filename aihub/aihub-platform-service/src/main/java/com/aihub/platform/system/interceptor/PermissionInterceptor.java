package com.aihub.platform.system.interceptor;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.common.tenant.TenantContext;
import com.aihub.platform.system.annotation.RequirePermission;
import com.aihub.platform.system.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 权限校验拦截器 —— @RequirePermission 注解的"执法者"。
 *
 * <p><b>工作原理：</b>请求到达 Controller 前，Spring MVC 把 handler 传给拦截器。
 * 如果 handler 是一个 Controller 方法（HandlerMethod），就反射读取它身上的
 * {@code @RequirePermission} 注解；有注解则校验当前用户（TenantContext 里的
 * 租户/用户 ID）是否拥有对应权限标识，没有就抛 FORBIDDEN。
 *
 * <p><b>执行顺序（WebMvcConfig 里注册）：</b>
 * TraceId(0) → Tenant(1) → <b>Permission(2)</b> —— 必须在租户拦截器之后
 * （要先有租户/用户上下文才能查权限）。
 *
 * <p><b>没有注解的接口默认放行</b>：只要求"登录"，不要求特定权限 ——
 * 这是芋道同款的宽松默认策略（登录即认证通过，敏感接口才逐个标权限）。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // 静态资源等非 Controller 请求直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 读取方法上的注解（方法级优先，可再扩展类级兜底）
        RequirePermission annotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (annotation == null) {
            return true;   // 没标注解 = 只要求登录态（租户拦截器已保证）
        }
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "缺少用户身份");
        }
        if (!permissionService.hasPermission(tenantId, userId, annotation.value())) {
            // 提示带上权限标识，方便排查"为什么这个账号点不了这个按钮"
            throw new BizException(ResultCode.FORBIDDEN, "无操作权限（" + annotation.value() + "）");
        }
        return true;
    }
}
