package com.aihub.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_menu 表数据对象：菜单（平台全局，不带 tenant_id）。
 *
 * <p><b>menu_type 三种类型（芋道同款设计）：</b>
 * <ul>
 *   <li>M 目录：侧边栏的分组节点（如"系统管理"），无 component；</li>
 *   <li>C 菜单：实际页面（对应前端一个路由 + 组件），permission 一般为 xxx:query；</li>
 *   <li>F 按钮：页面内的操作按钮权限（如"用户新增"），permission 如 system:user:create，
 *       前端用它控制按钮显隐，后端用它做接口校验。</li>
 * </ul>
 *
 * <p>父子结构靠 parent_id 表达（0 = 根节点），前端递归渲染成侧边栏树。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Data
@TableName("sys_menu")
public class SysMenuDO {

    /** 菜单 ID。内置菜单用固定 ID（迁移脚本 V7），管理员自建的用雪花 ID */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 菜单名称 */
    private String name;

    /** 权限标识（如 system:user:create）。C 类型一般是 xxx:query，F 类型必填 */
    private String permission;

    /** 类型：M目录 C菜单 F按钮 */
    private String menuType;

    /** 父菜单 ID，0 = 根 */
    private Long parentId;

    /** 前端路由路径（如 /system/user） */
    private String path;

    /** 前端组件路径（如 system/user/index，管理端按约定映射到 views 目录） */
    private String component;

    /** 图标名（Element Plus 图标） */
    private String icon;

    /** 显示顺序（小在前） */
    private Integer sort;

    /** 1显示 0隐藏（隐藏的授权后不可见但仍可访问） */
    private Integer visible;

    /** 1启用 0停用 */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;
}
