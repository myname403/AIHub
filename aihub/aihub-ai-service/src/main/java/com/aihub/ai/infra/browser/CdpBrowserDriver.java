package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.model.PageSnapshot;
import com.aihub.ai.domain.spi.BrowserDriver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 基于 CDP（Chrome DevTools Protocol）的浏览器驱动实现。
 *
 * <p>所有操作都归结为「往页面里 evaluate 一段 JS，把结果拿回来」，
 * 唯一的例外是点击与输入用 CDP 的输入事件派发——
 * 因为 {@code el.click()} 不会触发真实的 pointer 事件，
 * 一些依赖鼠标行为的站点（拖拽、hover 菜单）会点不动。
 *
 * <p><b>不提供「执行任意 JS」的口子</b>：模型拿到任意 JS 执行权，
 * 就能绕过标注直接操纵页面，这个面放开了就收不回来。
 */
@Slf4j
public class CdpBrowserDriver implements BrowserDriver {

    private static final int LOAD_POLL_INTERVAL_MS = 150;

    private final CdpTransport transport;
    private final PageAnnotator annotator;
    private final BrowserProperties properties;
    private final ObjectMapper mapper;
    private final String sessionId;

    public CdpBrowserDriver(CdpTransport transport, PageAnnotator annotator,
                            BrowserProperties properties, ObjectMapper mapper) {
        this.transport = transport;
        this.annotator = annotator;
        this.properties = properties;
        this.mapper = mapper;

        // 一个会话 = 一个独立标签页：建页 + 附着，之后所有命令都带这个 sessionId
        String targetId = transport.call("Target.createTarget",
                params().put("url", "about:blank"), null).path("targetId").asText();
        this.sessionId = transport.call("Target.attachToTarget",
                params().put("targetId", targetId).put("flatten", true), null)
                .path("sessionId").asText();
        if (sessionId == null || sessionId.isBlank()) {
            throw new BrowserException("附着浏览器标签页失败");
        }
        transport.call("Page.enable", null, sessionId);
        transport.call("Runtime.enable", null, sessionId);
    }

    // ---------------------------------------------------------------- 导航

    @Override
    public void open(String url) {
        ensureUrlAllowed(url);
        JsonNode result = transport.call("Page.navigate",
                params().put("url", url), sessionId);
        String errorText = result.path("errorText").asText("");
        if (!errorText.isBlank()) {
            throw new BrowserException("无法打开 " + url + "：" + errorText);
        }
        waitUntilLoaded();
    }

