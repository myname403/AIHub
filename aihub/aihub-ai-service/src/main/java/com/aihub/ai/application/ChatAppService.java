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
 * 对话编排服务（编排层）—— 对话业务的"总调度室"，本服务最值得精读的类之一。
 *
 * <p><b>职责边界（DDD 编排层的教科书示范）：</b>
 * <ul>
 *   <li>依赖约束：只依赖 domain SPI（接口）与 aihub-api 契约，
 *       <b>不感知 Spring AI 与传输协议</b>（SSE / NDJSON / WebSocket 由 web 层注入 StreamSink）；</li>
 *   <li>编排内容：配额检查 →（Agent 场景先扣任务费）→ 委托执行器/Agent → 用量上报 → token 补扣；</li>
 *   <li>不做的事：不调模型（ChatExecutor 干）、不写 SQL（SPI 实现干）、不碰 HTTP 细节（web 层干）。</li>
 * </ul>
 *
 * <p><b>两条主线路径：</b>
 * <pre>
 *   scene=chat：checkQuota → chatExecutor.call/stream → reportUsage → consumeTokens
 *   scene=agent：checkQuota → 扣 task 配额 → PlanningAgent 编排执行 → 上报
 * </pre>
 *
 * <p><b>降级哲学（本类反复出现的 try-catch 降级放行）：</b>
 * 配额/统计类远程调用失败时<b>放行而不拒绝</b> —— AI 能力不因依赖故障而不可用
 * （见 docs/07 MR3）。取舍：宁可账面少扣，不能让用户对话失败；
 * 真正卡钱的闸门在 checkQuota 正常路径上，降级只是兜底不是常态。
 * 详见学习文档《05-AI服务-aihub-ai-service.md》。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAppService {

    private static final String SCENE_AGENT = "agent";

    // ↓ 四个依赖全部是接口（domain SPI 或 api 契约），实现由 Spring 注入 —— 面向接口编程
    private final ChatExecutor chatExecutor;                    // 对话执行器（Spring AI 实现）
    private final PlatformClient platformClient;                // 平台服务 Feign 契约（配额/上报）
    private final AgentRegistry agentRegistry;                  // Agent 注册表（按名字找 Agent）
    private final MessageReferenceStore messageReferenceStore;  // RAG 引用来源存储

    /**
     * 同步对话主流程。
     * ① 配额前置检查（次数维度）→ ② Agent 场景先扣任务费再执行 / 普通对话直接调模型
     * → ③ 事后上报用量 → ④ 事后补扣 token。
     */
    public String chat(ChatTurn turn) {
        checkQuota(turn);                       // ① 闸门：超限抛 QUOTA_EXCEEDED，请求到此为止
        if (SCENE_AGENT.equals(turn.scene())) {
            consumeAgentTask(turn);             // ②a Agent 多步执行成本高，先扣 1 个 task 配额
            return runAgent(turn, null);        //    null sink = 同步模式，不发流式事件
        }
        StreamResult result = chatExecutor.call(turn);   // ②b 委托执行器（含 RAG 增强 + Spring AI 调用）
        reportUsage(turn, result);              // ③ 流水上报（审计/看板用）
        consumeTokens(turn, result);            // ④ token 配额补扣（事后才知道用量）
        return result.content();
    }

    /** 会话引用溯源（历史回看）：按会话列出所有引用来源 */
    public List<MessageReference> references(Long tenantId, String conversationId) {
        return messageReferenceStore.list(tenantId, conversationId);
    }

    /**
     * 流式对话：事件经 sink 回传；scene=agent 时走 Agent 规划链。
     * <p>本方法不关心 sink 背后是 SSE / NDJSON / WebSocket —— 这就是抽象的好处。
     * <p>注意 catch 语义：流式通道上抛异常前端收不到（连接已建立、响应头已发出），
     * 所以失败必须转成 StreamEvent.error 事件（执行器内部已做），这里只记日志防连接中断。
     */
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

    /**
     * 配额校验（M5）：同步路径超限直接抛异常。
     * <p><b>降级放行设计（重要）：</b>catch(Exception) 后放行 —— 平台服务挂了/超时，
     * 不应让所有用户对话失败。闸门只在平台"明确拒绝"（allowed=false）时生效。
     * checkAndConsume 扣"次数"维度：每次对话固定扣 1，requestId 用 UUID 保证幂等
     * （Feign 网络重试不会重复扣，见 aihub-api 文档）。
     */
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

    /**
     * 配额校验（流式路径）：超限发出 error 事件后结束。
     * <p>为什么复用同步版 checkQuota + catch 转 error 事件：保持"超限判定"只有一份逻辑，
     * 流式只是把"异常"翻译成"事件"（流式响应里抛异常前端看不到）。
     */
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

    /**
     * 规划 Agent 执行：拆解 → 派发 → 汇总（对齐课程 ReActPlanningAgent）。
     * <p>AgentTask 参数顺序（记录类按位置传参）：tenantId, userId, appId, conversationId, userText。
     * PlanningAgent 内部再按预算把目标拆成子任务链派给 table/chart/html 执行 Agent。
     */
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
