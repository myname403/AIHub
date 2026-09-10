package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.StreamEvent;

/**
 * 流式输出抽象。
 *
 * <p>application 层只依赖本接口，不感知 SSE / NDJSON / WebSocket 的差异，
 * 三种通道由 web 层提供不同实现（见 03 号文档 7.2 节）。
 */
public interface StreamSink {

    void emit(StreamEvent event);

    default void close() {
    }
}
