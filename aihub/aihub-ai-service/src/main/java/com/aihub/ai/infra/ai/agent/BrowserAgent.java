package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentBudget;
import com.aihub.ai.domain.model.AgentBudgetExceededException;
import com.aihub.ai.domain.model.AgentNames;
import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.ArtifactInfo;
import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.AgentCancelRegistry;
import com.aihub.ai.domain.spi.AgentTaskRepository;
import com.aihub.ai.domain.spi.BrowserDriver;
import com.aihub.ai.domain.spi.BrowserSessionManager;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.infra.ai.ChatClientFactory;
import com.aihub.ai.infra.ai.config.AgentProperties;
import com.aihub.ai.infra.metrics.AiMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 浏览器操作 Agent（对齐课程 ReActBrowserAgent）。
 *
 * <p>循环：观察（页面标注快照）→ 思考（LLM 输出一个 JSON 动作）
 * → 行动（open / snapshot / click / type）→ 再观察……直到模型输出 done/fail
 * 或预算（步数 / Token / 耗时）耗尽。
 *
 * <p>与 {@code BrowserTools} 的分工：工具是模型在普通对话里随手用的<b>单步</b>能力；
 * Agent 是围绕一个目标的<b>多步</b>编排。两者共享同一个会话管理器与会话键，
 * 因此工具开过的页面 Agent 能接着操作，反之亦然。
 *
 * <p>安全边界：模型能做的只有四个结构化动作，拿不到「执行任意 JS」的口子；
 * URL 白名单在驱动的 {@code open()} 里统一把关。单步失败不终止任务——
 * 错误会作为观察喂回给模型自行调整（如 ref 失效后重新标注），只有预算与取消能终止它。
 */
@Slf4j
public class BrowserAgent extends BaseAgent {

    public static final String NAME = AgentNames.BROWSER;

    private static final String SYSTEM_PROMPT = """
            你是浏览器操作 Agent，通过「观察 → 思考 → 行动」循环操控浏览器完成用户目标。
            每一步只能输出一个 JSON 对象，可用动作：
            {"action":"open","url":"https://..."}        打开网页（仅支持 http/https）
            {"action":"snapshot"}                        重新观察当前页面
            {"action":"click","ref":"e3"}                点击观察清单里的元素
            {"action":"type","ref":"e1","text":"..."}    向输入框输入文本（先清空原有内容）
            {"action":"done","summary":"..."}            任务完成，summary 是给用户的结果说明
            {"action":"fail","reason":"..."}             判断任务无法完成
            规则：
            - 只能操作观察结果中列出的 [eN] 元素，不要臆造元素编号或网址。
            - 页面跳转或变化后元素编号可能失效，先重新观察再操作。
            - 完成或确认无法完成时，必须用 done / fail 结束，不要原地打转。
            """;

    /** 给模型的「已执行动作」历史条数上限：防重复循环，也控制上下文长度 */
    private static final int MAX_HISTORY = 6;

    private final BrowserSessionManager sessionManager;
    private final AgentTaskRepository taskRepository;
    private final AgentCancelRegistry cancelRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 指标上报（可选）：构造器注入会牵动所有子类，用字段注入保持改动最小 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiMetrics aiMetrics;

    /**
     * Agent 三重预算参数。用 {@code @ConfigurationProperties} 而非 {@code @Value}：
     * Nacos 配置变更会触发重绑定（改配置无需重启），{@code @Value} 字段只在启动时绑定一次。
     */
    private final AgentProperties agentProperties;

    public BrowserAgent(ChatClientFactory chatClientFactory,
                        BrowserSessionManager sessionManager,
                        AgentTaskRepository taskRepository,
                        AgentCancelRegistry cancelRegistry,
                        AgentProperties agentProperties) {
        super(chatClientFactory);
        this.sessionManager = sessionManager;
        this.taskRepository = taskRepository;
        this.cancelRegistry = cancelRegistry;
        this.agentProperties = agentProperties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "浏览器操作：打开网页、按页面标注点击与输入，完成需要实际操作网页的任务";
    }

