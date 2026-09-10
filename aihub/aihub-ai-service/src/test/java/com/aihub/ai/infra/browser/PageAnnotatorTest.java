package com.aihub.ai.infra.browser;

import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.model.PageSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标注解析逻辑单测：不启动浏览器，只验证「JSON 文本 → PageSnapshot」。
 * （脚本的端到端行为由真实浏览器验证覆盖，见 README 的浏览器控制章节）
 */
class PageAnnotatorTest {

    private final PageAnnotator annotator = new PageAnnotator(new ObjectMapper(), 50);

    @Test
    void parsesSnapshotJson() {
        String json = """
                {"url":"https://example.com","title":"示例页","totalFound":3,
                 "elements":[
                   {"ref":"e1","role":"textbox","text":"搜索","x":100,"y":80},
                   {"ref":"e2","role":"button","text":"提交","x":200,"y":80}]}
                """;
        PageSnapshot snapshot = annotator.parse(json);

        assertThat(snapshot.url()).isEqualTo("https://example.com");
        assertThat(snapshot.title()).isEqualTo("示例页");
        assertThat(snapshot.elements()).hasSize(2);
        assertThat(snapshot.elements().get(0).ref()).isEqualTo("e1");
        assertThat(snapshot.elements().get(0).role()).isEqualTo("textbox");
        assertThat(snapshot.elements().get(0).text()).isEqualTo("搜索");
        assertThat(snapshot.elements().get(1).role()).isEqualTo("button");
        // totalFound=3 > 实际返回 2 个 → 应判定为被截断
        assertThat(snapshot.totalFound()).isEqualTo(3);
        assertThat(snapshot.truncated()).isTrue();
        assertThat(snapshot.describe()).contains("[e1]", "[e2]", "前 2 个");
    }

    @Test
    void missingTotalFoundFallsBackToElementCount() {
        String json = """
                {"url":"https://example.com","title":"t",
                 "elements":[{"ref":"e1","role":"button","text":"OK","x":1,"y":2}]}
                """;
        PageSnapshot snapshot = annotator.parse(json);

        assertThat(snapshot.totalFound()).isEqualTo(1);
        assertThat(snapshot.truncated()).isFalse();
        assertThat(snapshot.isEmpty()).isFalse();
    }

    @Test
    void blankJsonThrows() {
        assertThatThrownBy(() -> annotator.parse("   "))
                .isInstanceOf(BrowserException.class);
    }

    @Test
    void malformedJsonThrows() {
        assertThatThrownBy(() -> annotator.parse("not-json-at-all"))
                .isInstanceOf(BrowserException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void expressionWrapsScriptWithConfiguredLimit() {
        String expression = annotator.expression();

        // 表达式 = "(脚本)(上限)"，上限来自构造参数而不是写死在脚本里
        assertThat(expression).startsWith("(");
        assertThat(expression).endsWith("(50)");
        assertThat(expression).contains("data-testid");
    }

    @Test
    void emptyElementsDescribeHintsCauses() {
        PageSnapshot snapshot = annotator.parse(
                "{\"url\":\"https://x\",\"title\":\"t\",\"elements\":[]}");

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(snapshot.describe()).contains("0 个").contains("iframe");
    }
}
