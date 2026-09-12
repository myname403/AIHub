package com.aihub.gateway.filter;

import com.aihub.common.trace.TraceContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关链路追踪过滤器（链路起点）。
 *
 * <p>职责：为每个进入系统的请求确定唯一的 TraceId
 * （沿用客户端/上游已有的 {@code X-Trace-Id}，否则新生成），
 * 写入下游请求头，并回写响应头。
 *
 * <p><b>为什么它是整个过滤器链的第一个（Ordered.HIGHEST_PRECEDENCE）：</b>
 * 必须早于鉴权过滤器执行 —— 这样鉴权失败（401）的响应里也能带上链路 ID；
 * 而且 401 多是客户端配置问题，恰恰最需要 traceId 来定位。
 *
 * <p>注意：TraceId 无鉴权含义，沿用客户端传值不会带来越权风险
 * （伪造 traceId 只会影响"你自己的日志能不能被找到"），但能帮助串联排查。
 *
 * <p><b>对比下游服务：</b>下游用 common 的 TraceIdInterceptor（Servlet 拦截器），
 * 网关是 WebFlux（非 Servlet 环境），所以要写 GlobalFilter 版本 —— 两者逻辑相同、载体不同。
 * 详见学习文档《03-网关-aihub-gateway.md》。
 */
@Component   // 注册为 Bean，Gateway 自动收集所有 GlobalFilter
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 上游（前端/外部系统）带了 X-Trace-Id 就沿用，否则生成新的 —— 保证全链路同一 ID
        String incoming = request.getHeaders().getFirst(TraceContext.HEADER);
        String traceId = incoming == null || incoming.isBlank()
                ? TraceContext.newTraceId() : incoming;

        // 写入转发给下游的请求头：platform/ai 服务的 TraceIdInterceptor 会从这读
        ServerHttpRequest mutated = request.mutate()
                .header(TraceContext.HEADER, traceId)
                .build();
        // 同时回写响应头：前端控制台 Network 面板里就能看到本次请求的 traceId
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(TraceContext.HEADER, traceId);

        // 继续过滤器链，传给下一个过滤器（鉴权）的是改造后的 exchange
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** 最高优先级：Integer.MIN_VALUE，保证所有过滤器里最先执行 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
