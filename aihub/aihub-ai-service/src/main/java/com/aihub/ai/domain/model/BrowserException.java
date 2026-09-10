package com.aihub.ai.domain.model;

/**
 * 浏览器操作失败（导航超时、元素不存在、浏览器进程已退出等）。
 *
 * <p>领域层异常，不携带任何框架类型：infra 的各种 CDP / 进程异常都要在这一层
 * 归一成它，上层（Agent / 工具）才好给出可读提示。
 */
public class BrowserException extends RuntimeException {

    public BrowserException(String message) {
        super(message);
    }

    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }
}
