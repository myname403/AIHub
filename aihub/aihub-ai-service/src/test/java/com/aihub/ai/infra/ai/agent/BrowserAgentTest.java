package com.aihub.ai.infra.ai.agent;

import com.aihub.ai.domain.model.AgentBudget;
import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.BrowserException;
import com.aihub.ai.domain.model.PageElement;
import com.aihub.ai.domain.model.PageSnapshot;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.AgentCancelRegistry;
import com.aihub.ai.domain.spi.AgentTaskRepository;
import com.aihub.ai.domain.spi.BrowserDriver;
import com.aihub.ai.domain.spi.BrowserSessionManager;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.ai.infra.ai.ChatClientFactory;
import com.aihub.ai.infra.ai.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 浏览器 ReAct Agent 单测：脚本化假模型 + 假驱动，覆盖
 * 完整循环（open→click→done）、步数预算终止、取消、模型输出容错与单步失败恢复。
 * LLM 调用走真实 ChatClient 装配（BaseAgent 的 call 路径），只是底层模型是脚本。
 */
class BrowserAgentTest {

    /** 脚本化假模型：按调用次序返回预设文本，耗尽即抛错（防止测试被无界循环拖死） */
    private static class ScriptedChatModel implements ChatModel {
        private final Deque<String> script = new ArrayDeque<>();
        int calls;

