package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话管理器单测：不真实启动浏览器，只覆盖参数校验与空状态行为。
 * （创建/回收路径依赖真实 Chrome，由端到端验证覆盖）
 */
class CdpBrowserSessionManagerTest {

    /** reaper 是随构造启动的守护线程，每个测试都必须在 finally 里停掉它 */
    private static void withManager(Consumer<CdpBrowserSessionManager> test) {
        CdpBrowserSessionManager manager =
                new CdpBrowserSessionManager(new BrowserProperties(), new ObjectMapper());
        try {
            test.accept(manager);
        } finally {
            manager.destroy();
        }
    }

    @FunctionalInterface
    private interface Consumer<T> {
        void accept(T value);
    }

    @Test
    void acquireRejectsBlankSessionKey() {
        withManager(manager -> {
            assertThatThrownBy(() -> manager.acquire("  "))
                    .isInstanceOf(BrowserException.class)
                    .hasMessageContaining("会话标识");
            assertThat(manager.activeSessions()).isZero();
        });
    }

    @Test
    void closeAllOnEmptyManagerIsSafe() {
        withManager(manager -> {
            manager.closeAll();
            assertThat(manager.activeSessions()).isZero();
        });
    }

    @Test
    void releaseUnknownKeyIsNoOp() {
        withManager(manager -> {
            manager.release("nobody");
            assertThat(manager.activeSessions()).isZero();
        });
    }
}
