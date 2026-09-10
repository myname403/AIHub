package com.aihub.common.tenant;

/**
 * 租户上下文。
 *
 * <p>安全约束（★ 最高危项）：租户 ID 只允许从 JWT / API Key 解析后写入，
 * <b>绝不接受前端传参</b>。服务端在入口处解析并写入，后续全链路从此处读取。
 *
 * <p>跨服务场景由 Feign 拦截器自动透传（见 aihub-api 的 TenantFeignInterceptor）。
 */
public final class TenantContext {

    /** 服务间透传租户的请求头 */
    public static final String HEADER_TENANT = "X-Tenant-Id";
    /** 服务间透传用户的请求头 */
    public static final String HEADER_USER = "X-User-Id";

    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId, Long userId) {
        TENANT.set(tenantId);
        USER.set(userId);
    }

    public static Long getTenantId() {
        return TENANT.get();
    }

    /** 必须存在的租户 ID，缺失即视为越权风险 */
    public static Long requireTenantId() {
        Long id = TENANT.get();
        if (id == null) {
            throw new com.aihub.common.exception.BizException(
                    com.aihub.common.result.ResultCode.TENANT_MISMATCH, "租户上下文缺失");
        }
        return id;
    }

    public static Long getUserId() {
        return USER.get();
    }

    public static void clear() {
        TENANT.remove();
        USER.remove();
    }
}
