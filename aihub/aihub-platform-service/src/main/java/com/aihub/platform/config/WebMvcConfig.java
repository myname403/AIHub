package com.aihub.platform.config;

import com.aihub.common.tenant.TenantResolveInterceptor;
import com.aihub.common.trace.TraceIdInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册链路追踪与租户解析拦截器。
 *
 * <p>登录等公开路径不强制租户上下文；TraceId 对所有路径生效（含 /internal）。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /internal 也必须带 TraceId：AI 服务调过来的请求要能串起来
        registry.addInterceptor(new TraceIdInterceptor())
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(new TenantResolveInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/actuator/**", "/error", "/internal/**")
                .order(1);
    }
}
