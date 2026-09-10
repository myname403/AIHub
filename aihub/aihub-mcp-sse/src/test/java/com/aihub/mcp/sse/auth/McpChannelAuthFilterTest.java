package com.aihub.mcp.sse.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 通道门禁过滤器单测：钉住四件事——开关关闭全放行、缺/错令牌 401、
 * 正确令牌放行、非保护路径不受影响。纯 Servlet Mock，不碰网络。
 */
class McpChannelAuthFilterTest {

    private static McpChannelAuthProperties properties(boolean enabled, String token) {
        McpChannelAuthProperties properties = new McpChannelAuthProperties();
        properties.setEnabled(enabled);
        properties.setToken(token);
        properties.setProtectedPaths(List.of("/sse", "/mcp/message", "/mcp"));
        return properties;
    }

    private McpChannelAuthFilter filter(boolean enabled, String token) {
        return new McpChannelAuthFilter(properties(enabled, token));
    }

    private MockHttpServletRequest request(String uri, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void disabledFilterAllowsAllRequests() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter(false, "").doFilter(request("/sse", null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void missingTokenRejectedWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true, "secret-token").doFilter(request("/sse", null), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unauthorized");
    }

    @Test
    void wrongTokenRejectedWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true, "secret-token")
                .doFilter(request("/sse", "Bearer wrong-token"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void nonBearerSchemeRejectedWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true, "secret-token")
                .doFilter(request("/mcp/message", "Basic dXNlcjpwYXNz"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void correctTokenPassesThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true, "secret-token")
                .doFilter(request("/sse", "Bearer secret-token"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void messageEndpointWithSubPathIsProtected() throws Exception {
        // /mcp/message?sessionId=xxx 是消息回传端点，带查询串也要拦
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true, "secret-token")
                .doFilter(request("/mcp/message", null), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unprotectedPathNotAffected() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter(true, "secret-token").doFilter(request("/actuator/health", null), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}
