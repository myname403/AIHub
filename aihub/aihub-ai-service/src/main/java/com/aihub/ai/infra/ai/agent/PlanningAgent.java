package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentBudget;
import com.aihub.ai.domain.model.AgentBudgetExceededException;
import com.aihub.ai.domain.model.AgentNames;
import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.ArtifactInfo;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.AgentCancelRegistry;
import com.aihub.ai.domain.spi.AgentRegistry;
import com.aihub.ai.domain.spi.AgentTaskRepository;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.infra.ai.ChatClientFactory;
import com.aihub.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务规划 Agent（对齐课程 ReActPlanningAgent：入口智能体，负责任务拆解与派发）。
 *
 * <p>流程：目标 → LLM 拆解为子任务链（指定执行 Agent）→ AgentRegistry 派发
 * → 逐步执行并广播 agent.step → 结果拼接返回。
 *
 * <p>三重预算（06 号文档 R6）：最大子任务数 / 最大 Token / 最大耗时，
 * 任意一项触顶立即终止并返回 {@link AgentResult#BUDGET_EXCEEDED}。
 *
 * <p>中断：每一步执行前检查 {@link AgentCancelRegistry}，
 * 用户点击"停止"后后续子任务不再执行（M4 DoD）。
 */
@Slf4j
@Component
public class PlanningAgent extends BaseAgent {

    /** 名称常量来自 domain，编排层无需 import 本类（保持依赖方向朝内） */
    public static final String NAME = AgentNames.PLANNING;

    private final AgentRegistry registry;
    private final AgentTaskRepository taskRepository;
    private final AgentCancelRegistry cancelRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${aihub.agent.max-sub-tasks:3}")
    private int maxSubTasks;

    /** <=0 表示不限制 */
    @Value("${aihub.agent.max-tokens:0}")
    private long maxTokens;

    @Value("${aihub.agent.timeout-ms:180000}")
    private long timeoutMs;

    public PlanningAgent(ChatClientFactory chatClientFactory,
                         @Lazy AgentRegistry registry,
                         AgentTaskRepository taskRepository,
                         AgentCancelRegistry cancelRegistry) {
        super(chatClientFactory);
        this.registry = registry;
        this.taskRepository = taskRepository;
        this.cancelRegistry = cancelRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "任务规划：拆解目标并派发给 table/chart/html 等执行 Agent";
    }

    @Override
    public AgentResult execute(AgentTask task, StreamSink sink) {
        AgentBudget budget = task.budget() == null ? defaultBudget() : task.budget();
        AgentTask root = task.withBudget(budget);
        List<ArtifactInfo> artifacts = new ArrayList<>();
        int[] index = {0};
        Long taskId = taskRepository.create(root, NAME);
        long start = System.currentTimeMillis();

        AgentResult result = null;
        try {
            result = run(root, sink, index, artifacts, taskId);
        } catch (AgentBudgetExceededException e) {
            log.warn("Agent 预算终止: {}", e.getMessage());
            result = new AgentResult(AgentResult.BUDGET_EXCEEDED, e.getMessage(), artifacts);
        } catch (BizException e) {
            result = fallback(root, e);
        } catch (Exception e) {
            result = fallback(root, e);
        } finally {
            taskRepository.finish(taskId, result.status(), result.answer(),
                    System.currentTimeMillis() - start, budget.usedTokens());
            cancelRegistry.clear(root.conversationId());
        }
        return result;
    }

    private AgentResult run(AgentTask root, StreamSink sink, int[] index,
                            List<ArtifactInfo> artifacts, Long taskId) {
        if (cancelRegistry.isCanceled(root.conversationId())) {
            step(sink, index, "observe", "任务已被用户取消");
            return new AgentResult(AgentResult.CANCELED, "已取消", artifacts);
        }

        List<SubTask> subTasks = decompose(root);
        emit(sink, index, root, taskId, "think",
                "任务拆解为 " + subTasks.size() + " 个子任务："
                        + subTasks.stream().map(SubTask::agent).toList());

        StringBuilder answer = new StringBuilder();
        int done = 0;
        for (SubTask sub : subTasks) {
            if (cancelRegistry.isCanceled(root.conversationId())) {
                emit(sink, index, root, taskId, "observe", "收到取消信号，终止后续子任务");
                return new AgentResult(AgentResult.CANCELED,
                        answer.length() == 0 ? "已取消" : answer.toString(), artifacts);
            }
            if (root.budget().timeout() || root.budget().tokenExceeded()) {
                throw new AgentBudgetExceededException("预算耗尽，终止后续执行");
            }
            if (done >= root.budget().maxSubTasks()) {
                emit(sink, index, root, taskId, "observe",
                        "已达子任务预算上限（" + root.budget().maxSubTasks() + "），终止后续执行");
                return new AgentResult(AgentResult.BUDGET_EXCEEDED, answer.toString(), artifacts);
            }

            Agent agent = registry.lookup(sub.agent()).orElse(null);
            if (agent == null) {
                emit(sink, index, root, taskId, "observe", "未找到执行 Agent：" + sub.agent() + "，跳过");
                continue;
            }
            emit(sink, index, root, taskId, "act", agent.name() + " 执行：" + sub.task());
            long stepStart = System.currentTimeMillis();
            AgentResult result = agent.execute(root.subTask(sub.task()), sink);
            taskRepository.appendStep(root.tenantId(), taskId, index[0], "observe", agent.name(),
                    result.answer(), System.currentTimeMillis() - stepStart);
            if (result.artifacts() != null) {
                artifacts.addAll(result.artifacts());
            }
            answer.append("【").append(agent.name()).append("】")
                    .append(result.answer()).append("\n\n");
            done++;
        }

        emit(sink, index, root, taskId, "observe", "全部子任务执行完成");
        return AgentResult.done(answer.toString(), artifacts);
    }

    /** 广播步骤事件的同时落库（事件给前端时间线，落库给审计与回放） */
    private void emit(StreamSink sink, int[] index, AgentTask task, Long taskId,
                      String type, String content) {
        step(sink, index, type, content);
        taskRepository.appendStep(task.tenantId(), taskId, index[0], type, name(), content, 0L);
    }

    private AgentBudget defaultBudget() {
        return new AgentBudget(maxSubTasks, maxTokens, timeoutMs);
    }

    /** LLM 任务拆解：返回 JSON 数组 [{agent, task}]，解析失败则退化为单个 html 子任务 */
    private List<SubTask> decompose(AgentTask task) {
        String descriptions = """
                - table：把结构化数据整理成 HTML 表格页面
                - chart：把数据生成图表（ECharts）页面
                - html：生成网页 / 文档 / 总结类内容（默认）""";
        String system = "你是任务规划 Agent，负责把用户目标拆解为可执行的子任务链。";
        String user = """
                可用执行 Agent：
                %s

                用户目标：%s

                请拆解为不超过 %d 个子任务，只返回 JSON 数组，格式：
                [{"agent":"html","task":"子任务描述"}]
                不要输出任何其他内容。若目标无需拆解，返回单个 html 子任务。
                """.formatted(descriptions, task.goal(), task.budget() == null
                ? maxSubTasks : task.budget().maxSubTasks());

        String raw = llm(task, system, user);
        String json = extractJsonArray(raw);
        List<SubTask> subTasks = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(json);
            if (node.isArray()) {
                node.forEach(item -> subTasks.add(new SubTask(
                        item.path("agent").asText(AgentNames.HTML),
                        item.path("task").asText(task.goal()))));
            }
        } catch (Exception e) {
            log.warn("任务拆解 JSON 解析失败，退化为单任务: {}", e.getMessage());
        }
        if (subTasks.isEmpty()) {
            subTasks.add(new SubTask(AgentNames.HTML, task.goal()));
        }
        return subTasks;
    }

    private String extractJsonArray(String raw) {
        if (raw == null) {
            return "[]";
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "[]";
    }

    private record SubTask(String agent, String task) {
    }
}
