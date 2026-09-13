package com.aihub.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_user_role 表数据对象：用户-角色关联（V1 已建表，M0 占位未使用，本期开始启用）。
 *
 * <p>一个用户可绑多个角色，权限取并集（芋道同款）。
 * 管理端"用户管理 → 分配角色"操作的就是这张表。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Data
@TableName("sys_user_role")
public class SysUserRoleDO {

    /** 主键（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户 ID（冗余存储，便于按租户级联清理与查询） */
    private Long tenantId;

    /** 用户 ID */
    private Long userId;

    /** 角色 ID */
    private Long roleId;

    private LocalDateTime createTime;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;
}
