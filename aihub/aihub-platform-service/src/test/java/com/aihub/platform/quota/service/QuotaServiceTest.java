package com.aihub.platform.quota.service;

import com.aihub.api.client.PlatformClient;
import com.aihub.platform.quota.entity.AiQuotaPolicyDO;
import com.aihub.platform.quota.entity.AiQuotaUsageDO;
import com.aihub.platform.quota.mapper.AiQuotaPolicyMapper;
import com.aihub.platform.quota.mapper.AiQuotaUsageMapper;
import com.aihub.platform.quota.mapper.AiUsageRecordMapper;
import com.aihub.platform.user.mapper.SysTenantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private AiQuotaPolicyMapper policyMapper;
    @Mock
    private AiQuotaUsageMapper usageMapper;
    @Mock
    private AiUsageRecordMapper usageRecordMapper;
    @Mock
    private SysTenantMapper tenantMapper;

    @InjectMocks
    private QuotaService quotaService;

    private PlatformClient.QuotaRequest request() {
        return new PlatformClient.QuotaRequest("req-1", 1001L, "9001", "request", 1);
    }

    @Test
    void noPolicyMeansAllowed() {
        when(policyMapper.selectList(any())).thenReturn(List.of());
        assertTrue(quotaService.checkAndConsume(request()).allowed());
    }

    @Test
    void overLimitIsRejected() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("day", 10L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(100L));

        PlatformClient.QuotaResult result = quotaService.checkAndConsume(request());
        assertFalse(result.allowed(), "已用 100 超过限额 10，必须拒绝");
    }

    @Test
    void underLimitIsAllowed() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("day", 1000L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(5L));

        assertTrue(quotaService.checkAndConsume(request()).allowed());
    }

    /**
     * 回归用例：策略数 >= 3 时，旧的手写比较器 (a,b) -> a 是 day ? -1 : 1
     * 不满足传递性，会抛 "Comparison method violates its general contract"。
     */
    @Test
    void multiplePoliciesSortWithoutContractViolation() {
        when(policyMapper.selectList(any()))
                .thenReturn(List.of(policy("month", 20000L), policy("month", 5000L), policy("day", 100L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(1L));

        assertTrue(quotaService.checkAndConsume(request()).allowed());
    }

    @Test
    void usageIsCountedAtTenantLevel() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("day", 1000L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(1L));

        quotaService.checkAndConsume(request());
        // 策略是租户级（ai_quota_policy 无 app_id），用量也必须以 appId=null 累加，
        // 否则会出现「按应用累加、按租户查询」的口径错配
        org.mockito.Mockito.verify(usageMapper)
                .upsertConsume(any(long.class), eq(1001L), eq(null), eq("request"), any(), eq(1L));
    }

    /* ---------------- 多维扣减（M5） ---------------- */

    private PlatformClient.QuotaConsumeRequest consumeRequest(PlatformClient.QuotaItem... items) {
        return new PlatformClient.QuotaConsumeRequest("req-2", 1001L, "9001", List.of(items));
    }

    @Test
    void multipleDimensionsAllCharged() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("day", 100000L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(0L));

        PlatformClient.QuotaResult result = quotaService.consume(consumeRequest(
                new PlatformClient.QuotaItem("request", 1),
                new PlatformClient.QuotaItem("token", 500)));

        assertTrue(result.allowed());
        // request 与 token 两个维度都应被累加
        org.mockito.Mockito.verify(usageMapper)
                .upsertConsume(any(long.class), eq(1001L), eq(null), eq("request"), any(), eq(1L));
        org.mockito.Mockito.verify(usageMapper)
                .upsertConsume(any(long.class), eq(1001L), eq(null), eq("token"), any(), eq(500L));
    }

    /**
     * 关键语义：任一维度超限 → 整体拒绝，且<b>不产生任何扣减</b>。
     * 否则会出现「request 扣了、token 没扣」的脏用量。
     */
    @Test
    void rejectionDoesNotPartiallyConsume() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("day", 100L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(99L));

        PlatformClient.QuotaResult result = quotaService.consume(consumeRequest(
                new PlatformClient.QuotaItem("request", 1),   // 99 + 1 = 100，刚好不超
                new PlatformClient.QuotaItem("token", 500))); // 99 + 500 > 100，超限

        assertFalse(result.allowed(), "token 维度超限应整体拒绝");
        org.mockito.Mockito.verify(usageMapper, org.mockito.Mockito.never())
                .upsertConsume(any(long.class), any(long.class), any(), any(), any(), any(long.class));
    }

    @Test
    void dimensionWithoutPolicyIsSkipped() {
        // 只给 request 配了策略，token 未配置 → token 放行、request 正常扣
        when(policyMapper.selectList(any()))
                .thenReturn(List.of(policy("day", 1000L)))
                .thenReturn(List.of());
        when(usageMapper.selectOne(any())).thenReturn(usage(0L));

        PlatformClient.QuotaResult result = quotaService.consume(consumeRequest(
                new PlatformClient.QuotaItem("request", 1),
                new PlatformClient.QuotaItem("token", 999999)));

        assertTrue(result.allowed(), "未配置策略的维度应放行");
    }

    @Test
    void emptyItemsIsAllowed() {
        assertTrue(quotaService.consume(consumeRequest()).allowed(), "无扣减项应直接放行");
    }

    @Test
    void defaultDimensionFallsBackToRequest() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("day", 1000L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(0L));

        quotaService.consume(consumeRequest(new PlatformClient.QuotaItem(null, 1)));

        org.mockito.Mockito.verify(usageMapper)
                .upsertConsume(any(long.class), eq(1001L), eq(null), eq("request"), any(), eq(1L));
    }

    @Test
    void tokenDimensionIsSeparateFromRequest() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("day", 3000000L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(2999998L));

        // 用量 2999998 + 2 = 3000000，刚好用尽但不超限
        assertTrue(quotaService.consume(consumeRequest(
                new PlatformClient.QuotaItem("token", 2))).allowed());

        // 同样用量下再要 3 个就会越界（2999998 + 3 > 3000000）
        assertFalse(quotaService.consume(consumeRequest(
                new PlatformClient.QuotaItem("token", 3))).allowed());
    }

    private AiQuotaPolicyDO policy(String period, long limit) {
        AiQuotaPolicyDO policy = new AiQuotaPolicyDO();
        policy.setTenantId(1001L);
        policy.setDimension("request");
        policy.setPeriod(period);
        policy.setLimitValue(limit);
        return policy;
    }

    private AiQuotaUsageDO usage(long used) {
        AiQuotaUsageDO usage = new AiQuotaUsageDO();
        usage.setTenantId(1001L);
        usage.setUsedValue(used);
        return usage;
    }
}
