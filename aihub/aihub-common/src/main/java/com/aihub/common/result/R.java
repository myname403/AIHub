package com.aihub.common.result;

import com.aihub.common.trace.TraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>{@code traceId} 由工厂方法自动从 {@link TraceContext} 填充，
 * 无需调用方手工传参；不在请求线程内（如定时任务）时该字段为 null。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private String traceId;

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 统一出口：所有工厂方法都经此处填充链路 ID，避免漏填 */
    private static <T> R<T> build(int code, String message, T data) {
        R<T> result = new R<>(code, message, data);
        result.setTraceId(TraceContext.get());
        return result;
    }

    public static <T> R<T> ok() {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> R<T> ok(T data) {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> R<T> fail(ResultCode code) {
        return build(code.getCode(), code.getMessage(), null);
    }

    public static <T> R<T> fail(ResultCode code, String message) {
        return build(code.getCode(), message, null);
    }

    /** 保留原始业务码（用于 BizException 透传） */
    public static <T> R<T> fail(int code, String message) {
        return build(code, message, null);
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