    /** 轮询 document.readyState 直到 complete 或超时 */
    private void waitUntilLoaded() {
        long deadline = System.currentTimeMillis() + properties.getNavigationTimeout().toMillis();
        while (System.currentTimeMillis() < deadline) {
            String readyState = evaluateToString("document.readyState");
            if ("complete".equals(readyState) || "interactive".equals(readyState)) {
                return;
            }
            try {
                Thread.sleep(LOAD_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrowserException("等待页面加载被中断");
            }
        }
        throw new BrowserException("页面加载超时（"
                + properties.getNavigationTimeout().toSeconds() + "s）");
    }

    // ---------------------------------------------------------------- 标注

    @Override
    public PageSnapshot snapshot() {
        return annotator.parse(evaluateJsonString(annotator.expression()));
    }

    // ---------------------------------------------------------------- 点击

    @Override
    public void click(String ref) {
        requireRef(ref);
        /*
         * 一步做完：按 ref 找元素 → 滚到可视区中央 → 返回中心坐标与元素描述。
         * 滚动和取坐标必须在同一段 JS 里，否则滚动后布局变了、坐标就错了。
         * 定位取「第一个可见匹配」：页面变化后同一 ref 可能同时挂在
         * 隐藏的旧元素与新的元素上（标注脚本会清旧 ref，这里再兜一层底）。
         */
        String script = """
                ((ref) => {
                  const all = document.querySelectorAll('[data-testid="' + ref + '"]');
                  let el = null;
                  for (const n of all) {
                    const r = n.getBoundingClientRect();
                    if (r.width >= 1 && r.height >= 1) { el = n; break; }
                  }
                  if (!el) return JSON.stringify({found: false});
                  el.scrollIntoView({block: 'center', inline: 'center'});
                  const r = el.getBoundingClientRect();
                  return JSON.stringify({
                    found: true,
                    tag: el.tagName.toLowerCase(),
                    role: el.getAttribute('role') || '',
                    text: (el.innerText || el.value || el.getAttribute('aria-label') || '')
                              .replace(/\\s+/g, ' ').trim().slice(0, 40),
                    x: Math.round(r.left + r.width / 2),
                    y: Math.round(r.top + r.height / 2)
                  });
                })(%s)
                """.formatted(jsonStringLiteral(ref));

        JsonNode info = parseEvaluate(script);
        if (!info.path("found").asBoolean(false)) {
            throw new BrowserException("元素 " + ref + " 不存在或已从页面消失（页面可能已刷新），请重新标注");
        }
        int x = info.path("x").asInt();
        int y = info.path("y").asInt();

        // 先确认命中点确实是这个元素（或它的子元素），防止被别的层挡住点空。
        // 不能放宽到「祖先包含也算」——那会把点击坐标在 body 上的空点误判成命中。
        String hit = evaluateToString(
                "(() => { const t = document.elementFromPoint(" + x + "," + y + ");"
                        + " if (!t) return '';"
                        + " const w = document.querySelector('[data-testid=\"" + ref + "\"]');"
                        + " return (w && (t === w || w.contains(t))) ? 'hit' : 'miss'; })()");
        if (!"hit".equals(hit)) {
            throw new BrowserException("元素 " + ref + " 被其它内容遮挡，无法点击");
        }

        dispatchMouse(x, y);
        log.debug("点击 [{}] {} '{}' @({},{})", ref, info.path("tag").asText(),
                info.path("text").asText(), x, y);
    }

    private void dispatchMouse(int x, int y) {
        ObjectNode pressed = params()
                .put("type", "mousePressed").put("x", x).put("y", y)
                .put("button", "left").put("clickCount", 1);
        ObjectNode released = params()
                .put("type", "mouseReleased").put("x", x).put("y", y)
                .put("button", "left").put("clickCount", 1);
        transport.call("Input.dispatchMouseEvent", pressed, sessionId);
        transport.call("Input.dispatchMouseEvent", released, sessionId);
    }

    // ---------------------------------------------------------------- 输入

    @Override
    public void type(String ref, String text) {
        requireRef(ref);
        if (text == null) {
            throw new BrowserException("输入内容不能为空");
        }
        /*
         * 输入分两步：
         *  1) JS 里聚焦并全选已有内容 —— 这样下一步的 insertText 会替换而不是追加；
         *  2) Input.insertText —— 它模拟真实输入法行为，会正确触发 input/change 事件，
         *     这是 React/Vue 受控组件能收到值的关键。直接 el.value=xxx 是不行的。
         */
        String script = """
                ((ref) => {
                  const all = document.querySelectorAll('[data-testid="' + ref + '"]');
                  let el = null;
                  for (const n of all) {
                    const r = n.getBoundingClientRect();
                    if (r.width >= 1 && r.height >= 1) { el = n; break; }
                  }
                  if (!el) return JSON.stringify({found: false});
                  el.scrollIntoView({block: 'center'});
                  el.focus();
                  const tag = el.tagName.toLowerCase();
                  const editable = tag === 'textarea' || tag === 'input' || el.isContentEditable;
                  if (editable && typeof el.setSelectionRange === 'function') {
                    el.setSelectionRange(0, el.value ? el.value.length : 0);
                  }
                  return JSON.stringify({found: true, editable: editable});
                })(%s)
                """.formatted(jsonStringLiteral(ref));

        JsonNode info = parseEvaluate(script);
        if (!info.path("found").asBoolean(false)) {
            throw new BrowserException("元素 " + ref + " 不存在或已从页面消失（页面可能已刷新），请重新标注");
        }
        if (!info.path("editable").asBoolean(false)) {
            throw new BrowserException("元素 " + ref + " 不是可输入的元素（不是输入框/文本域）");
        }
        transport.call("Input.insertText",
                params().put("text", text), sessionId);
        log.debug("向 [{}] 输入 {} 个字符", ref, text.length());
    }

    // ---------------------------------------------------------------- 其它

    @Override
    public byte[] screenshot() {
        String base64 = transport.call("Page.captureScreenshot",
                params().put("format", "png"), sessionId).path("data").asText("");
        return base64.isEmpty() ? null : Base64.getDecoder().decode(base64);
    }

    @Override
    public String currentUrl() {
        String url = evaluateToString("location.href");
        return (url == null || url.isBlank()) ? null : url;
    }

    @Override
    public boolean alive() {
        return !transport.isClosed();
    }

    /**
     * 关闭会话。优先走 CDP 的 {@code Browser.close}（优雅退出，Chrome 会自己清理子进程），
     * 失败再由上层兜底强杀进程树。
     */
    @Override
    public void close() {
        if (transport.isClosed()) {
            return;
        }
        try {
            transport.call("Browser.close", null, null);
        } catch (Exception e) {
            log.debug("Browser.close 失败（将由上层强杀进程）: {}", e.getMessage());
        }
        transport.close();
    }

    // ---------------------------------------------------------------- 基础设施

    /** evaluate 并把字符串结果原样取回（用于标注脚本这类返回 JSON 字符串的场景） */
    private String evaluateJsonString(String expression) {
        JsonNode response = transport.call("Runtime.evaluate",
                params().put("expression", expression).put("returnByValue", true), sessionId);
        JsonNode exception = response.path("exceptionDetails");
        if (!exception.isMissingNode()) {
            throw new BrowserException("页面脚本执行出错："
                    + exception.path("exception").path("description").asText("未知错误"));
        }
        JsonNode value = response.path("result").path("value");
        if (!value.isTextual()) {
            throw new BrowserException("页面脚本未返回文本结果（页面可能尚未加载完成）");
        }
        return value.asText();
    }

    /** evaluate 并把返回值当作 JSON 解析 */
    private JsonNode parseEvaluate(String expression) {
        try {
            return mapper.readTree(evaluateJsonString(expression));
        } catch (BrowserException e) {
            throw e;
        } catch (Exception e) {
            throw new BrowserException("解析页面脚本返回值失败：" + e.getMessage(), e);
        }
    }

    private String evaluateToString(String expression) {
        JsonNode response = transport.call("Runtime.evaluate",
                params().put("expression", expression).put("returnByValue", true), sessionId);
        JsonNode value = response.path("result").path("value");
        return value.isValueNode() ? value.asText() : "";
    }

    private ObjectNode params() {
        return mapper.createObjectNode();
    }

    /** 把 Java 字符串变成可直接拼进 JS 源码的字符串字面量（防注入：ref 会进表达式） */
    private static String jsonStringLiteral(String value) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\'' -> sb.append("\\'");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('\'').toString();
    }

