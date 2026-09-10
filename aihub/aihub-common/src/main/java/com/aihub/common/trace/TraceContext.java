package com.aihub.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪 ID（全链路唯一标识）。
 *
 * <p>用 ThreadLocal 承载：入口生成（或从请求头继承），出口回写响应头，
 * 日志与响应体同时携带，便于一次请求跨服务串联排查。
 *
 * <p>同步写入 SLF4J MDC，因此日志 pattern 里加 {@code %X{traceId}} 即可自动输出，
 * 业务代码无需手工拼接。
 *
 * <p><b>注意</b>：流式/异步场景下线程会切换，必须显式传递
 * （见 {@link #wrap}），否则工作线程里拿到的是空值。
 */
public final class TraceContext {

    /** 请求头名：网关/前端透传用 */
    public static final String HEADER = "X-Trace-Id";

    /** 日志 MDC 键名 */
    public static final String MDC_KEY = "traceId";

    /** 无链路 ID 时的占位（避免日志里出现 null） */
    private static final String NONE = "-";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 生成一个新的链路 ID（32 位无连字符十六进制） */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 当前链路 ID；不存在时返回 null */
    public static String get() {
        return HOLDER.get();
    }

    /** 当前链路 ID；不存在时返回占位符（日志用） */
    public static String getOrDefault() {
        String value = HOLDER.get();
        return value == null || value.isBlank() ? NONE : value;
    }

    /** 设置链路 ID（空值将清除，避免脏数据跨请求残留） */
    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            HOLDER.remove();
            MDC.remove(MDC_KEY);
        } else {
            HOLDER.set(traceId);
            MDC.put(MDC_KEY, traceId);
        }
    }

    /** 若无链路 ID 则生成一个并返回当前值 */
    public static String ensure() {
        String current = HOLDER.get();
        if (current == null || current.isBlank()) {
            current = newTraceId();
            set(current);
        }
        return current;
    }

    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }

    /**
     * 包装任务，把当前链路 ID 带到另一个线程（线程池/异步任务用）。
     */
    public static Runnable wrap(Runnable task) {
        String traceId = HOLDER.get();
        return () -> {
            String previous = HOLDER.get();
            set(traceId);
            try {
                task.run();
            } finally {
                // 还原调用前状态，避免污染复用的工作线程
                set(previous);
            }
        };
    }

    /** 链路 ID 可直接用作日志前缀 */
    public static String logPrefix() {
        return "[" + getOrDefault() + "] ";
    }
}
