package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.model.PageSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CDP 驱动单测：用「假传输」替代真实 WebSocket——
 * 驱动的全部行为都归结为「调用方法 → 解析结果」，把传输换成假的即可覆盖
 * 导航 / 标注 / 点击 / 输入 / 关闭的完整协议交互，不必启动任何浏览器。
 */
class CdpBrowserDriverTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 假传输：按方法名分发到注册的处理器，并记录全部调用序列 */
    private static class FakeCdpTransport implements CdpTransport {
        final Map<String, Function<ObjectNode, JsonNode>> handlers = new HashMap<>();
        final List<String> methods = new java.util.ArrayList<>();
        boolean closed;

        @Override
        public JsonNode call(String method, ObjectNode params, String sessionId) {
            methods.add(method);
            Function<ObjectNode, JsonNode> handler = handlers.get(method);
            if (handler == null) {
                throw new BrowserException("fake transport 未注册方法：" + method);
            }
            return handler.apply(params);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static JsonNode json(String raw) {
        try {
            return M.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runtime.evaluate 的标准应答包装：{"result":{"value": ...}} */
    private static JsonNode textResult(String value) {
        ObjectNode node = M.createObjectNode();
        node.putObject("result").put("value", value);
        return node;
    }

    /** 构造已通过「建页 + 附着」阶段的驱动（处理器默认满足构造器协议） */
    private FakeCdpTransport newTransport() {
        FakeCdpTransport transport = new FakeCdpTransport();
        transport.handlers.put("Target.createTarget", p -> json("{\"targetId\":\"t-1\"}"));
        transport.handlers.put("Target.attachToTarget", p -> json("{\"sessionId\":\"s-1\"}"));
        transport.handlers.put("Page.enable", p -> json("{}"));
        transport.handlers.put("Runtime.enable", p -> json("{}"));
        transport.handlers.put("Page.navigate", p -> json("{}"));
        transport.handlers.put("Input.dispatchMouseEvent", p -> json("{}"));
        transport.handlers.put("Input.insertText", p -> json("{}"));
        return transport;
    }

    private CdpBrowserDriver newDriver(FakeCdpTransport transport) {
        PageAnnotator annotator = new PageAnnotator(new ObjectMapper(), 10);
        return new CdpBrowserDriver(transport, annotator, new BrowserProperties(), new ObjectMapper());
    }

    // ---------------------------------------------------------------- 导航

    @Test
    void openNavigatesAndWaitsUntilLoaded() {
        FakeCdpTransport transport = newTransport();
        AtomicReference<String> navigatedTo = new AtomicReference<>();
        transport.handlers.put("Page.navigate", p -> {
            navigatedTo.set(p.path("url").asText());
            return json("{}");
        });
        transport.handlers.put("Runtime.evaluate",
                p -> p.path("expression").asText().contains("document.readyState")
                        ? textResult("complete") : textResult(""));
        CdpBrowserDriver driver = newDriver(transport);

        driver.open("https://example.com/page");

        assertThat(navigatedTo.get()).isEqualTo("https://example.com/page");
    }

    @Test
    void openRejectsNonHttpSchemeBeforeAnyNavigation() {
        FakeCdpTransport transport = newTransport();
        CdpBrowserDriver driver = newDriver(transport);

        assertThatThrownBy(() -> driver.open("file:///C:/Windows/win.ini"))
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> driver.open("javascript:alert(1)"))
                .isInstanceOf(BrowserException.class);
        // 白名单未命中同样拒绝
        BrowserProperties props = new BrowserProperties();
        props.setAllowedUrlPrefixes(List.of("https://example.com"));
        CdpBrowserDriver restricted = new CdpBrowserDriver(transport,
                new PageAnnotator(new ObjectMapper(), 10), props, new ObjectMapper());
        assertThatThrownBy(() -> restricted.open("https://evil.com/path"))
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("允许访问的范围");
        assertThat(transport.methods).doesNotContain("Page.navigate");
    }

    @Test
    void openSurfacesNavigationErrorText() {
        FakeCdpTransport transport = newTransport();
        transport.handlers.put("Page.navigate",
                p -> json("{\"errorText\":\"ERR_NAME_NOT_RESOLVED\"}"));
        transport.handlers.put("Runtime.evaluate", p -> textResult("complete"));
        CdpBrowserDriver driver = newDriver(transport);

        assertThatThrownBy(() -> driver.open("https://no-such.invalid"))
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("ERR_NAME_NOT_RESOLVED");
    }

    // ---------------------------------------------------------------- 标注

    @Test
    void snapshotParsesAnnotatorPayload() {
        FakeCdpTransport transport = newTransport();
        String payload = """
                {"url":"https://example.com","title":"示例","totalFound":1,
                 "elements":[{"ref":"e1","role":"button","text":"OK","x":10,"y":20}]}
                """;
        transport.handlers.put("Runtime.evaluate",
                p -> p.path("expression").asText().contains("document.readyState")
                        ? textResult("complete") : textResult(payload));
        CdpBrowserDriver driver = newDriver(transport);
        driver.open("https://example.com");

        PageSnapshot snapshot = driver.snapshot();

        assertThat(snapshot.title()).isEqualTo("示例");
        assertThat(snapshot.elements()).hasSize(1);
        assertThat(snapshot.elements().get(0).ref()).isEqualTo("e1");
    }

    // ---------------------------------------------------------------- 点击

    @Test
    void clickScrollsVerifiesHitAndDispatchesRealMouseEvents() {
        FakeCdpTransport transport = newTransport();
        transport.handlers.put("Runtime.evaluate", p -> {
            String expr = p.path("expression").asText();
            if (expr.contains("elementFromPoint")) {
                return textResult("hit");
            }
            if (expr.contains("querySelector")) {
                return textResult("{\"found\":true,\"tag\":\"button\",\"text\":\"提交\",\"x\":150,\"y\":200}");
            }
            return textResult("complete");
        });
        CdpBrowserDriver driver = newDriver(transport);

        driver.click("e2");

        long mouseEvents = transport.methods.stream()
                .filter("Input.dispatchMouseEvent"::equals).count();
        assertThat(mouseEvents).isEqualTo(2);
    }

    @Test
    void clickFailsWhenElementMissing() {
        FakeCdpTransport transport = newTransport();
        transport.handlers.put("Runtime.evaluate", p -> {
            String expr = p.path("expression").asText();
            if (expr.contains("elementFromPoint")) {
                return textResult("hit");
            }
            if (expr.contains("querySelector")) {
                return textResult("{\"found\":false}");
            }
            return textResult("complete");
        });
        CdpBrowserDriver driver = newDriver(transport);

        assertThatThrownBy(() -> driver.click("e9"))
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("e9")
                .hasMessageContaining("重新标注");
    }

    @Test
    void clickFailsWhenElementCovered() {
        FakeCdpTransport transport = newTransport();
        transport.handlers.put("Runtime.evaluate", p -> {
            String expr = p.path("expression").asText();
            if (expr.contains("elementFromPoint")) {
                return textResult("miss");
            }
            if (expr.contains("querySelector")) {
                return textResult("{\"found\":true,\"tag\":\"button\",\"text\":\"x\",\"x\":1,\"y\":1}");
            }
            return textResult("complete");
        });
        CdpBrowserDriver driver = newDriver(transport);

        assertThatThrownBy(() -> driver.click("e1"))
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("遮挡");
        assertThat(transport.methods).doesNotContain("Input.dispatchMouseEvent");
    }

    // ---------------------------------------------------------------- 输入

    @Test
    void typeFocusesThenInsertsText() {
        FakeCdpTransport transport = newTransport();
        AtomicReference<ObjectNode> insertParams = new AtomicReference<>();
        transport.handlers.put("Input.insertText", p -> {
            insertParams.set(p);
            return json("{}");
        });
        transport.handlers.put("Runtime.evaluate", p -> {
            String expr = p.path("expression").asText();
            if (expr.contains("querySelector")) {
                return textResult("{\"found\":true,\"editable\":true}");
            }
            return textResult("complete");
        });
        CdpBrowserDriver driver = newDriver(transport);

        driver.type("e1", "你好 AIHub");

        assertThat(insertParams.get().path("text").asText()).isEqualTo("你好 AIHub");
    }

    @Test
    void typeRejectsNonEditableElement() {
        FakeCdpTransport transport = newTransport();
        transport.handlers.put("Runtime.evaluate", p -> {
            String expr = p.path("expression").asText();
            if (expr.contains("querySelector")) {
                return textResult("{\"found\":true,\"editable\":false}");
            }
            return textResult("complete");
        });
        CdpBrowserDriver driver = newDriver(transport);

        assertThatThrownBy(() -> driver.type("e1", "text"))
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("不是可输入的元素");
        assertThat(transport.methods).doesNotContain("Input.insertText");
    }

    // ---------------------------------------------------------------- URL 白名单

    @Test
    void ensureUrlAllowsHttpAndHttpsOnly() {
        CdpBrowserDriver.ensureUrlAllowed("https://example.com/a", List.of());
        CdpBrowserDriver.ensureUrlAllowed("HTTP://EXAMPLE.com", List.of());

        assertThatThrownBy(() -> CdpBrowserDriver.ensureUrlAllowed("file:///etc/passwd", List.of()))
                .isInstanceOf(BrowserException.class);
        assertThatThrownBy(() -> CdpBrowserDriver.ensureUrlAllowed("javascript:alert(1)", List.of()))
                .isInstanceOf(BrowserException.class);
        assertThatThrownBy(() -> CdpBrowserDriver.ensureUrlAllowed(null, List.of()))
                .isInstanceOf(BrowserException.class);
        assertThatThrownBy(() -> CdpBrowserDriver.ensureUrlAllowed("not a url", List.of()))
                .isInstanceOf(BrowserException.class);
    }

    @Test
    void ensureUrlEnforcesPrefixWhitelist() {
        List<String> prefixes = List.of("https://example.com");

        CdpBrowserDriver.ensureUrlAllowed("https://example.com/deep/page?q=1", prefixes);
        // 前缀相等（裸域）与同源带端口都算命中
        CdpBrowserDriver.ensureUrlAllowed("https://example.com", prefixes);
        CdpBrowserDriver.ensureUrlAllowed("https://example.com:8443/x", prefixes);

        // 前缀混淆攻击必须拒绝：example.com.evil.io 不是 example.com 的页面
        assertThatThrownBy(() -> CdpBrowserDriver.ensureUrlAllowed("https://example.com.evil.io", prefixes))
                .isInstanceOf(BrowserException.class);
        assertThatThrownBy(() -> CdpBrowserDriver.ensureUrlAllowed("https://example-company.com", prefixes))
                .isInstanceOf(BrowserException.class);
        assertThatThrownBy(() -> CdpBrowserDriver.ensureUrlAllowed("http://localhost:8080", prefixes))
                .isInstanceOf(BrowserException.class);
    }

    // ---------------------------------------------------------------- 关闭

    @Test
    void closeSendsBrowserCloseThenClosesTransport() {
        FakeCdpTransport transport = newTransport();
        transport.handlers.put("Browser.close", p -> json("{}"));
        CdpBrowserDriver driver = newDriver(transport);

        driver.close();

        assertThat(transport.methods).contains("Browser.close");
        assertThat(transport.closed).isTrue();
    }
}
