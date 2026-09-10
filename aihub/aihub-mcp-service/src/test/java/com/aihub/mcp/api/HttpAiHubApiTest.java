package com.aihub.mcp.api;

import com.aihub.mcp.model.AppBrief;
import com.aihub.mcp.model.ChatAnswer;
import com.aihub.mcp.model.ChunkHit;
import com.aihub.mcp.model.KbBrief;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HTTP 实现契约测试。
 *
 * <p>用 {@link MockRestServiceServer} 而不起真服务：这里要验证的是
 * 「请求发出去了长什么样」——路径、方法、API Key 头、请求体，
 * 以及服务端各种失败时我们翻译得对不对。这些都不需要网络。
 */
class HttpAiHubApiTest {

    private static final String BASE_URL = "http://aihub.test";
    private static final String API_KEY = "ak_test_key_0123456789";

    private MockRestServiceServer server;
    private HttpAiHubApi api;

    @BeforeEach
    void setUp() {
        // 走生产同一套 configure()，这样「Key 有没有带上」才算真被验证
        RestClient.Builder builder = HttpAiHubApi.configure(
                RestClient.builder(), BASE_URL, API_KEY, null);
        server = MockRestServiceServer.bindTo(builder).build();
        api = new HttpAiHubApi(builder.build(), 5);
    }

    /* ---------------- 应用列表 ---------------- */

    @Test
    void listApplicationsSendsApiKeyHeaderAndParsesEnvelope() {
        server.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(withSuccess("""
                        {"code":0,"message":"成功","data":[
                          {"id":9001,"name":"天机AI助手","agentStrategy":"plan_execute","status":1},
                          {"id":9002,"name":"停用的助手","status":0}
                        ]}""", MediaType.APPLICATION_JSON));

        List<AppBrief> apps = api.listApplications();

        assertEquals(2, apps.size());
        assertEquals("天机AI助手", apps.get(0).getName());
        assertEquals(9001L, apps.get(0).getId());
        assertTrue(apps.get(0).enabled());
        // status=0 的应用必须能被 MCP 工具识别为「已停用」，否则模型会白调一次
        assertFalse(apps.get(1).enabled());
        server.verify();
    }

    @Test
    void extraServerFieldsDoNotBreakDeserialization() {
        // 服务端给 App 加了新字段（systemPrompt/temperature…），MCP 侧不该因此报错
        server.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andRespond(withSuccess("""
                        {"code":0,"data":[{"id":1,"name":"x","systemPrompt":"很长一段",
                         "temperature":0.7,"modelRouteId":3,"tenantId":1001}]}""",
                        MediaType.APPLICATION_JSON));

        assertEquals(1, api.listApplications().size());
    }

    @Test
    void nullDataBecomesEmptyList() {
        server.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        assertTrue(api.listApplications().isEmpty());
    }

    /* ---------------- 知识库 ---------------- */

    @Test
    void listKnowledgeBasesReadsKbPayload() {
        // 服务端把知识库塞在 DocumentInfo 里（fileType 固定 "kb"），这里只取 id/name/status
        server.expect(requestTo(BASE_URL + "/api/ai/kb"))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(withSuccess("""
                        {"code":0,"data":[{"id":9001,"kbId":null,"name":"课程知识库",
                         "fileType":"kb","status":1,"errorMsg":null}]}""",
                        MediaType.APPLICATION_JSON));

        List<KbBrief> kbs = api.listKnowledgeBases();

        assertEquals(1, kbs.size());
        assertEquals(9001L, kbs.get(0).getId());
        assertEquals("课程知识库", kbs.get(0).getName());
    }

    /* ---------------- 检索 ---------------- */

    @Test
    void searchKnowledgePostsBodyAndParsesHits() {
        server.expect(requestTo(BASE_URL + "/api/ai/kb/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", API_KEY))
                .andExpect(jsonPath("$.kbId").value(9001))
                .andExpect(jsonPath("$.query").value("Java 入门"))
                .andExpect(jsonPath("$.topK").value(3))
                .andRespond(withSuccess("""
                        {"code":0,"data":[{"content":"推荐《Java 从入门到精通》",
                         "score":0.8345,"kbId":9001,"docId":100,"docName":"课程数据.txt"}]}""",
                        MediaType.APPLICATION_JSON));

        List<ChunkHit> hits = api.searchKnowledge(9001L, "Java 入门", 3);

        assertEquals(1, hits.size());
        assertEquals(83, hits.get(0).scorePercent(), "0.8345 应四舍五入成 83%");
        assertEquals("课程数据.txt", hits.get(0).getDocName());
    }

    @Test
    void searchKnowledgeFallsBackToConfiguredTopK() {
        server.expect(requestTo(BASE_URL + "/api/ai/kb/search"))
                .andExpect(jsonPath("$.topK").value(5))
                .andRespond(withSuccess("{\"code\":0,\"data\":[]}", MediaType.APPLICATION_JSON));

        // topK 传 null 时应落到配置的默认值（构造时给了 5）
        api.searchKnowledge(9001L, "q", null);

        server.verify();
    }

    @Test
    void searchKnowledgeNeverSendsKbIdNull() {
        server.expect(requestTo(BASE_URL + "/api/ai/kb/search"))
                .andExpect(jsonPath("$.kbId").doesNotExist())
                .andRespond(withSuccess("{\"code\":0,\"data\":[]}", MediaType.APPLICATION_JSON));

        // kbId 为 null 时 Jackson 默认不写该字段（NON_NULL 由 HashMap 的 null 值跳过），
        // 语义等同于「不限知识库」，与服务端 kbId 可空的约定一致
        api.searchKnowledge(null, "q", 1);

        server.verify();
    }

