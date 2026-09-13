package com.aihub.platform.system.mapper;

import com.aihub.platform.system.entity.SysRoleDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 角色表访问器。单表 CRUD 由 MyBatis-Plus BaseMapper 提供，
 * 条件构造见 SysRoleService（按 tenantId 过滤是硬性要求）。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
public interface SysRoleMapper extends BaseMapper<SysRoleDO> {
}
