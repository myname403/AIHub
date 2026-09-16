package com.aihub.ai.infra.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 三重预算参数（aihub.agent.*，见 06 号文档 R6）。
 *
 * <p>为什么从 {@code @Value} 改为 {@code @ConfigurationProperties}：
 * {@code @Value} 字段只在启动时绑定一次，Nacos 推送配置变更后不会回填；
 * {@code @ConfigurationProperties} bean 会被 spring-cloud-context 的
 * {@code ConfigurationPropertiesRebinder} 在收到 EnvironmentChangeEvent
 * （Nacos 配置变更触发 RefreshEvent）时整体重新绑定——Nacos 上改完配置，
 * 下一次 Agent 执行读到的就是新值，全程无需重启。
 *
 * <p>因此本类必须保持「可变 JavaBean」风格：record / 构造器绑定的不可变对象
 * 无法被重绑定（rebind 走的是 setter），写成不可变类会静默失去热更新能力。
 */
@Data
@ConfigurationProperties(prefix = "aihub.agent")
public class AgentProperties {

    /**
     * PlanningAgent 的最大子任务数（规划层语义：一个目标最多拆成几步）。
     *
     * <p><b>注意不要与浏览器步数混淆</b>：本值<b>不</b>约束 BrowserAgent 的 ReAct 循环步数，
     * 那是 {@code aihub.agent.browser.max-steps} 的职责。两者是不同维度的概念，
     * 早期版本让浏览器 Agent 沿用了本值，导致 max-steps 配置不生效（已修正）。
     */
    private int maxSubTasks = 3;

    /** 单任务 Token 预算上限，<=0 表示不限制 */
    private long maxTokens = 0;

    /** 单任务墙钟超时（毫秒） */
    private long timeoutMs = 180000;

    private final Browser browser = new Browser();

    /** 浏览器 Agent 独有的步数预算（aihub.agent.browser.max-steps） */
    @Data
    public static class Browser {

        /** 浏览器 Agent 单任务最大决策步数 */
        private int maxSteps = 8;
    }
}
