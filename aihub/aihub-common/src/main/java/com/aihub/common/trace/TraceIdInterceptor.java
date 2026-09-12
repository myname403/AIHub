package com.aihub.common.trace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 链路追踪拦截器（全链路 TraceId，下游服务使用：platform / ai 服务）。
 *
 * <p><b>执行时机：</b>preHandle 在所有 Controller 之前执行 —— 保证业务代码和日志
 * 一开始就有 traceId 可用；afterCompletion 在请求完全结束后执行 —— 负责清理。
 *
 * <p>入口：优先复用上游传来的 {@code X-Trace-Id}（网关或调用方），
 * 没有则新生成；同时回写响应头，前端可在控制台直接看到本次请求的链路 ID。
 * 出口：清理 ThreadLocal，避免线程复用导致链路 ID 串台。
 *
 * <p><b>注意事项：</b>
 * <ul>
 *   <li>需在 WebMvcConfig 注册才生效；</li>
 *   <li>本拦截器要在 TenantResolveInterceptor <b>之前</b>注册（先有 traceId，租户缺失的报错日志才有 ID 可查）；</li>
 *   <li>网关侧（WebFlux）不使用本类，网关用 TraceIdGlobalFilter 完成同样的事。</li>
 * </ul>
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
public class TraceIdInterceptor implements HandlerInterceptor {

    /**
     * Controller 执行前：确定本次请求的 traceId 并写入上下文。
     * 优先级：上游传来的 > 新生成的（保持全链路同一个 ID）。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // 读上游请求头；网关已生成过，所以大多数请求这里能拿到
        String incoming = request.getHeader(TraceContext.HEADER);
        String traceId = incoming == null || incoming.isBlank()
                ? TraceContext.newTraceId() : incoming;
        // 写入 ThreadLocal + MDC，此后本线程所有日志自动带 traceId
        TraceContext.set(traceId);
        // 回写响应头：即便 Body 是流式输出，前端也能拿到链路 ID
        response.setHeader(TraceContext.HEADER, traceId);
        return true;   // true = 放行，继续执行后续拦截器和 Controller
    }

    /** 请求完全结束后清理（成功/异常都会执行），防止线程复用串台 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TraceContext.clear();
    }
}
