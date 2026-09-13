package com.aihub.platform.auth.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 注册请求 VO：创建新租户 + 管理员账号 + ROLE_ADMIN 角色（单事务） */
@Data
public class AuthRegisterReqVO {

    /** 企业名称（将成为租户名） */
    @NotBlank(message = "企业名称不能为空")
    private String companyName;

    /** 租户码：注册后登录时要填的"公司标识"，全局唯一 */
    @NotBlank(message = "租户码不能为空")
    private String tenantCode;

    /** 管理员用户名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 管理员密码（6-64 位） */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 图形验证码 ID（GET /auth/captcha 返回） */
    private String captchaId;

    /** 用户输入的验证码答案 */
    private String captchaCode;
}
