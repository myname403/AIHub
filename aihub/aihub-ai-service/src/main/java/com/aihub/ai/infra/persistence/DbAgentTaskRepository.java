package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.spi.AgentTaskRepository;
import com.aihub.ai.infra.persistence.do_.AiAgentStepDO;
import com.aihub.ai.infra.persistence.do_.AiAgentTaskDO;
import com.aihub.ai.infra.persistence.mapper.AiAgentStepMapper;
import com.aihub.ai.infra.persistence.mapper.AiAgentTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 任务落库实现。
 *
 * <p>审计是旁路能力：任何一次写失败都只记日志，绝不打断 Agent 执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbAgentTaskRepository implements AgentTaskRepository {

    private static final Map<String, Integer> STATUS_CODE = Map.of(
            "done", 1, "budget_exceeded", 2, "canceled", 3, "failed", 4);

    private static final int MAX_ANSWER = 8000;

    private final AiAgentTaskMapper taskMapper;
    private final AiAgentStepMapper stepMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Long create(AgentTask task, String strategy) {
        try {
            AiAgentTaskDO entity = new AiAgentTaskDO();
            entity.setTenantId(task.tenantId());
            entity.setAppId(task.appId());
            entity.setUserId(task.userId());
            entity.setConversationId(task.conversationId());
            entity.setGoal(task.goal());
            entity.setStrategy(strategy);
            entity.setStatus(0);
            entity.setCurrentStep(0);
            entity.setStartedAt(LocalDateTime.now());
            if (task.budget() != null) {
                entity.setBudgetJson(objectMapper.writeValueAsString(Map.of(
                        "maxSubTasks", task.budget().maxSubTasks(),
                        "maxTokens", task.budget().maxTokens(),
                        "timeoutMs", task.budget().timeoutMs())));
            }
            taskMapper.insert(entity);
            return entity.getId();
        } catch (Exception e) {
            log.warn("Agent 任务落库失败 conv={} err={}", task.conversationId(), e.getMessage());
            return null;
        }
    }

    @Override
    public void appendStep(Long tenantId, Long taskId, int seq, String type, String agentName,
                           String content, long costMs) {
        if (taskId == null) {
            return;
        }
        try {
            AiAgentStepDO step = new AiAgentStepDO();
            step.setTenantId(tenantId);
            step.setTaskId(taskId);
            step.setSeq(seq);
            step.setType(type);
            step.setAgentName(agentName);
            step.setContentJson(objectMapper.writeValueAsString(Map.of("content", content)));
            step.setCostMs(costMs);
            stepMapper.insert(step);
        } catch (Exception e) {
            log.warn("Agent 步骤落库失败 task={} seq={} err={}", taskId, seq, e.getMessage());
        }
    }

    @Override
    public void finish(Long taskId, String status, String answer, long costMs, long usedTokens) {
        if (taskId == null) {
            return;
        }
        try {
            AiAgentTaskDO entity = new AiAgentTaskDO();
            entity.setId(taskId);
            entity.setStatus(STATUS_CODE.getOrDefault(status, 4));
            entity.setAnswer(answer == null ? null
                    : answer.length() <= MAX_ANSWER ? answer : answer.substring(0, MAX_ANSWER));
            entity.setCostMs(costMs);
            entity.setUsedTokens(usedTokens);
            entity.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(entity);
        } catch (Exception e) {
            log.warn("Agent 任务结束状态回写失败 task={} err={}", taskId, e.getMessage());
        }
    }
}
