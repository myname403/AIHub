package com.aihub.api.config;

import com.aihub.common.tenant.TenantContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;

/**
 * Feign 拦截器：自动透传租户与用户上下文。
 *
 * <p>架构要求（07 号文档 MR4）：跨服务调用必须携带租户上下文，
 * 服务端强制校验，避免下游服务拿到空租户而绕过隔离。
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
        };
    }
}
