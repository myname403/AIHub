package com.aihub.mcp.tools;

import com.aihub.mcp.api.AiHubApi;
import com.aihub.mcp.api.AiHubCallException;
import com.aihub.mcp.config.AiHubMcpProperties;
import com.aihub.mcp.model.AppBrief;
import com.aihub.mcp.model.ChatAnswer;
import com.aihub.mcp.model.ChunkHit;
import com.aihub.mcp.model.KbBrief;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具语义测试。
 *
 * <p>工具的价值在「参数校验 + 结果组织 + 失败提示」三件事，正好都不需要网络。
 */
class AiHubToolsTest {

    private AiHubApi api;
    private AiHubMcpProperties properties;
    private AiHubTools tools;

    @BeforeEach
    void setUp() {
        api = mock(AiHubApi.class);
        properties = new AiHubMcpProperties();
        properties.setDefaultAppId(9001L);
        properties.setDefaultTopK(5);
        properties.setChunkMaxChars(20);
        tools = new AiHubTools(api, properties);
    }

    /* ---------------- 应用列表 ---------------- */

    @Test
    void listApplicationsLabelsIdAndDisabledState() {
        AppBrief enabled = app(9001L, "天机AI助手", 1, "plan_execute");
        AppBrief disabled = app(9002L, "旧助手", 0, "none");
        when(api.listApplications()).thenReturn(List.of(enabled, disabled));

        String text = tools.listApplications();

        assertTrue(text.contains("id=9001"), text);
        assertTrue(text.contains("天机AI助手"), text);
        assertTrue(text.contains("plan_execute"), text);
        // 停用状态必须显式告诉模型，否则它会白调一次拿到 401/403
        assertTrue(text.contains("已停用"), text);
    }

    @Test
    void emptyApplicationListGivesActionableHint() {
        when(api.listApplications()).thenReturn(List.of());

        String text = tools.listApplications();

        assertTrue(text.contains("没有任何应用"), text);
        assertTrue(text.contains("管理后台"), text);
    }

    @Test
    void failureIsReturnedAsTextNotException() {
        when(api.listApplications()).thenThrow(new AiHubCallException("鉴权未通过"));

        String text = tools.listApplications();

        assertTrue(text.startsWith("查询应用列表失败："), text);
        assertTrue(text.contains("鉴权未通过"), text);
    }

    /* ---------------- 知识库列表 ---------------- */

    @Test
    void listKnowledgeBasesReportsIdsAndNames() {
        KbBrief kb = new KbBrief();
        kb.setId(9001L);
        kb.setName("课程知识库");
        kb.setStatus(1);
        when(api.listKnowledgeBases()).thenReturn(List.of(kb));

        String text = tools.listKnowledgeBases();

        assertTrue(text.contains("id=9001"), text);
        assertTrue(text.contains("课程知识库"), text);
    }

    @Test
    void emptyKbListTellsModelNotToFabricate() {
        when(api.listKnowledgeBases()).thenReturn(List.of());

        String text = tools.listKnowledgeBases();

        assertTrue(text.contains("没有知识库"), text);
    }

    /* ---------------- 检索 ---------------- */

    @Test
    void blankQueryIsRejectedWithoutCallingApi() {
        String text = tools.searchKnowledge(1L, "   ", null);

        assertTrue(text.contains("query 不能为空"), text);
        verify(api, never()).searchKnowledge(anyLong(), anyString(), any());
    }

    @Test
    void nullQueryIsRejectedWithoutCallingApi() {
        String text = tools.searchKnowledge(1L, null, 3);

        assertTrue(text.contains("query 不能为空"), text);
        verify(api, never()).searchKnowledge(anyLong(), anyString(), any());
    }

    @Test
    void searchFormatsChunksWithSourceAndPercent() {
        when(api.searchKnowledge(anyLong(), anyString(), any()))
                .thenReturn(List.of(hit("推荐《Java 从入门到精通》", 0.8345, "课程数据.txt")));

        String text = tools.searchKnowledge(9001L, "Java 入门", 3);

        assertTrue(text.contains("[1]"), text);
        assertTrue(text.contains("课程数据.txt"), text);
        assertTrue(text.contains("83%"), text);
        assertTrue(text.contains("推荐《Java 从入门到精通》"), text);
    }

    @Test
    void emptySearchResultSaysNotFound() {
        when(api.searchKnowledge(anyLong(), anyString(), any())).thenReturn(List.of());

        String text = tools.searchKnowledge(9001L, "不存在的话题", 3);

        // 明确告知「没找到」，模型才会如实回答而不是自己编
        assertTrue(text.contains("没有检索到"), text);
        assertTrue(text.contains("不存在的话题"), text);
    }

    @Test
    void oversizedTopKIsClampedToTwenty() {
        when(api.searchKnowledge(anyLong(), anyString(), any())).thenReturn(List.of());

        tools.searchKnowledge(9001L, "q", 500);

        // 服务端 limit 是硬上限，这里先夹住，避免把非法请求发出去
        verify(api).searchKnowledge(eq(9001L), eq("q"), eq(20));
        verify(api, times(1)).searchKnowledge(anyLong(), anyString(), any());
    }

