package com.aihub.mcp.sse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 传输模块的装配冒烟测试。
 *
 * <p>用 {@code RANDOM_PORT} 起真容器，验证的是三件必须同时成立的事：
 * <ol>
 *   <li>SSE 自动装配（{@code spring-ai-starter-mcp-server-webmvc}）能正常启动；</li>
 *   <li>{@code scanBasePackages="com.aihub.mcp"} 真的扫到了 aihub-mcp-service 里的
 *       {@code AiHubMcpConfiguration} —— 这是最容易出错的一环，写错了不会报错，
 *       只会静默地暴露 0 个工具；</li>
 *   <li>工具清单与预期一致。</li>
 * </ol>
 */
@SpringBootTest(classes = AiHubMcpSseApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiHubMcpSseApplicationTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void exposesAiHubToolsOverTheWebContext() {
        List<String> names = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .sorted()
                .toList();

        assertThat(names).containsExactly(
                "aihub_ask",
                "aihub_list_applications",
                "aihub_list_knowledge_bases",
                "aihub_search_knowledge");
    }
}
