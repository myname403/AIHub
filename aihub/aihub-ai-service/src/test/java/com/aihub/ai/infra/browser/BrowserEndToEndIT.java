package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.PageElement;
import com.aihub.ai.domain.model.PageSnapshot;
import com.aihub.ai.domain.spi.BrowserDriver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实浏览器端到端验证（唯一能证明本特性可用的方式）：
 * 拉起本机 Chrome → 导航内嵌 HTTP 服务器上的测试页 → 页面标注 → 输入 → 点击 → 全链路校验。
 *
 * <p>校验思路：测试页的「确认」按钮只有在页面 JS 真实收到输入内容时才会显示
 * 「校验通过」——输入（insertText）与点击（真实鼠标事件）任何一环是假动作，
 * 最终断言都会失败。截图落盘 target/browser-e2e.png 留证。
 *
 * <p>类名以 IT 结尾，surefire 默认不执行；需要验证时显式运行：
 * <pre>mvnd -f aihub/pom.xml -pl aihub-ai-service -Dtest=BrowserEndToEndIT test</pre>
 * （无 Chrome 的环境必然失败，这是预期行为——本特性依赖本机浏览器。）
 */
class BrowserEndToEndIT {

    private static final String EXPECTED = "AIHub 探针 123";
    private static final String PAGE = """
            <!doctype html>
            <html lang="zh">
            <head><meta charset="utf-8"><title>AIHub 浏览器探针页</title></head>
            <body>
              <h1>浏览器控制探针</h1>
              <form onsubmit="return false;">
                <input id="q" placeholder="请输入内容">
                <button id="go">提交</button>
              </form>
              <div id="result" style="display:none">
                <span id="echoValue"></span>
                <button id="ack">确认</button>
              </div>
              <div style="display:none">隐藏内容不应被标注</div>
              <script>
                var expected = new URLSearchParams(location.search).get('expected') || '';
                document.getElementById('go').onclick = function () {
                  document.getElementById('echoValue').textContent =
                      document.getElementById('q').value;
                  document.getElementById('result').style.display = 'block';
                  document.getElementById('go').style.display = 'none';
                };
                document.getElementById('ack').onclick = function () {
                  var echo = document.getElementById('echoValue').textContent;
                  this.textContent = (echo === expected)
                      ? '校验通过：输入与点击全链路生效'
                      : '校验失败：echo=' + echo;
                };
              </script>
            </body>
            </html>
            """;

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void openAnnotateTypeClickOnRealChrome() throws Exception {
        BrowserProperties properties = new BrowserProperties();
        // 用户数据目录指到 target 下，测试结束由会话管理器清理，不污染临时目录
        properties.setUserDataDir(Path.of("target", "browser-e2e-userdata").toString());
        CdpBrowserSessionManager manager = new CdpBrowserSessionManager(properties, new ObjectMapper());
        try {
            BrowserDriver driver = manager.acquire("e2e:probe");

            String url = "http://127.0.0.1:" + port + "/probe?expected="
                    + URLEncoder.encode(EXPECTED, StandardCharsets.UTF_8);
            driver.open(url);

            // 1) 标注：可见可交互元素被列出，隐藏内容被排除
            PageSnapshot first = driver.snapshot();
            assertThat(first.title()).isEqualTo("AIHub 浏览器探针页");
            assertThat(first.elements()).extracting(PageElement::text)
                    .contains("请输入内容", "提交")
                    .doesNotContain("隐藏内容不应被标注", "确认");

            // 2) 输入 + 真实点击提交
            PageElement input = findByRole(first, "textbox");
            PageElement submit = findByRole(first, "button");
            driver.type(input.ref(), EXPECTED);
            driver.click(submit.ref());

            // 3) 页面状态已变：提交按钮隐藏，确认按钮出现（证明点击触发了页面 JS）
            PageSnapshot second = driver.snapshot();
            PageElement ack = findByRole(second, "button");
            assertThat(ack.text()).isEqualTo("确认");

            // 4) 点击确认：只有输入内容真实送达页面 JS，才会显示「校验通过」。
            //    断言带上完整快照描述，失败时能直接看出页面当时长什么样。
            driver.click(ack.ref());
            PageSnapshot third = driver.snapshot();

            // 5) 截图留证（先落盘再断言，失败时也有现场可查）
            byte[] png = driver.screenshot();
            assertThat(png).isNotNull().isNotEmpty();
            Files.write(Path.of("target", "browser-e2e.png"), png);

            assertThat(third.describe() + "\n标题=" + third.title())
                    .contains("校验通过");
        } finally {
            manager.destroy();
        }
    }

    private static PageElement findByRole(PageSnapshot snapshot, String role) {
        Optional<PageElement> found = snapshot.elements().stream()
                .filter(element -> role.equals(element.role()))
                .findFirst();
        assertThat(found)
                .as("找不到 %s 元素，快照：%n%s", role, snapshot.describe())
                .isPresent();
        return found.get();
    }
}
