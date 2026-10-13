package com.aihub.ai.domain.model;

import java.util.Map;

/**
 * 流式事件桥——把"当前会话的 SequencedSink"送达 ChatClient 内部执行的工具层。
 *
 * <p><b>为什么需要它：</b>工具（{@code @Tool} 方法）由模型在 ChatClient 调用<b>内部</b>触发，
 * 方法签名里没有 sink 参数，工具实现层天然拿不到"往哪个流发事件"。
 * 与 AiCallContext（infra 层的调用上下文）解决的是同一类问题（调用上下文透传），
 * 但难度更高——因为工具执行可能发生在<b>另一个线程</b>：
 *
 * <ul>
 *   <li><b>Agent 链路</b>：{@code client.prompt().call()} 同步调用，工具执行与编排代码
 *       同线程 → ThreadLocal 直接命中；</li>
 *   <li><b>chat/rag 流式链路</b>：{@code .stream()} 返回 Flux，工具执行发生在 Reactor
 *       调度线程上，ThreadLocal 不跨线程 → 必须走 <b>ToolContext</b>（Spring AI 在
 *       prompt 级别携带的上下文 Map，随工具调用传入回调，与线程无关）。</li>
 * </ul>
 *
 * <p><b>双通道设计：</b>{@link #CTX_KEY} 常量 + {@link #toolContextMap()} 供 prompt 构建
 * 方把 sink 塞进 ToolContext（跨线程可靠，主通道）；{@link #get()} 供同步链兜底
 * （Agent 编排里每次 LLM 调用都会 set，同线程读必命中）。
 * 工具回调层按"ToolContext 优先、ThreadLocal 兜底"解析（见 AuditedToolCallback）。
 *
 * <p>放置在 domain.model 而非 infra：ChatAppService（application 层）要设置桥，
 * 依赖方向 application → domain 合法，application → infra 被架构约束禁止。
 * 另外桥还捎带记录"最近一次 LLM 调用实际使用的模型编码"，供 msg.end 带出
 * （此前 Agent 链路的 msg.end model 恒为空串，前端模型标签永远不显示）。
 */
public final class StreamBridge {

    /** ToolContext 中携带 SequencedSink 的键（工具回调层据此取桥） */
    public static final String CTX_KEY = "aihub.stream.bridge";

    private static final ThreadLocal<SequencedSink> SINK_TL = new ThreadLocal<>();
    /** 最近一次 LLM 调用的实际模型编码（Agent 链路同步执行，ThreadLocal 可靠） */
    private static final ThreadLocal<String> MODEL_TL = new ThreadLocal<>();

    private StreamBridge() {
    }

    public static void set(SequencedSink sink) {
        SINK_TL.set(sink);
    }

    public static SequencedSink get() {
        return SINK_TL.get();
    }

    public static void setModel(String modelCode) {
        MODEL_TL.set(modelCode);
    }

    public static String model() {
        return MODEL_TL.get();
    }

    /** 清空（请求结束必须调用，线程池线程会被复用，残留会串会话） */
    public static void clear() {
        SINK_TL.remove();
        MODEL_TL.remove();
    }

    /** 供 prompt 构建方使用：有桥时返回携带桥的 ToolContext，无桥返回空 Map（工具只审计不发事件） */
    public static Map<String, Object> toolContextMap() {
        SequencedSink sink = SINK_TL.get();
        return sink == null ? Map.of() : Map.of(CTX_KEY, sink);
    }
}
