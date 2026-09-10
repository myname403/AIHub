package com.aihub.mcp.config;

import com.aihub.mcp.api.AiHubApi;
import com.aihub.mcp.api.HttpAiHubApi;
import com.aihub.mcp.tools.AiHubTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * MCP 工具装配（sse / stdio 两个传输模块共用）。
 *
 * <p>把「工具实现」与「传输方式」彻底分开是 ADR-3 的核心：
 * 一套 {@link AiHubTools}，SSE 与 stdio 两种协议复用，
 * 新增传输方式（如 streamable-http）时这里一行都不用改。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiHubMcpProperties.class)
public class AiHubMcpConfiguration {

    @Bean
    public AiHubApi aiHubApi(AiHubMcpProperties properties) {
        RestClient client = HttpAiHubApi
                .configure(RestClient.builder(), properties.getBaseUrl(),
                        properties.getApiKey(), properties.getTimeout())
                .build();
        log.info("MCP 工具将回调 AIHub：baseUrl={} apiKey={} timeout={}s",
                properties.getBaseUrl(),
                HttpAiHubApi.maskApiKey(properties.getApiKey()),
                properties.getTimeout().toSeconds());
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.warn("未配置 aihub.mcp.api-key —— 所有工具调用都会被网关以 401 拒绝。"
                    + "请先在管理后台签发 API Key，再设置配置项 aihub.mcp.api-key "
                    + "或环境变量 AIHUB_API_KEY");
        }
        return new HttpAiHubApi(client, properties.getDefaultTopK());
    }

    @Bean
    public AiHubTools aiHubTools(AiHubApi api, AiHubMcpProperties properties) {
        return new AiHubTools(api, properties);
    }

    /**
     * 把 {@link AiHubTools} 里的 {@code @Tool} 方法注册为 MCP 工具。
     *
     * <p>MCP Server 的自动装配会收集容器里的 {@link ToolCallbackProvider}，
     * 并据此生成 tools/list 结果——因此这个 Bean 就是「能力对外暴露」的开关。
     */
    @Bean
    public ToolCallbackProvider aiHubToolCallbackProvider(AiHubTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
