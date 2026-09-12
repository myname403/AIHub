package com.aihub.common.exception;

import com.aihub.common.result.R;
import com.aihub.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 —— 整个服务的"异常兜底网"。
 *
 * <p><b>工作原理：</b>@RestControllerAdvice 让 Spring 把本类注册为所有 @RestController 的切面。
 * 任何 Controller / Service 抛出的异常，如果没有被业务代码捕获，最终都会进入本类
 * 匹配的 @ExceptionHandler 方法，由它决定返回什么。这样 Controller 里就不用写
 * try-catch，业务代码只需要"该抛就抛"（抛 BizException）。
 *
 * <p><b>匹配顺序：</b>Spring 按异常类型的"就近原则"匹配 —— 抛 BizException 走 handleBiz，
 * 抛参数校验异常走 handleValidate，其他一切异常走 handleException 兜底。
 *
 * <p><b>安全要求：</b>异常信息中不得出现密钥、完整 Prompt 等敏感内容；
 * 未知异常对前端只返回笼统的"系统内部错误"，详细信息仅写日志。
 *
 * <p><b>注意事项：</b>本类只能转换"HTTP 200 + 业务错误码"风格的响应；
 * 网关（WebFlux）过滤器里抛的异常不走这里，网关需要单独处理。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
@Slf4j   // Lombok：生成 private static final Logger log 字段，可直接 log.error(...)
@RestControllerAdvice   // = @ControllerAdvice + @ResponseBody：对所有 REST 控制器生效，且方法返回值自动转 JSON
public class GlobalExceptionHandler {

    /**
     * 处理业务异常：把 BizException 携带的业务码和文案原样转成统一响应体。
     * 这是用户可见的"预期错误"，用 log.warn 级别即可（不会真写，保持与其他处理器一致的静默透传）。
     */
    @ExceptionHandler(BizException.class)   // 声明本方法处理哪种异常；Spring 按类型就近匹配
    public R<Void> handleBiz(BizException e) {
        // 注意：这里不写日志是有意的 —— 业务异常属于正常业务分支（如密码错误），记 error 级别会污染告警
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常：@Valid 校验 DTO 失败（MethodArgumentNotValidException）
     * 或表单绑定失败（BindException）时触发。
     * 目标：把校验框架里定义的提示（如"用户名不能为空"）取出来给前端。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})   // 一个方法可同时声明多种异常
    public R<Void> handleValidate(Exception e) {
        // instanceof 模式匹配（Java 16+）：判断类型的同时直接强转为变量 m，省去先判断再强转两步
        String msg = e instanceof MethodArgumentNotValidException m
                ? m.getBindingResult().getFieldError() != null
                        ? m.getBindingResult().getFieldError().getDefaultMessage()   // 取第一个字段错误的自定义提示
                        : "参数校验失败"
                : "参数绑定失败";
        return R.fail(ResultCode.PARAM_ERROR, msg);
    }

    /**
     * 兜底处理：上面两类之外的任何异常（NPE、SQL 异常、模型调用失败……）都落到这里。
     * 完整堆栈只写日志（排障依据），给前端的永远是笼统的"系统内部错误"，
     * 避免把数据库表名、类名、模型地址等内部信息泄露出去。
     */
    @ExceptionHandler(Exception.class)   // Exception 是所有异常的父类，保证任何异常都有出口
    public R<Void> handleException(Exception e) {
        log.error("未处理异常", e);   // 第二个参数传异常对象，日志框架会打印完整堆栈
        return R.fail(ResultCode.SYSTEM_ERROR);
    }
}
