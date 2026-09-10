package com.aihub.mcp.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * AIHub 统一响应信封（服务端 {@code R<T>} 的客户端镜像）。
 *
 * <p>为什么不在 MCP 模块复用服务端的 {@code R}：
 * <ol>
 *   <li>{@code R} 只有私有构造器、没有 {@code @JsonCreator}，Jackson 反序列化不了；</li>
 *   <li>它住在 {@code aihub-common} 里，而那个模块带了 servlet 容器。</li>
 * </ol>
 * MCP Server 消费的是网关的 <b>公开 HTTP 契约</b>，像 SDK 一样自带一份客户端类型
 * 反而是正确做法——服务端重构内部 DTO 时不会连带打破 MCP 侧。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiEnvelope<T> {

    /** 0 表示成功，其余为业务错误码 */
    private int code;
    private String message;
    private T data;

    public boolean succeeded() {
        return code == 0;
    }

    /** 失败时给出可读原因，交给模型比抛异常更有用——模型能据此换个参数重试 */
    public String errorText() {
        return "AIHub 调用失败（code=" + code + "）："
                + (message == null || message.isBlank() ? "无错误信息" : message);
    }
}
