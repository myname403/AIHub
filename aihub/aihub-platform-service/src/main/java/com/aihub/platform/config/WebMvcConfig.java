package com.aihub.platform.config;

import com.aihub.common.tenant.TenantResolveInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册租户解析拦截器。
 *
 * <p>登录等公开路径不强制租户上下文。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantResolveInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/actuator/**", "/error", "/internal/**");
    }
}
