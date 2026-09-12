package com.aihub.platform.config;

import com.aihub.common.tenant.TenantResolveInterceptor;
import com.aihub.common.trace.TraceIdInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置 —— 注册链路追踪与租户解析两个拦截器（common 模块提供）。
 *
 * <p><b>为什么需要这个类：</b>拦截器写好了不会自动生效，必须在这里"挂"到 Spring MVC 上。
 * WebMvcConfigurer 是 Spring 提供的 MVC 定制接口（回调风格：实现关心的方法即可），
 * addInterceptors 就是"注册拦截器"的回调点。
 *
 * <p><b>执行顺序（order 越小越先）：</b>
 * <pre>
 *   order(0) TraceIdInterceptor   —— 先有 traceId，之后任何报错的日志都能查
 *   order(1) TenantResolveInterceptor —— 再定租户身份
 *   ↓
 *   Controller
 *   （请求结束后两个拦截器的 afterCompletion 倒序执行，各自清理 ThreadLocal）
 * </pre>
 *
 * <p>登录等公开路径不强制租户上下文（登录时还不知道租户 ID，靠租户码换租户 ID）；
 * TraceId 对所有路径生效（含 /internal——AI 服务调过来的请求也要能串日志）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Configuration   // 声明为配置类：Spring 启动时处理，@Bean 方法会被执行
@RequiredArgsConstructor   // 当前没有 final 依赖，保留以维持项目统一风格
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 注册拦截器。
     * addPathPatterns("/**") = 拦截所有路径；excludePathPatterns = 排除名单。
     * 排除名单是"信任边界"的精确刻画，改这里之前先想清楚安全影响。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /internal 也必须带 TraceId：AI 服务调过来的请求要能串起来
        registry.addInterceptor(new TraceIdInterceptor())
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(new TenantResolveInterceptor())
                .addPathPatterns("/**")
                // swagger 端点放行：直连调试用；生产环境用 springdoc.api-docs.enabled=false 关闭
                .excludePathPatterns("/auth/**", "/actuator/**", "/error", "/internal/**",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .order(1);
        // 注意 /internal/** 排除了租户拦截器：内部接口的租户信息直接从请求体参数里取
        // （QuotaRequest.tenantId，由 Feign 拦截器从上游上下文写入，不是前端可控的）。
        // /error 是 Spring Boot 的错误兜底路径，排除以免拦截器异常掩盖真实错误。
    }
}
