package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.spi.AgentCancelRegistry;
import com.aihub.ai.domain.spi.AgentTaskRepository;
import com.aihub.ai.infra.ai.ChatClientFactory;
import com.aihub.ai.infra.ai.agent.BrowserAgent;
import com.aihub.ai.infra.ai.tools.BrowserTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 浏览器能力总开关钉住测试：aihub.browser.enabled 决定全部浏览器 bean 是否存在。
 * 关闭时 ChatClientFactory 不挂浏览器工具、AgentRegistry 里也没有 browser Agent——
 * 这个「缺席即降级」的语义一旦被破坏，浏览器能力就会变成默认开启的资源与安全负担。
 */
class BrowserConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withBean(ChatClientFactory.class, () -> Mockito.mock(ChatClientFactory.class))
            .withBean(AgentTaskRepository.class, () -> Mockito.mock(AgentTaskRepository.class))
            .withBean(AgentCancelRegistry.class, () -> Mockito.mock(AgentCancelRegistry.class))
            .withBean(com.aihub.ai.infra.ai.config.AgentProperties.class,
                    com.aihub.ai.infra.ai.config.AgentProperties::new)
            .withUserConfiguration(BrowserConfiguration.class);

    @Test
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context.containsBean("browserSessionManager")).isFalse();
            assertThat(context.containsBean("browserTools")).isFalse();
            assertThat(context.containsBean("browserAgent")).isFalse();
        });
    }

    @Test
    void enabledWhenPropertySet() {
        runner.withPropertyValues("aihub.browser.enabled=true").run(context -> {
            assertThat(context.containsBean("browserSessionManager")).isTrue();
            assertThat(context.containsBean("browserTools")).isTrue();
            assertThat(context.containsBean("browserAgent")).isTrue();
            assertThat(context.getBean("browserTools")).isInstanceOf(BrowserTools.class);
            assertThat(context.getBean("browserAgent")).isInstanceOf(BrowserAgent.class);
        });
    }
}