    /* ---------------- 对话 ---------------- */

    @Test
    void chatDefaultsSceneToChat() {
        server.expect(requestTo(BASE_URL + "/api/ai/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.appId").value(9001))
                .andExpect(jsonPath("$.message").value("你好"))
                .andExpect(jsonPath("$.scene").value("chat"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"conversationId":"c-1","content":"你好，我是助手",
                         "costMs":321}}""", MediaType.APPLICATION_JSON));

        ChatAnswer answer = api.chat(9001L, "你好", null);

        assertEquals("c-1", answer.getConversationId());
        assertEquals("你好，我是助手", answer.getContent());
    }

    @Test
    void chatPassesExplicitScene() {
        server.expect(requestTo(BASE_URL + "/api/ai/chat"))
                .andExpect(jsonPath("$.scene").value("rag"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"conversationId":"c-2","content":"ok"}}""",
                        MediaType.APPLICATION_JSON));

        api.chat(9001L, "q", "rag");
        server.verify();
    }

    @Test
    void blankAnswerPayloadIsRejected() {
        server.expect(requestTo(BASE_URL + "/api/ai/chat"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        AiHubCallException e = assertThrows(AiHubCallException.class,
                () -> api.chat(9001L, "q", null));
        assertTrue(e.getMessage().contains("空结果"));
    }

    /* ---------------- 失败翻译 ---------------- */

    @Test
    void businessFailureCodeBecomesReadableException() {
        server.expect(requestTo(BASE_URL + "/api/ai/chat"))
                .andRespond(withSuccess("{\"code\":10007,\"message\":\"配额已用尽\"}",
                        MediaType.APPLICATION_JSON));

        AiHubCallException e = assertThrows(AiHubCallException.class,
                () -> api.chat(9001L, "q", null));
        assertTrue(e.getMessage().contains("10007"), e.getMessage());
        assertTrue(e.getMessage().contains("配额已用尽"), e.getMessage());
    }

    /** 401 是 MCP 接入最常见的故障（Key 没配/被吊销），提示必须能直接指出原因 */
    @Test
    void unauthorizedIsTranslatedToActionableHint() {
        server.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":10002,\"message\":\"API Key 无效\"}"));

        AiHubCallException e = assertThrows(AiHubCallException.class,
                () -> api.listApplications());
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertTrue(e.getMessage().contains("api-key"), e.getMessage());
    }

    @Test
    void notFoundHintsAtWrongBaseUrl() {
        server.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        AiHubCallException e = assertThrows(AiHubCallException.class,
                () -> api.listApplications());
        assertTrue(e.getMessage().contains("base-url"), e.getMessage());
    }

    @Test
    void rateLimitIsTranslated() {
        server.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        AiHubCallException e = assertThrows(AiHubCallException.class,
                () -> api.listApplications());
        assertTrue(e.getMessage().contains("限流"), e.getMessage());
    }

    /* ---------------- 配置装配 ---------------- */

    @Test
    void blankApiKeyOmitsHeaderInsteadOfSendingEmpty() {
        RestClient.Builder builder = HttpAiHubApi.configure(
                RestClient.builder(), BASE_URL, "  ", null);
        MockRestServiceServer server2 = MockRestServiceServer.bindTo(builder).build();
        HttpAiHubApi api2 = new HttpAiHubApi(builder.build(), 5);

        // 空 Key 就不该发这个头：发一个空值只会让网关产生无谓的鉴权分支
        server2.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andExpect(req -> assertNull(req.getHeaders().getFirst("X-API-Key"),
                        "空 Key 不应发出 X-API-Key 头"))
                .andRespond(withSuccess("{\"code\":0,\"data\":[]}", MediaType.APPLICATION_JSON));

        api2.listApplications();
        server2.verify();
    }

    @Test
    void apiKeyIsMaskedForLogging() {
        assertEquals("(未配置)", HttpAiHubApi.maskApiKey(null));
        assertEquals("(未配置)", HttpAiHubApi.maskApiKey("  "));
        assertEquals("***", HttpAiHubApi.maskApiKey("short"));
        String masked = HttpAiHubApi.maskApiKey(API_KEY);
        assertTrue(masked.startsWith("ak_test"), masked);
        assertTrue(masked.endsWith("***"), masked);
        assertTrue(masked.length() < API_KEY.length(), "日志里必须比原文短");
    }

    @Test
    void longServerMessageIsNotDumpedWholeIntoException() {
        String huge = "x".repeat(5000);
        server.expect(requestTo(BASE_URL + "/api/ai/app"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":50000,\"message\":\"" + huge + "\"}"));

        AiHubCallException e = assertThrows(AiHubCallException.class,
                () -> api.listApplications());
        // 异常信息会进日志与模型上下文，不该被一坨无意义正文淹掉
        assertTrue(e.getMessage().length() < 500, "异常信息长度 " + e.getMessage().length());
    }

    @Test
    void exceptionMessageContainsContext() {
        server.expect(requestTo(BASE_URL + "/api/ai/kb/search"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        AiHubCallException e = assertThrows(AiHubCallException.class,
                () -> api.searchKnowledge(1L, "q", 1));
        assertTrue(e.getMessage().contains("检索知识库"),
                "异常要说明是哪一步失败的，否则排查时无从下手");
    }
}
