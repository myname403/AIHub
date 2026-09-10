package com.aihub.ai.domain.model;

/**
 * 模型路由（场景选模结果）。
 *
 * <p>领域层值对象：只描述"该用哪个模型"，不涉及任何具体厂商 SDK。
 * 真正把 modelCode 解析成 ChatModel 的动作在 infra-ai 完成。
 */
public record ModelRoute(
        String scene,
        String primaryModelCode,
        String fallbackModelCode
) {
    public boolean hasFallback() {
        return fallbackModelCode != null && !fallbackModelCode.isBlank();
    }
}
