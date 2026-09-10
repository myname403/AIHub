package com.aihub.ai.domain.model;

/**
 * 模型管理视图（脱敏）。
 *
 * <p>刻意<b>不含</b> apiKey（明文或密文）：管理端只需要知道「这个模型有没有配 Key」，
 * 用 {@code hasApiKey} 布尔值表达即可。任何情况下密钥都不应出现在接口响应里。
 *
 * <p>字段命名为 {@code defaultModel} 而非 {@code isDefault} 是刻意的：
 * Jackson 会把 {@code isDefault()} 这类访问器映射成 JSON 属性 {@code default}，
 * 与前端直觉不符且容易踩坑，换个名字就没有歧义。
 */
public record ModelInfo(
        Long id,
        String providerCode,
        String modelCode,
        String baseUrl,
        Integer vectorDim,
        boolean defaultModel,
        Integer status,
        boolean hasApiKey
) {
    public boolean enabled() {
        return status != null && status == 1;
    }
}
