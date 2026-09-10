package com.aihub.api.config;

import com.aihub.common.tenant.TenantContext;
import com.aihub.common.trace.TraceContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;

/**
 * Feign 拦截器：自动透传租户、用户与链路上下文。
 *
 * <p>架构要求（07 号文档 MR4）：跨服务调用必须携带租户上下文，
 * 服务端强制校验，避免下游服务拿到空租户而绕过隔离。
 *
 * <p>链路追踪：TraceId 一并透传，使 AI 服务 → 平台服务的调用
 * 在日志中呈现为同一条链路。本地没有链路 ID 时先生成一个，
 * 避免下游出现「无 traceId」的孤立日志。
 */
public class FeignTenantConfig {

    @Bean
    public RequestInterceptor tenantPropagationInterceptor() {
        return template -> {
            Long tenantId = TenantContext.getTenantId();
            Long userId = TenantContext.getUserId();
            if (tenantId != null) {
                template.header(TenantContext.HEADER_TENANT, String.valueOf(tenantId));
            }
            if (userId != null) {
                template.header(TenantContext.HEADER_USER, String.valueOf(userId));
            }
            template.header(TraceContext.HEADER, TraceContext.ensure());
        };
    }
}
