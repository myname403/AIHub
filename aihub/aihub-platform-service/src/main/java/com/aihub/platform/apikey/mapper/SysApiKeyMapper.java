package com.aihub.platform.apikey.mapper;

import com.aihub.platform.apikey.entity.SysApiKeyDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * sys_api_key 表访问器（Mapper = DAO 层）。
 *
 * <p><b>为什么是空的却能用？</b>MyBatis-Plus 的 BaseMapper 泛型接口已经内置了
 * 单表 CRUD 的全部方法（insert / selectById / selectOne / selectList / update / deleteById...），
 * 方法参数是 Wrappers 条件构造器或主键 —— 不用写一行 SQL 就有完整 DAO。
 * 需要复杂 SQL（多表、聚合、特殊语法）时才在本接口里加 @Select 等注解方法或 XML。
 *
 * <p><b>生效条件：</b>启动类或配置类上要有 @MapperScan（本项目在 application.yml 用
 * mybatis-plus 配置 + 启动类扫描），Mapper 接口无需 @Mapper 注解逐个标注。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
public interface SysApiKeyMapper extends BaseMapper<SysApiKeyDO> {
}
