package com.aihub.platform.user.mapper;

import com.aihub.platform.user.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper。
 *
 * <p>租户隔离：查询方法统一带 tenantId 条件；
 * 另有 MyBatis 拦截器兜底（防止遗漏）。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("select * from sys_user where tenant_id = #{tenantId} and username = #{username} and deleted = 0 limit 1")
    SysUser selectByTenantAndUsername(Long tenantId, String username);
}
