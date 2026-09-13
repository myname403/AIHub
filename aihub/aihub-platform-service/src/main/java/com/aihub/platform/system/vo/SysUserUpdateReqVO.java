package com.aihub.platform.system.vo;

import lombok.Data;

/** 修改用户请求 VO（用户名不可改；status 传 0 表示停用） */
@Data
public class SysUserUpdateReqVO {

    /** 昵称 */
    private String nickname;

    /** 1启用 0停用 */
    private Integer status;
}
