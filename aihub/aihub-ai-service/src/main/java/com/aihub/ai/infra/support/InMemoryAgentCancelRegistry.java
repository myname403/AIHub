package com.aihub.ai.infra.support;

import com.aihub.ai.domain.spi.AgentCancelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机版中断注册表。
 *
 * <p>多实例部署时替换为 Redis（key 带 TTL）实现即可，domain 侧无需改动。
 */
@Slf4j
@Component
public class InMemoryAgentCancelRegistry implements AgentCancelRegistry {

    private final Set<String> canceled = ConcurrentHashMap.newKeySet();

    @Override
    public void cancel(String conversationId) {
        if (conversationId == null) {
            return;
        }
        canceled.add(conversationId);
        log.info("Agent 任务收到取消信号 conv={}", conversationId);
    }

    @Override
    public boolean isCanceled(String conversationId) {
        return conversationId != null && canceled.contains(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId != null) {
            canceled.remove(conversationId);
        }
    }
}
