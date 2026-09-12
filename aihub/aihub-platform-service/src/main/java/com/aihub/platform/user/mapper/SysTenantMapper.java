package com.aihub.platform.user.mapper;

import com.aihub.platform.tenant.entity.SysTenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 租户 Mapper —— 空接口，单表 CRUD 全部由 MyBatis-Plus BaseMapper 提供
 * （selectById 查租户、insert 建租户等），无需手写 SQL。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
public interface SysTenantMapper extends BaseMapper<SysTenant> {
}
