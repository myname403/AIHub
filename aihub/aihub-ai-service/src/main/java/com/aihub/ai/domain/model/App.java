package com.aihub.ai.domain.model;

import lombok.Data;

/**
 * 应用（助手）领域实体。
 *
 * <p>领域层约束：不依赖任何框架（Spring AI / MyBatis / Redis 均不可出现在 domain 包）。
 * Lombok 属于编译期代码生成，不会给字节码引入任何依赖，因此领域层可用。
 */
@Data
public class App {

    /** Agent 策略取值（与 ai_app.agent_strategy 保持一致） */
    public static final String STRATEGY_NONE = "none";
    /** 记忆策略取值 */
    public static final String MEMORY_WINDOW = "window";

    private Long id;
    private Long tenantId;
    private String name;
    /** none / react / plan_execute */
    private String agentStrategy;
    /** window / summary */
    private String memoryPolicy;
    private String systemPrompt;
    /** 绑定的场景路由（为空则走租户默认路由） */
    private Long modelRouteId;
    /** 采样温度 0.00 ~ 2.00 */
    private Double temperature;
    private Integer status;

    public boolean enabled() {
        return status != null && status == 1;
    }

    /**
     * 名称是否可用于落库：非空白且不超长。
     *
     * <p>放在领域层而不是 Controller 的注解里，是为了让「创建」与「更新」两条路径
     * 复用同一套规则——注解校验只在 web 层生效，绕过 web 直接调服务时会漏掉。
     */
    public boolean hasValidName() {
        return name != null && !name.isBlank() && name.length() <= 128;
    }

    /** 补齐缺省值：新建应用时前端不必传全量字段 */
    public void applyDefaults() {
        if (agentStrategy == null || agentStrategy.isBlank()) {
            agentStrategy = STRATEGY_NONE;
        }
        if (memoryPolicy == null || memoryPolicy.isBlank()) {
            memoryPolicy = MEMORY_WINDOW;
        }
        if (temperature == null) {
            temperature = 0.7d;
        }
        if (status == null) {
            status = 1;
        }
    }
}
