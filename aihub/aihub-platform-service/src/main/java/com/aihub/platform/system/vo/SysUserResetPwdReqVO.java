package com.aihub.platform.system.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 重置密码请求 VO */
@Data
public class SysUserResetPwdReqVO {

    @NotBlank(message = "新密码不能为空")
    private String newPassword;
}
