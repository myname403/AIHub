package com.aihub.platform.tenant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_tenant 表数据对象：租户（接入平台的企业/组织）。
 *
 * <p><b>租户是整个多租户体系的根</b>：所有业务表都以 tenant_id 作为隔离维度。
 * 用户属于租户（sys_user.tenant_id）、API Key 属于租户、配额策略属于租户……
 * 本表是"户口总账"，code（租户码）用于登录时定位租户。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Data
@TableName("sys_tenant")
public class SysTenant {

    /** 租户 ID（如 demo 租户=1001） */
    private Long id;

    /** 租户名称 */
    private String name;

    /** 租户码：登录时用户填的标识（AuthService.resolveTenantId 用它换 ID） */
    private String code;

    /** 1启用 0停用。停用的租户所有请求会被拒绝（QuotaService.checkTenant 校验） */
    private Integer status;

    /** 服务到期时间（null=长期） */
    private LocalDateTime expireAt;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
