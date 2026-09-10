package com.aihub.mcp.stdio;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stdio 传输模块的装配冒烟测试。
 *
 * <p>{@code webEnvironment = NONE} 是刻意的：stdio 版是被宿主进程以
 * {@code java -jar} 拉起的 CLI，运行环境里不该、也不需要 HTTP 服务器。
 * 如果将来有人往依赖里加了 web 模块，这里会因为「没有 servlet 容器却要建 Web 容器」而失败，
 * 从而在构建期而不是运行期暴露问题。
 *
 * <p>同时验证 {@code scanBasePackages="com.aihub.mcp"} 确实扫到了
 * aihub-mcp-service 中的 {@code AiHubMcpConfiguration}（写错不会报错，只会暴露 0 个工具）。
 */
@SpringBootTest(classes = AiHubMcpStdioApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiHubMcpStdioApplicationTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void exposesAiHubToolsWithoutAnyWebContainer() {
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
