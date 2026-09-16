package com.aihub.ai.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型接入点的协议族判定（与 ai_model_provider 字典保持一致）。
 *
 * <p>钉住这个判定的原因：它决定「管理端能选的供应商」与「运行时可构建的供应商」是否一致。
 * 历史上这里遗漏过 deepseek（字典补了、判定没补），后来又把 ollama 漏在外面——
 * 造成"ChatClientFactory 有 ollama 分支、但这个判定又不认 ollama"的语义分裂。
 * 本测试覆盖字典里的全部五个供应商，新增供应商时若忘记同步会立刻失败。
 */
class ModelEndpointTest {

    private static ModelEndpoint endpoint(String providerCode) {
        return new ModelEndpoint(providerCode, "http://localhost:11434", "cipher", "m");
    }

    @Test
    void allProviderCodesInDictionaryAreOpenAiCompatible() {
        // 与 V1__init_ai.sql（openai/volcengine/dashscope/ollama）+ V8（deepseek）一致
        assertThat(endpoint("openai").openAiCompatible()).isTrue();
        assertThat(endpoint("volcengine").openAiCompatible()).isTrue();
        assertThat(endpoint("dashscope").openAiCompatible()).isTrue();
        assertThat(endpoint("deepseek").openAiCompatible()).isTrue();
        // Ollama 暴露 /v1/chat/completions 兼容端点（向量模型也走它的 /v1/embeddings）
        assertThat(endpoint("ollama").openAiCompatible()).isTrue();
    }

    @Test
    void unknownOrNullProviderIsNotCompatible() {
        assertThat(endpoint("nope").openAiCompatible()).isFalse();
        assertThat(endpoint("").openAiCompatible()).isFalse();
        assertThat(endpoint(null).openAiCompatible()).isFalse();
        // 大小写敏感：字典里存的是小写，写错大小写应被快速失败拦住而不是静默走错协议
        assertThat(endpoint("OpenAI").openAiCompatible()).isFalse();
    }
}
