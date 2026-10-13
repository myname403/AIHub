package com.aihub.ai.domain.model;

import java.util.Map;

/**
 * 流式事件帧（统一信封，三种通道共用）。
 *
 * <pre>{ "i":12, "e":"token", "ts":1757400000000, "data":{...} }</pre>
 *
 * <p><b>record 语法（Java 17，本项目 DTO 的标准写法）：</b>
 * record 声明一行即得到：final 字段 + 构造器 + 访问器（index() 而非 getIndex()）+
 * equals/hashCode/toString。不可变对象天然线程安全，特别适合"产生后只读"的事件帧。
 *
 * <p><b>事件流的时间线（对照事件类型常量理解）：</b>
 * <pre>
 *   msg.start → [rag.sources] → token×N → [tool.start → tool.end]×N
 *   → [agent.step]×N → [artifact] → msg.end
 *   （任何时刻出错发 error；空闲心跳发 ping）
 * </pre>
 * index 字段是事件序号（从 0 递增），前端用它排序/去重/判断丢帧。
 */
public record StreamEvent(int index, String event, long ts, Map<String, Object> data) {

    /* 事件类型常量（与 03 号文档 7.1 节一致） */
    public static final String MSG_START = "msg.start";
    public static final String TOKEN = "token";
    /** 推理模型的“思考过程”增量（来自 reasoning_content），与正式回答 token 分开下发 */
    public static final String REASONING = "reasoning";
    /**
     * 工具调用开始/结束（对齐 ChatGPT / Coze 的工具调用卡片）。
     *
     * <p>为什么由 {@code AuditedToolCallback} 发：它是所有工具（内置 + MCP）的统一包装层，
     * 在这一层广播事件，工具实现类无需感知"自己在被谁调用、前端要不要看"——
     * 与审计落库同源同层，一次工具执行同时产生审计记录与前端事件。
     *
     * <p>data 约定：
     * <pre>
     *   tool.start: { name, source(local/mcp), input(入参摘要) }
     *   tool.end:   { name, source, success, costMs, output(出参/错误摘要) }
     * </pre>
     * 事件怎么从 ChatClient 内部送到 sink：见 {@link SequencedSink} 与
     * {@link StreamBridge}（ToolContext 跨线程传递 + ThreadLocal 同步链兜底）。
     */
    public static final String TOOL_START = "tool.start";
    public static final String TOOL_END = "tool.end";
    public static final String RAG_SOURCES = "rag.sources";
    public static final String AGENT_STEP = "agent.step";
    /**
     * 任务计划（对齐 Manus 的计划清单）：PlanningAgent 拆解完成后发一次，
     * data = { tasks: [{agent, task}] }；随后每个子任务完成发一条
     * {@link #AGENT_TASK_DONE}（data = { index, agent, task, costMs, status }），
     * 前端据此渲染可勾选的计划清单——用户在长任务里能随时看到"拆了几步、完成到哪"。
     */
    public static final String AGENT_PLAN = "agent.plan";
    public static final String AGENT_TASK_DONE = "agent.task.done";
    public static final String ARTIFACT = "artifact";
    public static final String MSG_END = "msg.end";
    public static final String ERROR = "error";
    public static final String PING = "ping";

    public static StreamEvent of(int index, String event, Map<String, Object> data) {
        return new StreamEvent(index, event, System.currentTimeMillis(), data);
    }

    public static StreamEvent token(int index, String content) {
        return of(index, TOKEN, Map.of("content", content == null ? "" : content));
    }

    public static StreamEvent reasoning(int index, String content) {
        return of(index, REASONING, Map.of("content", content == null ? "" : content));
    }

    public static StreamEvent error(int index, String code, String message) {
        return of(index, ERROR, Map.of("code", code, "message", message == null ? "" : message));
    }

    public static StreamEvent end(int index, Long costMs, String modelCode) {
        return of(index, MSG_END, Map.of(
                "costMs", costMs == null ? 0L : costMs,
                "model", modelCode == null ? "" : modelCode));
    }
}
