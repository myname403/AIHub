package com.aihub.platform.system.vo;

import lombok.Data;

/** 角色分页查询请求 VO */
@Data
public class SysRolePageReqVO {

    private long pageNo = 1;

    private long pageSize = 10;

    /** 关键词：模糊匹配角色名/编码 */
    private String keyword;
}
