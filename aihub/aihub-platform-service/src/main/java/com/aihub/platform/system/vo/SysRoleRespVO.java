package com.aihub.platform.system.vo;

import lombok.Data;

/** 角色响应 VO */
@Data
public class SysRoleRespVO {

    private Long id;
    private String code;
    private String name;
    private Integer sort;
    private Integer status;
    private String remark;
}
