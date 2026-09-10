package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.ChatTurn;

/**
 * 对话执行 SPI（★ 扩展点）。
 *
 * <p>定义在领域层、实现在 infra-ai（内部使用 Spring AI）。
 * application 层只认识本接口，因此将来升级 Spring AI 2.0 时业务代码零改动。
 */
public interface ChatExecutor {

    /** 同步对话，返回完整回答与用量（token 用于计费与配额，不可为 null 时填 0） */
    StreamResult call(ChatTurn turn);

    /**
     * 流式对话：逐 token 通过 sink 回传，阻塞直至结束。
     * 返回最终完整回答、模型标识与用量。
     */
    StreamResult stream(ChatTurn turn, StreamSink sink);

    record StreamResult(String content, String modelCode, Long costMs,
                        Integer tokenIn, Integer tokenOut) {

        /** 无用量数据的兜底（如 Agent 链路、模型未返回 usage） */
        public static StreamResult of(String content, String modelCode, Long costMs) {
            return new StreamResult(content, modelCode, costMs, 0, 0);
        }
    }
}
