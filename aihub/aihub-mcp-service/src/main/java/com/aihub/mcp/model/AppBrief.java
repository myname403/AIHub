package com.aihub.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 应用（助手）概要。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是刻意的：
 * 服务端 {@code App} 还有 systemPrompt / temperature 等字段，
 * 这里只取需要的几个，避免服务端加字段就把 MCP Server 打挂。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppBrief {

    private Long id;
    private String name;
    private String agentStrategy;
    private String memoryPolicy;
    private Integer status;

    public boolean enabled() {
        return status != null && status == 1;
    }
}
