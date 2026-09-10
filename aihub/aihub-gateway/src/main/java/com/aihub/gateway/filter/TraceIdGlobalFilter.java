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
 * <p>顺序：{@code Ordered.HIGHEST_PRECEDENCE}——必须早于鉴权过滤器，
 * 这样鉴权失败（401）的响应里也能带上链路 ID，便于定位问题。
 *
 * <p>注意：此处<b>不</b>剥离客户端传来的 X-Trace-Id 之外的行为；
 * TraceId 无鉴权含义，伪造它不会带来越权风险，但能帮助串联排查。
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String incoming = request.getHeaders().getFirst(TraceContext.HEADER);
        String traceId = incoming == null || incoming.isBlank()
                ? TraceContext.newTraceId() : incoming;

        ServerHttpRequest mutated = request.mutate()
                .header(TraceContext.HEADER, traceId)
                .build();
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(TraceContext.HEADER, traceId);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
