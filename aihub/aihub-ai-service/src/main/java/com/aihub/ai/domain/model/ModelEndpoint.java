package com.aihub.ai.domain.model;

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
    /** openai 兼容协议族：openai / volcengine / dashscope 均走同一解析器 */
    public boolean openAiCompatible() {
        return "openai".equals(providerCode)
                || "volcengine".equals(providerCode)
                || "dashscope".equals(providerCode)
                || "deepseek".equals(providerCode);
    }
}
