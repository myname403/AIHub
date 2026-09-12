package com.aihub.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关服务启动类 —— 整个系统的唯一入口（端口 8080）。
 *
 * <p><b>为什么需要网关？</b>没有网关时，前端要记住每个服务的地址（平台 8081、AI 8082...），
 * 而且每个服务都要自己做鉴权。有了网关：
 * <pre>
 *   前端 → 网关(8080) → 按 URL 前缀路由到对应服务
 *          ├── 鉴权：JWT / API Key 统一在这里校验一次
 *          ├── 限流：Sentinel 拦住洪峰
 *          └── 链路：给每个请求发 TraceId
 * </pre>
 * 下游服务只信任网关写进请求头的身份信息（X-Tenant-Id 等），自己不再重复鉴权。
 *
 * <p><b>本服务的技术底座是 WebFlux（响应式）而非传统 MVC：</b>
 * 网关要同时维持海量并发连接，传统"一个请求占一个线程"的模式线程开销太大；
 * 响应式用少量线程 + 事件回调处理所有请求。所以本服务的代码里到处是
 * {@code Mono} / {@code Flux}（见 AuthGlobalFilter），与下游 MVC 服务写法不同。
 *
 * <p>注解说明：
 * <ul>
 *   <li>{@code @SpringBootApplication} = 三个注解合一：
 *       {@code @SpringBootConfiguration}（本类是配置类）+
 *       {@code @EnableAutoConfiguration}（按 classpath 自动装配，如发现 webflux 就配 WebFlux 服务器）+
 *       {@code @ComponentScan}（扫描本包及子包的组件）。
 *       <b>所以启动类必须放在包结构根上</b>（com.aihub.gateway），否则组件扫不全；</li>
 *   <li>{@code @EnableDiscoveryClient}：启用服务注册发现 —— 启动后把自己注册到 Nacos，
 *       也能按服务名发现其他服务。Spring Cloud 2020+ 后该注解其实可省略（classpath 有 Nacos 就自动生效），
 *       但显式写出语义更清晰。</li>
 * </ul>
 *
 * <p>main 方法是整个 JVM 的入口：SpringApplication.run 会启动内嵌服务器（Netty）、
 * 加载 application.yml 配置、初始化所有 Bean，然后监听 8080 端口。
 * 详见学习文档《03-网关-aihub-gateway.md》。
 */
@EnableDiscoveryClient
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        // 固定写法：run() 内部完成"创建 Spring 容器 → 自动装配 → 启动服务器"三步
        SpringApplication.run(GatewayApplication.class, args);
    }
}
