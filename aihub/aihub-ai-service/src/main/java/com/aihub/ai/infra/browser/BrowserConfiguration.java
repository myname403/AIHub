package com.aihub.ai.infra.browser;

import com.aihub.ai.infra.ai.agent.BrowserAgent;
import com.aihub.ai.infra.ai.tools.BrowserTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 浏览器能力的统一注册入口。
 *
 * <p>所有浏览器相关 bean（会话管理器 / 工具 / Agent）都从这里发出，
 * 由 {@code aihub.browser.enabled} 一个开关统一控制——能力关闭时，
 * 这些 bean 不存在，ChatClientFactory 挂载工具时自然跳过，
 * AgentRegistry 里也不会出现 browser Agent。
 *
 * <p>为什么不用 {@code @Component} + 条件注解散落在各类上：
 * 开关分散是配置漂移的温床（关掉一个漏掉另一个），收敛到一处后
 * 「浏览器能力开与关」只看这一个类。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aihub.browser", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BrowserProperties.class)
public class BrowserConfiguration {

    @Bean
    public CdpBrowserSessionManager browserSessionManager(BrowserProperties properties,
                                                          ObjectMapper mapper) {
        return new CdpBrowserSessionManager(properties, mapper);
    }

    @Bean
    public BrowserTools browserTools(CdpBrowserSessionManager sessionManager) {
        return new BrowserTools(sessionManager);
    }

    @Bean
    public BrowserAgent browserAgent(com.aihub.ai.infra.ai.ChatClientFactory chatClientFactory,
                                     CdpBrowserSessionManager sessionManager,
                                     com.aihub.ai.domain.spi.AgentTaskRepository taskRepository,
                                     com.aihub.ai.domain.spi.AgentCancelRegistry cancelRegistry,
                                     com.aihub.ai.infra.ai.config.AgentProperties agentProperties) {
        return new BrowserAgent(chatClientFactory, sessionManager, taskRepository, cancelRegistry,
                agentProperties);
    }
}
