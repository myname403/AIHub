package com.aihub.mcp.api;

/**
 * AIHub 调用失败（网络、超时、非 2xx、业务码非 0）。
 *
 * <p>刻意做成非受检异常：工具方法要把失败<b>转成文字</b>回给模型，
 * 而不是让异常穿透 MCP 协议层变成一条看不懂的 JSON-RPC error。
 */
public class AiHubCallException extends RuntimeException {

    public AiHubCallException(String message) {
        super(message);
    }

    public AiHubCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
