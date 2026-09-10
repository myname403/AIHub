package com.aihub.ai.infra.browser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 浏览器能力配置（前缀 {@code aihub.browser}）。
 *
 * <p><b>为什么默认关闭</b>：这个能力会真的在本机拉起一个 Chrome 进程，并且让模型
 * 去访问任意 URL —— 这既是资源开销，也是一个典型的 SSRF 面（模型可以被诱导去访问内网地址）。
 * 所以它必须显式开启，而不是"默认能用"。
 */
@Data
@ConfigurationProperties(prefix = "aihub.browser")
public class BrowserProperties {

    /** 总开关，默认关闭。开启后 BrowserAgent 与 BrowserTools 才会注册。 */
    private boolean enabled = false;

    /**
     * 浏览器可执行文件路径。留空时按「配置路径 → Chrome → Edge → PATH」顺序自动探测，
     * 复用本机已装浏览器（不下载任何浏览器内核）。
     */
    private String executable;

    /**
     * 无头模式。默认 true（无界面，适合服务器）。
     * 本机调试时设为 false 可以亲眼看到模型在点什么——排错时非常有用。
     */
    private boolean headless = true;

    /** 用户数据目录的父目录；留空则用 {@code ${java.io.tmpdir}/aihub-browser} */
    private String userDataDir;

    /** 单次标注最多返回多少个元素。上限的作用是给 token 成本封顶。 */
    private int maxElements = 120;

    /** 单条 CDP 命令的超时 */
    private Duration commandTimeout = Duration.ofSeconds(30);

    /** 页面导航后的加载等待上限 */
    private Duration navigationTimeout = Duration.ofSeconds(20);

    /**
     * 会话空闲多久后自动回收。
     * 浏览器进程很重，用户不再追问时必须还回去，否则会一直占着内存直到服务重启。
     */
    private Duration idleTimeout = Duration.ofMinutes(5);

    /** 同时允许存在的浏览器会话数上限，防止把机器拖垮 */
    private int maxSessions = 3;

    private int windowWidth = 1280;

    private int windowHeight = 900;

    /** 允许访问的地址前缀白名单，留空表示不限制。生产环境强烈建议配置。 */
    private java.util.List<String> allowedUrlPrefixes = new java.util.ArrayList<>();
}