    @Override
    public AgentResult execute(AgentTask task, StreamSink sink) {
        AgentBudget budget = task.budget() == null ? defaultBudget() : task.budget();
        AgentTask root = task.withBudget(budget);
        List<ArtifactInfo> artifacts = new ArrayList<>();
        int[] index = {0};
        Long taskId = taskRepository.create(root, NAME);
        long start = System.currentTimeMillis();

        AgentResult result;
        try {
            result = run(root, sink, index, artifacts, taskId);
        } catch (AgentBudgetExceededException e) {
            log.warn("浏览器 Agent 预算终止: {}", e.getMessage());
            result = new AgentResult(AgentResult.BUDGET_EXCEEDED, e.getMessage(), artifacts);
        } catch (Exception e) {
            result = fallback(root, e);
        }
        // 不放进 finally：finally 里读 result 会被确定性赋值分析拒绝（catch 自身可能先抛异常）
        taskRepository.finish(taskId, result.status(), result.answer(),
                System.currentTimeMillis() - start, budget.usedTokens());
        cancelRegistry.clear(root.conversationId());
        if (aiMetrics != null) {
            aiMetrics.recordAgentTask(result.status());
        }
        return result;
    }

    private AgentResult run(AgentTask root, StreamSink sink, int[] index,
                            List<ArtifactInfo> artifacts, Long taskId) {
        if (cancelRegistry.isCanceled(root.conversationId())) {
            step(sink, index, "observe", "任务已被用户取消");
            return new AgentResult(AgentResult.CANCELED, "已取消", artifacts);
        }

        BrowserDriver driver;
        try {
            driver = sessionManager.acquire(sessionKey(root));
        } catch (Exception e) {
            log.warn("浏览器会话获取失败: {}", e.getMessage());
            step(sink, index, "observe", "无法启动浏览器：" + e.getMessage());
            return new AgentResult(AgentResult.FAILED, "无法启动浏览器：" + e.getMessage(), artifacts);
        }

        String observation = "尚未打开任何页面。请先用 open 动作打开目标网页。";
        Deque<String> history = new ArrayDeque<>();
        int steps = Math.max(1, root.budget().maxSubTasks());

        for (int i = 0; i < steps; i++) {
            if (cancelRegistry.isCanceled(root.conversationId())) {
                emit(sink, index, root, taskId, "observe", "收到取消信号，终止执行");
                return new AgentResult(AgentResult.CANCELED, "已取消", artifacts);
            }
            if (root.budget().timeout() || root.budget().tokenExceeded()) {
                throw new AgentBudgetExceededException("预算耗尽，终止后续执行");
            }

            BrowserAction action = parseAction(
                    llm(root, SYSTEM_PROMPT, userPrompt(root.goal(), observation, history)));
            emit(sink, index, root, taskId, "think", action.describe());

            switch (action.action()) {
                case "done" -> {
                    emit(sink, index, root, taskId, "observe", "任务完成");
                    return AgentResult.done(action.summary().isBlank() ? observation : action.summary(),
                            artifacts);
                }
                case "fail" -> {
                    emit(sink, index, root, taskId, "observe", "模型判断任务无法完成");
                    return new AgentResult(AgentResult.FAILED,
                            action.reason().isBlank() ? "模型判断任务无法完成" : action.reason(), artifacts);
                }
                default -> {
                    observation = act(driver, action);
                    history.addLast(action.describe());
                    while (history.size() > MAX_HISTORY) {
                        history.removeFirst();
                    }
                    emit(sink, index, root, taskId, "observe", brief(observation));
                }
            }
        }

        emit(sink, index, root, taskId, "observe", "已达步数上限（" + steps + "），任务终止");
        return new AgentResult(AgentResult.BUDGET_EXCEEDED,
                "已达步数上限（" + steps + "）,最后状态：" + brief(observation), artifacts);
    }

