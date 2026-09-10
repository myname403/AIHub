package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.spi.BrowserDriver;
import com.aihub.ai.domain.spi.BrowserSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 浏览器会话管理器：按会话复用浏览器进程，空闲超时回收。
 *
 * <p>为什么要复用：一个 Chrome 进程几百 MB，如果每次「点击」都新起一个，
 * 一轮 ReAct 下来机器就垮了。而且连续操作必须落在同一个页面上下文里，
 * 否则「点完按钮页面变了」这个最基本的观察链路就断了。
 *
 * <p>空闲回收用本类自带的守护线程，而不是全局 {@code @EnableScheduling}：
 * 本能力默认关闭，不该为它把整个应用的定时调度打开。
 *
 * <p>注册入口在 {@link BrowserConfiguration}，由 {@code aihub.browser.enabled} 统一开关。
 */
@Slf4j
public class CdpBrowserSessionManager implements BrowserSessionManager, DisposableBean {

    private final BrowserProperties properties;
    private final ChromeLauncher launcher;
    private final ObjectMapper mapper;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** 创建路径串行化：浏览器创建很贵，用锁换正确性，不值得在这里抠并发 */
    private final ReentrantLock createLock = new ReentrantLock();
    private final ScheduledExecutorService reaper;

    /** 不能用 record：lastUsedAt 需要就地可变（record 不允许实例字段），且组件个数固定 */
    private static final class Session {
        final BrowserDriver driver;
        final ChromeLauncher.LaunchedChrome chrome;
        volatile long lastUsedAt;

        Session(BrowserDriver driver, ChromeLauncher.LaunchedChrome chrome) {
            this.driver = driver;
            this.chrome = chrome;
            this.lastUsedAt = System.currentTimeMillis();
        }
    }

    public CdpBrowserSessionManager(BrowserProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.launcher = new ChromeLauncher(properties);
        this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aihub-browser-reaper");
            thread.setDaemon(true);
            return thread;
        });
        this.reaper.scheduleWithFixedDelay(this::reapIdleSessions, 30, 30, TimeUnit.SECONDS);
        log.info("浏览器能力已启用（headless={}，会话上限={}）",
                properties.isHeadless(), properties.getMaxSessions());
    }

    @Override
    public BrowserDriver acquire(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new BrowserException("浏览器会话标识不能为空");
        }
        Session usable = usableSession(sessionKey);
        if (usable != null) {
            return usable.driver;
        }
        createLock.lock();
        try {
            // 双重检查：并发请求同一会话时只创建一次
            usable = usableSession(sessionKey);
            if (usable != null) {
                return usable.driver;
            }
            // 没拿到可用会话但表里有记录，说明它已死（进程被杀/连接断开），先清再建
            Session dead = sessions.remove(sessionKey);
            if (dead != null) {
                log.warn("浏览器会话已失效，重建：{}", sessionKey);
                destroyQuietly(dead, sessionKey);
            }
            if (sessions.size() >= properties.getMaxSessions()) {
                throw new BrowserException("浏览器会话数已达上限（" + properties.getMaxSessions()
                        + "），请稍后再试");
            }
            Session created = create(sessionKey);
            sessions.put(sessionKey, created);
            return created.driver;
        } finally {
            createLock.unlock();
        }
    }

    /** 存活检查：浏览器进程被外部杀掉或连接断开时视同不存在（命中时顺便刷新最近使用时间） */
    private Session usableSession(String sessionKey) {
        Session session = sessions.get(sessionKey);
        if (session == null) {
            return null;
        }
        if (session.driver.alive()) {
            session.lastUsedAt = System.currentTimeMillis();
            return session;
        }
        return null;
    }

    private Session create(String sessionKey) {
        ChromeLauncher.LaunchedChrome chrome = launcher.launch();
        try {
            CdpTransport transport = new WebSocketCdpTransport(
                    chrome.webSocketUrl(), mapper, properties.getCommandTimeout());
            PageAnnotator annotator = new PageAnnotator(mapper, properties.getMaxElements());
            BrowserDriver driver = new CdpBrowserDriver(transport, annotator, properties, mapper);
            Session session = new Session(driver, chrome);
            log.info("浏览器会话已创建：{}（chrome pid={}）", sessionKey, chrome.process().pid());
            return session;
        } catch (RuntimeException e) {
            // 建到一半失败必须把进程收掉，否则留下一个没人管的 Chrome
            launcher.destroyTree(chrome.process());
            launcher.deleteUserDataDir(chrome.userDataDir());
            throw e;
        }
    }

    @Override
    public void release(String sessionKey) {
        Session session = sessions.remove(sessionKey);
        if (session != null) {
            destroyQuietly(session, sessionKey);
        }
    }

    /** 空闲回收：用户不再追问时把浏览器还回去 */
    void reapIdleSessions() {
        if (sessions.isEmpty()) {
            return;
        }
        long idleLimit = properties.getIdleTimeout().toMillis();
        long now = System.currentTimeMillis();
        sessions.forEach((key, session) -> {
            if (now - session.lastUsedAt > idleLimit) {
                if (sessions.remove(key, session)) {
                    log.info("回收空闲浏览器会话：{}", key);
                    destroyQuietly(session, key);
                }
            }
        });
    }

    @Override
    public int activeSessions() {
        return sessions.size();
    }

    @Override
    public void destroy() {
        closeAll();
        reaper.shutdownNow();
    }

    @PreDestroy
    public void onShutdown() {
        destroy();
    }

    @Override
    public void closeAll() {
        sessions.forEach((key, session) -> destroyQuietly(session, key));
        sessions.clear();
    }

    private void destroyQuietly(Session session, String sessionKey) {
        try {
            session.driver.close();
        } catch (Exception e) {
            log.debug("关闭浏览器会话 {} 时 CDP 关闭失败: {}", sessionKey, e.getMessage());
        }
        // CDP 优雅退出失败时兜底：整棵进程树一起收，避免留下孤儿 Chrome 吃内存
        launcher.destroyTree(session.chrome.process());
        launcher.deleteUserDataDir(session.chrome.userDataDir());
        log.info("浏览器会话已释放：{}", sessionKey);
    }
}
