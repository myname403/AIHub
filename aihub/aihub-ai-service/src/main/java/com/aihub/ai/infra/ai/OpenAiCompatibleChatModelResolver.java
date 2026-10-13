package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.ModelEndpoint;
import com.aihub.common.crypto.AesGcmTextCipher;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议的 ChatModel 构建器。
 *
 * <p>覆盖：openai / 火山方舟 DeepSeek / 通义 DashScope（兼容模式）/ DeepSeek /
 * 本地 Ollama（同样暴露 /v1 兼容端点）等所有 OpenAI 协议族供应商——
 * 它们只是 base_url 与 model 不同。
 *
 * <p>构建前用 {@link ModelEndpoint#openAiCompatible()} 做<b>快速失败</b>：
 * 供应商编码打错或接入了非兼容协议的供应商时，立刻给出明确提示，
 * 而不是带着错误的 baseUrl 去发请求（那会得到难以定位的连接/404 错误）。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleChatModelResolver {

    /** 解密后的密钥只存在于本次构建过程，绝不落日志 */
    public ChatModel build(ModelEndpoint endpoint, String dataKey) {
        if (!endpoint.openAiCompatible()) {
            throw new BizException(ResultCode.MODEL_UNAVAILABLE,
                    "供应商 " + endpoint.providerCode() + " 暂不支持（当前仅支持 OpenAI 兼容协议族）");
        }
        // 本地 Ollama 等免鉴权供应商的 api_key 可能为空：此时不解密（decrypt(null) 会抛异常，
        // 进而被上层 catch 成“构建失败→静默降级回默认 OpenAI”的隐蔽故障），直接用空串占位。
        String apiKey = (endpoint.apiKeyCipher() == null || endpoint.apiKeyCipher().isBlank())
                ? "" : AesGcmTextCipher.decrypt(endpoint.apiKeyCipher(), dataKey);

        var apiBuilder = OpenAiApi.builder().apiKey(apiKey);
        String baseUrl = endpoint.baseUrl() == null ? "" : endpoint.baseUrl();
        // 部分 OpenAI 兼容供应商把版本号写进了 baseUrl（智谱 /api/paas/v4、通义 /compatible-mode/v1 等），
        // 而 Spring AI 默认补全路径是 /v1/chat/completions，直接拼接会得到 …/v4/v1/chat/completions 而 404。
        // 约定：baseUrl 若已以 /vN（可带结尾斜杠）结尾，则去掉结尾斜杠并改用不带版本前缀的 /chat/completions；
        // 否则（如 https://api.openai.com、http://localhost:11434）沿用默认的 /v1/chat/completions。
        if (baseUrl.matches(".*/v\\d+/?$")) {
            apiBuilder = apiBuilder.baseUrl(baseUrl.replaceAll("/+$", "")).completionsPath("/chat/completions");
        } else {
            apiBuilder = apiBuilder.baseUrl(baseUrl);
        }
        OpenAiApi api = apiBuilder.build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(endpoint.modelCode())
                        .temperature(0.7)
                        .build())
                .build();
    }
}
