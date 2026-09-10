package com.aihub.ai.domain.model;

/**
 * 模型路由视图：某场景下用哪个模型做主、哪个做备。
 *
 * <p>同时带 id 与 code 是因为两者用途不同——id 供管理端下拉框回显，
 * code 供人直接看懂「这条路由指向哪个模型」。
 */
public record ModelRouteInfo(
        Long id,
        String scene,
        Long primaryModelId,
        String primaryModelCode,
        Long fallbackModelId,
        String fallbackModelCode
) {
}
