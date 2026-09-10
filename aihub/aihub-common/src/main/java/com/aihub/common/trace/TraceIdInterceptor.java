package com.aihub.common.trace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 链路追踪拦截器（全链路 TraceId）。
 *
 * <p>入口：优先复用上游传来的 {@code X-Trace-Id}（网关或调用方），
 * 没有则新生成；同时回写响应头，前端可在控制台直接看到本次请求的链路 ID。
 * 出口：清理 ThreadLocal，避免线程复用导致链路 ID 串台。
 */
public class TraceIdInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        String incoming = request.getHeader(TraceContext.HEADER);
        String traceId = incoming == null || incoming.isBlank()
                ? TraceContext.newTraceId() : incoming;
        TraceContext.set(traceId);
        // 回写响应头：即便 Body 是流式输出，前端也能拿到链路 ID
        response.setHeader(TraceContext.HEADER, traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TraceContext.clear();
    }
}
