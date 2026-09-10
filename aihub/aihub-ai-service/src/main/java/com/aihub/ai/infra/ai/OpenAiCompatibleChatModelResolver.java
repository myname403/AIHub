package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.ModelEndpoint;
import com.aihub.common.crypto.AesGcmTextCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议的 ChatModel 构建器。
 *
 * <p>覆盖：openai / 火山方舟 DeepSeek / 通义 DashScope（兼容模式）/ DeepSeek 等所有
 * OpenAI 协议族供应商——它们只是 base_url 与 model 不同。
 * Ollama 等其他协议后续新增独立 Resolver。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleChatModelResolver {

    /** 解密后的密钥只存在于本次构建过程，绝不落日志 */
    public ChatModel build(ModelEndpoint endpoint, String dataKey) {
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
