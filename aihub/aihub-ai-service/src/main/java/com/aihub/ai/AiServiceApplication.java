package com.aihub.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * AI 服务启动类 —— 整个产品的"能力核心"（端口 8082）。
 *
 * <p><b>为什么这个服务这么"胖"？</b>架构要求（docs/07 第 1 节）：
 * 模型网关、对话、RAG、工具、MCP、Agent、会话、产物 <b>全部在本服务内完成</b>，
 * 绝不拆分——避免三大分布式难题：
 * ① 流式响应跨服务传递（SSE 断了算谁的）；② Agent 长任务跨服务编排（状态放哪）；
 * ③ 分布式事务（扣配额和写对话记录怎么保证一致）。
 * 这是"模块化单体思想在微服务中的应用"：边界按包划分，进程保持一个。
 *
 * <p><b>内部采用 DDD 风格分层（学习本服务先看懂这个结构）：</b>
 * <pre>
 *   web/          接口层：Controller、SSE/NDJSON/WS 三种流式通道
 *      ↓ 只依赖
 *   application/  编排层：ChatAppService 等（只依赖 domain）
 *      ↓ 只依赖
 *   domain/       领域层：model（值对象）+ spi（接口，"我要什么"）
 *      ↑ 实现
 *   infra/        基础设施：Spring AI 实现、MySQL/Redis 持久化、浏览器驱动
 * </pre>
 * 依赖方向永远朝内（web→application→domain←infra），由 ArchUnit 架构测试强制卡口
 * （见测试类 ArchitectureTest）——谁违反分层，测试直接红。
 *
 * <p><b>三个启动注解：</b>
 * <ul>
 *   <li>{@code @EnableDiscoveryClient}：注册到 Nacos（网关靠 aihub-ai-service 这个名字路由过来）；</li>
 *   <li>{@code @EnableFeignClients(basePackages = "com.aihub.api")}：扫描并激活 Feign 契约接口
 *       （PlatformClient），AI 服务要调平台服务扣配额。basePackages 限定扫描范围，
 *       避免误扫无关包；</li>
 *   <li>{@code @MapperScan("com.aihub.ai.infra.persistence.mapper")}：把该包下所有 Mapper 接口
 *       注册为 MyBatis 代理 Bean —— Mapper 是接口没有实现类，MyBatis 运行时动态代理生成
 *       实现（这就是为什么 Mapper 不写实现却能注入）。</li>
 * </ul>
 *
 * <p>详见学习文档《05-AI服务-aihub-ai-service.md》。
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
