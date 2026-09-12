package com.aihub.common.tenant;

/**
 * 租户上下文 —— 基于 ThreadLocal 的"当前请求是谁"存储器。
 *
 * <p><b>背景 —— 什么是多租户？</b>
 * SaaS 架构里，一套系统同时服务多家公司（租户）。所有数据都带 tenantId 字段，
 * 任何查询都必须限定在"当前租户"内，绝不允许 A 公司看到 B 公司的数据。
 * 这是本项目的最高安全优先级约束。
 *
 * <p><b>为什么用 ThreadLocal？</b>
 * Spring MVC 下"一个请求 = 一个工作线程"。ThreadLocal 相当于每个线程自己的私有储物柜：
 * 拦截器在请求入口把 tenantId 放进来，之后 Controller → Service → Mapper 无论调用多深，
 * 代码里随手 {@code TenantContext.getTenantId()} 就能取到，不用一层层传参。
 * 请求结束时必须 {@code clear()}，否则线程复用会把上一个请求的租户带给下一个请求（串台事故）。
 *
 * <p><b>安全约束（★ 最高危项）：租户 ID 只允许从 JWT / API Key 解析后写入，
 * <b>绝不接受前端传参</b>。服务端在入口处解析并写入，后续全链路从此处读取。</b>
 *
 * <p>跨服务场景由 Feign 拦截器自动透传（见 aihub-api 的 FeignTenantConfig）。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
public final class TenantContext {

    /** 服务间透传租户的请求头。网关解析 JWT 后写入，下游服务读取 */
    public static final String HEADER_TENANT = "X-Tenant-Id";

    /** 服务间透传用户的请求头 */
    public static final String HEADER_USER = "X-User-Id";

    /** 租户 ID 的线程级存储。泛型 Long 对应数据库 tenant_id 列 */
    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();

    /** 用户 ID 的线程级存储（记录"是谁在操作"，用于审计） */
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();

    /** 私有构造器：纯静态工具类，禁止实例化 */
    private TenantContext() {
    }

    /** 入口处一次性写入（拦截器调用）。两个值要么都有、要么至少有租户 */
    public static void set(Long tenantId, Long userId) {
        TENANT.set(tenantId);
        USER.set(userId);
    }

    /** 读取当前租户 ID；不在请求线程内（如定时任务）时返回 null */
    public static Long getTenantId() {
        return TENANT.get();
    }

    /** 必须存在的租户 ID，缺失即视为越权风险 */
    public static Long requireTenantId() {
        Long id = TENANT.get();
        if (id == null) {
            // 类名用全限定名而不是 import：说明这是"借用兄弟包的类"，避免与本项目同名类混淆时可读性更好
            throw new com.aihub.common.exception.BizException(
                    com.aihub.common.result.ResultCode.TENANT_MISMATCH, "租户上下文缺失");
        }
        return id;
    }

    /** 读取当前用户 ID，可能为 null（如 API Key 调用没有"用户"概念） */
    public static Long getUserId() {
        return USER.get();
    }

    /**
     * 清理线程变量。必须在请求结束时调用（拦截器 afterCompletion），
     * 否则线程池复用线程时会污染下一个请求 —— 这是 ThreadLocal 最经典的内存泄漏/数据串台坑。
     */
    public static void clear() {
        TENANT.remove();
        USER.remove();
    }
}
