package com.aihub.platform.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户响应 VO —— DO 永不出持久层的示范：
 * 与 SysUserDO 相比<b>刻意没有</b> passwordHash 字段（敏感信息靠分层而不是"记得置空"保证不出门）。
 */
@Data
public class SysUserRespVO {

    private Long id;
    private Long tenantId;
    private String username;
    private String nickname;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
