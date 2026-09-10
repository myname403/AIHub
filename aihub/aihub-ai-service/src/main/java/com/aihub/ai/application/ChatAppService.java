package com.aihub.ai.application;

import com.aihub.ai.domain.model.AgentNames;
import com.aihub.ai.domain.model.AgentResult;
import com.aihub.ai.domain.model.AgentTask;
import com.aihub.ai.domain.model.ChatTurn;
import com.aihub.ai.domain.model.MessageReference;
import com.aihub.ai.domain.model.StreamEvent;
import com.aihub.ai.domain.spi.Agent;
import com.aihub.ai.domain.spi.AgentRegistry;
import com.aihub.ai.domain.spi.ChatExecutor;
import com.aihub.ai.domain.spi.ChatExecutor.StreamResult;
import com.aihub.ai.domain.spi.MessageReferenceStore;
import com.aihub.ai.domain.spi.StreamSink;
import com.aihub.api.client.PlatformClient;
import com.aihub.api.client.QuotaDimensions;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 对话编排服务（编排层）。
 *
 * <p>依赖约束：只依赖 domain SPI 与 aihub-api 契约，
 * 不感知 Spring AI 与传输协议（SSE / NDJSON / WebSocket 由 web 层注入 StreamSink）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppService {

    private static final String SCENE_AGENT = "agent";

    private final ChatExecutor chatExecutor;
    private final PlatformClient platformClient;
    private final AgentRegistry agentRegistry;
    private final MessageReferenceStore messageReferenceStore;

    /** 同步对话 */
    public String chat(ChatTurn turn) {
        checkQuota(turn);
        if (SCENE_AGENT.equals(turn.scene())) {
            consumeAgentTask(turn);
            return runAgent(turn, null);
        }
        StreamResult result = chatExecutor.call(turn);
        reportUsage(turn, result);
        consumeTokens(turn, result);
        return result.content();
    }

    /** 会话引用溯源（历史回看）：按会话列出所有引用来源 */
    public List<MessageReference> references(Long tenantId, String conversationId) {
        return messageReferenceStore.list(tenantId, conversationId);
    }

    /** 流式对话：事件经 sink 回传；scene=agent 时走 Agent 规划链 */
    public void chatStream(ChatTurn turn, StreamSink sink) {
        try {
            if (!checkQuotaOrEmit(turn, sink)) {
                return;
            }
            if (SCENE_AGENT.equals(turn.scene())) {
                consumeAgentTask(turn);
                String answer = runAgent(turn, sink);
                reportUsage(turn, StreamResult.of(answer, "", 0L));
                return;
            }
            StreamResult result = chatExecutor.stream(turn, sink);
            reportUsage(turn, result);
            consumeTokens(turn, result);
        } catch (Exception e) {
            // 执行器已发出 error 事件并关闭 sink；此处仅记录，避免向上抛断开连接
            log.warn("流式对话结束（含失败）conv={}", turn.conversationId());
        }
    }

    /** 配额校验（M5）：同步路径超限直接抛异常 */
    private void checkQuota(ChatTurn turn) {
        try {
            var quotaResp = platformClient.checkAndConsume(
                    new PlatformClient.QuotaRequest(
                            UUID.randomUUID().toString(),
                            turn.tenantId(), String.valueOf(turn.appId()),
                            QuotaDimensions.REQUEST, 1));
            PlatformClient.QuotaResult result = quotaResp == null ? null : quotaResp.getData();
            if (result == null) {
                log.warn("配额响应为空（已降级放行）conv={}", turn.conversationId());
                return;
            }
            if (!result.allowed()) {
                throw new BizException(ResultCode.QUOTA_EXCEEDED, result.reason());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("配额校验失败（已降级放行）conv={} err={}", turn.conversationId(), e.getMessage());
        }
    }

    /**
     * token 维度扣减（M5）：用量只有拿到模型响应后才知道，因此属<b>事后补扣</b>。
     *
     * <p>补扣失败只记日志：请求已经产生真实成本，不能因为统计失败而让用户看到错误。
     * 用完 token 配额的下一次请求会在 checkQuota 阶段被拦下。
     */
    private void consumeTokens(ChatTurn turn, StreamResult result) {
        int tokens = (result.tokenIn() == null ? 0 : result.tokenIn())
                + (result.tokenOut() == null ? 0 : result.tokenOut());
        if (tokens <= 0) {
            return;
        }
        try {
            platformClient.consume(new PlatformClient.QuotaConsumeRequest(
                    UUID.randomUUID().toString(), turn.tenantId(), String.valueOf(turn.appId()),
                    List.of(new PlatformClient.QuotaItem(QuotaDimensions.TOKEN, tokens))));
        } catch (Exception e) {
            log.warn("token 配额扣减失败（已降级）tenant={} tokens={} err={}",
                    turn.tenantId(), tokens, e.getMessage());
        }
    }

    /** Agent 任务维度扣减（M5）：task 维度用于限制 Agent 这类高成本调用 */
    private void consumeAgentTask(ChatTurn turn) {
        try {
            platformClient.consume(new PlatformClient.QuotaConsumeRequest(
                    UUID.randomUUID().toString(), turn.tenantId(), String.valueOf(turn.appId()),
                    List.of(new PlatformClient.QuotaItem(QuotaDimensions.TASK, 1))));
        } catch (Exception e) {
            log.warn("task 配额扣减失败（已降级）tenant={} err={}", turn.tenantId(), e.getMessage());
        }
    }

    /** 配额校验（流式路径）：超限发出 error 事件后结束 */
    private boolean checkQuotaOrEmit(ChatTurn turn, StreamSink sink) {
        try {
            checkQuota(turn);
            return true;
        } catch (BizException e) {
            sink.emit(StreamEvent.error(0, String.valueOf(e.getCode()), e.getMessage()));
            sink.close();
            return false;
        } catch (Exception e) {
            return true; // 降级放行
        }
    }

    /** 规划 Agent 执行：拆解 → 派发 → 汇总（对齐课程 ReActPlanningAgent） */
    private String runAgent(ChatTurn turn, StreamSink sink) {
        Agent planning = agentRegistry.lookup(AgentNames.PLANNING)
                .orElseThrow(() -> new BizException(ResultCode.SYSTEM_ERROR, "规划 Agent 未注册"));
        AgentTask task = new AgentTask(
                turn.tenantId(), turn.userId(), turn.appId(), turn.conversationId(), turn.userText());
        AgentResult result = planning.execute(task, sink);
        return result.answer();
    }

    /**
     * 上报用量到平台服务（跨服务调用之一：配额/用量）。
     * 平台服务不可用时降级放行——AI 能力不因依赖故障而不可用（见 07 号文档 MR3）。
     */
    private void reportUsage(ChatTurn turn, StreamResult result) {
        try {
            platformClient.reportUsage(new PlatformClient.UsageReport(
                    turn.tenantId(), turn.userId(), String.valueOf(turn.appId()),
                    result.modelCode(), result.tokenIn(), result.tokenOut(),
                    result.costMs() == null ? 0L : result.costMs(), "chat"));
        } catch (Exception e) {
            log.warn("用量上报失败（已降级）conv={} err={}", turn.conversationId(), e.getMessage());
        }
    }
}
