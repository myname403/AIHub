package com.aihub.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 知识库概要。
 *
 * <p>服务端 {@code GET /api/ai/kb} 复用 {@code DocumentInfo} 承载知识库
 * （id = 知识库 ID、fileType 固定为 "kb"），字段语义略显别扭；
 * 这里显式声明成独立类型，让 MCP 侧不必知道那个历史包袱。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbBrief {

    private Long id;
    private String name;
    private Integer status;

    public boolean enabled() {
        return status != null && status == 1;
    }
}
