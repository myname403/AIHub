package com.aihub.ai.web;

import com.aihub.ai.application.ChatAppService;
import com.aihub.ai.domain.model.ChatTurn;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.web.sink.NdjsonStreamSink;
import com.aihub.ai.web.sink.SseStreamSink;
import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话接口：同一套编排，三种流式通道。
 *
 * <ul>
 *   <li>POST /api/ai/chat         同步（调试用）</li>
 *   <li>POST /api/ai/chat/sse     H5（SSE）</li>
 *   <li>POST /api/ai/chat/ndjson  微信小程序（NDJSON Chunked，禁 gzip）</li>
 * </ul>
 *
 * <p>架构约束：web 层只依赖 application 与 domain，禁止触碰 Spring AI / infra。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAppService chatAppService;

    /** 流式任务执行线程池（阻塞式写回，与容器线程隔离） */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(32, r -> {
        Thread t = new Thread(r, "aihub-stream-" + UUID.randomUUID().toString().substring(0, 8));
        t.setDaemon(true);
        return t;
    });

    /* ---------------- 同步 ---------------- */

    @PostMapping("/chat")
    public R<Map<String, Object>> chat(@Valid @RequestBody ChatRequest request) {
        ChatTurn turn = turn(request);
        long start = System.currentTimeMillis();
        String content = chatAppService.chat(turn);
        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", turn.conversationId());
        data.put("content", content);
        data.put("costMs", System.currentTimeMillis() - start);
        return R.ok(data);
    }

    /* ---------------- H5：SSE ---------------- */

    @PostMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatSse(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        ChatTurn turn = turn(request);
        streamExecutor.submit(() -> {
            StreamSink sink = new SseStreamSink(emitter);
            try {
                chatAppService.chatStream(turn, sink);
            } catch (Exception e) {
                sink.emit(StreamEvent.error(9999, "50000", "服务异常"));
                sink.close();
            }
        });
        return emitter;
    }

    /* ---------------- 微信小程序：NDJSON Chunked ---------------- */

    @PostMapping(value = "/chat/ndjson", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> chatNdjson(@Valid @RequestBody ChatRequest request) {
        ChatTurn turn = turn(request);

        StreamingResponseBody body = (OutputStream out) -> {
            StreamSink sink = new NdjsonStreamSink(out);
            try {
                chatAppService.chatStream(turn, sink);
            } catch (Exception e) {
                sink.emit(StreamEvent.error(9999, "50000", "服务异常"));
                sink.close();
            }
        };

        HttpHeaders headers = new HttpHeaders();
        // ★ 小程序分块传输硬性要求：禁 gzip、禁中间层缓冲与转换
        headers.setContentType(new MediaType("application", "x-ndjson", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_ENCODING, "identity");
        headers.set("X-Accel-Buffering", "no");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /* ---------------- 私有 ---------------- */

    private ChatTurn turn(ChatRequest request) {
        Long tenantId = TenantContext.requireTenantId();
        String conversationId = request.getConversationId() == null || request.getConversationId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : request.getConversationId();
        return new ChatTurn(
                tenantId,
                TenantContext.getUserId(),
                request.getAppId() == null ? 0L : request.getAppId(),
                conversationId,
                request.getMessage(),
                request.getScene() == null ? "chat" : request.getScene());
    }

    @Data
    public static class ChatRequest {
        /** 应用（助手）ID，未指定时走默认配置 */
        private Long appId;
        /** 会话 ID，不传则新建 */
        private String conversationId;
        @NotBlank(message = "消息内容不能为空")
        private String message;
        /** 场景：chat / rag（M1 固定 chat） */
        private String scene;
    }
}
