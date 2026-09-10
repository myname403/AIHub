package com.aihub.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住「MCP 对外暴露了哪些工具」这个契约。
 *
 * <p>为什么这条测试值得单独存在：
 * 工具没注册上时，MCP Server 会正常启动、正常握手，只是 {@code tools/list} 返回空数组。
 * 客户端表现为「连上了但什么也干不了」，而服务端日志里一句错误都没有。
 * 这里在装配层面把工具清单钉死，这类静默失败就变成构建期红灯。
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：
 * 只测这一个 {@code @Configuration}，不起 Web 容器、不扫全包，毫秒级完成。
 */
class AiHubMcpConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiHubMcpConfiguration.class)
            // 只需能构造出 RestClient，本测试不会真的发请求
            .withPropertyValues(
                    "aihub.mcp.base-url=http://127.0.0.1:9",
                    "aihub.mcp.timeout=5s");

    private static List<String> toolNames(ToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .sorted()
                .toList();
    }

    @Test
    void exposesExactlyFourAiHubTools() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            ToolCallbackProvider provider = context.getBean(ToolCallbackProvider.class);
            assertThat(toolNames(provider)).containsExactly(
                    "aihub_ask",
                    "aihub_list_applications",
                    "aihub_list_knowledge_bases",
                    "aihub_search_knowledge");
        });
    }

    /**
     * 工具描述是模型决定「用哪个工具」的唯一依据。
     * 描述为空或过于敷衍时，模型只能靠工具名瞎猜，调用错误率会显著上升。
     * 因此这里要求每个工具都有足够长度的描述——不是为了凑字数，
     * 而是要挡住「加了个新工具但忘了写 description」这种退化。
     */
    @Test
    void everyExposedToolCarriesUsableDescription() {
        runner.run(context -> {
            ToolCallbackProvider provider = context.getBean(ToolCallbackProvider.class);

            assertThat(provider.getToolCallbacks()).hasSize(4);
            for (ToolCallback callback : provider.getToolCallbacks()) {
                String name = callback.getToolDefinition().name();
                String description = callback.getToolDefinition().description();
                assertThat(description)
                        .as("工具 %s 的描述", name)
                        .isNotBlank()
                        .hasSizeGreaterThan(40);
            }
        });
    }

    /** 两个传输模块都靠 scanBasePackages="com.aihub.mcp" 扫到这个配置，Bean 名别随意改。 */
    @Test
    void beanNamesAreStableForTransportModulesToRelyOn() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String beanName : List.of("aiHubApi", "aiHubTools", "aiHubToolCallbackProvider")) {
                assertThat(context.containsBean(beanName)).as("Bean %s", beanName).isTrue();
            }
        });
    }
}