    /** 执行一个动作并返回新的观察；单步失败转成文字观察而非异常，让模型自行纠偏 */
    private String act(BrowserDriver driver, BrowserAction action) {
        try {
            return switch (action.action()) {
                case "open" -> {
                    driver.open(action.url());
                    yield driver.snapshot().describe();
                }
                case "snapshot" -> driver.snapshot().describe();
                case "click" -> {
                    driver.click(action.ref());
                    yield "已点击 [" + action.ref() + "]。\n" + driver.snapshot().describe();
                }
                case "type" -> {
                    driver.type(action.ref(), action.text());
                    yield "已在 [" + action.ref() + "] 输入文本。\n" + driver.snapshot().describe();
                }
                default -> "动作无法执行：" + action.describe()
                        + "。请严格按 JSON 格式输出动作（open/snapshot/click/type/done/fail）";
            };
        } catch (BrowserException e) {
            return "上一步操作失败：" + e.getMessage() + "。请根据失败原因调整动作";
        } catch (Exception e) {
            return "上一步操作出现异常：" + e.getMessage();
        }
    }

    private BrowserAction parseAction(String raw) {
        String json = extractJsonObject(raw);
        try {
            JsonNode node = mapper.readTree(json);
            String action = node.path("action").asText("").trim().toLowerCase(Locale.ROOT);
            if (action.isBlank()) {
                return BrowserAction.invalid("模型未返回 action 字段");
            }
            return new BrowserAction(action,
                    node.path("url").asText(""),
                    node.path("ref").asText(""),
                    node.path("text").asText(""),
                    node.path("summary").asText(""),
                    node.path("reason").asText(""));
        } catch (Exception e) {
            return BrowserAction.invalid("模型输出无法解析为动作 JSON：" + abbreviate(raw));
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    private String userPrompt(String goal, String observation, Deque<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户目标：").append(goal).append("\n\n已执行的动作：\n");
        if (history.isEmpty()) {
            sb.append("（无）\n");
        } else {
            history.forEach(item -> sb.append("- ").append(item).append('\n'));
        }
        sb.append("\n当前页面观察：\n").append(observation);
        sb.append("\n\n请输出下一步动作（只输出一个 JSON 对象，不要输出其他内容）");
        return sb.toString();
    }

    /** 会话键与 BrowserTools 保持同一规则，工具开过的页面 Agent 可以接着操作 */
    private String sessionKey(AgentTask task) {
        String tenant = task.tenantId() == null ? "anon" : String.valueOf(task.tenantId());
        String conv = task.conversationId() == null || task.conversationId().isBlank()
                ? "default" : task.conversationId();
        return tenant + ":" + conv;
    }

    /** 广播步骤事件的同时落库（事件给前端时间线，落库给审计与回放） */
    private void emit(StreamSink sink, int[] index, AgentTask task, Long taskId,
                      String type, String content) {
        step(sink, index, type, content);
        taskRepository.appendStep(task.tenantId(), taskId, index[0], type, name(), content, 0L);
    }

    private AgentBudget defaultBudget() {
        return new AgentBudget(agentProperties.getBrowser().getMaxSteps(),
                agentProperties.getMaxTokens(), agentProperties.getTimeoutMs());
    }

    /** 观察文本只留首行给事件流（完整内容随下一步提示词传给模型，事件里塞长文没意义） */
    private static String brief(String observation) {
        if (observation == null || observation.isBlank()) {
            return "（无观察内容）";
        }
        int lineEnd = observation.indexOf('\n');
        String first = lineEnd < 0 ? observation : observation.substring(0, lineEnd);
        return first.length() > 120 ? first.substring(0, 120) + "…" : first;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    /** 模型输出的一条动作指令 */
    record BrowserAction(String action, String url, String ref, String text,
                         String summary, String reason) {

        static BrowserAction invalid(String reason) {
            return new BrowserAction("invalid", "", "", "", "", reason);
        }

        String describe() {
            return switch (action) {
                case "open" -> "打开网页 " + url;
                case "snapshot" -> "重新观察当前页面";
                case "click" -> "点击 [" + ref + "]";
                case "type" -> "向 [" + ref + "] 输入文本";
                case "done" -> "任务完成：" + summary;
                case "fail" -> "任务失败：" + reason;
                default -> "无效输出：" + reason;
            };
        }
    }
}
