package com.aihub.ai.domain.spi;

/**
 * 浏览器会话管理端口（★ 领域端口，实现在 infra）。
 *
 * <p>浏览器是<b>重资源</b>（一个 Chrome 进程几百 MB），所以必须复用而不是每次操作都新起：
 * 同一个会话里的连续操作（打开 → 标注 → 点击 → 再标注）要落在同一个页面上下文中，
 * 否则「点击后页面变了」这个最基本的观察就没了。
 *
 * <p>复用的粒度按 {@code sessionKey}（实际传入 conversationId）划分：
 * 多轮追问能接着上一次的页面继续操作，不同会话之间则互不干扰。
 */
public interface BrowserSessionManager {

    /**
     * 取得（或惰性创建）该会话的浏览器。
     *
     * <p>实现需保证并发安全：同一会话的并发请求不应创建出多个浏览器。
     */
    BrowserDriver acquire(String sessionKey);

    /** 主动释放某个会话的浏览器（进程随即关闭，release 后磁盘上不留用户数据目录） */
    void release(String sessionKey);

    /** 关闭全部会话（服务停机时调用，避免留下孤儿 Chrome 进程） */
    void closeAll();

    /** 当前活跃会话数，供指标与运维观察 */
    int activeSessions();
}
