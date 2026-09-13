package com.aihub.platform.system.vo;

import lombok.Data;

/** 用户分页查询请求 VO */
@Data
public class SysUserPageReqVO {

    /** 页码，从 1 开始 */
    private long pageNo = 1;

    /** 每页条数 */
    private long pageSize = 10;

    /** 关键词：模糊匹配用户名/昵称 */
    private String keyword;
}
