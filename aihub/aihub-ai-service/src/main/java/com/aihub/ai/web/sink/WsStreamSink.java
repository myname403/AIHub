package com.aihub.ai.web.sink;

import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.StreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * WebSocket 流式通道（Agent 长任务）：双向通信、可中断。
 */
@Slf4j
public class WsStreamSink implements StreamSink {

    private final WebSocketSession session;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean closed = false;

    public WsStreamSink(WebSocketSession session) {
        this.session = session;
    }

    @Override
    public void emit(StreamEvent event) {
        if (closed || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
        } catch (IOException e) {
            closed = true;
            log.debug("WS 发送失败（连接可能已断开）: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
