package com.aihub.common.result;

import lombok.Getter;

/**
 * 统一响应码。
 * 规范：业务码使用 1xxxx，HTTP 语义沿用标准状态码。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "成功"),

    /* 通用错误 1xxxx */
    PARAM_ERROR(10001, "参数错误"),
    UNAUTHORIZED(10002, "未认证或令牌失效"),
    FORBIDDEN(10003, "无权限访问"),
    NOT_FOUND(10004, "资源不存在"),
    TENANT_MISMATCH(10005, "跨租户访问被拒绝"),
    RATE_LIMITED(10006, "请求过于频繁，请稍后再试"),
    QUOTA_EXCEEDED(10007, "配额已用尽"),

    /* AI 能力 2xxxx */
    MODEL_UNAVAILABLE(20001, "模型不可用"),
    MODEL_DEGRADED(20002, "主模型不可用，已降级"),
    KNOWLEDGE_EMPTY(20003, "知识库暂无可用内容"),
    TOOL_TIMEOUT(20004, "工具调用超时"),
    AGENT_BUDGET_EXCEEDED(20005, "Agent 执行超出预算"),

    /* 系统 5xxxx */
    SYSTEM_ERROR(50000, "系统内部错误"),
    UPSTREAM_UNAVAILABLE(50001, "依赖服务不可用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
