package com.aihub.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪 ID（TraceId）—— 一次请求在全系统的"身份证号"。
 *
 * <p><b>为什么需要它？</b>微服务下一个请求会经过：网关 → 平台/AI 服务 →（可能再调其他服务）。
 * 出了问题只知道"用户报错了"，要把 3 个服务的日志翻个遍才能拼出完整链路。
 * 有了 TraceId：网关生成 → 透传给下游 → 每个服务的每行日志都带上它，
 * 排障时 grep 一下 TraceId，整个请求的生命周期一目了然。
 *
 * <p><b>实现原理 —— ThreadLocal + MDC 双写：</b>
 * <ul>
 *   <li>ThreadLocal（HOLDER）：业务代码用 {@code TraceContext.get()} 取值、
 *       响应体 R.traceId 自动填充</li>
 *   <li>SLF4J MDC：日志框架的"上下文地图"，在 logback pattern 里配
 *       {@code %X{traceId}} 即可让每行日志自动输出 traceId，业务代码零改动</li>
 * </ul>
 *
 * <p><b>注意：</b>流式/异步场景下线程会切换，必须显式传递
 * （见 {@link #wrap}），否则工作线程里拿到的是空值。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
public final class TraceContext {

    /** 请求头名：网关/前端透传用。前端也可主动带自己的 traceId（如页面级关联） */
    public static final String HEADER = "X-Trace-Id";

    /** 日志 MDC 键名，与 logback 配置里的 %X{traceId} 对应 */
    public static final String MDC_KEY = "traceId";

    /** 无链路 ID 时的占位（避免日志里出现 null） */
    private static final String NONE = "-";

    /** 链路 ID 的线程级存储 */
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    /** 私有构造器：纯静态工具类，禁止实例化 */
    private TraceContext() {
    }

    /** 生成一个新的链路 ID（32 位无连字符十六进制）。UUID 本身 36 位带连字符，去掉连字符方便日志检索 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 当前链路 ID；不存在时返回 null（响应体填充用，null 会被 @JsonInclude 隐藏） */
    public static String get() {
        return HOLDER.get();
    }

    /** 当前链路 ID；不存在时返回占位符（日志用，保证日志格式整齐） */
    public static String getOrDefault() {
        String value = HOLDER.get();
        return value == null || value.isBlank() ? NONE : value;
    }

    /**
     * 设置链路 ID（空值将清除，避免脏数据跨请求残留）。
     * 注意 ThreadLocal 和 MDC 要同时写，两边的值才不会不一致。
     */
    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            HOLDER.remove();
            MDC.remove(MDC_KEY);
        } else {
            HOLDER.set(traceId);
            MDC.put(MDC_KEY, traceId);
        }
    }

    /** 若无链路 ID 则生成一个并返回当前值。适用于"我不确定上游有没有传"的中间层入口 */
    public static String ensure() {
        String current = HOLDER.get();
        if (current == null || current.isBlank()) {
            current = newTraceId();
            set(current);
        }
        return current;
    }

    /** 清理线程变量（请求结束时调用，防串台，理由同 TenantContext.clear） */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }

    /**
     * 包装任务，把当前链路 ID 带到另一个线程（线程池/异步任务用）。
     *
     * <p><b>用法：</b>{@code executor.submit(TraceContext.wrap(() -> doAsync()));}
     * ThreadLocal 不跨线程，直接 submit 的话异步线程里 traceId 是空的；
     * 本方法在提交任务那一刻"拍快照"记住当前 traceId，任务执行时先 set 进去、执行完还原，
     * 既保证了异步任务有链路 ID，又不污染线程池里复用的线程。
     */
    public static Runnable wrap(Runnable task) {
        // 提交任务时（主线程）读取当前 traceId，闭包捕获这个值
        String traceId = HOLDER.get();
        return () -> {
            // 记住执行线程原有的值（可能为 null），任务结束后还原
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

    /** 链路 ID 可直接用作日志前缀：log.info("{}处理完成", TraceContext.logPrefix()) */
    public static String logPrefix() {
        return "[" + getOrDefault() + "] ";
    }
}
