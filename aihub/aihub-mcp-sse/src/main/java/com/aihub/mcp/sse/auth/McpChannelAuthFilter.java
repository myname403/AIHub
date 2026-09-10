package com.aihub.mcp.sse.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP 通道门禁过滤器：受保护路径要求 {@code Authorization: Bearer <token>}。
 *
 * <p>设计要点：
 * <ul>
 *   <li>只拦 {@code aihub.mcp.auth.protected-paths} 列出的 MCP 端点，其余路径
 *       （actuator 等）不受影响；</li>
 *   <li>令牌比较用 {@link MessageDigest#isEqual} 常量时间比较，不给时序攻击留口子；</li>
 *   <li>拒绝时返回 401 + JSON，MCP 客户端在建立连接 / 发消息时能立刻看到明确原因，
 *       而不是连接挂着等到工具调用才神秘失败；</li>
 *   <li>{@code enabled=false} 时整个过滤器直接放行——本地默认形态零行为变化。</li>
 * </ul>
 */
public class McpChannelAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final McpChannelAuthProperties properties;

    public McpChannelAuthFilter(McpChannelAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.isEnabled() || !isProtected(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && matchesToken(authorization)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\":\"unauthorized\",\"message\":\"MCP 通道需要鉴权：Authorization: Bearer <aihub.mcp.auth.token>\"}");
    }

    /** URI 精确或前缀匹配受保护路径（/mcp/* 这类带会话后缀的端点走前缀匹配） */
    private boolean isProtected(String uri) {
        for (String path : properties.getProtectedPaths()) {
            if (uri.equals(path) || uri.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 校验 "Bearer <token>"；常量时间比较，防止逐字节试探令牌 */
    private boolean matchesToken(String authorization) {
        if (!authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] provided = authorization.substring(BEARER_PREFIX.length())
                .getBytes(StandardCharsets.UTF_8);
        byte[] expected = properties.getToken().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }
}
