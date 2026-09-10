package com.aihub.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JWT 校验器（common 层共用：WebSocket 握手等无法经过网关过滤器的入口）。
 *
 * <p>安全约束不变：tenantId 只来自令牌，绝不接受前端传参。
 */
public final class JwtVerifier {

    private JwtVerifier() {
    }

    /** 校验并返回 claims；无效/过期返回 empty */
    public static Map<String, Object> verify(String secret, String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
