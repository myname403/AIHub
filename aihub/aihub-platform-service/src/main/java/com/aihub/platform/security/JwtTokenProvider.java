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
 * JWT 签发与解析 —— 平台服务是全系统唯一的"发证机关"。
 *
 * <p><b>与 JwtVerifier 的分工：</b>本类负责"签发"（登录时）+"自证解析"；
 * common 的 JwtVerifier 负责其他入口（如 WebSocket 握手）的"校验"。
 * 两者使用同一个密钥（aihub.security.jwt-secret），网关验签也用它 ——
 * <b>密钥一致是整个 JWT 体系成立的前提</b>。
 *
 * <p><b>令牌里放了什么（payload/Claims）：</b>
 * <ul>
 *   <li>sub（subject）：用户名；</li>
 *   <li>tenantId / userId：自定义 claim —— <b>tenantId 是租户上下文的唯一可信来源</b>，
 *       网关从令牌解析后写入 X-Tenant-Id，全链路以此为准；</li>
 *   <li>iat：签发时间；exp：过期时间（默认 2 小时，过期即失效，无需服务端注销）。</li>
 * </ul>
 *
 * <p>令牌无状态（服务端不存会话），天然适合微服务横向扩容；
 * 代价是"签发后无法主动作废"——所以有敏感操作要重新认证，密钥泄露只能换密钥。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Component   // 注册为 Bean，AuthService / 其他服务注入使用
public class JwtTokenProvider {

    /** 自定义 claim 键名：tenantId。网关 AuthGlobalFilter 按这个名字取值 */
    public static final String CLAIM_TENANT = "tenantId";

    /** 自定义 claim 键名：userId */
    public static final String CLAIM_USER = "userId";

    /** 签名密钥（字节）。HS256 对称加密：签名和验签用同一把钥匙，必须保密 */
    private final byte[] secretKey;

    /** 有效期毫秒数（默认 7200 秒 = 2 小时） */
    private final long expireMillis;

    /**
     * 构造器注入配置。
     * @Value 语法：从 application.yml / Nacos 读值，冒号后是默认值（配置缺失时兜底）。
     */
    public JwtTokenProvider(@Value("${aihub.security.jwt-secret}") String jwtSecret,
                            @Value("${aihub.security.jwt-expire-seconds:7200}") long expireSeconds) {
        this.secretKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.expireMillis = expireSeconds * 1000;
    }

    /**
     * 签发令牌（登录成功后调用）。
     *
     * @param tenantId 租户 ID（写入自定义 claim，租户上下文的源头）
     * @param userId   用户 ID
     * @param username 用户名（写入标准 claim sub，便于日志排查）
     * @return 三段式 JWT 字符串
     */
    public String generate(Long tenantId, Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)                                              // sub = 用户名
                .claims(Map.of(CLAIM_TENANT, tenantId, CLAIM_USER, userId))     // 自定义 claims
                .issuedAt(now)                                                  // iat = 签发时间
                .expiration(new Date(now.getTime() + expireMillis))             // exp = 过期时间
                .signWith(Keys.hmacShaKeyFor(secretKey))                        // 用密钥签名（HS256）
                .compact();                                                     // 生成最终字符串
    }

    /**
     * 解析令牌（本服务内部自证用）。
     * parseSignedClaims 会同时完成：格式检查、验签、过期检查——任何一步失败抛异常。
     *
     * @throws io.jsonwebtoken.JwtException 签名不匹配/格式错误
     * @throws ExpiredJwtException          已过期
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secretKey))   // 绑定验签密钥
                .build()
                .parseSignedClaims(token)
                .getPayload();                               // Claims 继承自 Map，可直接 get
    }

    /** 从令牌提取租户 ID。claim 里存的是数字，JSON 往返后可能是字符串/整数，统一经 String 转换防类型歧义 */
    public Long parseTenantId(String token) {
        Object v = parse(token).get(CLAIM_TENANT);
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }

    /** 从令牌提取用户 ID（同上） */
    public Long parseUserId(String token) {
        Object v = parse(token).get(CLAIM_USER);
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }
}
