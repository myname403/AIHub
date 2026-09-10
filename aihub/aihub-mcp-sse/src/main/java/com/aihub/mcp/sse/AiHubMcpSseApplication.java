package com.aihub.mcp.sse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server（SSE 传输）启动类。
 *
 * <p>{@code scanBasePackages} 指到 {@code com.aihub.mcp}：
 * 工具装配在 {@code aihub-mcp-service} 模块里，两个传输模块共用同一套实现，
 * 因此必须往上扫一层，否则只会扫到本包、拿不到 {@code AiHubMcpConfiguration}。
 */
@SpringBootApplication(scanBasePackages = "com.aihub.mcp")
public class AiHubMcpSseApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiHubMcpSseApplication.class, args);
    }
}
