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
 * 网关鉴权过滤器 —— 全系统身份认证的唯一关卡（★ 安全核心类）。
 *
 * <p><b>GlobalFilter 是什么：</b>Spring Cloud Gateway 的"全局过滤器"接口，
 * 所有经过网关的请求都会执行 filter() 方法。多个 GlobalFilter 按
 * getOrder() 返回值从小到大依次执行，形成"过滤器链"：
 * 每个过滤器干完活后调用 chain.filter(exchange) 把请求传给下一个。
 *
 * <p><b>本过滤器在链路中的位置（getOrder = -100）：</b>
 * TraceIdGlobalFilter（Integer.MIN_VALUE，最先）→ <b>本过滤器</b> → 路由转发。
 * 保证所有需要鉴权的路径都先经过这里。
 *
 * <p>支持两种凭证：
 * <ol>
 *   <li><b>JWT</b>：{@code Authorization: Bearer <jwt>}，面向终端用户（H5 / 小程序）；</li>
 *   <li><b>API Key</b>：{@code X-API-Key: ak_xxx}，面向第三方系统对接。</li>
 * </ol>
 *
 * <p><b>核心安全设计 —— 为什么这是全系统最重要的防线：</b>
 * <ul>
 *   <li>租户 ID 只从凭证解析，<b>绝不接受前端传参</b>。JWT 有签名验不出来不了假，
 *       API Key 由平台反查租户 —— 两条路都绕不开服务端验证；</li>
 *   <li><b>强制剥离并覆盖</b>客户端伪造的 {@code X-Tenant-Id} / {@code X-User-Id} 头：
 *       如果不剥离，攻击者自己带个 {@code X-Tenant-Id: 1002} 就能冒充别的租户
 *       （下游 TenantResolveInterceptor 是信任这个头的）；</li>
 *   <li>API Key 不携带用户身份，因此只注入租户、不注入 userId。</li>
 * </ul>
 *
 * <p><b>响应式编程提示：</b>本类返回 Mono&lt;Void&gt;（"一个最终完成的信号"）而不是直接写响应，
 * 因为 WebFlux 是非阻塞的 —— 万一手写阻塞代码（如 Thread.sleep、同步 JDBC）会卡死整个网关的事件循环线程。
 * 详见学习文档《03-网关-aihub-gateway.md》。
 */
@Component   // 注册为 Spring Bean，Spring Cloud Gateway 自动收集所有 GlobalFilter 类型的 Bean
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** API Key 请求头 */
    private static final String HEADER_API_KEY = "X-API-Key";

    /** JWT 验签密钥（字节数组）。HS256 要求≥32字节；与平台服务 JwtTokenProvider 的签名密钥必须一致 */
    private final byte[] secretKey;

    /** API Key 校验客户端（带本地缓存），见 apikey 包 */
    private final ApiKeyCache apiKeyCache;

    /**
     * 构造器注入：@Value 从配置读 JWT 密钥（来自环境变量/Nacos，绝不硬编码）。
     * 构造器注入优于字段注入：依赖不可变（final）、便于测试、Spring 官方推荐。
     */
    public AuthGlobalFilter(@Value("${aihub.security.jwt-secret}") String jwtSecret,
                            ApiKeyCache apiKeyCache) {
        this.secretKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.apiKeyCache = apiKeyCache;
    }

    /**
     * 过滤器主逻辑，每个请求都会执行。
     *
     * @param exchange 一次 HTTP 交互的上下文（请求 + 响应 + 属性袋）
     * @param chain    过滤器链，chain.filter(exchange) = "放行给下一个过滤器"
     * @return Mono&lt;Void&gt;：完成信号。返回链式调用表示"处理完继续传"；返回 writeWith(...) 表示"到此为止，直接响应客户端"
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 公开路径（登录、健康检查）直接放行，无需凭证
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // 鉴权分派：优先识别 API Key（第三方对接的固定头），其次走 JWT
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
        // apiKeyCache.verify 返回 Mono：empty=校验失败，有值=校验通过
        return apiKeyCache.verify(apiKey)
                // flatMap：拿到校验结果后继续异步处理（相当于异步世界的 map+展开）
                .flatMap(principal -> {
                    // 重建请求：mutate() 是"基于原请求造一个修改过的副本"（请求对象不可变）
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .headers(headers -> {
                                // 强制覆盖，防止伪造租户头：先 remove 再 set，客户端带的假头在这里被清掉
                                headers.remove(TenantContext.HEADER_TENANT);
                                headers.remove(TenantContext.HEADER_USER);
                                headers.set(TenantContext.HEADER_TENANT,
                                        String.valueOf(principal.tenantId()));
                                // API Key 代表系统调用而非具体用户，显式清空用户头（不 set）
                            })
                            .build();
                    // 用"改造后的 exchange"继续过滤器链 —— 后续过滤器和路由拿到的是干净请求
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                // switchIfEmpty：上游 Mono 为空（校验失败/平台不可用）时执行拒绝逻辑；
                // Mono.defer 延迟构造，避免 unauthorized 提前执行
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange, "API Key 无效、已停用或已过期")));
    }

    /** JWT 通道：解析令牌取租户与用户 */
    private Mono<Void> authenticateByJwt(ServerWebExchange exchange,
                                         GatewayFilterChain chain, String token) {
        final String tenantId;
        final String userId;
        try {
            // 验签 + 解析 payload（与 common 的 JwtVerifier 同一套 jjwt API，网关自带一份）
            Claims claims = Jwts.parser()
                    .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretKey))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // 令牌内的 tenantId 是租户上下文的唯一可信来源（签名保证不可伪造）
            tenantId = String.valueOf(claims.get("tenantId", Object.class));
            userId = String.valueOf(claims.get("userId", Object.class));
        } catch (Exception e) {
            // 签名不对 / 过期 / 格式错误，统一按 401 拒绝
            return unauthorized(exchange, "令牌无效或已过期");
        }

        // "null" 字符串防御：String.valueOf(null) 会产生 "null" 而不是 null
        if (tenantId == null || "null".equals(tenantId)) {
            return unauthorized(exchange, "令牌缺少租户信息");
        }

        // 重建请求头：先剥离客户端带来的同名头（防伪造），再写入从令牌解析出的可信值
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

    /** 公开路径白名单：登录接口（发令牌前当然没令牌）和健康检查（探活不能要凭证） */
    private boolean isPublicPath(String path) {
        return path.startsWith("/auth/") || path.startsWith("/actuator/health");
    }

    /** 从 Authorization 头解析 Bearer 令牌（OAuth2 标准格式：Bearer <token>） */
    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);   // 去掉 "Bearer " 前缀（7 个字符）
        }
        return null;
    }

    /**
     * 手写 401 响应（网关是 WebFlux，没有 common 的 GlobalExceptionHandler 可用，必须自己写响应体）。
     * 响应体格式与 R 保持一致：{"code":10002,"message":"...","traceId":"..."}
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);   // HTTP 401
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        // 带上链路 ID：401 多发生在客户端配置错误时，没有 traceId 很难排查
        String traceId = exchange.getResponse().getHeaders().getFirst(TraceContext.HEADER);
        String tracePart = traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"";
        // 手工拼 JSON（不用 Jackson 是因为这里格式固定且要避免额外依赖开销）
        String body = String.format("{\"code\":10002,\"message\":\"%s\"%s}", message, tracePart);
        // writeWith：把数据写入响应并结束请求 —— Mono.just 包装字节数组作为响应体
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * 过滤器顺序：-100。数字越小越先执行。
     * 必须在 TraceIdGlobalFilter（最小值）之后、路由转发之前。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
