package com.aihub.common.exception;

import com.aihub.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常 —— 表示"业务规则不允许"的可预期错误，如密码错误、配额不足、跨租户访问。
 *
 * <p><b>继承 RuntimeException（非受检异常）的原因：</b>
 * Java 异常分两种：受检异常（如 IOException）必须在方法签名上 throws 或 try-catch，
 * 会让每一层业务代码都写满 try-catch；非受检异常可以一路向上抛，
 * 最终由 {@link GlobalExceptionHandler} 统一捕获转成统一响应体。业务异常99%都不需要中间层关心，所以选非受检。
 *
 * <p><b>标准用法：</b>
 * <pre>{@code
 * // 用法一：直接用枚举默认文案
 * throw new BizException(ResultCode.UNAUTHORIZED);
 * // 用法二：自定义文案（最常用）
 * throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
 * // 用法三：保留底层异常堆栈便于排查
 * throw new BizException(ResultCode.SYSTEM_ERROR, "加密失败", e);
 * }</pre>
 *
 * <p><b>注意事项：</b>
 * <ul>
 *   <li>异常 message 会原样返回给前端，绝不能拼接密钥、完整 Prompt、SQL 等敏感内容；</li>
 *   <li>不要在 catch 里"吞掉"BizException 后转成别的异常，会丢失业务码。</li>
 * </ul>
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
@Getter   // Lombok：生成 getCode() 方法，供全局异常处理器读取业务码
public class BizException extends RuntimeException {

    /** 业务码，取值来自 {@link ResultCode}，最终写入响应体 R.code */
    private final int code;

    /** 用枚举默认文案构造。适合提示信息就是枚举里那句话的场景 */
    public BizException(ResultCode resultCode) {
        // super(...) 把文案传给 RuntimeException 的 message 字段，getMessage() 即可取到
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /** 用自定义文案构造。最常用：错误码沿用枚举，但提示语需要更具体 */
    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /** 带原始异常构造。用于捕获底层异常（如加密失败、JSON 解析失败）后包装上抛，保留完整堆栈 */
    public BizException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.code = resultCode.getCode();
    }
}
