package com.aihub.ai.domain.model;

/**
 * 模型供应商定义（平台级，租户不可改）。
 */
public record ModelProvider(String code, String name, String baseUrl) {
}
