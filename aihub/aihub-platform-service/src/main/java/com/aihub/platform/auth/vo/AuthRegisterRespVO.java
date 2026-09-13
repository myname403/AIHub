package com.aihub.platform.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 注册响应 VO */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthRegisterRespVO {

    /** 注册成功的租户码（登录时要用） */
    private String tenantCode;

    /** 管理员用户名 */
    private String username;

    /** 提示语 */
    private String notice;
}
