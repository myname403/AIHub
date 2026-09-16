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
        String apiKey = AesGcmTextCipher.decrypt(endpoint.apiKeyCipher(), dataKey);
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(endpoint.modelCode())
                        .temperature(0.7)
                        .build())
                .build();
    }
}
