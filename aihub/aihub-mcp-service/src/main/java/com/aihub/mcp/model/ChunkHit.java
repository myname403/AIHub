package com.aihub.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 检索命中的分片。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChunkHit {

    private String content;
    private double score;
    private Long kbId;
    private Long docId;
    private String docName;

    /** 相似度百分比，便于模型直读（模型对 0.83 这种小数的把握不如 83%） */
    public long scorePercent() {
        return Math.round(score * 100);
    }
}
