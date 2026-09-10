package com.aihub.ai.web.sink;

import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.StreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * H5 流式通道（SSE）。
 *
 * <p>注意：前端须用 fetch + ReadableStream 解析（EventSource 不能带自定义 Header）。
 */
@Slf4j
public class SseStreamSink implements StreamSink {

    private final SseEmitter emitter;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean closed = false;

    public SseStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void emit(StreamEvent event) {
        if (closed) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.event())
                    .data(mapper.writeValueAsString(event)));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开等情况：标记关闭，后续事件直接丢弃
            closed = true;
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 异常: {}", e.getMessage());
        }
    }
}
