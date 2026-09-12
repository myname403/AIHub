package com.aihub.platform.auth.controller;

import com.aihub.common.result.R;
import com.aihub.platform.auth.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 认证接口 —— 登录入口（全系统唯一"发令牌"的地方）。
 *
 * <p><b>请求链路说明：</b>前端 → 网关 8080 /auth/login → 路由到本服务。
 * /auth/** 在网关白名单里（登录前当然没有令牌），也在本服务的租户拦截器排除列表里
 * （登录时还不知道租户 ID，只带租户码 tenantCode）。
 *
 * <p><b>注解逐个解释：</b>
 * <ul>
 *   <li>{@code @RestController} = {@code @Controller} + {@code @ResponseBody}：
 *       声明 REST 控制器，所有方法返回值自动转 JSON（而不是跳转 JSP 页面）；</li>
 *   <li>{@code @RequestMapping("/auth")}：类级公共路径前缀，本类所有接口都以 /auth 开头；</li>
 *   <li>{@code @PostMapping("/login")}：方法级路径，POST /auth/login（登录必须 POST，
 *       密码不能出现在 URL 里——URL 会进访问日志）；</li>
 *   <li>{@code @RequestBody}：把请求体 JSON 自动反序列化成 LoginRequest 对象；</li>
 *   <li>{@code @RequiredArgsConstructor}（Lombok）：为所有 final 字段生成构造器，
 *       Spring 用它完成构造器注入 —— 依赖不可变（final）、便于单测，优于 @Autowired 字段注入。</li>
 * </ul>
 *
 * <p><b>返回数据：</b>token（前端存 localStorage，之后每次请求带
 * {@code Authorization: Bearer <token>}）、tenantId、userId、username。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 业务逻辑委托给 Service 层 —— Controller 只做"接参数、调服务、包响应"三件事 */
    private final AuthService authService;

    /**
     * 登录：校验租户码 + 用户名 + 密码，签发 JWT。
     *
     * <p>{@code @RequestBody}：JSON 请求体 → LoginRequest 对象；
     * 注意这里没有加 @Valid，字段校验在 Service 里兜底（历史设计，更规范做法是加 @Valid 由框架校验）。
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest request) {
        // 用 Map 而不是专门的 VO：登录返回的字段就是这几样且含义自明，暂不建类。
        // 更规范的做法是定义 LoginResponse record，方便 Swagger 文档展示字段说明。
        return R.ok(authService.login(request.getTenantCode(), request.getUsername(), request.getPassword()));
    }

    /**
     * 登录请求体。
     * <p>static 嵌套类：只服务于本 Controller 的请求/响应模型，不污染全局包结构。
     */
    @Data   // Lombok：生成 getter/setter（Jackson 反序列化需要 setter）
    public static class LoginRequest {
        /** 租户码，如 "demo"。@NotBlank 校验"非 null 且非空白"，校验失败由全局异常处理器转 PARAM_ERROR */
        @NotBlank(message = "租户标识不能为空")
        private String tenantCode;

        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }
}
