package com.aihub.platform.auth.controller;

import com.aihub.common.result.R;
import com.aihub.platform.auth.service.AuthService;
import com.aihub.platform.auth.service.CaptchaService;
import com.aihub.platform.auth.vo.AuthCaptchaRespVO;
import com.aihub.platform.auth.vo.AuthLoginReqVO;
import com.aihub.platform.auth.vo.AuthLoginRespVO;
import com.aihub.platform.auth.vo.AuthRegisterReqVO;
import com.aihub.platform.auth.vo.AuthRegisterRespVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口 —— 登录 + 注册入口（全系统唯一"发令牌"的地方）。
 *
 * <p><b>请求链路说明：</b>前端 → 网关 8080 /auth/** → 路由到本服务。
 * /auth/** 在网关白名单里（登录/注册前当然没有令牌），也在本服务的租户拦截器排除列表里
 * （登录时还不知道租户 ID，只带租户码 tenantCode；注册是在创建新租户）。
 *
 * <p><b>VO 分层（工程化约定）：</b>本类只收发 vo 包下的请求/响应 VO——
 * 入参校验注解（@NotBlank）写在 VO 字段上，配合 @Valid 触发；
 * 响应 VO 字段按前端需要裁剪。业务转换在 AuthService。
 *
 * <p>详见学习文档《04-平台服务》《07-RBAC与管理端.md》。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 业务逻辑委托给 Service 层 —— Controller 只做"接 VO、调服务、包响应"三件事 */
    private final AuthService authService;

    /** 图形验证码（登录/注册防刷） */
    private final CaptchaService captchaService;

    /**
     * 获取图形验证码：返回 {id, imageBase64}。
     * 前端把 base64 直接放进 <img> 展示，提交时回传 id + 用户输入。
     */
    @GetMapping("/captcha")
    public R<AuthCaptchaRespVO> captcha() {
        CaptchaService.CaptchaImage image = captchaService.generate();
        return R.ok(new AuthCaptchaRespVO(image.id(), image.imageBase64()));
    }

    /**
     * 自助注册：创建新租户 + 管理员账号 + ROLE_ADMIN 角色（单事务）。
     * 成功后前端跳登录页，用 租户码 + 用户名 + 密码 登录。
     */
    @PostMapping("/register")
    public R<AuthRegisterRespVO> register(@Valid @RequestBody AuthRegisterReqVO reqVO) {
        return R.ok(authService.register(reqVO));
    }

    /** 登录：校验验证码 + 租户码 + 用户名 + 密码，签发 JWT。 */
    @PostMapping("/login")
    public R<AuthLoginRespVO> login(@Valid @RequestBody AuthLoginReqVO reqVO) {
        return R.ok(authService.login(reqVO));
    }
}
