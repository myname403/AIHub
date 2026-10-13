package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentBudgetExceededException;
import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.SequencedSink;
import com.aihub.ai.domain.model.StreamBridge;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.infra.ai.AiCallContext;
import com.aihub.ai.infra.ai.ChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Agent 基类（对齐课程 MyManus 的 BaseAgent：抽取公共逻辑，规范与复用）。
 *
 * <p><b>模板方法模式（设计模式必学）：</b>abstract 类定义"骨架"——
 * LLM 调用（llm）、预算校验（checkBudget）、步骤广播（step）、失败兜底（fallback）
 * 全在这里统一实现；子类（Planning/Table/Chart/Html/Browser/Generation）
 * 只需实现 execute() 填"具体怎么干"。新增一种 Agent = 一个几十行的子类。
 *
 * <p><b>abstract 关键字：</b>本类不能被实例化（new BaseAgent() 编译错），
 * 只能被继承；abstract 方法（来自 Agent 接口的 execute）强制子类实现。
 *
 * <p>子类只需实现 execute()，LLM 调用、预算校验与步骤事件广播在此统一提供。
 * 详见学习文档《05-AI服务-aihub-ai-service.md》。
 */
@Slf4j
public abstract class BaseAgent implements Agent {

    protected final ChatClientFactory chatClientFactory;

    protected BaseAgent(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    /** LLM 调用（无预算场景，如调试或工具内部） */
    protected String llm(Long tenantId, String systemPrompt, String userPrompt) {
        return llm(new AgentTask(tenantId, null, 0L, null, userPrompt), systemPrompt, userPrompt);
    }

    /**
     * LLM 调用（场景 agent-plan，走模型网关的场景选模与降级）。
     *
     * <p>调用前后都会校验预算：超时或 Token 超限时抛 {@link AgentBudgetExceededException}，
     * 由上层 Agent 转成 {@link AgentResult#BUDGET_EXCEEDED}，实现三重预算中的两项。
     *
     * <p>Agent 链路不经过 SpringAiChatExecutor，AiCallContext 不会被设置；
     * 不补设置的话，模型自主调用的工具（知识检索/审计）拿不到租户上下文，
     * 表现为"无法检索知识库"+ 工具审计落库失败。
     *
     * <p><b>工具事件双通道：</b>
     * ThreadLocal 通道由编排层（ChatAppService）在 Agent 执行前统一 set、结束后 clear，
     * 本方法不重复设置（单一职责：谁发起会话谁管桥的生命周期）；
     * {@code .toolContext(...)} 是 prompt 级跨线程主通道——即使某天调用改为流式/异步
     * （工具执行换线程），ToolContext 仍能把桥送达工具回调。
     * 桥存在时顺带把实际使用的模型编码记到桥上，供 PlanningAgent 汇总进 AgentResult。
     */
    protected String llm(AgentTask task, String systemPrompt, String userPrompt) {
        checkBudget(task);
        ChatClient client = chatClientFactory.create(task.tenantId(), 0L, "agent-plan");
        AiCallContext.set(task.tenantId(), task.appId(), "agent-plan", task.conversationId());
        if (StreamBridge.get() != null) {
            // resolvedModelCode 读工厂缓存——create 刚执行过，必命中
            StreamBridge.setModel(chatClientFactory.resolvedModelCode(task.tenantId(), 0L, "agent-plan"));
        }
        try {
            ChatResponse response = client.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    // prompt 级 ToolContext：跨线程主通道（工具执行线程拿到的仍是本会话的桥）
                    .toolContext(StreamBridge.toolContextMap())
                    .call()
                    .chatResponse();

            if (task.budget() != null && response != null && response.getMetadata() != null
                    && response.getMetadata().getUsage() != null) {
                task.budget().addTokens(response.getMetadata().getUsage().getPromptTokens(),
                        response.getMetadata().getUsage().getCompletionTokens());
            }
            checkBudget(task);

            String content = response == null || response.getResult() == null
                    ? "" : response.getResult().getOutput().getText();
            return content == null ? "" : content.trim();
        } finally {
            AiCallContext.clear();
        }
    }

    private void checkBudget(AgentTask task) {
        if (task == null || task.budget() == null) {
            return;
        }
        if (task.budget().timeout()) {
            throw new AgentBudgetExceededException(
                    "任务超时（上限 " + task.budget().timeoutMs() + "ms）");
        }
        if (task.budget().tokenExceeded()) {
            throw new AgentBudgetExceededException(
                    "Token 预算耗尽（已用 " + task.budget().usedTokens() + "）");
        }
    }

    /**
     * 广播执行步骤（对齐课程 agent.step 事件）。
     *
     * <p><b>两个序号的分工（容易混淆）：</b>{@code index[0]} 是<b>落库用</b>的
     * Agent 内步骤计数（ai_agent_task_step.step_index，各 Agent 独立从 0 起）；
     * 事件帧的 index 由 {@link SequencedSink} 全局统一编号（前端去重/排序依据），
     * 这里不再手工管理——传什么占位值都会被重编号覆盖。
     */
    protected void step(SequencedSink sink, int[] index, String type, String content) {
        if (sink != null) {
            sink.emit(StreamEvent.AGENT_STEP,
                    java.util.Map.of("agent", name(), "type", type, "content", content));
        }
        index[0]++;
    }

    /** 统一的失败兜底：模型不可用时给出明确提示而非异常 */
    protected AgentResult fallback(AgentTask task, Exception e) {
        log.warn("Agent [{}] 执行失败: {}", name(), e.getMessage());
        return new AgentResult(AgentResult.FAILED,
                "任务执行失败（" + name() + "）：" + e.getMessage(), java.util.List.of());
    }
}
