package com.aihub.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路追踪上下文测试（M5 · TraceId 全链路）。
 */
class TraceContextTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void newTraceIdIs32HexChars() {
        String traceId = TraceContext.newTraceId();

        assertEquals(32, traceId.length());
        assertTrue(traceId.matches("[0-9a-f]{32}"), "应为无连字符的 32 位小写十六进制");
    }

    @Test
    void newTraceIdIsUnique() {
        assertNotEquals(TraceContext.newTraceId(), TraceContext.newTraceId());
    }

    @Test
    void getReturnsNullWhenUnset() {
        assertNull(TraceContext.get());
        assertEquals("-", TraceContext.getOrDefault(), "日志占位符不应是 null");
    }

    @Test
    void setThenGet() {
        TraceContext.set("abc123");

        assertEquals("abc123", TraceContext.get());
        assertEquals("abc123", TraceContext.getOrDefault());
    }

    @Test
    void ensureGeneratesOnceAndStaysStable() {
        String first = TraceContext.ensure();
        String second = TraceContext.ensure();

        assertEquals(first, second, "已有链路 ID 时 ensure 不应重新生成");
        assertEquals(first, TraceContext.get());
    }

    @Test
    void blankSetClearsInsteadOfStoringBlank() {
        TraceContext.set("abc123");

        TraceContext.set("  ");

        assertNull(TraceContext.get(), "空白值应清除，避免日志出现空链路 ID");
    }

    /** 关键场景：异步线程必须能拿到父线程的链路 ID，否则日志会断链 */
    @Test
    void wrapPropagatesTraceIdToOtherThread() throws Exception {
        TraceContext.set("parent-trace");
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread worker = new Thread(TraceContext.wrap(() -> {
            seen.set(TraceContext.get());
            latch.countDown();
        }));
        worker.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals("parent-trace", seen.get(), "异步线程应继承链路 ID");
    }

    /**
     * 线程池复用场景：任务执行期间看到父线程的链路 ID，
     * 任务结束后线程状态回到执行前的样子，不把链路 ID 残留给下一个任务。
     */
    @Test
    void wrapUsesParentTraceIdDuringRun() throws Exception {
        TraceContext.set("parent-trace");           // 父线程（提交任务的请求线程）
        AtomicReference<String> duringRun = new AtomicReference<>();
        AtomicReference<String> afterRun = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // ★ wrap 必须在父线程调用：它在此刻捕获当前链路 ID
        Runnable task = TraceContext.wrap(() -> duringRun.set(TraceContext.get()));

        // 工作线程自身没有链路 ID（模拟线程池中新取出的线程）
        Thread worker = new Thread(() -> {
            task.run();
            afterRun.set(TraceContext.get());
            latch.countDown();
        });
        worker.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals("parent-trace", duringRun.get(), "执行期间应看到父线程的链路 ID");
        assertNull(afterRun.get(), "任务结束后工作线程不应残留链路 ID");
    }

    /** 工作线程原本已有链路 ID 时，任务结束后应还原为它自己的值 */
    @Test
    void wrapRestoresWorkerOwnTraceIdAfterRun() throws Exception {
        TraceContext.set("parent-trace");
        AtomicReference<String> duringRun = new AtomicReference<>();
        AtomicReference<String> afterRun = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task = TraceContext.wrap(() -> duringRun.set(TraceContext.get()));

        Thread worker = new Thread(() -> {
            TraceContext.set("worker-own");          // 工作线程原有状态
            task.run();
            afterRun.set(TraceContext.get());
            latch.countDown();
        });
        worker.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals("parent-trace", duringRun.get(), "执行期间用父线程的值");
        assertEquals("worker-own", afterRun.get(), "结束后还原为工作线程自己的值");
    }

    @Test
    void wrapInheritsNullWhenParentHasNoTraceId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("initial");
        CountDownLatch latch = new CountDownLatch(1);

        // 父线程此时没有链路 ID
        Runnable task = TraceContext.wrap(() -> {
            seen.set(TraceContext.get());
            latch.countDown();
        });

        Thread worker = new Thread(task);
        worker.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertNull(seen.get(), "父线程无链路 ID 时子线程也不应凭空生成");
    }

    @Test
    void clearRemovesTraceId() {
        TraceContext.set("abc123");

        TraceContext.clear();

        assertNull(TraceContext.get());
    }

    @Test
    void logPrefixFormatsWithPlaceholder() {
        assertEquals("[-] ", TraceContext.logPrefix());

        TraceContext.set("t-1");

        assertEquals("[t-1] ", TraceContext.logPrefix());
    }
}
