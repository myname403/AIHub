package com.aihub.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密配置：统一提供 BCrypt 的 PasswordEncoder Bean。
 *
 * <p><b>为什么要做成 Bean 而不是各 Service 自己 new：</b>
 * ① 单例复用，避免重复创建；② 全局只有一种加密实现——将来升级加密算法
 * （如换 argon2）只改这一处；③ 测试时可注入 Mock。
 * 这正是"配置归配置、业务归业务"的工程化约定。
 */
@Configuration
public class CryptoConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
