package com.aihub.common.result;

import com.aihub.common.trace.TraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体 —— 整个项目所有 HTTP 接口返回给前端的数据格式。
 *
 * <p><b>为什么需要它？</b>微服务架构下，前端可能同时调用网关、平台服务、AI 服务等多个接口。
 * 如果每个服务返回的数据格式不一样，前端就要写各种兼容逻辑。所以我们约定：
 * 任何一个接口，无论成功失败，都返回如下 JSON 结构：
 * <pre>{@code
 * {
 *   "code": 0,                  // 业务状态码：0 表示成功，非 0 表示失败（见 ResultCode 枚举）
 *   "message": "成功",           // 给人看的提示信息
 *   "data": {...},              // 真正的业务数据，失败时通常为 null
 *   "traceId": "a1b2c3d4..."    // 链路追踪 ID，排查问题时拿它去各个服务的日志里搜
 * }
 * }</pre>
 *
 * <p><b>泛型说明：</b>{@code R<T>} 中的 T 是业务数据的类型。比如登录接口返回
 * {@code R<Map<String,Object>>}（data 是一个 Map），查询用户列表返回 {@code R<List<SysUserDO>>}。
 * 泛型只在编译期生效，运行时会被"擦除"，不影响性能。
 *
 * <p><b>使用方式：</b>Controller 方法里不要手动 new 本类（构造器是 private 的），
 * 必须通过静态工厂方法 {@code R.ok(...)} / {@code R.fail(...)} 创建，
 * 这样能保证 traceId 一定被填充、状态码一定来自 ResultCode 枚举，避免各处手写不一致。
 *
 * <p>{@code traceId} 由工厂方法自动从 {@link TraceContext} 填充，
 * 无需调用方手工传参；不在请求线程内（如定时任务）时该字段为 null。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
@Data   // Lombok 注解：编译期自动生成所有字段的 getter/setter、toString、equals、hashCode，省去手写样板代码
@JsonInclude(JsonInclude.Include.NON_NULL)  // Jackson 序列化注解：转 JSON 时跳过值为 null 的字段，让响应更干净（如失败时不会出现 "data": null）
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;  // 序列化版本号：实现 Serializable 后建议显式声明，避免类升级后反序列化兼容性问题

    /** 业务状态码：0 成功；1xxxx 通用错误；2xxxx AI 能力错误；5xxxx 系统错误。完整定义见 {@link ResultCode} */
    private int code;

    /** 提示信息，直接展示给用户或前端开发者看 */
    private String message;

    /** 业务数据。成功时才有意义；失败时一般为 null（且不会出现在 JSON 里，见类上的 @JsonInclude） */
    private T data;

    /** 链路追踪 ID。网关的 TraceIdGlobalFilter 为每个请求生成，贯穿所有服务的日志，用于问题排查 */
    private String traceId;

    /**
     * 私有构造器：强制外部只能通过下面的静态工厂方法创建实例。
     * 这是"工厂模式"的典型应用 —— 把"怎么构造对象"收拢到一处，调用方只需要"要一个成功/失败的响应"。
     */
    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 统一出口：所有工厂方法都经此处填充链路 ID，避免漏填 */
    private static <T> R<T> build(int code, String message, T data) {
        R<T> result = new R<>(code, message, data);
        // TraceContext 基于 ThreadLocal 存储当前请求的 traceId。
        // 网关/拦截器在请求进来时放入，这里响应出去时取出 —— 同一个请求的全链路日志就能用同一个 ID 串联。
        result.setTraceId(TraceContext.get());
        return result;
    }

    /** 成功响应（不带数据）。适用于"操作成功但没有返回值"的场景，如删除、修改 */
    public static <T> R<T> ok() {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /** 成功响应（带数据）。Controller 里最常用的方法，把查询/计算结果包进 data 字段 */
    public static <T> R<T> ok(T data) {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 失败响应：message 使用枚举里预设的文案。适用于不需要自定义提示的错误 */
    public static <T> R<T> fail(ResultCode code) {
        return build(code.getCode(), code.getMessage(), null);
    }

    /** 失败响应：用自定义文案覆盖枚举默认提示。适用于需要给出具体原因的场景（如"用户名或密码错误"） */
    public static <T> R<T> fail(ResultCode code, String message) {
        return build(code.getCode(), message, null);
    }

    /** 保留原始业务码（用于 BizException 透传）：全局异常处理器捕获业务异常后，原样返回异常里携带的 code 和 message */
    public static <T> R<T> fail(int code, String message) {
        return build(code, message, null);
    }

    /** 便捷判断：前端/其他服务常用 {@code if (r.isSuccess())} 代替 {@code if (r.getCode() == 0)}，可读性更好 */
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
