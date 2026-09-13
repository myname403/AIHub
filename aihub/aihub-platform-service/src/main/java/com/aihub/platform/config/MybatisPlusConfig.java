package com.aihub.platform.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：注册分页插件。
 *
 * <p><b>为什么必须配：</b>MP 的 selectPage() 分两个动作——生成 LIMIT 子句（由本插件负责）
 * 和执行 COUNT 查询（也是本插件负责）。不配这个插件，selectPage 会退化成"查全表"，
 * 分页参数被静默忽略（经典的 MP 坑，本章学习文档有展开）。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定数据库类型：分页方言按 MySQL 生成 LIMIT offset,size
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
