package com.aihub.platform.system.mapper;

import com.aihub.platform.system.entity.SysRoleMenuDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 角色-菜单授权访问器。菜单授权 = 删旧插新（整批替换）。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenuDO> {
}
