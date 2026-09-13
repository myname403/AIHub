package com.aihub.platform.system.vo;

import lombok.Data;

import java.util.List;

/** 角色菜单授权请求 VO（整批替换，含半选父节点） */
@Data
public class SysRoleAssignMenusReqVO {

    private List<Long> menuIds;
}
