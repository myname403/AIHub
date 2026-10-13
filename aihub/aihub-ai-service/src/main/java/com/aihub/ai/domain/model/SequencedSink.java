package com.aihub.ai.domain.model;

import com.aihub.ai.domain.spi.StreamSink;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带全局事件序号的 StreamSink 装饰器（装饰器模式）。
 *
 * <p><b>它解决什么问题（本期真实 bug）：</b>一次 Agent 会话的事件由多个角色发出——
 * ChatAppService 发 msg.start / token / msg.end，PlanningAgent 发 agent.step / agent.plan，
 * 子 Agent 又各自发 agent.step / artifact。此前每个角色都维护自己的 {@code int[] index}
 * 从 0 计数，导致事件帧序号全局重复（规划层发 0..5，子 Agent 又从 0 开始），
 * {@link StreamEvent} 注释承诺的"前端用它排序/去重/判断丢帧"完全落空。
 *
 * <p><b>为什么用装饰器而不是改各处调用点：</b>一次会话只包装一层，
 * 所有调用方（编排层 / Agent / 工具回调）拿到的都是本类实例，
 * emit 时统一用 {@link AtomicInteger} 重编号——调用方继续传占位序号也无所谓，
 * index 以这里为准。改动收敛在一个类，不存在"漏改某个 emit 点"的可能。
 *
 * <p><b>AtomicInteger 而非 int[]：</b>工具事件可能来自不同线程
 * （chat/rag 流式链路的工具执行在 Reactor 调度线程上，见 {@link StreamBridge}），
 * volatile 语义的自增保证并发下序号不重复；Agent 同步链则天然串行。
 */
public final class SequencedSink implements StreamSink {

    private final StreamSink delegate;
    private final AtomicInteger counter = new AtomicInteger(0);

    public SequencedSink(StreamSink delegate) {
        this.delegate = delegate == null ? event -> {
        } : delegate;
    }

    /** 已是序号版则原样复用（避免双重包装），否则包装——调用方无需关心自己拿到的是哪种 */
    public static SequencedSink of(StreamSink sink) {
        return sink instanceof SequencedSink s ? s : new SequencedSink(sink);
    }

    /** 重编号 emit：忽略调用方给的 index，统一取全局自增序号 */
    @Override
    public void emit(StreamEvent event) {
        delegate.emit(new StreamEvent(counter.getAndIncrement(), event.event(), event.ts(), event.data()));
    }

    /** 便捷 emit：直接按类型 + 数据发帧（新代码推荐走这里，不再手工拼占位 index） */
    public void emit(String event, Map<String, Object> data) {
        delegate.emit(StreamEvent.of(counter.getAndIncrement(), event, data));
    }

    /** 当前已发帧数（日志与监控用） */
    public int emitted() {
        return counter.get();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
