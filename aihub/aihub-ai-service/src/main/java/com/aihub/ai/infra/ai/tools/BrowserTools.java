package com.aihub.ai.infra.ai.tools;

import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.spi.BrowserDriver;
import com.aihub.ai.domain.spi.BrowserSessionManager;
import com.aihub.ai.infra.ai.AiCallContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.function.Function;

/**
 * 浏览器操作工具（把「看页面 / 点 / 输入」暴露给对话模型自主调用）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>会话复用</b>：浏览器按 (租户, 会话) 复用，同一轮对话里多次调用落在同一个页面上，
 *       「点完按钮再看页面」这条链路才能成立。会话键取自 {@link AiCallContext}，
 *       工具方法签名里不出现任何会话参数（模型不该管这些）。</li>
 *   <li><b>只声明不关闭</b>：驱动由会话管理器统一持有与回收（空闲超时自动释放），
 *       工具绝不调用 {@code close()}——关掉的是整个 Chrome 进程，不是一次操作。</li>
 *   <li><b>点击/输入后自动重标</b>：页面状态变了，旧引用就作废了；
 *       把新清单随操作结果一起返回，省掉模型一次额外的 snapshot 往返。</li>
 *   <li><b>错误转文字</b>：工具异常直接返回给模型看（而不是抛出去中断对话），
 *       模型能据此调整（比如换个 ref 重试）。</li>
 * </ul>
 *
 * <p>URL 白名单等安全约束在驱动的 {@code open()} 内统一把关，工具层不重复实现。
 */
public class BrowserTools {

    private final BrowserSessionManager sessionManager;

    public BrowserTools(BrowserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Tool(description = "打开网页：让浏览器访问指定地址，返回页面上可交互元素的清单（编号如 [e1]）。仅支持 http/https")
    public String browser_open(
            @ToolParam(description = "要打开的网页地址，必须以 http:// 或 https:// 开头") String url) {
        return withDriver(driver -> {
            driver.open(url);
            return driver.snapshot().describe();
        });
    }

    @Tool(description = "重新观察当前页面：页面发生变化（跳转、点击、输入）后调用，返回最新的可交互元素清单")
    public String browser_snapshot() {
        return withDriver(driver -> driver.snapshot().describe());
    }

    @Tool(description = "点击页面元素：ref 必须来自最近一次页面观察清单中的编号（如 e3），点击后返回新页面的元素清单")
    public String browser_click(
            @ToolParam(description = "要点击的元素编号，如 e3") String ref) {
        return withDriver(driver -> {
            driver.click(ref);
            return "已点击 [" + ref + "]。\n" + driver.snapshot().describe();
        });
    }

    @Tool(description = "向输入框输入文本：会先清空输入框原有内容再输入，ref 必须来自最近一次页面观察清单")
    public String browser_type(
            @ToolParam(description = "输入框的元素编号，如 e1") String ref,
            @ToolParam(description = "要输入的文本内容") String text) {
        return withDriver(driver -> {
            driver.type(ref, text);
            return "已在 [" + ref + "] 输入 " + (text == null ? 0 : text.length()) + " 个字符。\n"
                    + driver.snapshot().describe();
        });
    }

    /** 统一的「取驱动 → 执行 → 异常转文字」骨架；绝不关闭驱动（生命周期归会话管理器） */
    private String withDriver(Function<BrowserDriver, String> action) {
        try {
            BrowserDriver driver = sessionManager.acquire(sessionKey());
            return action.apply(driver);
        } catch (BrowserException e) {
            return "浏览器操作失败：" + e.getMessage();
        } catch (Exception e) {
            return "浏览器操作出现异常：" + e.getMessage();
        }
    }

    /** 会话键 = 租户:会话。同一租户的同一对话共享一个浏览器，跨对话互不可见 */
    private String sessionKey() {
        Long tenantId = AiCallContext.tenantId();
        String conversationId = AiCallContext.conversationId();
        return (tenantId == null ? "anon" : tenantId) + ":"
                + (conversationId == null || conversationId.isBlank() ? "default" : conversationId);
    }
}
