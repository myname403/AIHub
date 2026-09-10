package com.aihub.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MCP Server 侧配置。
 *
 * <p>MCP Server 本身不持有用户身份，它以一个 <b>API Key</b> 的身份回调 AIHub 网关。
 * 因此这里最关键的两项就是 {@code baseUrl} 与 {@code apiKey}——
 * 换 Key 即可切换租户，这正是多租户在 MCP 场景下的落点。
 */
@Data
@ConfigurationProperties(prefix = "aihub.mcp")
public class AiHubMcpProperties {

    /** AIHub 网关地址；MCP Server 的一切业务调用都经它转发 */
    private String baseUrl = "http://127.0.0.1:8080";

    /** 开放 API Key（ak_ 开头）。为空则不做鉴权，网关会直接返回 401 */
    private String apiKey = "";

    /**
     * 单次调用超时。
     *
     * <p>必须设：MCP 工具是同步阻塞的，网关或模型侧卡住会把整个会话线程一并挂死。
     */
    private Duration timeout = Duration.ofSeconds(60);

    /** 未指定 appId 时使用的默认应用 */
    private Long defaultAppId;

    /** 检索默认返回条数 */
    private int defaultTopK = 5;

    /** 单条分片回传给模型时的截断长度，防止一次工具调用塞爆上下文 */
    private int chunkMaxChars = 800;
}
