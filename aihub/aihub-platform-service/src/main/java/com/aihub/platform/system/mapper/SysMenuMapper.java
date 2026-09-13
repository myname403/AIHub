package com.aihub.platform.system.mapper;

import com.aihub.platform.system.entity.SysMenuDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 菜单表访问器（平台全局表）。树组装在 SysMenuService 完成。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
public interface SysMenuMapper extends BaseMapper<SysMenuDO> {
}
