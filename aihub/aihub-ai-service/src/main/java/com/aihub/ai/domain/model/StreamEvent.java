package com.aihub.ai.domain.model;

import java.util.Map;

/**
 * 流式事件帧（统一信封，三种通道共用）。
 *
 * <pre>{ "i":12, "e":"token", "ts":1757400000000, "data":{...} }</pre>
 */
public record StreamEvent(int index, String event, long ts, Map<String, Object> data) {

    /* 事件类型常量（与 03 号文档 7.1 节一致） */
    public static final String MSG_START = "msg.start";
    public static final String TOKEN = "token";
    public static final String TOOL_START = "tool.start";
    public static final String TOOL_END = "tool.end";
    public static final String RAG_SOURCES = "rag.sources";
    public static final String AGENT_STEP = "agent.step";
    public static final String ARTIFACT = "artifact";
    public static final String MSG_END = "msg.end";
    public static final String ERROR = "error";
    public static final String PING = "ping";

    public static StreamEvent of(int index, String event, Map<String, Object> data) {
        return new StreamEvent(index, event, System.currentTimeMillis(), data);
    }

    public static StreamEvent token(int index, String content) {
        return of(index, TOKEN, Map.of("content", content == null ? "" : content));
    }

    public static StreamEvent error(int index, String code, String message) {
        return of(index, ERROR, Map.of("code", code, "message", message == null ? "" : message));
    }

    public static StreamEvent end(int index, Long costMs, String modelCode) {
        return of(index, MSG_END, Map.of(
                "costMs", costMs == null ? 0L : costMs,
                "model", modelCode == null ? "" : modelCode));
    }
}
