package com.aihub.platform.system.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 角色新增/修改请求 VO（id 为空 = 新增；编码创建后不可改） */
@Data
public class SysRoleSaveReqVO {

    /** 修改时必传 */
    private Long id;

    @NotBlank(message = "角色编码不能为空")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    private String name;

    private Integer sort;

    private Integer status;

    private String remark;
}
