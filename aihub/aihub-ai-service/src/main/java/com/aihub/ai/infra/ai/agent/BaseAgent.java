package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.infra.ai.ChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Agent 基类（对齐课程 MyManus 的 BaseAgent：抽取公共逻辑，规范与复用）。
 *
 * <p>子类只需实现 execute()，LLM 调用与步骤事件广播在此统一提供。
 */
@Slf4j
public abstract class BaseAgent implements Agent {

    protected final ChatClientFactory chatClientFactory;

    protected BaseAgent(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    /**
     * LLM 调用（场景 agent-plan，走模型网关的场景选模与降级）。
     */
    protected String llm(Long tenantId, String systemPrompt, String userPrompt) {
        ChatClient client = chatClientFactory.create(tenantId, 0L, "agent-plan");
        String content = client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        return content == null ? "" : content.trim();
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
