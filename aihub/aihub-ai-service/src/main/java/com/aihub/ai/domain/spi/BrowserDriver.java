package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.PageSnapshot;

/**
 * 浏览器会话端口（★ 领域端口，实现在 infra）。
 *
 * <p>把「驱动一个浏览器」抽象成这 6 个动作，是为了让上层完全不认识 Playwright / CDP：
 * <ul>
 *   <li>换实现（Playwright → CDP → 别的）不用改 Agent 与工具；</li>
 *   <li>单测可以用假实现跑完 ReAct 全流程，不启动真实浏览器（快、稳定）。</li>
 * </ul>
 *
 * <p><b>设计取舍</b>：这里只暴露「开页 / 标注 / 点 / 输入 / 截图」这几个高层动作，
 * 不暴露「执行任意 JS」——否则模型可以绕过标注直接操纵页面，等于把不可控面放开。
 * 需要更复杂交互时，应该在这里新增一个语义明确的方法，而不是开一个 execJs 后门。
 */
public interface BrowserDriver extends AutoCloseable {

    /**
     * 打开 URL 并等待加载完成。
     *
     * @throws com.aihub.ai.domain.model.BrowserException 地址非法、超时或浏览器已退出
     */
    void open(String url);

    /** 对当前页面做标注，返回精简后的可交互元素清单 */
    PageSnapshot snapshot();

    /**
     * 点击指定元素。
     *
     * @param ref {@link PageSnapshot} 里给出的引用；元素已消失时抛异常
     */
    void click(String ref);

    /** 向指定元素输入文本（会先聚焦并清空原有内容） */
    void type(String ref, String text);

    /**
     * 截取当前视口，供「操作留痕」使用（可为 null，表示该实现不支持截图）。
     *
     * <p>不返回 base64 字符串而返回字节：编码方式交给调用方决定，
     * 领域层不该关心 png/base64 这些传输细节。
     */
    byte[] screenshot();

    /** 当前页地址（未打开任何页面时为 null） */
    String currentUrl();

    /** 会话是否仍然可用（浏览器进程是否还活着） */
    boolean alive();

    @Override
    void close();
}
