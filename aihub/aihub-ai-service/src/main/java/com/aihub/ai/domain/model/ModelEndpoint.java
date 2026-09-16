package com.aihub.ai.domain.model;

import java.util.Set;

/**
 * 模型接入点（供应商侧信息）。
 *
 * <p>apiKey 为密文（AES-GCM），解密动作由 infra 层完成，密文永不外泄、永不进日志。
 */
public record ModelEndpoint(
        String providerCode,
        String baseUrl,
        String apiKeyCipher,
        String modelCode
) {
    /**
     * OpenAI 兼容协议族：这些供应商只是 baseUrl / modelCode 不同，可复用同一个解析器。
     *
     * <p>为什么把 {@code ollama} 也算进来：Ollama 从 0.x 起就暴露 {@code /v1/chat/completions}
     * 兼容端点（本项目向量模型也正是走它的 OpenAI 兼容 /v1/embeddings），
     * 故它与 openai/volcengine/dashscope/deepseek 走同一条构建路径，
     * 差别只在 ChatClientFactory 会对它追加 Qwen 系列所需的 {@code /no_think} 软开关。
     *
     * <p>早期版本该方法遗漏了 ollama，导致"ChatClientFactory 里有 ollama 分支、
     * 但这个判断又不认 ollama"的语义分裂（且该方法当时无人调用，是死代码）。
     * 现在由 {@code OpenAiCompatibleChatModelResolver} 在构建前用它做快速失败校验。
     */
    public boolean openAiCompatible() {
        return providerCode != null && OPENAI_COMPATIBLE.contains(providerCode);
    }

    /** 已知的 OpenAI 兼容供应商编码（与 ai_model_provider 字典保持同步） */
    private static final Set<String> OPENAI_COMPATIBLE =
            Set.of("openai", "volcengine", "dashscope", "deepseek", "ollama");
}
