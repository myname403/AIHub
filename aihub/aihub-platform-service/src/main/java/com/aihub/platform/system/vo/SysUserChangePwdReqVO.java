package com.aihub.platform.system.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 修改自己密码请求 VO（需旧密码验证，与管理员重置不同） */
@Data
public class SysUserChangePwdReqVO {

    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
