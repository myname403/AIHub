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
