package com.aihub.platform.config;

import com.aihub.common.tenant.TenantResolveInterceptor;
import com.aihub.common.trace.TraceIdInterceptor;
import com.aihub.platform.system.interceptor.PermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置 —— 注册链路追踪、租户解析与权限校验三个拦截器（前两个来自 common 模块）。
 *
 * <p><b>为什么需要这个类：</b>拦截器写好了不会自动生效，必须在这里"挂"到 Spring MVC 上。
 * WebMvcConfigurer 是 Spring 提供的 MVC 定制接口（回调风格：实现关心的方法即可），
 * addInterceptors 就是"注册拦截器"的回调点。
 *
 * <p><b>执行顺序（order 越小越先）：</b>
 * <pre>
 *   order(0) TraceIdInterceptor   —— 先有 traceId，之后任何报错的日志都能查
 *   order(1) TenantResolveInterceptor —— 再定租户身份
 *   order(2) PermissionInterceptor —— 最后做 RBAC 权限校验（依赖租户上下文）
 *   ↓
 *   Controller
 * </pre>
 *
 * <p>登录/注册等公开路径不强制租户上下文（登录时还不知道租户 ID，靠租户码换租户 ID）；
 * TraceId 对所有路径生效（含 /internal——AI 服务调过来的请求也要能串日志）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》《07-RBAC与管理端.md》。
 */
@Configuration   // 声明为配置类：Spring 启动时处理，@Bean 方法会被执行
@RequiredArgsConstructor   // 注入 PermissionInterceptor（其内部依赖 PermissionService）
public class WebMvcConfig implements WebMvcConfigurer {

    /** 权限校验拦截器（@Component，构造器注入进来） */
    private final PermissionInterceptor permissionInterceptor;

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
        // 权限校验：标注了 @RequirePermission 的接口才做 RBAC 校验；
        // 公开路径与内部契约接口同样排除（它们不走用户登录态）
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/actuator/**", "/error", "/internal/**",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .order(2);
        // 注意 /internal/** 排除了租户拦截器：内部接口的租户信息直接从请求体参数里取
        // （QuotaRequest.tenantId，由 Feign 拦截器从上游上下文写入，不是前端可控的）。
        // /error 是 Spring Boot 的错误兜底路径，排除以免拦截器异常掩盖真实错误。
    }
}
