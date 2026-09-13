package com.aihub.platform.system.vo;

import lombok.Data;

import java.util.List;

/** 用户分配角色请求 VO（整批替换） */
@Data
public class SysUserAssignRolesReqVO {

    private List<Long> roleIds;
}
