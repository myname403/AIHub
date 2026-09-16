package com.aihub.ai.infra.outbox;

import com.aihub.ai.domain.model.EventTypes;
import com.aihub.ai.infra.persistence.dataobject.AiEventOutboxDO;
import com.aihub.ai.infra.persistence.mapper.AiEventOutboxMapper;
import com.aihub.api.client.PlatformClient;
import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 发件箱投递器 —— 把「上报失败」的事件重投给平台服务，直至成功或超出重试上限。
 *
 * <p><b>为什么要有它：</b>用量上报是跨服务调用，平台抖动时会丢账。
 * {@link DbEventOutbox} 负责把失败事件落库，本类负责把它们捞出来重投，
 * 合起来即 Outbox 模式（至少一次投递），把账目从"尽力而为"提升为"最终一致"。
 *
 * <p><b>为什么用自带守护线程而不是 {@code @Scheduled}：</b>与
 * {@code CdpBrowserSessionManager} 的回收线程保持一致 —— 不为一个后台任务
 * 引入全局 {@code @EnableScheduling}（多一个调度线程池与潜在的配置面）。
 * 单线程 + 固定间隔足够：发件箱的写入量极小（只在跨服务调用失败时产生）。
 *
 * <p><b>★ 关键细节：投递前必须补齐租户上下文。</b>
 * 后台线程没有请求上下文，而 {@code FeignTenantConfig} 只在租户非空时写
 * {@code X-Tenant-Id} 头，平台侧的 {@code TenantResolveInterceptor} 又要求该头必须存在
 * —— 不补上下文的重投会 100% 失败（且失败三次后被放弃，等于白做）。
 * 因此这里按事件自带的 tenant_id 临时写入 {@link TenantContext}，投完立刻清理。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aihub.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventOutboxDispatcher {

    private final AiEventOutboxMapper outboxMapper;
    private final PlatformClient platformClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单次最多投递条数（防止一次拉太多拖长单轮耗时） */
    private final int batchSize;
    /** 投递失败的最大重试次数，超过即放弃（status=2） */
    private final int maxRetry;
    private final long fixedDelaySeconds;

    private ScheduledExecutorService scheduler;

    public EventOutboxDispatcher(AiEventOutboxMapper outboxMapper,
                                 PlatformClient platformClient,
                                 @Value("${aihub.outbox.batch-size:50}") int batchSize,
                                 @Value("${aihub.outbox.max-retry:3}") int maxRetry,
                                 @Value("${aihub.outbox.fixed-delay-seconds:30}") long fixedDelaySeconds) {
        this.outboxMapper = outboxMapper;
        this.platformClient = platformClient;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
        this.fixedDelaySeconds = fixedDelaySeconds;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aihub-outbox-");
            t.setDaemon(true);   // 守护线程：不阻止 JVM 退出
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollSafely,
                fixedDelaySeconds, fixedDelaySeconds, TimeUnit.SECONDS);
        log.info("发件箱投递器已启动（每 {}s 一轮，批量 {}，最大重试 {}）",
                fixedDelaySeconds, batchSize, maxRetry);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 一轮轮询的外层保护：任何异常都只记日志，绝不让调度线程死掉 */
    private void pollSafely() {
        try {
            int handled = pollOnce();
            if (handled > 0) {
                log.info("发件箱本轮投递完成，处理 {} 条", handled);
            }
        } catch (Exception e) {
            log.error("发件箱轮询异常（下轮重试）err={}", e.getMessage());
        }
    }

    /**
     * 执行一轮投递。包级可见以便单测直接驱动（不必启动调度线程）。
     *
     * @return 本轮处理的条数
     */
    int pollOnce() {
        List<AiEventOutboxDO> pending = outboxMapper.selectPending(batchSize);
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        for (AiEventOutboxDO event : pending) {
            deliver(event);
        }
        return pending.size();
    }

    private void deliver(AiEventOutboxDO event) {
        // 后台线程没有请求上下文，按事件自带的租户补齐（否则平台侧会因缺 X-Tenant-Id 拒绝）
        TenantContext.set(event.getTenantId(), null);
        try {
            doDeliver(event);
            outboxMapper.markPublished(event.getId());
            log.info("发件箱事件投递成功 id={} type={} tenant={}",
                    event.getId(), event.getEventType(), event.getTenantId());
        } catch (Exception e) {
            outboxMapper.markRetry(event.getId(), maxRetry);
            log.warn("发件箱事件投递失败 id={} type={} 已重试 {}/{} 次 err={}",
                    event.getId(), event.getEventType(),
                    event.getRetryCount() == null ? 1 : event.getRetryCount() + 1, maxRetry, e.getMessage());
        } finally {
            TenantContext.clear();   // ThreadLocal 必须清理，避免污染本线程的下一轮
        }
    }

    /** 按事件类型分发（新增事件类型时在此登记） */
    private void doDeliver(AiEventOutboxDO event) throws Exception {
        switch (event.getEventType() == null ? "" : event.getEventType()) {
            case EventTypes.USAGE_REPORT -> {
                PlatformClient.UsageReport report = objectMapper.readValue(
                        event.getPayloadJson(), PlatformClient.UsageReport.class);
                R<Void> response = platformClient.reportUsage(report);
                if (response == null || !response.isSuccess()) {
                    throw new IllegalStateException("平台返回失败: "
                            + (response == null ? "null" : response.getMessage()));
                }
            }
            // 未知类型也会走「重试 → 超限放弃」的正常流程，最终落在 status=2，不会无限打转
            default -> throw new IllegalArgumentException("未知事件类型: " + event.getEventType());
        }
    }
}
