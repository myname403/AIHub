package com.aihub.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 平台服务：租户、用户、登录、API Key、配额、审计。
 *
 * <p>架构约束：本服务独占 sys_* 与配额 / 审计 / 用量相关表，
 * AI 服务不得直接访问（见 07 号文档）。
 *
 * <p>本服务是 Feign <b>被调方</b>（AI 服务通过 aihub-api 的契约调用本服务的 /internal 接口），
 * 因此无需 @EnableFeignClients。
 */
@EnableDiscoveryClient
@SpringBootApplication
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
