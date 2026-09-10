package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.AgentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册中心实现（对应课程 AgentFactory：按名查找、启动时自动注册）。
 */
@Slf4j
@Component
public class AgentRegistryImpl implements AgentRegistry {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    public AgentRegistryImpl(@Lazy List<Agent> allAgents) {
        for (Agent agent : allAgents) {
            agents.put(agent.name(), agent);
            log.info("Agent 注册: {} -> {}", agent.name(), agent.getClass().getSimpleName());
        }
    }

    @Override
    public void register(Agent agent) {
        agents.put(agent.name(), agent);
    }

    @Override
    public Optional<Agent> lookup(String name) {
        return Optional.ofNullable(agents.get(name));
    }
}
