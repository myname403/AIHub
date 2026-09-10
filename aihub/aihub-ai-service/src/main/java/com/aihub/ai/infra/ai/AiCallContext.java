package com.aihub.ai.infra.ai;

import com.aihub.common.tenant.TenantContext;

/**
 * 一次 AI 调用的线程内上下文（租户 / 应用 / 场景 / 会话）。
 *
 * <p>为什么需要它：ChatClient 是按 (租户, 应用, 场景) 缓存复用的，而被模型自主调用的
 * 工具（{@code @Tool}）拿不到这些参数——工具方法签名里没有它们。
 * 这里用 ThreadLocal 在调用入口写入、出口清除，供工具与工具审计回调读取。
 *
 * <p>conversationId 的用途：需要「按会话隔离状态」的工具（如浏览器会话复用）
 * 靠它把状态挂到正确的对话上，而不是全局共享一份。
 *
 * <p>只在 infra-ai 内部使用，不泄漏到 domain / application。
 */
public final class AiCallContext {

    private static final ThreadLocal<Values> TL = new ThreadLocal<>();

    private AiCallContext() {
    }

    public static void set(Long tenantId, Long appId, String scene) {
        set(tenantId, appId, scene, null);
    }

    public static void set(Long tenantId, Long appId, String scene, String conversationId) {
        TL.set(new Values(tenantId, appId, scene, conversationId));
    }

    /** 租户 ID：优先取上下文，取不到时回落到 TenantContext（如异步线程场景） */
    public static Long tenantId() {
        Values values = TL.get();
        if (values != null && values.tenantId() != null) {
            return values.tenantId();
        }
        try {
            return TenantContext.requireTenantId();
        } catch (Exception e) {
            return null;
        }
    }

    public static Long appId() {
        Values values = TL.get();
        return values == null ? null : values.appId();
    }

    public static String scene() {
        Values values = TL.get();
        return values == null ? null : values.scene();
    }

    public static String conversationId() {
        Values values = TL.get();
        return values == null ? null : values.conversationId();
    }

    public static void clear() {
        TL.remove();
    }

    private record Values(Long tenantId, Long appId, String scene, String conversationId) {
    }
}
