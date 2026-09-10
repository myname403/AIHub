package com.aihub.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与解析。
 *
 * <p>令牌内的 {@code tenantId} 是租户上下文的唯一可信来源。
 */
@Component
public class JwtTokenProvider {

    public static final String CLAIM_TENANT = "tenantId";
    public static final String CLAIM_USER = "userId";

    private final byte[] secretKey;
    private final long expireMillis;

    public JwtTokenProvider(@Value("${aihub.security.jwt-secret}") String jwtSecret,
                            @Value("${aihub.security.jwt-expire-seconds:7200}") long expireSeconds) {
        this.secretKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.expireMillis = expireSeconds * 1000;
    }

    public String generate(Long tenantId, Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claims(Map.of(CLAIM_TENANT, tenantId, CLAIM_USER, userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(Keys.hmacShaKeyFor(secretKey))
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secretKey))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long parseTenantId(String token) {
        Object v = parse(token).get(CLAIM_TENANT);
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }

    public Long parseUserId(String token) {
        Object v = parse(token).get(CLAIM_USER);
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }
}