    private static void requireRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new BrowserException("缺少元素引用 ref，请先对页面做标注");
        }
    }

    /**
     * 地址白名单校验。
     *
     * <p>只放行 http/https：{@code file://} 能读本地文件、{@code javascript:} 能执行脚本，
     * 这两个口子一开，模型的"浏览网页"就变成"操纵本机"了。
     */
    public static void ensureUrlAllowed(String url, List<String> prefixes) {
        if (url == null || url.isBlank()) {
            throw new BrowserException("URL 不能为空");
        }
        String scheme;
        try {
            scheme = URI.create(url.trim()).getScheme();
        } catch (Exception e) {
            throw new BrowserException("URL 格式非法：" + url);
        }
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BrowserException("只允许 http/https 地址，拒绝：" + url);
        }
        if (prefixes == null || prefixes.isEmpty()) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            String candidate = prefix.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(candidate)) {
                continue;
            }
            /*
             * 前缀相等，或紧随其后的字符是路径/查询/锚点/端口分隔符，才算真正命中。
             * 否则 https://example.com 会放行 https://example.com.evil.io ——
             * 朴素 startsWith 是能被前缀混淆攻击绕过的白名单，形同虚设。
             */
            if (lower.length() == candidate.length()) {
                return;
            }
            char next = lower.charAt(candidate.length());
            if (next == '/' || next == '?' || next == '#' || next == ':') {
                return;
            }
        }
        throw new BrowserException("该地址不在允许访问的范围内：" + url);
    }

    private void ensureUrlAllowed(String url) {
        ensureUrlAllowed(url, properties.getAllowedUrlPrefixes());
    }
}
