package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;

import java.util.Optional;

/**
 * Agent 注册中心（对应课程 MyManus 的 AgentFactory：按名查找）。
 */
public interface AgentRegistry {

    void register(Agent agent);

    Optional<Agent> lookup(String name);
}
