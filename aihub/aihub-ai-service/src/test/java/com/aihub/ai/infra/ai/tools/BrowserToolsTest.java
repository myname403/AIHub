package com.aihub.ai.infra.ai.tools;

import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.model.PageElement;
import com.aihub.ai.domain.model.PageSnapshot;
import com.aihub.ai.domain.spi.BrowserDriver;
import com.aihub.ai.domain.spi.BrowserSessionManager;
import com.aihub.ai.infra.ai.AiCallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浏览器工具单测：假会话管理器 + 假驱动，验证
 * 会话键来源（AiCallContext）、操作结果组装、错误转文字、绝不关闭驱动。
 */
class BrowserToolsTest {

    private static class FakeDriver implements BrowserDriver {
        final List<String> actions = new ArrayList<>();
        BrowserException failOn;
        PageSnapshot snapshot = new PageSnapshot("https://example.com", "示例页",
                List.of(new PageElement("e1", "textbox", "搜索", 10, 20),
                        new PageElement("e2", "button", "提交", 30, 20)),
                2);

        @Override
        public void open(String url) {
            if (failOn != null) {
                throw failOn;
            }
            actions.add("open:" + url);
        }

        @Override
        public PageSnapshot snapshot() {
            actions.add("snapshot");
            return snapshot;
        }

        @Override
        public void click(String ref) {
            if (failOn != null) {
                throw failOn;
            }
            actions.add("click:" + ref);
        }

        @Override
        public void type(String ref, String text) {
            actions.add("type:" + ref + ":" + text);
        }

        @Override
        public byte[] screenshot() {
            return new byte[0];
        }

        @Override
        public String currentUrl() {
            return snapshot.url();
        }

        @Override
        public boolean alive() {
            return true;
        }

        @Override
        public void close() {
            actions.add("close");
        }
    }

    private static class FakeSessions implements BrowserSessionManager {
        final FakeDriver driver = new FakeDriver();
        String lastKey;

        @Override
        public BrowserDriver acquire(String sessionKey) {
            lastKey = sessionKey;
            return driver;
        }

        @Override
        public void release(String sessionKey) {
        }

        @Override
        public void closeAll() {
        }

        @Override
        public int activeSessions() {
            return 1;
        }
    }

    private final FakeSessions sessions = new FakeSessions();
    private final BrowserTools tools = new BrowserTools(sessions);

    @AfterEach
    void cleanContext() {
        AiCallContext.clear();
    }

    @Test
    void openReturnsSnapshotAndUsesContextSessionKey() {
        AiCallContext.set(7L, 1L, "chat", "conv-1");

        String out = tools.browser_open("https://example.com");

        assertThat(sessions.lastKey).isEqualTo("7:conv-1");
        assertThat(sessions.driver.actions).containsExactly("open:https://example.com", "snapshot");
        assertThat(out).contains("示例页").contains("[e1]").contains("[e2]");
    }

    @Test
    void missingConversationFallsBackToDefault() {
        AiCallContext.set(7L, 1L, "chat", null);

        tools.browser_open("https://example.com");

        assertThat(sessions.lastKey).isEqualTo("7:default");
    }

    @Test
    void clickFollowedByFreshSnapshot() {
        AiCallContext.set(7L, 1L, "chat", "conv-1");

        String out = tools.browser_click("e2");

        assertThat(sessions.driver.actions).containsExactly("click:e2", "snapshot");
        assertThat(out).startsWith("已点击 [e2]").contains("[e1]");
    }

    @Test
    void typeReportsLengthAndSnapshot() {
        AiCallContext.set(7L, 1L, "chat", "conv-1");

        String out = tools.browser_type("e1", "AIHub 浏览器");

        assertThat(sessions.driver.actions).containsExactly("type:e1:AIHub 浏览器", "snapshot");
        // "AIHub 浏览器" = 5 + 1 + 3 = 9 个字符
        assertThat(out).contains("输入 9 个字符").contains("[e1]");
    }

    @Test
    void browserExceptionBecomesReadableText() {
        AiCallContext.set(7L, 1L, "chat", "conv-1");
        sessions.driver.failOn = new BrowserException("元素 e3 被其它内容遮挡，无法点击");

        String out = tools.browser_open("https://example.com");

        assertThat(out).contains("浏览器操作失败").contains("遮挡");
    }

    @Test
    void toolNeverClosesTheDriver() {
        AiCallContext.set(7L, 1L, "chat", "conv-1");

        tools.browser_open("https://example.com");
        tools.browser_click("e1");
        tools.browser_type("e1", "x");
        tools.browser_snapshot();

        // 关闭 = 杀掉整个 Chrome 进程，必须由会话管理器统一做
        assertThat(sessions.driver.actions).doesNotContain("close");
    }
}
