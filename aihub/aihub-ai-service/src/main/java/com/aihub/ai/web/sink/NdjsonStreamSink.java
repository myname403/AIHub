package com.aihub.ai.web.sink;

import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.StreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 微信小程序流式通道（NDJSON Chunked）。
 *
 * <p>每行一个 JSON 事件帧，客户端用 uni.request({enableChunked:true}) 的
 * onChunkReceived 接收，并按 \n 切分处理半包。
 *
 * <p>服务端硬性要求（见 03 号文档 7.2）：禁止 gzip（Content-Encoding: identity）、
 * X-Accel-Buffering: no、Cache-Control: no-transform，由 Controller 统一设置。
 */
public class NdjsonStreamSink implements StreamSink {

    private final OutputStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean closed = false;

    public NdjsonStreamSink(OutputStream out) {
        this.out = out;
    }

    @Override
    public void emit(StreamEvent event) {
        if (closed) {
            return;
        }
        try {
            String line = mapper.writeValueAsString(event) + "\n";
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            closed = true; // 客户端断开
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            out.flush();
        } catch (IOException ignored) {
        }
    }
}
