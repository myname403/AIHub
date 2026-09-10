package com.aihub.mcp.api;

import com.aihub.mcp.model.AppBrief;
import com.aihub.mcp.model.ChatAnswer;
import com.aihub.mcp.model.ChunkHit;
import com.aihub.mcp.model.KbBrief;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 经网关调用 AIHub 的 HTTP 实现。
 *
 * <p>鉴权用开放 API Key（{@code X-API-Key} 头）：MCP Server 不代表某个终端用户，
 * 用一个固定 Key 标识「哪个租户在通过 MCP 使用 AIHub」最贴切。
 */
public class HttpAiHubApi implements AiHubApi {

    /** 与服务端 AuthGlobalFilter 约定一致 */
    private static final String HEADER_API_KEY = "X-API-Key";

    private static final ParameterizedTypeReference<ApiEnvelope<List<AppBrief>>> APP_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<List<KbBrief>>> KB_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<List<ChunkHit>>> CHUNK_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiEnvelope<ChatAnswer>> CHAT_ANSWER =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final int defaultTopK;

    public HttpAiHubApi(RestClient client, int defaultTopK) {
        this.client = client;
        this.defaultTopK = defaultTopK;
    }

    /**
     * 统一装配客户端。
     *
     * <p>生产与测试共用这一个方法，测试才能真正验证「请求带没带 API Key 头」
     * 这类接线细节——各写一份配置的话，测过了也说明不了线上是对的。
     *
     * @param timeout 为 null 时不接管请求工厂（测试会自行绑定 MockRestServiceServer）
     */
    public static RestClient.Builder configure(RestClient.Builder builder, String baseUrl,
                                              String apiKey, Duration timeout) {
        RestClient.Builder configured = builder.baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            configured = configured.defaultHeader(HEADER_API_KEY, apiKey);
        }
        if (timeout != null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
            configured = configured.requestFactory(factory);
        }
        return configured;
    }

    @Override
    public List<AppBrief> listApplications() {
        ApiEnvelope<List<AppBrief>> envelope = exchange(() -> client.get()
                .uri("/api/ai/app")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(APP_LIST), "查询应用列表");
        return envelope.getData() == null ? Collections.emptyList() : envelope.getData();
    }

    @Override
    public List<KbBrief> listKnowledgeBases() {
        ApiEnvelope<List<KbBrief>> envelope = exchange(() -> client.get()
                .uri("/api/ai/kb")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(KB_LIST), "查询知识库列表");
        return envelope.getData() == null ? Collections.emptyList() : envelope.getData();
    }

    @Override
    public List<ChunkHit> searchKnowledge(Long kbId, String query, Integer topK) {
        Map<String, Object> body = new HashMap<>();
        // 只放有值的字段：多余的 null 键在服务端会走一遍无意义的判空分支
        putIfPresent(body, "kbId", kbId);
        putIfPresent(body, "query", query);
        body.put("topK", topK == null || topK <= 0 ? defaultTopK : Math.min(topK, 20));

        ApiEnvelope<List<ChunkHit>> envelope = exchange(() -> client.post()
                .uri("/api/ai/kb/search")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(CHUNK_LIST), "检索知识库");
        return envelope.getData() == null ? Collections.emptyList() : envelope.getData();
    }

    @Override
    public ChatAnswer chat(Long appId, String message, String scene) {
        Map<String, Object> body = new HashMap<>();
        // appId 为空表示「用服务端默认应用」，此时不必发这个键
        putIfPresent(body, "appId", appId);
        putIfPresent(body, "message", message);
        body.put("scene", scene == null || scene.isBlank() ? "chat" : scene);

        ApiEnvelope<ChatAnswer> envelope = exchange(() -> client.post()
                .uri("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(CHAT_ANSWER), "发起对话");
        if (envelope.getData() == null) {
            throw new AiHubCallException("AIHub 对话返回了空结果");
        }
        return envelope.getData();
    }

    /* ---------------- 内部 ---------------- */

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    /**
     * 统一处理两类失败：传输层异常（连不上 / 超时 / 非 2xx）与业务码非 0。
     *
     * <p>把 HTTP 状态码也翻成中文提示：模型看到「401」只会瞎猜，
     * 看到「API Key 无效或已停用」才知道该怎么办。
     */
    private <T> ApiEnvelope<T> exchange(Supplier<ApiEnvelope<T>> call, String action) {
        ApiEnvelope<T> envelope;
        try {
            envelope = call.get();
        } catch (RestClientException e) {
            throw new AiHubCallException(action + "失败：" + describe(e), e);
        }
        if (envelope == null) {
            throw new AiHubCallException(action + "失败：AIHub 未返回内容");
        }
        if (!envelope.succeeded()) {
            throw new AiHubCallException(envelope.errorText());
        }
        return envelope;
    }

    private String describe(RestClientException e) {
        String text = e.getMessage() == null ? "" : e.getMessage();
        if (text.contains("401")) {
            return "鉴权未通过（HTTP 401），请检查 aihub.mcp.api-key 是否有效或已吊销";
        }
        if (text.contains("403")) {
            return "无权限（HTTP 403）";
        }
        if (text.contains("404")) {
            return "接口不存在（HTTP 404），请确认 aihub.mcp.base-url 指向 AIHub 网关";
        }
        if (text.contains("429")) {
            return "触发限流（HTTP 429），请稍后重试";
        }
        if (e instanceof org.springframework.web.client.ResourceAccessException) {
            return "无法连接 AIHub（" + firstLine(text) + "），请确认网关已启动且 base-url 正确";
        }
        return "HTTP 响应异常（" + firstLine(text) + "）";
    }

    private String firstLine(String text) {
        int nl = text.indexOf('\n');
        String one = nl < 0 ? text : text.substring(0, nl);
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }

    /** 供启动日志使用：只回显前缀，绝不打印完整 Key */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "(未配置)";
        }
        return apiKey.length() <= 8 ? "***" : apiKey.substring(0, 7) + "***";
    }
}
