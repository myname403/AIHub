package com.aihub.platform.quota.service;

import com.aihub.platform.quota.entity.AiQuotaPolicyDO;
import com.aihub.platform.quota.entity.AiQuotaUsageDO;
import com.aihub.platform.quota.mapper.AiQuotaPolicyMapper;
import com.aihub.platform.quota.mapper.AiQuotaUsageMapper;
import com.aihub.platform.quota.mapper.AiUsageRecordMapper;
import com.aihub.platform.quota.mapper.UsageModelRow;
import com.aihub.platform.quota.mapper.UsageOverviewRow;
import com.aihub.platform.quota.mapper.UsageTrendRow;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 用量看板查询服务（M5 补齐「只写不读」的缺口）。
 *
 * <p>与 {@link QuotaService} 的分工：那个负责扣减（写路径，跑在鉴权热链路上），
 * 这个负责聚合查询（读路径，供管理端展示）。分开是为了让写路径保持最小依赖。
 *
 * <p>所有方法都要求 tenantId，且 tenantId 由调用方从会话上下文取得——绝不接受前端传参。
 */
@Service
@RequiredArgsConstructor
public class UsageQueryService {

    /** 趋势查询允许的最大窗口，防止一次拉全表 */
    public static final int MAX_DAYS = 90;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AiUsageRecordMapper usageRecordMapper;
    private final AiQuotaPolicyMapper policyMapper;
    private final AiQuotaUsageMapper usageMapper;

    /** 概览：调用次数 / Token / 平均耗时 / 活跃模型数 */
    public UsageOverview overview(Long tenantId, int days) {
        LocalDateTime from = from(days);
        UsageOverviewRow row = usageRecordMapper.selectOverview(tenantId, from);
        if (row == null) {
            return new UsageOverview(0, 0, 0, 0, 0d, 0, days);
        }
        long tokenIn = nz(row.getTokenIn());
        long tokenOut = nz(row.getTokenOut());
        return new UsageOverview(
                nz(row.getCalls()), tokenIn, tokenOut, tokenIn + tokenOut,
                row.getAvgCostMs() == null ? 0d : Math.round(row.getAvgCostMs() * 100) / 100d,
                nz(row.getModelCount()), days);
    }

    /**
     * 按天趋势。
     *
     * <p>SQL 的 {@code group by} 只会返回<b>有数据的日期</b>，这里补齐窗口内的空白日期为 0，
     * 否则前端折线图会把「两天没调用」画成一条跨越的直线，产生误导。
     */
    public List<UsageTrend> trend(Long tenantId, int days) {
        int window = normalizeDays(days);
        LocalDateTime from = from(window);
        List<UsageTrendRow> rows = usageRecordMapper.selectTrend(tenantId, from);

        java.util.Map<String, UsageTrendRow> byDay = new java.util.HashMap<>();
        for (UsageTrendRow row : rows) {
            byDay.put(row.getDay(), row);
        }

        List<UsageTrend> result = new ArrayList<>(window);
        LocalDate today = LocalDate.now();
        for (int i = window - 1; i >= 0; i--) {
            String day = today.minusDays(i).format(DAY_FMT);
            UsageTrendRow row = byDay.get(day);
            result.add(row == null
                    ? new UsageTrend(day, 0, 0)
                    : new UsageTrend(day, nz(row.getCalls()), nz(row.getTokens())));
        }
        return result;
    }

    /** 按模型聚合（调用量倒序） */
    public List<UsageByModel> byModel(Long tenantId, int days) {
        return usageRecordMapper.selectByModel(tenantId, from(days)).stream()
                .map(row -> new UsageByModel(
                        row.getModelCode(),
                        nz(row.getCalls()),
                        nz(row.getTokenIn()),
                        nz(row.getTokenOut()),
                        row.getAvgCostMs() == null ? 0d : Math.round(row.getAvgCostMs() * 100) / 100d))
                .toList();
    }

    /**
     * 配额快照：每个维度的策略限额与当前已用量。
     *
     * <p>用量查询口径必须与扣减口径一致——都取 {@code app_id is null} 的租户级记录
     * （见 {@link QuotaService} 的累加逻辑），否则看板会显示一个从未被扣过的数字。
     */
    public List<QuotaSnapshot> quotaSnapshot(Long tenantId) {
        List<AiQuotaPolicyDO> policies = policyMapper.selectList(
                Wrappers.<AiQuotaPolicyDO>lambdaQuery()
                        .eq(AiQuotaPolicyDO::getTenantId, tenantId));
        if (policies.isEmpty()) {
            return List.of();
        }

        // 同一维度可能同时有 day 与 month 两条策略，都展示出来（前端按维度分组）
        List<AiQuotaPolicyDO> sorted = new ArrayList<>(policies);
        sorted.sort(Comparator.comparing(AiQuotaPolicyDO::getDimension)
                .thenComparing(p -> !"day".equalsIgnoreCase(p.getPeriod())));

        List<QuotaSnapshot> snapshots = new ArrayList<>(sorted.size());
        for (AiQuotaPolicyDO policy : sorted) {
            String periodKey = periodKey(policy.getPeriod());
            long used = currentUsed(tenantId, policy.getDimension(), periodKey);
            long limit = policy.getLimitValue() == null ? 0L : policy.getLimitValue();
            snapshots.add(new QuotaSnapshot(
                    policy.getDimension(), policy.getPeriod(), periodKey,
                    used, limit, Math.max(limit - used, 0L),
                    limit <= 0 ? 0d : Math.min((double) used / limit, 1d)));
        }
        return snapshots;
    }

    /* ---------------- 内部 ---------------- */

    private long currentUsed(Long tenantId, String dimension, String periodKey) {
        AiQuotaUsageDO usage = usageMapper.selectOne(Wrappers.<AiQuotaUsageDO>lambdaQuery()
                .eq(AiQuotaUsageDO::getTenantId, tenantId)
                .isNull(AiQuotaUsageDO::getAppId)
                .eq(AiQuotaUsageDO::getDimension, dimension)
                .eq(AiQuotaUsageDO::getPeriodKey, periodKey)
                .last("limit 1"));
        return usage == null || usage.getUsedValue() == null ? 0L : usage.getUsedValue();
    }

    private String periodKey(String period) {
        LocalDate today = LocalDate.now();
        return "month".equals(period) ? today.format(MONTH_FMT) : today.format(DAY_FMT);
    }

    private LocalDateTime from(int days) {
        return LocalDate.now().minusDays(normalizeDays(days) - 1L).atStartOfDay();
    }

    private int normalizeDays(int days) {
        if (days <= 0) {
            return 7;
        }
        return Math.min(days, MAX_DAYS);
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    /* ---------------- 返回视图 ---------------- */

    public record UsageOverview(long calls, long tokenIn, long tokenOut, long tokens,
                                double avgCostMs, long modelCount, int days) {
    }

    public record UsageTrend(String day, long calls, long tokens) {
    }

    public record UsageByModel(String modelCode, long calls, long tokenIn, long tokenOut,
                               double avgCostMs) {
    }

    /** quotaRemain / quotaRatio 由服务端算好，避免各端重复实现同一套除法 */
    public record QuotaSnapshot(String dimension, String period, String periodKey,
                                long used, long limit, long remain, double ratio) {
    }
}
