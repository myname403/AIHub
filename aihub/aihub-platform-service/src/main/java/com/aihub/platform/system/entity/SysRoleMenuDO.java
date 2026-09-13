package com.aihub.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_role_menu 表数据对象：角色-菜单授权（本期新建）。
 *
 * <p>角色管理页的"菜单授权"弹窗：勾选菜单树保存 = 先删该角色旧关联、
 * 再批量插入新关联（整批替换，避免逐条 diff）。ROLE_ADMIN 角色在
 * 租户注册时被授权全部菜单。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenuDO {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色 ID */
    private Long roleId;

    /** 菜单 ID */
    private Long menuId;

    private LocalDateTime createTime;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;
}
