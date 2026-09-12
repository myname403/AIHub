package com.aihub.platform.quota.service;

import com.aihub.api.client.PlatformClient;
import com.aihub.api.client.QuotaDimensions;
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
 * 配额与用量服务（M5 落地）—— 平台的"计费闸门"。
 *
 * <p>策略：按 租户-维度-周期 匹配策略（day 优先于 month），
 * 原子累加用量后与限额比较；超限返回 allowed=false，AI 侧据此限流。
 *
 * <p><b>数据模型（三张表各司其职）：</b>
 * <ul>
 *   <li>ai_quota_policy：规则表 —— 每租户每维度每周期一条限额（如 demo 租户 token 每天 10 万）；</li>
 *   <li>ai_quota_usage：计数器表 —— 租户+维度+周期 唯一的已用量（upsert 原子累加）；</li>
 *   <li>ai_usage_record：明细流水 —— 每次调用一行（供看板聚合，不参与扣减判断）。</li>
 * </ul>
 *
 * <p><b>为什么两阶段扣减（试算→累加）：</b>一次对话要同时扣"次数"和"token"两个维度。
 * 如果先扣次数、再扣 token 时发现超限，就留下了"次数被扣了但请求没执行"的脏数据。
 * 先全部试算、全部通过才统一累加，保证"要么全扣、要么全不扣"。
 * （注意：这不是严格的数据库事务隔离，并发极端场景仍有超扣窗口，够用且简单。）
 *
 * <p>详见学习文档《04-平台服务-aihub-platform-service.md》。
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

    /** 租户有效性校验（对应 PlatformClient.checkTenant）：存在 + 状态=1 才算有效 */
    public Optional<PlatformClient.TenantBrief> checkTenant(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
            return Optional.empty();
        }
        return Optional.of(new PlatformClient.TenantBrief(tenantId, tenant.getName(), "active"));
    }

    /** 单维度扣减入口：把旧的单维请求包装成"只有一个元素"的多维请求，复用 consume 逻辑 */
    public PlatformClient.QuotaResult checkAndConsume(PlatformClient.QuotaRequest request) {
        String dimension = request.dimension() == null
                ? QuotaDimensions.REQUEST : request.dimension();   // 未指定维度默认按"次数"
        long amount = Math.max(request.amount(), 1);               // 防御：扣减量最少为 1
        return consume(new PlatformClient.QuotaConsumeRequest(
                request.requestId(), request.tenantId(), request.appId(),
                List.of(new PlatformClient.QuotaItem(dimension, amount))));
    }

    /**
     * 多维批量扣减（M5）。
     *
     * <p>两阶段：先对全部维度试算（判断是否会超限），全部通过后才真正累加。
     * 这样任一维度超限时不会留下「部分扣减」的脏用量。
     */
    public PlatformClient.QuotaResult consume(PlatformClient.QuotaConsumeRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new PlatformClient.QuotaResult(true, Long.MAX_VALUE, "无扣减项，默认放行");
        }

        // 第一阶段：试算——逐维度取策略，判断 used + amount 是否越界
        record Pending(String dimension, String periodKey, long amount) {
        }
        List<Pending> pending = new ArrayList<>();
        for (PlatformClient.QuotaItem item : request.items()) {
            String dimension = item.dimension() == null
                    ? QuotaDimensions.REQUEST : item.dimension();
            long amount = Math.max(item.amount(), 1);

            List<AiQuotaPolicyDO> sorted = sortedPolicies(request.tenantId(), dimension);
            if (sorted.isEmpty()) {
                continue; // 该维度未配置策略 → 放行
            }
            for (AiQuotaPolicyDO policy : sorted) {
                String periodKey = periodKey(policy.getPeriod());
                Long used = currentUsed(request.tenantId(), dimension, periodKey);
                long usedNow = used == null ? 0L : used;
                if (usedNow + amount > policy.getLimitValue()) {
                    log.warn("配额超限 tenant={} dim={} period={} used={} amount={} limit={}",
                            request.tenantId(), dimension, periodKey, usedNow, amount,
                            policy.getLimitValue());
                    return new PlatformClient.QuotaResult(false,
                            Math.max(policy.getLimitValue() - usedNow, 0),
                            "维度 " + dimension + " 配额已用尽");
                }
                pending.add(new Pending(dimension, periodKey, amount));
            }
        }

        // 第二阶段：全部通过，真正累加（口径为租户级，app_id = NULL —— 看板查询也用同样口径）
        for (Pending p : pending) {
            // IdWorker.getId()：MyBatis-Plus 雪花 ID 生成器（upsert 需要 id 作插入主键）
            usageMapper.upsertConsume(IdWorker.getId(), request.tenantId(),
                    null, p.dimension(), p.periodKey(), p.amount());
        }
        return new PlatformClient.QuotaResult(true, 0, "ok");
    }

    /** 取某维度的策略并按 day 优先于 month 排序（详见下方注释） */
    private List<AiQuotaPolicyDO> sortedPolicies(Long tenantId, String dimension) {
        List<AiQuotaPolicyDO> policies = policyMapper.selectList(Wrappers.<AiQuotaPolicyDO>lambdaQuery()
                .eq(AiQuotaPolicyDO::getTenantId, tenantId)
                .eq(AiQuotaPolicyDO::getDimension, dimension));
        if (policies.isEmpty()) {
            return List.of();
        }
        // day 优先于 month（更细粒度的先扣）。
        // 两个注意点：
        // ① 必须用 Comparator 而非手写三元比较——(a,b) -> a 是 day ? -1 : 1 不满足传递性，
        //    策略数 >= 3 时会抛 "Comparison method violates its general contract"；
        // ② 必须拷贝到新列表再排序——Mapper 返回的列表可能是不可变的（如 List.of / 缓存视图），
        //    原地 sort 会抛 UnsupportedOperationException。
        List<AiQuotaPolicyDO> sorted = new ArrayList<>(policies);
        sorted.sort(Comparator.comparing(p -> !"day".equalsIgnoreCase(p.getPeriod())));
        return sorted;
    }

    /** 用量上报：把 AI 侧报来的明细（token 数、耗时）插入 ai_usage_record 流水表，供看板聚合 */
    public void reportUsage(PlatformClient.UsageReport report) {
        AiUsageRecordDO record = new AiUsageRecordDO();
        record.setTenantId(report.tenantId());
        record.setAppId(parseLong(report.appId()));   // appId 在契约里是 String（兼容多端），入库转 Long
        record.setUserId(report.userId());
        record.setModelCode(report.modelCode());
        record.setTokenIn(report.tokenIn());
        record.setTokenOut(report.tokenOut());
        record.setCostMs(report.costMs());
        record.setCreateTime(LocalDateTime.now());
        usageRecordMapper.insert(record);
    }

    /** 查询当前周期的已用量（租户级口径：app_id IS NULL，与扣减写入口径严格一致） */
    private Long currentUsed(Long tenantId, String dimension, String periodKey) {
        AiQuotaUsageDO usage = usageMapper.selectOne(Wrappers.<AiQuotaUsageDO>lambdaQuery()
                .eq(AiQuotaUsageDO::getTenantId, tenantId)
                .isNull(AiQuotaUsageDO::getAppId)
                .eq(AiQuotaUsageDO::getDimension, dimension)
                .eq(AiQuotaUsageDO::getPeriodKey, periodKey)
                .last("limit 1"));
        return usage == null ? null : usage.getUsedValue();
    }

    /** 周期键：day → "2026-09-11"，month → "2026-09"。同一维度不同周期的用量各记一行 */
    private String periodKey(String period) {
        LocalDate today = LocalDate.now();
        return "month".equals(period) ? today.format(MONTH_FMT) : today.format(DAY_FMT);
    }

    /** 宽容解析：字符串转 Long 失败返回 null 而不是抛异常（上报数据允许不完整） */
    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
