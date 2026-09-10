package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.AgentRegistry;
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
 * <p>预算控制：最大子任务数上限，防止死循环与成本失控。
 */
@Slf4j
@Component
public class PlanningAgent extends BaseAgent {

    /** 名称常量来自 domain，编排层无需 import 本类（保持依赖方向朝内） */
    public static final String NAME = com.aihub.ai.domain.model.AgentNames.PLANNING;

    private final AgentRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${aihub.agent.max-sub-tasks:3}")
    private int maxSubTasks;

    public PlanningAgent(ChatClientFactory chatClientFactory,
                         @Lazy AgentRegistry registry) {
        super(chatClientFactory);
        this.registry = registry;
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
        int[] index = {0};
        List<com.aihub.ai.domain.model.ArtifactInfo> artifacts = new ArrayList<>();
        try {
            List<SubTask> subTasks = decompose(task);
            step(sink, index, "think", "任务拆解为 " + subTasks.size() + " 个子任务："
                    + subTasks.stream().map(SubTask::agent).toList());

            StringBuilder answer = new StringBuilder();
            int done = 0;
            for (SubTask sub : subTasks) {
                if (done >= maxSubTasks) {
                    step(sink, index, "observe", "已达子任务预算上限（" + maxSubTasks + "），终止后续执行");
                    return new AgentResult(AgentResult.BUDGET_EXCEEDED,
                            answer.toString(), artifacts);
                }
                Agent agent = registry.lookup(sub.agent()).orElse(null);
                if (agent == null) {
                    step(sink, index, "observe", "未找到执行 Agent：" + sub.agent() + "，跳过");
                    continue;
                }
                step(sink, index, "act", agent.name() + " 执行：" + sub.task());
                AgentResult result = agent.execute(task.subTask(sub.task()), sink);
                if (result.artifacts() != null) {
                    artifacts.addAll(result.artifacts());
                }
                answer.append("【").append(agent.name()).append("】")
                        .append(result.answer()).append("\n\n");
                done++;
            }

            step(sink, index, "observe", "全部子任务执行完成");
            return AgentResult.done(answer.toString(), artifacts);
        } catch (BizException e) {
            return fallback(task, e);
        } catch (Exception e) {
            return fallback(task, e);
        }
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
                """.formatted(descriptions, task.goal(), maxSubTasks);

        String raw = llm(task.tenantId(), system, user);
        String json = extractJsonArray(raw);
        List<SubTask> subTasks = new ArrayList<>();
        try {
            JsonNode node = mapper.readTree(json);
            if (node.isArray()) {
                node.forEach(item -> subTasks.add(new SubTask(
                        item.path("agent").asText("html"),
                        item.path("task").asText(task.goal()))));
            }
        } catch (Exception e) {
            log.warn("任务拆解 JSON 解析失败，退化为单任务: {}", e.getMessage());
        }
        if (subTasks.isEmpty()) {
            subTasks.add(new SubTask("html", task.goal()));
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
