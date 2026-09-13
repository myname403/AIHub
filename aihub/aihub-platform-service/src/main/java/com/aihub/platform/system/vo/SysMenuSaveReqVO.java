package com.aihub.platform.system.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 菜单新增/修改请求 VO（id 为空 = 新增；menuType 创建后不可改） */
@Data
public class SysMenuSaveReqVO {

    /** 修改时必传 */
    private Long id;

    @NotBlank(message = "菜单名称不能为空")
    private String name;

    /** 权限标识（按钮必填） */
    private String permission;

    /** M目录 C菜单 F按钮 */
    @NotBlank(message = "菜单类型不能为空")
    private String menuType;

    /** 父菜单 ID，0 = 根 */
    private Long parentId;

    private String path;
    private String component;
    private String icon;
    private Integer sort;
    private Integer visible;
    private Integer status;
}
