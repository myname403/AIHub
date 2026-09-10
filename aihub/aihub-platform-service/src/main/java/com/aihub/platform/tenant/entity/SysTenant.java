package com.aihub.platform.tenant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户。所有业务表都以 tenant_id 作为隔离维度。
 */
@Data
@TableName("sys_tenant")
public class SysTenant {

    private Long id;
    private String name;
    private String code;
    private Integer status;
    private LocalDateTime expireAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
