package com.aihub.platform.quota.controller;

import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import com.aihub.platform.quota.service.UsageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用量看板接口（M5）。
 *
 * <p>租户取自会话上下文，<b>不接受前端传 tenantId</b>——否则等于开放跨租户读。
 */
@RestController
@RequestMapping("/api/platform/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageQueryService usageQueryService;

    /**
     * 概览指标卡。
     *
     * @param days 统计窗口天数，默认 7，上限 90
     */
    @GetMapping("/overview")
    public R<UsageQueryService.UsageOverview> overview(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return R.ok(usageQueryService.overview(TenantContext.requireTenantId(), days));
    }

    /** 按天趋势（含空白日期补零，前端可直接画折线） */
    @GetMapping("/trend")
    public R<List<UsageQueryService.UsageTrend>> trend(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return R.ok(usageQueryService.trend(TenantContext.requireTenantId(), days));
    }

    /** 按模型聚合（调用量倒序，最多 20 条） */
    @GetMapping("/by-model")
    public R<List<UsageQueryService.UsageByModel>> byModel(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return R.ok(usageQueryService.byModel(TenantContext.requireTenantId(), days));
    }

    /** 配额余量快照（每个维度一至两条：day / month） */
    @GetMapping("/quota")
    public R<List<UsageQueryService.QuotaSnapshot>> quota() {
        return R.ok(usageQueryService.quotaSnapshot(TenantContext.requireTenantId()));
    }
}
