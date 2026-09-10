package com.aihub.ai.config;

import com.aihub.api.config.FeignTenantConfig;
import com.aihub.common.tenant.TenantResolveInterceptor;
import com.aihub.common.trace.TraceIdInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * AI 服务 Web 配置：
 * 1) 链路追踪拦截器（全链路 TraceId，最先执行）
 * 2) 租户解析拦截器（租户上下文唯一来源）
 * 3) Feign 租户透传（调用平台服务时携带租户头）
 * 4) 异步请求执行器（NDJSON StreamingResponseBody 需要，否则默认 SimpleAsyncTaskExecutor）
 */
@Configuration
@Import(FeignTenantConfig.class)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // order 越小越先执行：TraceId 必须先就绪，否则租户校验失败的日志里拿不到链路 ID
        registry.addInterceptor(new TraceIdInterceptor())
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(new TenantResolveInterceptor())
                .addPathPatterns("/**")
                // swagger 端点放行：直连调试用；生产环境用 springdoc.api-docs.enabled=false 关闭
                .excludePathPatterns("/actuator/**", "/error",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .order(1);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("aihub-async-");
        executor.initialize();
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(120_000L);
    }
}
