package com.aihub.platform.system.mapper;

import com.aihub.platform.system.entity.SysUserRoleDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 用户-角色关联访问器。分配角色 = 删旧插新（整批替换）。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
public interface SysUserRoleMapper extends BaseMapper<SysUserRoleDO> {
}
