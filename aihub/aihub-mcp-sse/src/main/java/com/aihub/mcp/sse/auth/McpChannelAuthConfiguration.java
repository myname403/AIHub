package com.aihub.mcp.sse.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 通道门禁装配：{@code aihub.mcp.auth.enabled=true} 时注册鉴权过滤器。
 *
 * <p>与存储开关（StorageConfiguration）同一个收敛思路：开关 + 装配收拢在一处，
 * 默认关闭保持既有本地行为；过滤器挂在最前（order 0），赶在 MCP transport
 * 处理请求之前完成门禁。
 */
@Configuration
@ConditionalOnProperty(prefix = "aihub.mcp.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpChannelAuthProperties.class)
public class McpChannelAuthConfiguration {

    /**
     * enabled=true 却没配 token 是部署错误：**启动期 fail-fast**，
     * 而不是默默让所有人 401 或（更糟）全部放行。
     */
    public McpChannelAuthConfiguration(McpChannelAuthProperties properties) {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            throw new IllegalStateException(
                    "aihub.mcp.auth.enabled=true 但未配置 aihub.mcp.auth.token：通道门禁没有令牌可校验，拒绝启动");
        }
    }

    @Bean
    public FilterRegistrationBean<McpChannelAuthFilter> mcpChannelAuthFilter(McpChannelAuthProperties properties) {
        FilterRegistrationBean<McpChannelAuthFilter> registration =
                new FilterRegistrationBean<>(new McpChannelAuthFilter(properties));
        registration.setOrder(0);
        // 只对 MCP 端点生效；用 properties 的 protected-paths 做双保险
        //（过滤器内部还会按同一份列表判断，配置改动只需一处）
        registration.addUrlPatterns(properties.getProtectedPaths().stream()
                .map(path -> path.endsWith("/") ? path + "*" : path + "/*")
                .toArray(String[]::new));
        return registration;
    }
}