    @Test
    void missingTopKUsesConfiguredDefault() {
        when(api.searchKnowledge(anyLong(), anyString(), any())).thenReturn(List.of());

        tools.searchKnowledge(9001L, "q", null);

        verify(api).searchKnowledge(eq(9001L), eq("q"), eq(5));
    }

    @Test
    void zeroTopKUsesConfiguredDefault() {
        when(api.searchKnowledge(anyLong(), anyString(), any())).thenReturn(List.of());

        tools.searchKnowledge(9001L, "q", 0);

        verify(api).searchKnowledge(eq(9001L), eq("q"), eq(5));
    }

    /** 一条几万字的分片会把上下文预算直接打满，挤掉后续对话 */
    @Test
    void longChunkIsTruncatedWithNotice() {
        String long1 = "x".repeat(200);
        when(api.searchKnowledge(anyLong(), anyString(), any()))
                .thenReturn(List.of(hit(long1, 0.9, "大文档.txt")));

        String text = tools.searchKnowledge(9001L, "q", 1);

        assertTrue(text.contains("已截断"), text);
        assertTrue(text.contains("共 200 字"), text);
        assertFalse(text.contains("x".repeat(30)), "超长原文不该整个塞进结果");
    }

    /* ---------------- 提问 ---------------- */

    @Test
    void blankMessageIsRejectedWithoutCallingApi() {
        String text = tools.ask("  ", null, null);

        assertTrue(text.contains("message 不能为空"), text);
        verify(api, never()).chat(any(), anyString(), any());
    }

    @Test
    void missingAppIdFallsBackToConfiguredDefault() {
        when(api.chat(any(), anyString(), any()))
                .thenReturn(answer("你好", "c-1"));

        tools.ask("在吗", null, null);

        verify(api).chat(eq(9001L), eq("在吗"), isNull());
    }

    @Test
    void explicitAppIdWinsOverDefault() {
        when(api.chat(any(), anyString(), any())).thenReturn(answer("ok", "c-2"));

        tools.ask("在吗", 7777L, "rag");

        verify(api).chat(eq(7777L), eq("在吗"), eq("rag"));
    }

    @Test
    void answerIncludesConversationIdForFollowUp() {
        when(api.chat(any(), anyString(), any())).thenReturn(answer("回答正文", "conv-abc"));

        String text = tools.ask("问题", 9001L, null);

        assertTrue(text.contains("回答正文"), text);
        assertTrue(text.contains("conv-abc"), text);
    }

    /** 空回答比报错更隐蔽：多半是模型没配或配额用尽，得把可能原因说出来 */
    @Test
    void blankAnswerExplainsLikelyCauses() {
        when(api.chat(any(), anyString(), any())).thenReturn(answer("   ", "c-3"));

        String text = tools.ask("问题", 9001L, null);

        assertTrue(text.contains("空回答"), text);
        assertTrue(text.contains("配额"), text);
    }

    @Test
    void answerWithoutConversationIdOmitsTrailer() {
        ChatAnswer noConv = new ChatAnswer();
        noConv.setContent("只有正文");
        when(api.chat(any(), anyString(), any())).thenReturn(noConv);

        String text = tools.ask("问题", 9001L, null);

        assertEquals("只有正文", text);
    }

    @Test
    void chatFailureIsReturnedAsText() {
        when(api.chat(any(), anyString(), any()))
                .thenThrow(new AiHubCallException("配额已用尽"));

        String text = tools.ask("问题", 9001L, null);

        assertTrue(text.startsWith("调用 AIHub 失败："), text);
        assertTrue(text.contains("配额已用尽"), text);
    }

    /* ---------------- 工具的「元数据」质量 ---------------- */

    /**
     * 描述是模型选择工具的唯一依据。
     *
     * <p>没有描述的工具等于没暴露——模型永远不知道该在什么时候调它。
     * 这条测试看着像形式主义，实际是把「别忘了写描述」变成构建期约束。
     */
    @Test
    void everyToolHasUniqueNameAndMeaningfulDescription() {
        Set<String> names = new HashSet<>();
        List<Method> toolMethods = Arrays.stream(AiHubTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .toList();

        assertEquals(4, toolMethods.size(), "工具数量变了就要同步更新文档与 README");

        for (Method method : toolMethods) {
            Tool tool = method.getAnnotation(Tool.class);
            assertFalse(tool.name().isBlank(), method.getName() + " 必须显式声明工具名");
            assertTrue(names.add(tool.name()), "工具名重复：" + tool.name());
            assertTrue(tool.description().length() >= 40,
                    method.getName() + " 的工具描述过短，模型无法据此判断使用时机");
        }
    }

    /* ---------------- 构造辅助 ---------------- */

    private AppBrief app(Long id, String name, int status, String strategy) {
        AppBrief brief = new AppBrief();
        brief.setId(id);
        brief.setName(name);
        brief.setStatus(status);
        brief.setAgentStrategy(strategy);
        return brief;
    }

    private ChunkHit hit(String content, double score, String docName) {
        ChunkHit chunk = new ChunkHit();
        chunk.setContent(content);
        chunk.setScore(score);
        chunk.setDocName(docName);
        chunk.setKbId(9001L);
        return chunk;
    }

    private ChatAnswer answer(String content, String conversationId) {
        ChatAnswer answer = new ChatAnswer();
        answer.setContent(content);
        answer.setConversationId(conversationId);
        answer.setCostMs(120L);
        return answer;
    }
}
