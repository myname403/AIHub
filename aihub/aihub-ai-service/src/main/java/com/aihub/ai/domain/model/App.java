package com.aihub.ai.domain.model;

/**
 * 应用（助手）领域实体。
 *
 * <p>领域层约束：不依赖任何框架（Spring AI / MyBatis / Redis 均不可出现在 domain 包）。
 */
public class App {

    private Long id;
    private Long tenantId;
    private String name;
    /** none / react / plan_execute */
    private String agentStrategy;
    /** window / summary */
    private String memoryPolicy;
    private String systemPrompt;
    private Integer status;

    public boolean enabled() {
        return status != null && status == 1;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAgentStrategy() {
        return agentStrategy;
    }

    public void setAgentStrategy(String agentStrategy) {
        this.agentStrategy = agentStrategy;
    }

    public String getMemoryPolicy() {
        return memoryPolicy;
    }

    public void setMemoryPolicy(String memoryPolicy) {
        this.memoryPolicy = memoryPolicy;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
