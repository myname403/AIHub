package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentBudgetExceededException;
import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.infra.ai.ChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Agent 基类（对齐课程 MyManus 的 BaseAgent：抽取公共逻辑，规范与复用）。
 *
 * <p>子类只需实现 execute()，LLM 调用、预算校验与步骤事件广播在此统一提供。
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
     */
    protected String llm(AgentTask task, String systemPrompt, String userPrompt) {
        checkBudget(task);
        ChatClient client = chatClientFactory.create(task.tenantId(), 0L, "agent-plan");
        ChatResponse response = client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
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

    /** 广播执行步骤（对齐课程 agent.step 事件） */
    protected void step(StreamSink sink, int[] index, String type, String content) {
        if (sink != null) {
            sink.emit(StreamEvent.of(index[0]++, StreamEvent.AGENT_STEP,
                    java.util.Map.of("agent", name(), "type", type, "content", content)));
        }
    }

    /** 统一的失败兜底：模型不可用时给出明确提示而非异常 */
    protected AgentResult fallback(AgentTask task, Exception e) {
        log.warn("Agent [{}] 执行失败: {}", name(), e.getMessage());
        return new AgentResult(AgentResult.FAILED,
                "任务执行失败（" + name() + "）：" + e.getMessage(), java.util.List.of());
    }
}
