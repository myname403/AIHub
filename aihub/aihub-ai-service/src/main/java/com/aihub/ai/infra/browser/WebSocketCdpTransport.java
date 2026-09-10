package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 <b>JDK 内置</b> {@code java.net.http.WebSocket} 的 CDP 通道。
 *
 * <p><b>为什么不用 Playwright / Selenium：</b>
 * 那两个方案的 Java 绑定都要额外拉一个几十到上百 MB 的驱动内核（Playwright 的
 * driver-bundle 就有 192MB）。而 CDP（Chrome DevTools Protocol）本身就是
 * 「WebSocket + JSON」这么简单的东西，JDK 从 11 起就自带 WebSocket 客户端，
 * 于是我们**零新增依赖**就能驱动本机已装的 Chrome/Edge。
 *
 * <p>代价是要自己处理请求-响应配对与超时——就是这个类在干的事，代码量并不大。
 *
 * <p><b>关于事件：</b>这里只做请求-响应，收到的 CDP 事件直接丢弃。
 * 因为驱动侧（等待页面加载完）用轮询 {@code document.readyState} 实现，
 * 比维护事件订阅 + sessionId 过滤简单得多，也更不容易出错。
 */
@Slf4j
public class WebSocketCdpTransport implements CdpTransport {

    private final ObjectMapper mapper;
    private final Duration timeout;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private final WebSocket socket;
    private volatile boolean closed;

    public WebSocketCdpTransport(String wsUrl, ObjectMapper mapper, Duration timeout) {
        this.mapper = mapper;
        this.timeout = timeout;
        try {
            this.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(URI.create(wsUrl), new Listener())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new BrowserException("连接浏览器调试端口失败：" + wsUrl, e);
        }
    }

    @Override
    public JsonNode call(String method, ObjectNode params, String sessionId) {
        if (closed) {
            throw new BrowserException("浏览器连接已关闭，无法执行 " + method);
        }
        long id = ids.getAndIncrement();
        ObjectNode request = mapper.createObjectNode();
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        if (sessionId != null) {
            request.put("sessionId", sessionId);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            socket.sendText(request.toString(), true)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            JsonNode response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            JsonNode error = response.get("error");
            if (error != null) {
                throw new BrowserException(method + " 失败：" + error.path("message").asText());
            }
            JsonNode result = response.get("result");
            return result == null ? mapper.createObjectNode() : result;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new BrowserException(method + " 超时（" + timeout.toSeconds() + "s）");
        } catch (BrowserException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            throw new BrowserException(method + " 调用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 先让等待中的调用失败，免得它们干等到超时
        failAllPending("浏览器连接已关闭");
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            socket.abort();
        }
    }

    private void failAllPending(String reason) {
        pending.forEach((id, future) -> future.completeExceptionally(new BrowserException(reason)));
        pending.clear();
    }

    /**
     * 收包监听器。
     *
     * <p>注意一条 CDP 消息可能被拆成多次 {@code onText} 回调（最后一个片段 last=true），
     * 所以必须自己拼接；截图那种几十 KB 的 base64 一定会走这条路径。
     */
    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            String complete = null;
            synchronized (buffer) {
                buffer.append(data);
                if (last) {
                    complete = buffer.toString();
                    buffer.setLength(0);
                }
            }
            if (complete != null) {
                handle(complete);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed = true;
            failAllPending("浏览器连接被关闭（" + statusCode + " " + reason + "）");
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed = true;
            log.warn("CDP WebSocket 异常: {}", error.toString());
            failAllPending("浏览器连接异常：" + error.getMessage());
        }
    }

    private void handle(String message) {
        JsonNode node;
        try {
            node = mapper.readTree(message);
        } catch (Exception e) {
            log.warn("CDP 消息解析失败（已忽略）: {}", message.length() > 200
                    ? message.substring(0, 200) + "..." : message);
            return;
        }
        JsonNode idNode = node.get("id");
        if (idNode != null && idNode.isNumber()) {
            CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
            if (future != null) {
                future.complete(node);
            }
            return;
        }
        // 无 id = 事件（Page.loadEventFired 之类）。本实现不依赖事件，丢弃即可。
        if (log.isTraceEnabled()) {
            log.trace("CDP 事件: {}", node.path("method").asText());
        }
    }
}
