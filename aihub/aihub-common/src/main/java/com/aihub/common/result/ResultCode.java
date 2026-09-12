package com.aihub.common.result;

import lombok.Getter;

/**
 * 统一响应码 —— 所有接口返回的 {@link R} 中 code 字段的合法取值，用枚举集中管理。
 *
 * <p><b>为什么用枚举而不是写死数字？</b>
 * ① 可读性：{@code ResultCode.UNAUTHORIZED} 比 {@code 10002} 一眼能看懂；
 * ② 集中管理：新增错误码只改这一个文件，避免"魔法数字"散落各处；
 * ③ 编译期检查：不可能出现拼错的 code。
 *
 * <p>规范：业务码使用 1xxxx，HTTP 语义沿用标准状态码。
 * 分段规则（注意 code 是"业务码"，不是 HTTP 状态码，HTTP 状态码始终返回 200，
 * 由前端根据 body 里的 code 判断成败）：
 * <ul>
 *   <li>0          —— 成功</li>
 *   <li>1xxxx      —— 通用错误（参数、认证、权限、租户、限流、配额）</li>
 *   <li>2xxxx      —— AI 能力相关错误（模型、知识库、工具、Agent）</li>
 *   <li>5xxxx      —— 系统级错误（内部异常、下游依赖不可用）</li>
 * </ul>
 *
 * <p><b>枚举小知识：</b>Java 枚举是一种特殊的类，实例个数固定且由 JVM 保证唯一（天然单例），
 * 不能用 new 创建。这里的每个常量（如 SUCCESS）都是构造器生成的实例。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
@Getter   // Lombok 注解：只为所有字段生成 getter（枚举字段一般不允许外部修改，所以不用 @Data）
public enum ResultCode {

    /** 成功。注意成功码是 0 而不是 200，与 HTTP 状态码区分开 */
    SUCCESS(0, "成功"),

    /* ==================== 通用错误 1xxxx ==================== */

    /** 请求参数不合法：@Valid 校验失败、缺少必填字段、格式错误等 */
    PARAM_ERROR(10001, "参数错误"),

    /** 未认证或令牌失效：没带 token / token 过期 / token 签名不对。前端收到后一般应跳转登录页 */
    UNAUTHORIZED(10002, "未认证或令牌失效"),

    /** 无权限访问：认证通过了，但当前用户/租户没有权限操作目标资源 */
    FORBIDDEN(10003, "无权限访问"),

    /** 资源不存在：如查询的会话、知识库 ID 不存在 */
    NOT_FOUND(10004, "资源不存在"),

    /** 跨租户访问被拒绝：多租户核心防线。A 租户的用户尝试访问 B 租户的数据时抛出 */
    TENANT_MISMATCH(10005, "跨租户访问被拒绝"),

    /** 请求过于频繁：网关 Sentinel 限流触发或接口级限流 */
    RATE_LIMITED(10006, "请求过于频繁，请稍后再试"),

    /** 配额已用尽：租户的 token 数/调用次数配额耗尽，需要充值或等待周期重置 */
    QUOTA_EXCEEDED(10007, "配额已用尽"),

    /* ==================== AI 能力 2xxxx ==================== */

    /** 模型不可用：主模型和备用模型都调用失败 */
    MODEL_UNAVAILABLE(20001, "模型不可用"),

    /** 主模型不可用，已降级：请求成功但走的是备用模型，message 中会说明降级详情 */
    MODEL_DEGRADED(20002, "主模型不可用，已降级"),

    /** 知识库暂无可用内容：RAG 检索没有命中任何知识片段 */
    KNOWLEDGE_EMPTY(20003, "知识库暂无可用内容"),

    /** 工具调用超时：Agent 调用外部工具（如浏览器、图表生成）超时 */
    TOOL_TIMEOUT(20004, "工具调用超时"),

    /** Agent 执行超出预算：Agent 的步数/时间/token 预算耗尽（见 AgentBudget），强制终止防止无限循环烧钱 */
    AGENT_BUDGET_EXCEEDED(20005, "Agent 执行超出预算"),

    /* ==================== 系统 5xxxx ==================== */

    /** 系统内部错误：未被业务代码捕获的异常，兜底返回。真实异常细节只记日志不返回给前端（防止泄露内部信息） */
    SYSTEM_ERROR(50000, "系统内部错误"),

    /** 依赖服务不可用：Feign 调用其他微服务失败、模型 API 无法连接等 */
    UPSTREAM_UNAVAILABLE(50001, "依赖服务不可用");

    /** 业务码数值，写入响应体 R.code */
    private final int code;

    /** 默认提示文案，写入响应体 R.message */
    private final String message;

    /**
     * 枚举构造器：只能在枚举常量定义处调用（如 SUCCESS(0, "成功")），外部无法调用。
     * final 字段保证一旦创建就不可修改。
     */
    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
