package com.aihub.ai.domain.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 三重预算（★ 防止死循环与成本失控，见 06 号文档 R6）。
 *
 * <ul>
 *   <li>最大子任务数：限制任务拆解规模</li>
 *   <li>最大 Token：限制总成本</li>
 *   <li>最大耗时：限制单任务墙钟时间</li>
 * </ul>
 *
 * <p>实例在整条子任务链上共享（AgentTask.subTask 传递同一引用），
 * 因此子 Agent 的消耗也会计入总预算。
 *
 * <p><b>AtomicLong 是什么（并发必读）：</b>多个子 Agent 可能在不同线程同时累加 token，
 * 普通 long 的 usedTokens += delta 不是原子操作（读-改-写三步会互相覆盖）。
 * AtomicLong 用 CPU 级 CAS 指令保证累加原子且无锁（比 synchronized 快）。
 * 详见学习文档《05-AI服务-aihub-ai-service.md》。
 */
public final class AgentBudget {

    private final int maxSubTasks;
    private final long maxTokens;
    private final long timeoutMs;
    private final long deadlineAt;
    private final AtomicLong usedTokens = new AtomicLong();

    /** @param maxTokens  <=0 表示不限制 */
    public AgentBudget(int maxSubTasks, long maxTokens, long timeoutMs) {
        this.maxSubTasks = maxSubTasks <= 0 ? Integer.MAX_VALUE : maxSubTasks;
        this.maxTokens = maxTokens <= 0 ? Long.MAX_VALUE : maxTokens;
        this.timeoutMs = timeoutMs <= 0 ? Long.MAX_VALUE : timeoutMs;
        this.deadlineAt = this.timeoutMs == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + this.timeoutMs;
    }

    public boolean timeout() {
        return System.currentTimeMillis() > deadlineAt;
    }

    public boolean tokenExceeded() {
        return usedTokens.get() > maxTokens;
    }

    /** 累加一次 LLM 调用的用量（prompt + completion） */
    public void addTokens(Integer tokenIn, Integer tokenOut) {
        long delta = (long) (tokenIn == null ? 0 : tokenIn) + (long) (tokenOut == null ? 0 : tokenOut);
        usedTokens.addAndGet(delta);
    }

    public long usedTokens() {
        return usedTokens.get();
    }

    public int maxSubTasks() {
        return maxSubTasks;
    }

    public long maxTokens() {
        return maxTokens;
    }

    public long timeoutMs() {
        return timeoutMs;
    }
}
