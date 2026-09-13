package com.aihub.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_role 表数据对象：角色。
 *
 * <p><b>与芋道模型的对齐点：</b>角色是租户内概念（带 tenant_id），
 * 每个租户注册后自动获得 ROLE_ADMIN（授权全部菜单）；
 * 管理员可再建自定义角色并勾选部分菜单 —— 子用户绑定该角色后，
 * 只能看到被授权的菜单和按钮（多租户下的权限隔离核心）。
 *
 * <p>code 编码约定：ROLE_ 前缀大写（如 ROLE_ADMIN、ROLE_OPERATOR），
 * code 在租户内唯一（索引见迁移脚本）。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Data
@TableName("sys_role")
public class SysRoleDO {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属租户（角色隔离维度） */
    private Long tenantId;

    /** 角色编码（如 ROLE_ADMIN），租户内唯一 */
    private String code;

    /** 角色名称（如 "管理员"、"运营"） */
    private String name;

    /** 显示顺序（小在前） */
    private Integer sort;

    /** 1启用 0停用 */
    private Integer status;

    /** 备注 */
    private String remark;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;
}
