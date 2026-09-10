package com.aihub.api.client;

/**
 * 配额维度常量（跨服务共享，AI 侧与平台侧必须用同一套字面量）。
 *
 * <p>放在契约模块，避免两端各写一份字符串导致扣减口径漂移。
 */
public final class QuotaDimensions {

    /** 请求次数：每次对话/调用扣 1 */
    public static final String REQUEST = "request";

    /** Token 用量：按 token_in + token_out 扣减 */
    public static final String TOKEN = "token";

    /** 文档入库：每个文档入库扣 1 */
    public static final String DOC = "doc";

    /** Agent 任务：每个 Agent 任务扣 1 */
    public static final String TASK = "task";

    private QuotaDimensions() {
    }
}
