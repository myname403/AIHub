package com.aihub.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 平台服务启动类 —— 管理端"账号与账单"的中枢（端口 8081）。
 *
 * <p><b>本服务的四大职责：</b>
 * <ol>
 *   <li>登录认证：验密码、发 JWT（网关只验不发）；</li>
 *   <li>租户/用户管理：多租户体系的"户口本"；</li>
 *   <li>API Key 管理：签发/校验/吊销开放密钥；</li>
 *   <li>配额与用量：扣减配额（写路径）+ 用量看板（读路径）。</li>
 * </ol>
 *
 * <p><b>架构约束：</b>本服务独占 sys_* 与配额 / 审计 / 用量相关表，
 * AI 服务不得直接访问（微服务"数据自治"原则：每个服务的数据只有自己能碰）。
 *
 * <p>本服务是 Feign <b>被调方</b>（AI 服务通过 aihub-api 的契约调用本服务的 /internal 接口），
 * 因此无需 @EnableFeignClients —— 只有"要调用别人"的服务才需要启用 Feign 客户端。
 *
 * <p>注解说明：
 * <ul>
 *   <li>{@code @SpringBootApplication}：三合一（配置类 + 自动装配 + 组件扫描），
 *       启动类必须放在 com.aihub.platform 包根，否则子包组件扫不到；</li>
 *   <li>{@code @EnableDiscoveryClient}：把自己注册到 Nacos（服务名 aihub-platform-service），
 *       网关和 AI 服务靠这个名字找到本服务。</li>
 * </ul>
 *
 * <p>与网关不同，本服务是传统 Spring MVC（Tomcat 阻塞模型）——
 * 业务服务请求量可控，阻塞模型代码更简单直观。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@EnableDiscoveryClient
// 本服务的 Mapper 接口未标 @Mapper，必须显式扫描注册（漏掉会报
// "required a bean of type '...Mapper' that could not be found"）
@MapperScan({"com.aihub.platform.user.mapper",
        "com.aihub.platform.apikey.mapper",
        "com.aihub.platform.quota.mapper",
        "com.aihub.platform.system.mapper"})
// scanBasePackages 扩大到 com.aihub：让 common 模块的 GlobalExceptionHandler 也被扫描注册，
// 否则 BizException 会变成原生 500 而不是 {"code":10001,...} 的统一响应体
@SpringBootApplication(scanBasePackages = "com.aihub")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
