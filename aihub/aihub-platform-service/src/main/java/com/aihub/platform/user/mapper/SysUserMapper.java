package com.aihub.platform.user.mapper;

import com.aihub.platform.user.entity.SysUserDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper。
 *
 * <p>租户隔离：查询方法统一带 tenantId 条件；
 * 另有 MyBatis 拦截器兜底（防止遗漏）。
 *
 * <p><b>@Select 注解 SQL 写法：</b>简单 SQL 直接用注解写在接口方法上
 * （复杂 SQL 建议用 XML 或 MP 条件构造器）。#{param} 是预编译占位符
 * （参数安全注入，防 SQL 注入；与 ${} 的字符串拼接有本质区别，永不用 ${} 拼用户输入）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
public interface SysUserMapper extends BaseMapper<SysUserDO> {

    /**
     * 按 租户+用户名 查用户（登录用）。
     * deleted=0 手动带逻辑删除条件：@Select 原生 SQL 不会自动加 @TableLogic 过滤。
     */
    @Select("select * from sys_user where tenant_id = #{tenantId} and username = #{username} and deleted = 0 limit 1")
    SysUserDO selectByTenantAndUsername(Long tenantId, String username);
}
