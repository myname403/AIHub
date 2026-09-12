package com.aihub.api.config;

import com.aihub.common.tenant.TenantContext;
import com.aihub.common.trace.TraceContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;

/**
 * Feign 拦截器：自动透传租户、用户与链路上下文。
 *
 * <p><b>解决什么问题：</b>AI 服务调平台服务时，平台服务也要知道"这是哪个租户的请求"
 * （platform 的 TenantResolveInterceptor 要求 X-Tenant-Id 头必须存在）。
 * 如果每个调用点都手动塞头，迟早有地方忘掉。放在 Feign 拦截器里，
 * <b>所有</b> Feign 调用自动携带，一处配置全局生效。
 *
 * <p><b>RequestInterceptor 是什么：</b>Feign 的扩展点，每次发请求前回调一次，
 * 参数 RequestTemplate 就是"即将发出的 HTTP 请求"的可编辑草稿 —— 往里加 header、
 * 改 URL、加 body 都行。这里的 lambda 语法是函数式接口的简写。
 *
 * <p><b>生效机制：</b>本类被 @FeignClient 的 configuration 属性或
 * @EnableFeignClients(defaultConfiguration) 引用后，其 @Bean 会被注册。
 * 注意：Feign 配置类<b>不应</b>标 @Configuration（否则会被组件扫描到全局，
 * 影响所有 Feign 客户端）；本项目通过模块依赖共享这份配置。
 *
 * <p><b>架构要求（docs/07 MR4）：</b>跨服务调用必须携带租户上下文，
 * 服务端强制校验，避免下游服务拿到空租户而绕过隔离。
 *
 * <p>链路追踪：TraceId 一并透传，使 AI 服务 → 平台服务的调用
 * 在日志中呈现为同一条链路。本地没有链路 ID 时先生成一个
 * （TraceContext.ensure()），避免下游出现「无 traceId」的孤立日志。
 * 详见学习文档《02-契约模块-aihub-api.md》。
 */
public class FeignTenantConfig {

    /**
     * 注册请求拦截器：每次 Feign 调用前，从 ThreadLocal 取出当前上下文塞进请求头。
     * 取值来源就是 common 模块的 TenantContext / TraceContext ——
     * 上游拦截器（TenantResolveInterceptor）已经把值放进来了，这里只负责"搬运"。
     */
    @Bean   // 声明为 Spring Bean，Feign 启动时收集所有 RequestInterceptor 类型 Bean
    public RequestInterceptor tenantPropagationInterceptor() {
        // template = 即将发出的 HTTP 请求草稿；lambda 等价于匿名内部类实现 apply(template) 方法
        return template -> {
            // 读当前线程的租户/用户上下文（正常请求里一定有，定时任务等场景可能为 null）
            Long tenantId = TenantContext.getTenantId();
            Long userId = TenantContext.getUserId();
            if (tenantId != null) {
                // 写入 X-Tenant-Id 头 —— 下游 TenantResolveInterceptor 就靠它识别租户
                template.header(TenantContext.HEADER_TENANT, String.valueOf(tenantId));
            }
            if (userId != null) {
                template.header(TenantContext.HEADER_USER, String.valueOf(userId));
            }
            // 链路 ID：本地有就透传，没有就现场生成（ensure），保证全链路日志可串联
            template.header(TraceContext.HEADER, TraceContext.ensure());
        };
    }
}
