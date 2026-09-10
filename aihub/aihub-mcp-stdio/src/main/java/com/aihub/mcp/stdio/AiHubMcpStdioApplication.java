package com.aihub.mcp.stdio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * MCP Server（stdio 传输）启动类。
 *
 * <p><b>为什么必须显式声明 {@code WebApplicationType.NONE}：</b>
 * stdio 传输靠 stdin/stdout 传 JSON-RPC，进程里不该有 HTTP 服务器。
 * 当前 classpath 上确实没有 servlet 容器（我们刻意不依赖 {@code aihub-common}），
 * 但显式声明能防止将来有人往依赖里加个 web 模块就把这个 CLI 工具变成半吊子 Web 应用——
 * 那时它会安静地启动一个 Tomcat 并等 HTTP 请求，而 stdin 那边永远没有响应。
 */
@SpringBootApplication(scanBasePackages = "com.aihub.mcp")
public class AiHubMcpStdioApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AiHubMcpStdioApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        // 不主动关闭：stdio 会话的生命周期由宿主进程（Claude Desktop / IDE 插件）决定，
        // 它关闭管道时 MCP 框架会自行结束。
        context.registerShutdownHook();
    }
}
