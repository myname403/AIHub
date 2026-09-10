package com.aihub.ai.web.ws;

import com.aihub.ai.application.ChatAppService;
import com.aihub.ai.domain.model.ChatTurn;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.web.sink.WsStreamSink;
import com.aihub.common.security.JwtVerifier;
import com.aihub.common.trace.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket 处理器（Agent 长任务通道，对齐课程 MyManus 的 WebSocket 通信）。
 *
 * <p>连接：ws://host:8082/ws/ai?token=JWT（令牌在握手时校验）
 * 消息：{"appId":9001,"conversationId":"x","message":"...","scene":"agent"}
 * 回推：统一事件帧（与 SSE / NDJSON 完全一致）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final ChatAppService chatAppService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${aihub.security.jwt-secret:aihub-default-dev-secret-please-change-me-32bytes}")
    private String jwtSecret;

    private final ExecutorService executor = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "aihub-ws-" + UUID.randomUUID().toString().substring(0, 8));
        t.setDaemon(true);
        return t;
    });

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String token = queryParam(session, "token");
        Map<String, Object> claims = JwtVerifier.verify(jwtSecret, token);
        if (claims.isEmpty()) {
            log.warn("WS 握手拒绝：令牌无效");
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        // 令牌校验通过后，把租户上下文绑定到会话属性
        session.getAttributes().put("tenantId", String.valueOf(claims.get("tenantId")));
        session.getAttributes().put("userId", String.valueOf(claims.get("userId")));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long tenantId = parseLong(session.getAttributes().get("tenantId"));
        if (tenantId == null) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            ChatTurn turn = new ChatTurn(
                    tenantId,
                    parseLong(session.getAttributes().get("userId")),
                    node.path("appId").asLong(0L),
                    node.path("conversationId").asText(
                            UUID.randomUUID().toString().replace("-", "").substring(0, 16)),
                    node.path("message").asText(""),
                    node.path("scene").asText("agent"));

            // 每个 WS 消息视为一条链路：先在本线程设置 traceId，再由 wrap 带到工作线程
            TraceContext.set(TraceContext.newTraceId());
            executor.submit(TraceContext.wrap(() -> {
                StreamSink sink = new WsStreamSink(session);
                try {
                    chatAppService.chatStream(turn, sink);
                } catch (Exception e) {
                    log.warn("WS 任务结束（含失败）conv={}", turn.conversationId());
                }
            }));
            TraceContext.clear();
        } catch (Exception e) {
            log.warn("WS 消息处理失败: {}", e.getMessage());
        }
    }

    private String queryParam(WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return kv[1];
            }
        }
        return null;
    }

    private Long parseLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
        }
    }
}
