package com.aihub.platform.quota.service;

import com.aihub.platform.quota.entity.AiQuotaPolicyDO;
import com.aihub.platform.quota.entity.AiQuotaUsageDO;
import com.aihub.platform.quota.mapper.AiQuotaPolicyMapper;
import com.aihub.platform.quota.mapper.AiQuotaUsageMapper;
import com.aihub.platform.quota.mapper.AiUsageRecordMapper;
import com.aihub.platform.quota.mapper.UsageModelRow;
import com.aihub.platform.quota.mapper.UsageOverviewRow;
import com.aihub.platform.quota.mapper.UsageTrendRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageQueryServiceTest {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Long TENANT = 1001L;

    @Mock
    private AiUsageRecordMapper usageRecordMapper;
    @Mock
    private AiQuotaPolicyMapper policyMapper;
    @Mock
    private AiQuotaUsageMapper usageMapper;

    @InjectMocks
    private UsageQueryService usageQueryService;

    /* ---------------- 概览 ---------------- */

    @Test
    void overviewOnEmptyWindowReturnsZeros() {
        when(usageRecordMapper.selectOverview(any(), any())).thenReturn(null);

        UsageQueryService.UsageOverview overview = usageQueryService.overview(TENANT, 7);

        assertEquals(0, overview.calls());
        assertEquals(0, overview.tokens());
        assertEquals(7, overview.days());
    }

    @Test
    void overviewSumsTokensAndRoundsLatency() {
        UsageOverviewRow row = new UsageOverviewRow();
        row.setCalls(12L);
        row.setTokenIn(1000L);
        row.setTokenOut(2500L);
        row.setAvgCostMs(123.456d);
        row.setModelCount(3L);
        when(usageRecordMapper.selectOverview(any(), any())).thenReturn(row);

        UsageQueryService.UsageOverview overview = usageQueryService.overview(TENANT, 7);

        assertEquals(12, overview.calls());
        assertEquals(1000, overview.tokenIn());
        assertEquals(2500, overview.tokenOut());
        // tokens 是入+出，供前端直接展示「总消耗」
        assertEquals(3500, overview.tokens());
        assertEquals(123.46d, overview.avgCostMs());
        assertEquals(3, overview.modelCount());
    }

    @Test
    void overviewToleratesNullColumns() {
        // SQL 用了 coalesce，但驱动/方言差异下仍可能给到 null，必须兜底
        when(usageRecordMapper.selectOverview(any(), any())).thenReturn(new UsageOverviewRow());

        UsageQueryService.UsageOverview overview = usageQueryService.overview(TENANT, 7);

        assertEquals(0, overview.calls());
        assertEquals(0, overview.tokens());
        assertEquals(0d, overview.avgCostMs());
    }

    /* ---------------- 趋势 ---------------- */

    @Test
    void trendFillsMissingDaysWithZero() {
        // 窗口 5 天，但只有其中两天有数据
        LocalDate today = LocalDate.now();
        UsageTrendRow row1 = trendRow(today.minusDays(4).format(DAY_FMT), 3L, 100L);
        UsageTrendRow row2 = trendRow(today.format(DAY_FMT), 7L, 900L);
        when(usageRecordMapper.selectTrend(any(), any())).thenReturn(List.of(row1, row2));

        List<UsageQueryService.UsageTrend> trend = usageQueryService.trend(TENANT, 5);

        // 必须补零，否则折线图会把断档画成一条跨越的直线
        assertEquals(5, trend.size(), "窗口内每一天都要有点");
        assertEquals(0, trend.get(1).calls(), "中间的无数据日期应补 0");
        assertEquals(3, trend.get(0).calls());
        assertEquals(7, trend.get(4).calls());
        // 顺序必须是从旧到新（前端折线图的 x 轴方向）
        assertEquals(today.minusDays(4).format(DAY_FMT), trend.get(0).day());
        assertEquals(today.format(DAY_FMT), trend.get(4).day());
    }

    @Test
    void trendWindowIsClampedToMaxDays() {
        when(usageRecordMapper.selectTrend(any(), any())).thenReturn(List.of());

        List<UsageQueryService.UsageTrend> trend = usageQueryService.trend(TENANT, 5000);

        assertEquals(UsageQueryService.MAX_DAYS, trend.size(), "超大窗口必须被截断");
    }

    @Test
    void nonPositiveDaysFallsBackToSeven() {
        when(usageRecordMapper.selectTrend(any(), any())).thenReturn(List.of());

        assertEquals(7, usageQueryService.trend(TENANT, 0).size());
        assertEquals(7, usageQueryService.trend(TENANT, -3).size());
    }

    /* ---------------- 按模型 ---------------- */

    @Test
    void byModelMapsRowsAndRoundsLatency() {
        UsageModelRow row = new UsageModelRow();
        row.setModelCode("deepseek-v3");
        row.setCalls(9L);
        row.setTokenIn(100L);
        row.setTokenOut(200L);
        row.setAvgCostMs(88.888d);
        when(usageRecordMapper.selectByModel(any(), any())).thenReturn(List.of(row));

        List<UsageQueryService.UsageByModel> result = usageQueryService.byModel(TENANT, 7);

        assertEquals(1, result.size());
        assertEquals("deepseek-v3", result.get(0).modelCode());
        assertEquals(300, result.get(0).tokenIn() + result.get(0).tokenOut());
        assertEquals(88.89d, result.get(0).avgCostMs());
    }

    /* ---------------- 配额快照 ---------------- */

    @Test
    void quotaSnapshotComputesRemainAndRatio() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("request", "day", 1000L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(250L));

        List<UsageQueryService.QuotaSnapshot> snapshot = usageQueryService.quotaSnapshot(TENANT);

        assertEquals(1, snapshot.size());
        UsageQueryService.QuotaSnapshot item = snapshot.get(0);
        assertEquals(250, item.used());
        assertEquals(1000, item.limit());
        assertEquals(750, item.remain());
        assertEquals(0.25d, item.ratio());
    }

    @Test
    void missingUsageMeansZeroUsed() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("token", "month", 5000L)));
        when(usageMapper.selectOne(any())).thenReturn(null);

        UsageQueryService.QuotaSnapshot item = usageQueryService.quotaSnapshot(TENANT).get(0);

        assertEquals(0, item.used());
        assertEquals(5000, item.remain());
        assertEquals(0d, item.ratio());
    }

    /** 已超限时 remain 不给负数、ratio 封顶 1，避免前端进度条画出负宽度 */
    @Test
    void overspentQuotaIsClamped() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("doc", "day", 10L)));
        when(usageMapper.selectOne(any())).thenReturn(usage(13L));

        UsageQueryService.QuotaSnapshot item = usageQueryService.quotaSnapshot(TENANT).get(0);

        assertEquals(0, item.remain());
        assertEquals(1d, item.ratio());
    }

    @Test
    void zeroLimitDoesNotDivideByZero() {
        when(policyMapper.selectList(any())).thenReturn(List.of(policy("task", "day", 0L)));
        when(usageMapper.selectOne(any())).thenReturn(null);

        assertEquals(0d, usageQueryService.quotaSnapshot(TENANT).get(0).ratio());
    }

    @Test
    void noPolicyMeansEmptySnapshot() {
        when(policyMapper.selectList(any())).thenReturn(List.of());

        assertTrue(usageQueryService.quotaSnapshot(TENANT).isEmpty());
    }

    /** 同维度 day + month 两条策略都要展示，且 day 排在前面（与扣减优先级一致） */
    @Test
    void dailyPolicySortsBeforeMonthly() {
        when(policyMapper.selectList(any())).thenReturn(List.of(
                policy("token", "month", 9000L),
                policy("token", "day", 300L)));
        when(usageMapper.selectOne(any())).thenReturn(null);

        List<UsageQueryService.QuotaSnapshot> snapshot = usageQueryService.quotaSnapshot(TENANT);

        assertEquals(2, snapshot.size());
        assertEquals("day", snapshot.get(0).period());
        assertEquals("month", snapshot.get(1).period());
    }

    /* ---------------- 辅助 ---------------- */

    private UsageTrendRow trendRow(String day, long calls, long tokens) {
        UsageTrendRow row = new UsageTrendRow();
        row.setDay(day);
        row.setCalls(calls);
        row.setTokens(tokens);
        return row;
    }

    private AiQuotaPolicyDO policy(String dimension, String period, long limit) {
        AiQuotaPolicyDO policy = new AiQuotaPolicyDO();
        policy.setTenantId(TENANT);
        policy.setDimension(dimension);
        policy.setPeriod(period);
        policy.setLimitValue(limit);
        return policy;
    }

    private AiQuotaUsageDO usage(long used) {
        AiQuotaUsageDO usage = new AiQuotaUsageDO();
        usage.setTenantId(TENANT);
        usage.setUsedValue(used);
        return usage;
    }
}
