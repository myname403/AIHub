package com.aihub.api.client;

/**
 * 配额维度常量（跨服务共享，AI 侧与平台侧必须用同一套字面量）。
 *
 * <p><b>解决什么问题：</b>配额系统按"维度"计量扣减（请求次数、token 数、文档数……）。
 * AI 服务上报时写字符串、平台服务扣减时也用字符串 —— 如果两边各写一份，
 * 一边手滑写成 "tokens"，扣减就静默失配（不报错但账对不上）。
 * 把字符串收进常量类，两端引用同一份定义，杜绝口径漂移。
 *
 * <p><b>为什么放 aihub-api 而不是各自模块：</b>契约模块被两端同时依赖，
 * 天然是"共享常量"的家 —— 与 PlatformClient（也是两端共享的契约）同源。
 *
 * <p><b>类设计：</b>final + 私有构造器的常量工具类（接口定义常量的老写法已过时，
 * 且接口会被类实现导致常量污染，用 final class 更规范）。
 * 详见学习文档《02-契约模块-aihub-api.md》。
 */
public final class QuotaDimensions {

    /** 请求次数：每次对话/调用扣 1 */
    public static final String REQUEST = "request";

    /** Token 用量：按 token_in + token_out 扣减（大模型计费的基本单位） */
    public static final String TOKEN = "token";

    /** 文档入库：每个文档入库扣 1（RAG 知识库上传） */
    public static final String DOC = "doc";

    /** Agent 任务：每个 Agent 任务扣 1（Agent 通常多步执行，单独计） */
    public static final String TASK = "task";

    /** 私有构造器：常量类禁止实例化 */
    private QuotaDimensions() {
    }
}
