package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动本机已安装的 Chrome / Edge 并开启远程调试端口。
 *
 * <p><b>不下载任何浏览器内核</b>：探测本机已有浏览器复用，这与 Playwright/Selenium
 * 需要额外拉几十上百 MB 内核的做法不同。
 *
 * <p>两个关键实现细节：
 * <ol>
 *   <li><b>端口用 0 让系统分配</b>，然后从 stderr 里读回真实地址
 *       （Chrome 会打印 {@code DevTools listening on ws://...}）。
 *       写死端口在多人/多实例环境下必然撞车，而读回地址是天然无冲突的。</li>
 *   <li><b>必须持续读走 stderr/stdout</b>：这两个管道的缓冲区一旦被写满，
 *       Chrome 会阻塞在那里，表现为"浏览器莫名其妙卡住不响应"。
 *       所以启动后就开守护线程一直排空。</li>
 * </ol>
 */
@Slf4j
public class ChromeLauncher {

    private static final Pattern DEVTOOLS_URL =
            Pattern.compile("ws://127\\.0\\.0\\.1:\\d+/devtools/browser/[0-9a-fA-F-]+");

    private final BrowserProperties properties;

    public ChromeLauncher(BrowserProperties properties) {
        this.properties = properties;
    }

    /** 一次已启动的浏览器实例 */
    public record LaunchedChrome(Process process, String webSocketUrl, Path userDataDir) {
    }

    public LaunchedChrome launch() {
        Path executable = locate();
        Path userDataDir = createUserDataDir();
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + userDataDir);
        if (properties.isHeadless()) {
            // new headless 才是现代无头实现（旧 headless 行为差异大）
            command.add("--headless=new");
        }
        command.add("--disable-gpu");
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-extensions");
        command.add("--disable-background-networking");
        // 关掉"Chrome 正受到自动测试软件控制"提示条，否则会占掉页面顶部一块区域
        command.add("--disable-infobars");
        command.add("--window-size=" + properties.getWindowWidth()
                + "," + properties.getWindowHeight());
        command.add("about:blank");

        log.info("启动浏览器：{}（headless={}）", executable, properties.isHeadless());
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new BrowserException("无法启动浏览器：" + executable, e);
        }

        String wsUrl = waitForDevToolsUrl(process);
        return new LaunchedChrome(process, wsUrl, userDataDir);
    }

    /**
     * 定位浏览器：显式配置 → Windows 常见安装位置（Chrome 优先，其次 Edge）→ PATH。
     */
    Path locate() {
        if (properties.getExecutable() != null && !properties.getExecutable().isBlank()) {
            Path configured = Path.of(properties.getExecutable());
            if (!Files.isExecutable(configured)) {
                throw new BrowserException("配置的浏览器路径不可执行：" + configured);
            }
            return configured;
        }
        List<String> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LOCALAPPDATA");
            for (String base : List.of(pf, pf86)) {
                if (base != null) {
                    candidates.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
                    candidates.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
                }
            }
            if (local != null) {
                candidates.add(local + "\\Google\\Chrome\\Application\\chrome.exe");
            }
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            candidates.add("/usr/bin/google-chrome");
            candidates.add("/usr/bin/chromium");
            candidates.add("/usr/bin/chromium-browser");
            candidates.add("/usr/bin/microsoft-edge");
        }
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return path;
            }
        }
        throw new BrowserException(
                "未找到可用的 Chrome / Edge。请安装浏览器，或显式配置 aihub.browser.executable");
    }

    private Path createUserDataDir() {
        String base = properties.getUserDataDir();
        Path parent = (base == null || base.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "aihub-browser")
                : Path.of(base);
        /*
         * 必须转绝对路径：Chrome 收到相对 --user-data-dir 时会当无效处理，
         * 回落到默认配置目录，然后拒绝开调试端口
         * （报 "DevTools remote debugging requires a non-default data directory"）。
         */
        Path dir = parent.resolve("session-" + UUID.randomUUID()).toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BrowserException("无法创建浏览器用户数据目录：" + dir, e);
        }
        return dir;
    }

    /** 从 stderr 里等出 DevTools 地址；同时开守护线程持续排空两个输出管道 */
    private String waitForDevToolsUrl(Process process) {
        List<String> recent = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread stderrReader = new Thread(
                () -> drain(process.getErrorStream(), recent, true), "aihub-chrome-stderr");
        Thread stdoutReader = new Thread(
                () -> drain(process.getInputStream(), recent, false), "aihub-chrome-stdout");
        stderrReader.setDaemon(true);
        stdoutReader.setDaemon(true);
        stderrReader.start();
        stdoutReader.start();

        long deadline = System.currentTimeMillis()
                + properties.getNavigationTimeout().toMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (recent) {
                for (String line : recent) {
                    Matcher matcher = DEVTOOLS_URL.matcher(line);
                    if (matcher.find()) {
                        return matcher.group();
                    }
                }
            }
            if (!process.isAlive()) {
                throw new BrowserException("浏览器进程提前退出（退出码 "
                        + process.exitValue() + "），最近输出：" + tail(recent));
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrowserException("等待浏览器启动被中断", e);
            }
        }
        destroyTree(process);
        throw new BrowserException("等待浏览器调试端口超时，最近输出：" + tail(recent));
    }

    private void drain(java.io.InputStream stream, List<String> sink, boolean collect) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (collect && !line.isBlank()) {
                    synchronized (sink) {
                        sink.add(line);
                        // 只留最近若干行，避免长期运行时无限增长
                        if (sink.size() > 40) {
                            sink.remove(0);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程结束时管道关闭是正常现象
        }
    }

    private static String tail(List<String> lines) {
        synchronized (lines) {
            int from = Math.max(0, lines.size() - 3);
            return String.join(" | ", lines.subList(from, lines.size()));
        }
    }

    /**
     * 结束浏览器进程树并清理用户数据目录。
     *
     * <p>Windows 上 Chrome 会派生一堆子进程，只 destroy 主进程容易留下孤儿进程占内存，
     * 所以用 {@code taskkill /T} 整棵树一起收；其它平台直接 destroy。
     * 目录删除是尽力而为——Chrome 有时还握着文件锁，删不掉就留给临时目录清理。
     */
    void destroyTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            if (process.isAlive()) {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID",
                            String.valueOf(process.pid()))
                            .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
                } else {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            }
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("结束浏览器进程失败: {}", e.getMessage());
        }
    }

    void deleteUserDataDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("清理浏览器数据文件失败（可忽略）: {}", path);
                }
            });
        } catch (IOException e) {
            log.debug("清理浏览器用户数据目录失败: {}", dir);
        }
    }
}
