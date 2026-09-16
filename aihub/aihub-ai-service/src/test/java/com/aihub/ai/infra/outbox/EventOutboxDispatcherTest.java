package com.aihub.ai.infra.outbox;

import com.aihub.ai.infra.persistence.dataobject.AiEventOutboxDO;
import com.aihub.ai.infra.persistence.mapper.AiEventOutboxMapper;
import com.aihub.api.client.PlatformClient;
import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发件箱投递器单测：成功 → 标记已投递；失败 → 累加重试次数；
 * 以及最关键的一点 —— <b>投递期间必须补齐租户上下文</b>。
 *
 * <p>为什么这条最要紧：后台线程没有请求上下文，而 Feign 拦截器只在租户非空时写
 * {@code X-Tenant-Id}，平台侧又强制要求该头存在。不补上下文的重投会 100% 失败，
 * 且失败三次后被放弃 —— 兜底机制看似存在却完全无效。本用例把这个坑钉住。
 */
class EventOutboxDispatcherTest {

    private static final String PAYLOAD = "{\"tenantId\":1,\"userId\":9,\"appId\":\"2\","
            + "\"modelCode\":\"m\",\"tokenIn\":10,\"tokenOut\":20,\"costMs\":5,\"action\":\"chat\"}";

    private final AiEventOutboxMapper outboxMapper = Mockito.mock(AiEventOutboxMapper.class);
    private final PlatformClient platformClient = Mockito.mock(PlatformClient.class);

    /** 不走 Spring：直接构造，调度线程由 @PostConstruct 启动，单测里不会触发 */
    private EventOutboxDispatcher dispatcher() {
        return new EventOutboxDispatcher(outboxMapper, platformClient, 50, 3, 30);
    }

    private static AiEventOutboxDO row(Long id, int retryCount) {
        AiEventOutboxDO row = new AiEventOutboxDO();
        row.setId(id);
        row.setTenantId(1L);
        row.setEventType("usage.report");
        row.setPayloadJson(PAYLOAD);
        row.setStatus(0);
        row.setRetryCount(retryCount);
        return row;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void deliversPendingEventAndMarksPublished() {
        Mockito.when(outboxMapper.selectPending(50)).thenReturn(List.of(row(1001L, 0)));
        Mockito.when(platformClient.reportUsage(Mockito.any())).thenReturn(R.ok());

        assertThat(dispatcher().pollOnce()).isEqualTo(1);

        Mockito.verify(outboxMapper).markPublished(1001L);
        Mockito.verify(outboxMapper, Mockito.never()).markRetry(Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    void tenantContextIsPresentDuringDeliveryAndClearedAfter() {
        AtomicReference<Long> tenantSeenByFeign = new AtomicReference<>();
        Mockito.when(outboxMapper.selectPending(50)).thenReturn(List.of(row(1002L, 0)));
        // 模拟"Feign 拦截器读上下文"的时刻
        Mockito.when(platformClient.reportUsage(Mockito.any())).thenAnswer(inv -> {
            tenantSeenByFeign.set(TenantContext.getTenantId());
            return R.ok();
        });

        dispatcher().pollOnce();

        assertThat(tenantSeenByFeign.get()).isEqualTo(1L);          // 投递期间有租户
        assertThat(TenantContext.getTenantId()).isNull();           // 投递后已清理，不污染下一轮
    }

    @Test
    void platformFailureIncrementsRetry() {
        Mockito.when(outboxMapper.selectPending(50)).thenReturn(List.of(row(1003L, 1)));
        Mockito.when(platformClient.reportUsage(Mockito.any()))
                .thenThrow(new RuntimeException("连接超时"));

        dispatcher().pollOnce();

        Mockito.verify(outboxMapper).markRetry(1003L, 3);
        Mockito.verify(outboxMapper, Mockito.never()).markPublished(Mockito.anyLong());
    }

    @Test
    void nonSuccessResponseCountsAsFailure() {
        // HTTP 通了但业务码非 0（例如平台侧校验不过），同样算失败要重试
        Mockito.when(outboxMapper.selectPending(50)).thenReturn(List.of(row(1004L, 0)));
        Mockito.when(platformClient.reportUsage(Mockito.any()))
                .thenReturn(R.fail(50000, "平台内部错误"));

        dispatcher().pollOnce();

        Mockito.verify(outboxMapper).markRetry(1004L, 3);
    }

    @Test
    void unknownEventTypeIsRetriedThenAbandonedByLimit() {
        AiEventOutboxDO unknown = row(1005L, 0);
        unknown.setEventType("no.such.type");
        Mockito.when(outboxMapper.selectPending(50)).thenReturn(List.of(unknown));

        dispatcher().pollOnce();

        // 走正常的重试流程（3 次后 status=2 被放弃），不会无限打转
        Mockito.verify(outboxMapper).markRetry(1005L, 3);
        Mockito.verifyNoInteractions(platformClient);
    }

    @Test
    void emptyQueueIsNoOp() {
        Mockito.when(outboxMapper.selectPending(50)).thenReturn(List.of());

        assertThat(dispatcher().pollOnce()).isZero();
        Mockito.verifyNoInteractions(platformClient);
    }
}
