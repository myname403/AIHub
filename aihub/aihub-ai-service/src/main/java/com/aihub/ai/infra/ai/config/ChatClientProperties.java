package com.aihub.ai.infra.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatClient 装配参数（aihub.chat-client.*）。
 *
 * <p>热更新机制与 {@link AgentProperties} 相同（Nacos 变更 → 重绑定）。
 * 缓存 TTL 的生效方式是「惰性过期」：create() 每次都读当前 TTL 做过期判断，
 * 缩短 TTL 后最多等一个旧 TTL 周期，新配置即参与装配。
 */
@Data
@ConfigurationProperties(prefix = "aihub.chat-client")
public class ChatClientProperties {

    /** ChatClient 装配缓存 TTL（秒）；DB/Nacos 里的模型路由与系统提示词变更靠它轮换生效 */
    private long cacheSeconds = 60;
}
