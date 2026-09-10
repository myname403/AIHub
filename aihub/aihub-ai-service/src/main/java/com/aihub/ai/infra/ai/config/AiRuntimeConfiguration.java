package com.aihub.ai.infra.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * infra.ai 动态配置注册入口：集中登记需要 Nacos 热更新的属性 bean。
 *
 * <p>工作原理：服务通过 {@code spring.config.import: optional:nacos:} 拉取 Nacos 配置；
 * Nacos 上的配置变更会触发 RefreshEvent → spring-cloud-context 发布
 * EnvironmentChangeEvent → ConfigurationPropertiesRebinder 把这里登记的
 * 属性 bean 整体重绑定。各使用方（Agent / 执行器 / 工厂）在调用时读属性，
 * 因此新值下一次请求即生效，无需重启。
 *
 * <p>登记的属性（均可热更新）：
 * <ul>
 *   <li>{@link AgentProperties} — aihub.agent.*（Agent 三重预算）</li>
 *   <li>{@link RagProperties} — aihub.rag.top-k / similarity-threshold（检索参数）</li>
 *   <li>{@link ChatClientProperties} — aihub.chat-client.cache-seconds（装配缓存 TTL）</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentProperties.class, RagProperties.class, ChatClientProperties.class})
public class AiRuntimeConfiguration {
}