        void enqueue(String... outputs) {
            script.addAll(List.of(outputs));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            String next = script.poll();
            if (next == null) {
                throw new IllegalStateException("模型脚本已耗尽（第 " + calls + " 次调用）");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(next))));
        }
    }

    /** 假驱动：记录动作序列，可选择在某一步抛浏览器异常 */
    private static class FakeDriver implements BrowserDriver {
        final List<String> actions = new ArrayList<>();
        BrowserException failOnOpen;
        PageSnapshot snapshot = new PageSnapshot("https://example.com", "示例页",
                List.of(new PageElement("e1", "textbox", "搜索", 10, 20),
                        new PageElement("e2", "button", "提交", 30, 20)),
                2);

        @Override
        public void open(String url) {
            if (failOnOpen != null) {
                throw failOnOpen;
            }
            actions.add("open:" + url);
        }

        @Override
        public PageSnapshot snapshot() {
            actions.add("snapshot");
            return snapshot;
        }

        @Override
        public void click(String ref) {
            actions.add("click:" + ref);
        }

        @Override
        public void type(String ref, String text) {
            actions.add("type:" + ref + ":" + text);
        }

        @Override
        public byte[] screenshot() {
            return new byte[0];
        }

        @Override
        public String currentUrl() {
            return snapshot.url();
        }

        @Override
        public boolean alive() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static class RecordingSessions implements BrowserSessionManager {
        final FakeDriver driver;
        String lastKey;

        RecordingSessions(FakeDriver driver) {
            this.driver = driver;
        }

        @Override
        public BrowserDriver acquire(String sessionKey) {
            lastKey = sessionKey;
            return driver;
        }

        @Override
        public void release(String sessionKey) {
        }

        @Override
        public void closeAll() {
        }

        @Override
        public int activeSessions() {
            return 1;
        }
    }

    private record RecordingSink(List<StreamEvent> events) implements StreamSink {
        RecordingSink() {
            this(new ArrayList<>());
        }

        @Override
        public void emit(StreamEvent event) {
            events.add(event);
        }
    }

    /** 组装一个带脚本模型的 Agent（任务预算由用例给定，决定步数上限） */
    private BrowserAgent newAgent(ScriptedChatModel model, BrowserSessionManager sessions,
                                  AgentTaskRepository taskRepository, AgentCancelRegistry cancelRegistry) {
        ChatClientFactory factory = Mockito.mock(ChatClientFactory.class);
        Mockito.when(factory.create(any(), any(), any()))
                .thenReturn(ChatClient.builder(model).build());
        return new BrowserAgent(factory, sessions, taskRepository, cancelRegistry, new AgentProperties());
    }

    @Test
    void completesReActLoopOpenClickDone() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(
                "{\"action\":\"open\",\"url\":\"https://example.com\"}",
                "{\"action\":\"click\",\"ref\":\"e2\"}",
                "{\"action\":\"done\",\"summary\":\"已在示例页完成搜索提交\"}");
        RecordingSessions sessions = new RecordingSessions(new FakeDriver());
        AgentTaskRepository taskRepository = Mockito.mock(AgentTaskRepository.class);
        Mockito.when(taskRepository.create(any(), anyString())).thenReturn(100L);
        AgentCancelRegistry cancelRegistry = Mockito.mock(AgentCancelRegistry.class);
        Mockito.when(cancelRegistry.isCanceled(any())).thenReturn(false);
        BrowserAgent agent = newAgent(model, sessions, taskRepository, cancelRegistry);

        AgentTask task = new AgentTask(1L, 9L, 2L, "conv-9", "在示例页搜索 AIHub")
                .withBudget(new AgentBudget(4, 0, 0));
        RecordingSink sink = new RecordingSink();

        AgentResult result = agent.execute(task, sink);

        assertThat(result.status()).isEqualTo(AgentResult.DONE);
        assertThat(result.answer()).isEqualTo("已在示例页完成搜索提交");
        assertThat(sessions.driver.actions).containsExactly(
                "open:https://example.com", "snapshot", "click:e2", "snapshot");
        // 会话键与 BrowserTools 同规则：租户:会话
        assertThat(sessions.lastKey).isEqualTo("1:conv-9");
        // 步骤事件广播（think/act/observe）与落库
        assertThat(sink.events()).isNotEmpty();
        assertThat(sink.events()).allMatch(e -> StreamEvent.AGENT_STEP.equals(e.event()));
        Mockito.verify(taskRepository, Mockito.atLeast(4)).appendStep(
                any(), any(), anyInt(), anyString(), anyString(), anyString(), anyLong());
        Mockito.verify(taskRepository).finish(Mockito.eq(100L), Mockito.eq(AgentResult.DONE),
                Mockito.eq("已在示例页完成搜索提交"), anyLong(), anyLong());
        Mockito.verify(cancelRegistry).clear("conv-9");
    }

    @Test
    void stopsAtStepBudgetAndReportsBudgetExceeded() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(
                "{\"action\":\"open\",\"url\":\"https://example.com\"}",
                "{\"action\":\"click\",\"ref\":\"e1\"}");
        RecordingSessions sessions = new RecordingSessions(new FakeDriver());
        AgentTaskRepository taskRepository = Mockito.mock(AgentTaskRepository.class);
        Mockito.when(taskRepository.create(any(), anyString())).thenReturn(101L);
        AgentCancelRegistry cancelRegistry = Mockito.mock(AgentCancelRegistry.class);
        Mockito.when(cancelRegistry.isCanceled(any())).thenReturn(false);
        BrowserAgent agent = newAgent(model, sessions, taskRepository, cancelRegistry);

        // 步数预算 2，脚本里没有 done → 循环两步后必须被预算终止
        AgentTask task = new AgentTask(1L, 9L, 2L, "conv-9", "目标")
                .withBudget(new AgentBudget(2, 0, 0));

        AgentResult result = agent.execute(task, new RecordingSink());

        assertThat(result.status()).isEqualTo(AgentResult.BUDGET_EXCEEDED);
        assertThat(result.answer()).contains("步数上限");
        Mockito.verify(taskRepository).finish(Mockito.eq(101L),
                Mockito.eq(AgentResult.BUDGET_EXCEEDED), anyString(), anyLong(), anyLong());
    }

    @Test
    void cancelSignalStopsBeforeFirstDecision() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue("{\"action\":\"open\",\"url\":\"https://example.com\"}");
        RecordingSessions sessions = new RecordingSessions(new FakeDriver());
        AgentTaskRepository taskRepository = Mockito.mock(AgentTaskRepository.class);
        Mockito.when(taskRepository.create(any(), anyString())).thenReturn(102L);
        AgentCancelRegistry cancelRegistry = Mockito.mock(AgentCancelRegistry.class);
        Mockito.when(cancelRegistry.isCanceled(any())).thenReturn(true);
        BrowserAgent agent = newAgent(model, sessions, taskRepository, cancelRegistry);

        AgentResult result = agent.execute(
                new AgentTask(1L, 9L, 2L, "conv-9", "目标").withBudget(new AgentBudget(3, 0, 0)),
                new RecordingSink());

        assertThat(result.status()).isEqualTo(AgentResult.CANCELED);
        assertThat(model.calls).isZero();
        assertThat(sessions.driver.actions).isEmpty();
    }

    @Test
    void invalidModelOutputIsFedBackInsteadOfAborting() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(
                "抱歉，我不明白你的意思。", // 第一轮不按格式输出
                "{\"action\":\"done\",\"summary\":\"恢复后完成\"}");
        RecordingSessions sessions = new RecordingSessions(new FakeDriver());
        AgentTaskRepository taskRepository = Mockito.mock(AgentTaskRepository.class);
        Mockito.when(taskRepository.create(any(), anyString())).thenReturn(103L);
        AgentCancelRegistry cancelRegistry = Mockito.mock(AgentCancelRegistry.class);
        Mockito.when(cancelRegistry.isCanceled(any())).thenReturn(false);
        BrowserAgent agent = newAgent(model, sessions, taskRepository, cancelRegistry);

        AgentResult result = agent.execute(
                new AgentTask(1L, 9L, 2L, "conv-9", "目标").withBudget(new AgentBudget(3, 0, 0)),
                new RecordingSink());

        // 格式错误不终止任务：作为观察喂回后模型下一轮纠正
        assertThat(result.status()).isEqualTo(AgentResult.DONE);
        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void driverFailureIsFedBackAndModelCanConcede() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(
                "{\"action\":\"open\",\"url\":\"https://unreachable.invalid\"}",
                "{\"action\":\"fail\",\"reason\":\"目标网站无法访问\"}");
        FakeDriver driver = new FakeDriver();
        driver.failOnOpen = new BrowserException("页面加载超时");
        RecordingSessions sessions = new RecordingSessions(driver);
        AgentTaskRepository taskRepository = Mockito.mock(AgentTaskRepository.class);
        Mockito.when(taskRepository.create(any(), anyString())).thenReturn(104L);
        AgentCancelRegistry cancelRegistry = Mockito.mock(AgentCancelRegistry.class);
        Mockito.when(cancelRegistry.isCanceled(any())).thenReturn(false);
        BrowserAgent agent = newAgent(model, sessions, taskRepository, cancelRegistry);

        AgentResult result = agent.execute(
                new AgentTask(1L, 9L, 2L, "conv-9", "目标").withBudget(new AgentBudget(4, 0, 0)),
                new RecordingSink());

        assertThat(result.status()).isEqualTo(AgentResult.FAILED);
        assertThat(result.answer()).isEqualTo("目标网站无法访问");
        // 驱动抛错没有向上传播，循环继续走到了模型主动认输
        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void sessionAcquisitionFailureReturnsFailedResult() {
        ScriptedChatModel model = new ScriptedChatModel();
        BrowserSessionManager broken = new BrowserSessionManager() {
            @Override
            public BrowserDriver acquire(String sessionKey) {
                throw new BrowserException("浏览器会话数已达上限");
            }

            @Override
            public void release(String sessionKey) {
            }

            @Override
            public void closeAll() {
            }

            @Override
            public int activeSessions() {
                return 0;
            }
        };
        AgentTaskRepository taskRepository = Mockito.mock(AgentTaskRepository.class);
        Mockito.when(taskRepository.create(any(), anyString())).thenReturn(105L);
        AgentCancelRegistry cancelRegistry = Mockito.mock(AgentCancelRegistry.class);
        Mockito.when(cancelRegistry.isCanceled(any())).thenReturn(false);
        BrowserAgent agent = newAgent(model, broken, taskRepository, cancelRegistry);

        AgentResult result = agent.execute(
                new AgentTask(1L, 9L, 2L, "conv-9", "目标").withBudget(new AgentBudget(3, 0, 0)),
                new RecordingSink());

        assertThat(result.status()).isEqualTo(AgentResult.FAILED);
        assertThat(result.answer()).contains("浏览器会话数已达上限");
        assertThat(model.calls).isZero();
    }
}
