package com.aihub.ai.infra.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 审计 Advisor（★ 同时实现 Call 与 Stream 两条路径）。
 *
 * <p>为什么必须实现 {@link StreamAdvisor}：只实现 Call 路径的 Advisor 在流式链路上不会生效
 * ——这正是 06 号文档风险 R9 描述的坑。这里两条路径都覆盖，
 * 保证 call() 与 stream() 都有审计留痕。
 *
 * <p>安全红线：审计只记录元数据（租户 / 应用 / 场景 / 模型 / token / 耗时），
 * <b>不记录提示词与回答正文</b>，避免明文进入日志。
 */
@Slf4j
public class AuditAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String CTX_TENANT = "aihub.tenantId";
    public static final String CTX_APP = "aihub.appId";
    public static final String CTX_SCENE = "aihub.scene";

    @Override
    public String getName() {
        return "aihub.audit";
    }

    /** 排在其他 Advisor 之后，确保统计到链路最终的 token 用量 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        ChatClientResponse response = chain.nextCall(request);
        audit(request, response == null ? null : response.chatResponse(),
                System.currentTimeMillis() - start, false);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.currentTimeMillis();
        AtomicReference<ChatClientResponse> last = new AtomicReference<>();
        return chain.nextStream(request)
                .doOnNext(last::set)
                .doOnComplete(() -> audit(request,
                        last.get() == null ? null : last.get().chatResponse(),
                        System.currentTimeMillis() - start, true))
                .doOnError(e -> log.warn("AI_CALL_AUDIT stream failed tenant={} err={}",
                        request.context().get(CTX_TENANT), e.getMessage()));
    }

    private void audit(ChatClientRequest request, ChatResponse response, long costMs, boolean stream) {
        String model = "";
        int tokenIn = 0;
        int tokenOut = 0;
        if (response != null && response.getMetadata() != null) {
            model = response.getMetadata().getModel() == null ? "" : response.getMetadata().getModel();
            var usage = response.getMetadata().getUsage();
            if (usage != null) {
                tokenIn = nz(usage.getPromptTokens());
                tokenOut = nz(usage.getCompletionTokens());
            }
        }
        log.info("AI_CALL_AUDIT tenant={} app={} scene={} stream={} model={} tokenIn={} tokenOut={} costMs={}",
                request.context().get(CTX_TENANT),
                request.context().get(CTX_APP),
                request.context().get(CTX_SCENE),
                stream, model, tokenIn, tokenOut, costMs);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
