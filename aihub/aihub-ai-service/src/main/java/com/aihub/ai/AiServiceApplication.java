package com.aihub.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI 服务（内聚）。
 *
 * <p>架构要求（07 号文档第 1 节）：
 * 模型网关、对话、RAG、工具、MCP、Agent、会话、产物 <b>全部在本服务内完成</b>，
 * 绝不拆分——以避免分布式事务、流式跨服务传递、Agent 长任务跨服务编排三大难题。
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.aihub.api")
@MapperScan("com.aihub.ai.infra.persistence.mapper")
@SpringBootApplication
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
