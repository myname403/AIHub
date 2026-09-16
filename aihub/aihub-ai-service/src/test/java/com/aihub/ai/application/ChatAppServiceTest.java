package com.aihub.ai.application;

import com.aihub.ai.domain.model.AgentNames;
import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.ChatTurn;
import com.aihub.ai.domain.model.EventTypes;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.AgentRegistry;
import com.aihub.ai.domain.spi.ChatExecutor;
import com.aihub.ai.domain.spi.EventOutbox;
import com.aihub.ai.domain.spi.MessageReferenceStore;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.api.client.PlatformClient;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.R;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对话编排的配额语义单测（M5）。
 *
 * <p>重点是 <b>task 维度</b>：Agent 调用成本高，超限必须与 request / doc 维度一样
 * <b>真正拦下请求</b>，而不是"只记账不阻断"。同时钉住降级语义——
 * 平台服务不可用时仍要放行（可用性优先），只有平台明确返回 allowed=false 才拒绝。
 */
class ChatAppServiceTest {

    private final ChatExecutor chatExecutor = Mockito.mock(ChatExecutor.class);
    private final PlatformClient platformClient = Mockito.mock(PlatformClient.class);
    private final AgentRegistry agentRegistry = Mockito.mock(AgentRegistry.class);
    private final MessageReferenceStore messageReferenceStore = Mockito.mock(MessageReferenceStore.class);
    private final EventOutbox eventOutbox = Mockito.mock(EventOutbox.class);

    private ChatAppService service() {
        return new ChatAppService(chatExecutor, platformClient, agentRegistry, messageReferenceStore,
                eventOutbox);
    }

    private static ChatTurn agentTurn() {
        return new ChatTurn(1L, 9L, 2L, "conv-1", "统计中国省会城市并生成表格", "agent", null);
    }

    private static R<PlatformClient.QuotaResult> quota(boolean allowed) {
        return R.ok(new PlatformClient.QuotaResult(allowed, allowed ? 100L : 0L,
                allowed ? "ok" : "维度 task 配额已用尽"));
    }

    /** 把 Agent 执行成功所需的最小桩打上（规划 Agent 在注册表里，执行返回 done） */
    private void stubPlanningAgent() {
        Agent planning = Mockito.mock(Agent.class);
        Mockito.when(agentRegistry.lookup(AgentNames.PLANNING)).thenReturn(Optional.of(planning));
        Mockito.when(planning.execute(Mockito.any(), Mockito.any()))
                .thenReturn(AgentResult.done("已生成表格", List.of()));
    }

    private static class RecordingSink implements StreamSink {
        final List<StreamEvent> events = new ArrayList<>();
        boolean closed;

        @Override
        public void emit(StreamEvent event) {
            events.add(event);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void agentTaskQuotaRejectionFailsSyncCall() {
        Mockito.when(platformClient.checkAndConsume(Mockito.any())).thenReturn(quota(true));
        Mockito.when(platformClient.consume(Mockito.any())).thenReturn(quota(false));

        assertThatThrownBy(() -> service().chat(agentTurn()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("配额已用尽");

        // 超限即拒绝：绝不能继续执行 Agent（否则高成本调用照样发生）
        Mockito.verifyNoInteractions(agentRegistry);
    }

    @Test
    void agentRunsWhenTaskQuotaAllows() {
        Mockito.when(platformClient.checkAndConsume(Mockito.any())).thenReturn(quota(true));
        Mockito.when(platformClient.consume(Mockito.any())).thenReturn(quota(true));
        stubPlanningAgent();

        assertThat(service().chat(agentTurn())).isEqualTo("已生成表格");
    }

    @Test
    void streamEmitsErrorEventWhenTaskQuotaRejected() {
        Mockito.when(platformClient.checkAndConsume(Mockito.any())).thenReturn(quota(true));
        Mockito.when(platformClient.consume(Mockito.any())).thenReturn(quota(false));
        RecordingSink sink = new RecordingSink();

        service().chatStream(agentTurn(), sink);

        // 流式通道上抛异常前端看不到，必须转成 error 事件再关闭连接
        assertThat(sink.events).hasSize(1);
        assertThat(sink.events.get(0).event()).isEqualTo(StreamEvent.ERROR);
        assertThat(sink.closed).isTrue();
        Mockito.verifyNoInteractions(agentRegistry);
    }

    @Test
    void platformFailureStillDegradesOpenForTaskQuota() {
        // 降级语义不能被"改成拦截"这件事破坏：平台挂了要放行，不能让人没法用 Agent
        Mockito.when(platformClient.checkAndConsume(Mockito.any()))
                .thenThrow(new RuntimeException("平台服务不可用"));
        Mockito.when(platformClient.consume(Mockito.any()))
                .thenThrow(new RuntimeException("平台服务不可用"));
        stubPlanningAgent();

        assertThat(service().chat(agentTurn())).isEqualTo("已生成表格");
    }

    @Test
    void usageReportFailureFallsBackToOutbox() {
        // 用量上报失败不能只打日志丢账：必须落发件箱由后台重投（最终一致）
        ChatTurn turn = ChatTurn.of(1L, 9L, 2L, "conv-1", "你好");
        Mockito.when(platformClient.checkAndConsume(Mockito.any())).thenReturn(quota(true));
        Mockito.when(chatExecutor.call(Mockito.any()))
                .thenReturn(new ChatExecutor.StreamResult("回答", "gpt-x", 30L, 11, 22));
        Mockito.when(platformClient.reportUsage(Mockito.any()))
                .thenThrow(new RuntimeException("平台不可用"));

        assertThat(service().chat(turn)).isEqualTo("回答");

        Mockito.verify(eventOutbox).enqueue(Mockito.eq(1L),
                Mockito.eq(EventTypes.USAGE_REPORT), Mockito.any());
    }

    @Test
    void successfulUsageReportDoesNotTouchOutbox() {
        ChatTurn turn = ChatTurn.of(1L, 9L, 2L, "conv-1", "你好");
        Mockito.when(platformClient.checkAndConsume(Mockito.any())).thenReturn(quota(true));
        Mockito.when(chatExecutor.call(Mockito.any()))
                .thenReturn(new ChatExecutor.StreamResult("回答", "gpt-x", 30L, 11, 22));

        assertThat(service().chat(turn)).isEqualTo("回答");

        Mockito.verifyNoInteractions(eventOutbox);
    }
}
