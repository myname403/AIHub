package com.aihub.ai.config;

import com.aihub.api.config.FeignTenantConfig;
import com.aihub.common.tenant.TenantResolveInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * AI 服务 Web 配置：
 * 1) 租户解析拦截器（租户上下文唯一来源）
 * 2) Feign 租户透传（调用平台服务时携带租户头）
 * 3) 异步请求执行器（NDJSON StreamingResponseBody 需要，否则默认 SimpleAsyncTaskExecutor）
 */
@Configuration
@Import(FeignTenantConfig.class)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantResolveInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/error");
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
