package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.model.PageElement;
import com.aihub.ai.domain.model.PageSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 页面标注：把「注入脚本」与「解析结果」这两件事封装起来。
 *
 * <p>脚本本身放在 {@code resources/browser/annotate-dom.js}（而不是写成 Java 字符串），
 * 好处是可以直接在浏览器 Console 里粘贴调试——课程里讲的手动验证方式能原样复用。
 *
 * <p>解析逻辑与浏览器无关，所以能脱离真实浏览器单测（见 PageAnnotatorTest）。
 */
public class PageAnnotator {

    private static final String SCRIPT_RESOURCE = "browser/annotate-dom.js";

    private final String script;
    private final ObjectMapper mapper;
    private final int maxElements;

    public PageAnnotator(ObjectMapper mapper, int maxElements) {
        this.mapper = mapper;
        this.maxElements = maxElements;
        this.script = loadScript();
    }

    private static String loadScript() {
        try (InputStream in = PageAnnotator.class.getClassLoader()
                .getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new BrowserException("缺少页面标注脚本资源：" + SCRIPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BrowserException("读取页面标注脚本失败", e);
        }
    }

    /**
     * 可直接交给 {@code Runtime.evaluate} 的表达式。
     *
     * <p>脚本文件是一段箭头函数，这里把上限作为实参传进去——
     * 这样元素上限是配置项而不是写死在脚本里。
     */
    public String expression() {
        return "(" + script + ")(" + maxElements + ")";
    }

    /** 解析脚本返回的 JSON 字符串 */
    public PageSnapshot parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BrowserException("页面标注未返回内容");
        }
        try {
            JsonNode root = mapper.readTree(json);
            List<PageElement> elements = new ArrayList<>();
            for (JsonNode node : root.path("elements")) {
                elements.add(new PageElement(
                        node.path("ref").asText(),
                        node.path("role").asText("element"),
                        node.path("text").asText(""),
                        node.path("x").asInt(),
                        node.path("y").asInt()));
            }
            int totalFound = root.path("totalFound").asInt(elements.size());
            return new PageSnapshot(
                    root.path("url").asText(""),
                    root.path("title").asText(""),
                    elements,
                    totalFound);
        } catch (Exception e) {
            throw new BrowserException("页面标注结果解析失败：" + abbreviate(json), e);
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
