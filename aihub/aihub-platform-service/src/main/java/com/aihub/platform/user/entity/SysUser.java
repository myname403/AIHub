package com.aihub.platform.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户。必须属于唯一租户。
 */
@Data
@TableName("sys_user")
public class SysUser {

    private Long id;
    private Long tenantId;
    private String username;
    /** BCrypt 加密存储，永不明文落库或出现在日志中 */
    private String passwordHash;
    private String nickname;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
