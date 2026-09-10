package com.aihub.ai.domain.model;

import java.util.List;

/**
 * 一次页面标注的结果快照。
 *
 * <p>刻意做成「标注后的精简视图」而不是原始 HTML：token 成本可控，
 * 且模型看到的就是它能操作的东西，不会去臆想不存在的元素。
 *
 * @param url     当前页地址
 * @param title   页面标题
 * @param elements 当前视口内可交互的元素（已过滤不可见元素）
 * @param totalFound 标注实际找到的元素总数；大于 {@code elements.size()} 说明被上限截断了
 */
public record PageSnapshot(
        String url,
        String title,
        List<PageElement> elements,
        int totalFound
) {

    public PageSnapshot {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /** 是否因为元素上限而只返回了前一部分 */
    public boolean truncated() {
        return totalFound > elements.size();
    }

    /**
     * 渲染成给模型看的紧凑文本。
     *
     * <p>放在领域层（而不是 infra）是因为这只涉及字符串拼接，没有任何框架依赖；
     * 放这里也能被单测直接覆盖，不必启动浏览器。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前页面：").append(title == null || title.isBlank() ? "(无标题)" : title)
                .append('\n').append("地址：").append(url == null ? "" : url).append('\n');
        if (elements.isEmpty()) {
            sb.append("可交互元素：0 个。")
                    .append("若这不是预期结果，页面可能尚未加载完、需要滚动，或内容在 iframe 内。");
            return sb.toString();
        }
        sb.append("可交互元素（共 ").append(elements.size()).append(" 个）：\n");
        for (PageElement e : elements) {
            sb.append('[').append(e.ref()).append("] ")
                    .append(e.role());
            if (e.text() != null && !e.text().isBlank()) {
                sb.append(' ').append(oneLine(e.text()));
            }
            sb.append('\n');
        }
        if (truncated()) {
            sb.append("（页面共有 ").append(totalFound)
                    .append(" 个可交互元素，以上为前 ").append(elements.size())
                    .append(" 个。目标元素不在其中时可先滚动页面或缩小范围。）\n");
        }
        return sb.toString();
    }

    /** 元素文本内可能有多行/多余空白，压成一行免得把清单撑散 */
    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 60 ? flat.substring(0, 60) + "…" : flat;
    }
}
