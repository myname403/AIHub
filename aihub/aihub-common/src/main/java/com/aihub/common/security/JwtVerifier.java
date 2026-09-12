package com.aihub.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JWT 校验器（common 层共用：WebSocket 握手等无法经过网关过滤器的入口）。
 *
 * <p><b>背景 —— 什么是 JWT？</b>
 * JWT（JSON Web Token）是一串自带签名的令牌，形如 {@code xxxxx.yyyyy.zzzzz}（三段，点分隔）：
 * <ul>
 *   <li>第 1 段 Header：声明签名算法，如 {"alg":"HS256"}</li>
 *   <li>第 2 段 Payload（即 Claims）：业务数据，如 tenantId、userId、过期时间</li>
 *   <li>第 3 段 Signature：用服务端密钥对前两段的签名，防伪造、防篡改</li>
 * </ul>
 * 服务端不存会话（无状态），拿到 token 验签通过即认人 —— 天然适合微服务。
 *
 * <p><b>本类职责：</b>平台服务负责"签发"（见 platform 的 JwtTokenProvider），
 * 本类只负责"校验"：验签 + 检查过期。签名不对 / token 过期 / 格式错误统一返回空 Map。
 *
 * <p><b>安全约束不变：</b>tenantId 只来自令牌，绝不接受前端传参。
 *
 * <p><b>为什么网关验过一次，这里还要验？</b>纵深防御：
 * WebSocket 握手（AiHub 部署时直连 AI 服务）等入口绕过了网关过滤器，
 * 这些入口必须自己再验一遍，不能假设"一定有人验过了"。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
public final class JwtVerifier {

    /** 私有构造器：纯静态工具类，禁止实例化 */
    private JwtVerifier() {
    }

    /**
     * 校验并解析 JWT。
     *
     * @param secret 签名密钥（与签发方一致，来自配置；HS256 要求至少 32 字节）
     * @param token  前端带来的完整 JWT 字符串
     * @return Claims（payload 的键值对，含 tenantId/userId/sub/exp 等）；<b>无效/过期返回空 Map</b>
     *         —— 用"空结果"而不是抛异常，让调用方用 {@code isEmpty()} 判断，避免每个调用点都写 try-catch
     */
    public static Map<String, Object> verify(String secret, String token) {
        try {
            // Jwts.parser() 构建解析器：先绑定验签密钥，再解析 token
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseSignedClaims(token)   // 这一步完成：验签 + 过期检查 + 格式检查，任一失败抛异常
                    .getPayload();              // 取出 payload（Claims 继承自 Map）
            return claims;
        } catch (Exception e) {
            // 签名不匹配、ExpiredJwtException、格式错误……统统视为"无效令牌"，返回空 Map
            // 注意：这里吞异常是有意设计（无效 token 是常态，不是错误），需要细节时可在网关日志里看
            return Map.of();
        }
    }
}
