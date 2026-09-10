package com.aihub.platform.quota.service;

import com.aihub.api.client.PlatformClient;
import com.aihub.platform.quota.entity.AiQuotaPolicyDO;
import com.aihub.platform.quota.entity.AiQuotaUsageDO;
import com.aihub.platform.quota.entity.AiUsageRecordDO;
import com.aihub.platform.quota.mapper.AiQuotaPolicyMapper;
import com.aihub.platform.quota.mapper.AiQuotaUsageMapper;
import com.aihub.platform.quota.mapper.AiUsageRecordMapper;
import com.aihub.platform.tenant.entity.SysTenant;
import com.aihub.platform.user.mapper.SysTenantMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 配额与用量服务（M5 落地）。
 *
 * <p>策略：按 租户-维度-周期 匹配策略（day 优先于 month），
 * 原子累加用量后与限额比较；超限返回 allowed=false，AI 侧据此限流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AiQuotaPolicyMapper policyMapper;
    private final AiQuotaUsageMapper usageMapper;
    private final AiUsageRecordMapper usageRecordMapper;
    private final SysTenantMapper tenantMapper;

    public Optional<PlatformClient.TenantBrief> checkTenant(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
            return Optional.empty();
        }
        return Optional.of(new PlatformClient.TenantBrief(tenantId, tenant.getName(), "active"));
    }

    public PlatformClient.QuotaResult checkAndConsume(PlatformClient.QuotaRequest request) {
        String dimension = request.dimension() == null ? "request" : request.dimension();
        long amount = Math.max(request.amount(), 1);

        List<AiQuotaPolicyDO> policies = policyMapper.selectList(Wrappers.<AiQuotaPolicyDO>lambdaQuery()
                .eq(AiQuotaPolicyDO::getTenantId, request.tenantId())
                .eq(AiQuotaPolicyDO::getDimension, dimension));
        if (policies.isEmpty()) {
            return new PlatformClient.QuotaResult(true, Long.MAX_VALUE, "未配置配额策略，默认放行");
        }

        // day 优先于 month（更细粒度的先扣）。
        // 两个注意点：
        // ① 必须用 Comparator 而非手写三元比较——(a,b) -> a 是 day ? -1 : 1 不满足传递性，
        //    策略数 >= 3 时会抛 "Comparison method violates its general contract"；
        // ② 必须拷贝到新列表再排序——Mapper 返回的列表可能是不可变的（如 List.of / 缓存视图），
        //    原地 sort 会抛 UnsupportedOperationException。
        List<AiQuotaPolicyDO> sorted = new ArrayList<>(policies);
        sorted.sort(Comparator.comparing(p -> !"day".equalsIgnoreCase(p.getPeriod())));

        for (AiQuotaPolicyDO policy : sorted) {
            String periodKey = periodKey(policy.getPeriod());
            // 口径说明：ai_quota_policy 目前是租户级（无 app_id 列），
            // 因此用量也必须按租户级统计（app_id = NULL），否则会出现
            // 「按应用累加、按租户查询」的口径错配。应用明细仍记在 ai_usage_record.app_id。
            usageMapper.upsertConsume(IdWorker.getId(), request.tenantId(),
                    null, dimension, periodKey, amount);

            Long used = currentUsed(request.tenantId(), dimension, periodKey);
            if (used != null && used > policy.getLimitValue()) {
                log.warn("配额超限 tenant={} dim={} period={} used={} limit={}",
                        request.tenantId(), dimension, periodKey, used, policy.getLimitValue());
                return new PlatformClient.QuotaResult(false,
                        Math.max(policy.getLimitValue() - used, 0), "配额已用尽");
            }
        }
        return new PlatformClient.QuotaResult(true, 0, "ok");
    }

    public void reportUsage(PlatformClient.UsageReport report) {
        AiUsageRecordDO record = new AiUsageRecordDO();
        record.setTenantId(report.tenantId());
        record.setAppId(parseLong(report.appId()));
        record.setUserId(report.userId());
        record.setModelCode(report.modelCode());
        record.setTokenIn(report.tokenIn());
        record.setTokenOut(report.tokenOut());
        record.setCostMs(report.costMs());
        record.setCreateTime(LocalDateTime.now());
        usageRecordMapper.insert(record);
    }

    private Long currentUsed(Long tenantId, String dimension, String periodKey) {
        AiQuotaUsageDO usage = usageMapper.selectOne(Wrappers.<AiQuotaUsageDO>lambdaQuery()
                .eq(AiQuotaUsageDO::getTenantId, tenantId)
                .isNull(AiQuotaUsageDO::getAppId)
                .eq(AiQuotaUsageDO::getDimension, dimension)
                .eq(AiQuotaUsageDO::getPeriodKey, periodKey)
                .last("limit 1"));
        return usage == null ? null : usage.getUsedValue();
    }

    private String periodKey(String period) {
        LocalDate today = LocalDate.now();
        return "month".equals(period) ? today.format(MONTH_FMT) : today.format(DAY_FMT);
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
