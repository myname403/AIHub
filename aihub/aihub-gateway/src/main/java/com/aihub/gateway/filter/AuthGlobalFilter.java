package com.aihub.gateway.filter;

import com.aihub.common.tenant.TenantContext;
import com.aihub.common.trace.TraceContext;
import com.aihub.gateway.apikey.ApiKeyCache;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关鉴权过滤器。
 *
 * <p>支持两种凭证（M5 起）：
 * <ol>
 *   <li><b>JWT</b>：{@code Authorization: Bearer <jwt>}，面向终端用户（H5 / 小程序）；</li>
 *   <li><b>API Key</b>：{@code X-API-Key: ak_xxx}，面向第三方系统对接。</li>
 * </ol>
 *
 * <p>安全约束（★）：租户 ID 只从凭证解析，<b>绝不接受前端传参</b>；
 * 同时强制剥离客户端伪造的 {@code X-Tenant-Id} / {@code X-User-Id}，
 * 防止越权。API Key 不携带用户身份，因此只注入租户、不注入 userId。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** API Key 请求头 */
    private static final String HEADER_API_KEY = "X-API-Key";

    private final byte[] secretKey;
    private final ApiKeyCache apiKeyCache;

    public AuthGlobalFilter(@Value("${aihub.security.jwt-secret}") String jwtSecret,
                            ApiKeyCache apiKeyCache) {
        this.secretKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.apiKeyCache = apiKeyCache;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // 优先识别 API Key：第三方对接常用固定头，避免与用户 JWT 混用
        String apiKey = request.getHeaders().getFirst(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return authenticateByApiKey(exchange, chain, apiKey);
        }

        String token = resolveToken(request);
        if (token == null) {
            return unauthorized(exchange, "缺少认证令牌");
        }
        return authenticateByJwt(exchange, chain, token);
    }

    /** API Key 通道：Key 反查出租户，不注入 userId（无用户身份） */
    private Mono<Void> authenticateByApiKey(ServerWebExchange exchange,
                                            GatewayFilterChain chain, String apiKey) {
        return apiKeyCache.verify(apiKey)
                .flatMap(principal -> {
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .headers(headers -> {
                                // 强制覆盖，防止伪造租户头
                                headers.remove(TenantContext.HEADER_TENANT);
                                headers.remove(TenantContext.HEADER_USER);
                                headers.set(TenantContext.HEADER_TENANT,
                                        String.valueOf(principal.tenantId()));
                                // API Key 代表系统调用而非具体用户，显式清空用户头
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange, "API Key 无效、已停用或已过期")));
    }

    /** JWT 通道：解析令牌取租户与用户 */
    private Mono<Void> authenticateByJwt(ServerWebExchange exchange,
                                         GatewayFilterChain chain, String token) {
        final String tenantId;
        final String userId;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretKey))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // 令牌内的 tenantId 是租户上下文的唯一可信来源
            tenantId = String.valueOf(claims.get("tenantId", Object.class));
            userId = String.valueOf(claims.get("userId", Object.class));
        } catch (Exception e) {
            return unauthorized(exchange, "令牌无效或已过期");
        }

        if (tenantId == null || "null".equals(tenantId)) {
            return unauthorized(exchange, "令牌缺少租户信息");
        }

        // 重建请求头：强制覆盖，防止客户端伪造租户头
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(TenantContext.HEADER_TENANT);
                    headers.remove(TenantContext.HEADER_USER);
                    headers.set(TenantContext.HEADER_TENANT, tenantId);
                    headers.set(TenantContext.HEADER_USER, userId);
                })
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/auth/") || path.startsWith("/actuator/health");
    }

    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        // 带上链路 ID：401 多发生在客户端配置错误时，没有 traceId 很难排查
        String traceId = exchange.getResponse().getHeaders().getFirst(TraceContext.HEADER);
        String tracePart = traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"";
        String body = String.format("{\"code\":10002,\"message\":\"%s\"%s}", message, tracePart);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
