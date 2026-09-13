package com.aihub.mcp.sse.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * MCP 通道门禁鉴权配置（{@code aihub.mcp.auth.*}）。
 *
 * <p>背景：MCP 工具能读写业务数据（列应用、检索知识库、发起对话），而这条通道
 * 历史上不做鉴权（默认只绑 127.0.0.1，鉴权在它背后的 AIHub 开放 API 上）。
 * 跨机暴露前，先在这里开启通道门禁，避免端口暴露即工具裸奔。
 *
 * <p>多租户语义（当前形态）：<b>一个 MCP 实例服务一个租户</b>——工具调用走
 * AIHub 开放 API 时携带的是 {@code aihub.mcp.api-key}（某租户的 Key），
 * 数据边界由网关的 Key 鉴权保证。本开关解决的是「通道本身要不要登录」，
 * 而不是「一条连接切多个租户」。多租户共享实例需按会话传递 Key，
 * 待 MCP 客户端生态支持 headers 透传后再演进（见 README 遗留清单）。
 */
@Data
@ConfigurationProperties(prefix = "aihub.mcp.auth")
public class McpChannelAuthProperties {

    /** 是否开启通道门禁（默认关闭，保持既有本地行为） */
    private boolean enabled = false;

    /** 门禁令牌：开启后请求须携带 {@code Authorization: Bearer <token>} */
    private String token = "";

    /**
     * 受保护的路径前缀：SSE 建连与消息回传端点 + streamable-http 默认端点。
     * 与 application.yml 的 sse-endpoint / sse-message-endpoint / streamable mcp-endpoint 对齐。
     */
    private List<String> protectedPaths = List.of("/sse", "/mcp/message", "/mcp");
}
