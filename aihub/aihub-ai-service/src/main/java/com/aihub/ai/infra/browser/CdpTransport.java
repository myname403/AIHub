package com.aihub.ai.infra.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * CDP 传输通道（把 JSON-RPC 的收发抽象出来，方便给驱动做假实现单测）。
 *
 * <p>这条接口存在的唯一理由就是**可测**：{@link CdpBrowserDriver} 的逻辑
 * （导航等待、标注解析、按 ref 定位）都能靠一个假 transport 覆盖，
 * 不必真的拉起浏览器。
 */
public interface CdpTransport extends AutoCloseable {

    /**
     * 发送一条 CDP 命令并等待响应。
     *
     * @param params    命令参数，可为 null
     * @param sessionId 目标标签页会话；为 null 表示命令发给浏览器本体
     * @return 响应里的 {@code result} 节点（不含 result 时返回空节点）
     */
    JsonNode call(String method, ObjectNode params, String sessionId);

    boolean isClosed();

    @Override
    void close();
}
