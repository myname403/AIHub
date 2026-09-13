package com.aihub.platform.system.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** 新增子用户请求 VO（Service 层负责密码强度等业务校验） */
@Data
public class SysUserCreateReqVO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 昵称，留空默认取用户名 */
    private String nickname;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 初始角色（可空） */
    private List<Long> roleIds;
}
