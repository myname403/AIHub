package com.aihub.ai.web.sink;

import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.StreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * H5 流式通道（SSE）—— StreamSink 的 SSE 实现。
 *
 * <p><b>设计模式要点：</b>SseEmitter 是 Spring MVC 的 SSE 推送器；本类把它包成
 * 统一的 StreamSink 抽象，编排层（ChatAppService）完全不知道自己在跟谁说话 ——
 * 换成 WebSocket 或 NDJSON 只需换实现类。这就是"依赖倒置"：高层定接口，底层做实现。
 *
 * <p><b>volatile 关键字（本类正确性关键）：</b>closed 标记会被多个线程读写
 * （流任务线程 emit/close，可能还有超时线程），volatile 保证一个线程的写入
 * 立即对其他线程可见（否则可能一直读到缓存的旧值 false，往已关闭的连接写数据）。
 *
 * <p>注意：前端须用 fetch + ReadableStream 解析（EventSource 不能带自定义 Header，
 * 而本项目鉴权需要 Authorization 头）。
 * 详见学习文档《05-AI服务-aihub-ai-service.md》。
 */
@Slf4j
public class SseStreamSink implements StreamSink {

    private final SseEmitter emitter;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean closed = false;

    public SseStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * 推送一个事件给前端（H5 SSE 通道）。
     * 编排层（ChatAppService）每生成一段内容就调一次本方法，实现"打字机"效果。
     */
    @Override
    public void emit(StreamEvent event) {
        // ① 快速失败：连接已关闭（客户端断开 / 已完成）就直接丢弃，避免往死连接写数据抛异常
        if (closed) {
            return;
        }
        try {
            // ② 组装并发送一帧 SSE 事件：
            //    SseEmitter.event() 构建一个事件；
            //    .name(event.event()) 设置事件名（对应前端 addEventListener 的事件类型，如 token/end/error）；
            //    .data(...) 放事件数据——用 Jackson 把整个 StreamEvent 对象序列化成 JSON 字符串。
            //    发出去后浏览器收到的就是标准的 "event: xxx\ndata: {...}\n\n" 文本帧。
            emitter.send(SseEmitter.event()
                    .name(event.event())
                    .data(mapper.writeValueAsString(event)));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开等情况：标记关闭，后续事件直接丢弃
            // IOException：网络写失败（多为客户端已断开）；IllegalStateException：emitter 已 complete/超时
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
