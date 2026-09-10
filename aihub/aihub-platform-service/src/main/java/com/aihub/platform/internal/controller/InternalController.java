package com.aihub.platform.internal.controller;

import com.aihub.api.client.PlatformClient;
import com.aihub.common.result.R;
import com.aihub.platform.quota.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 内部接口：供 AI 服务通过 Feign 调用（网关不对外暴露 /internal/**）。
 *
 * <p>M5：配额真实扣减（策略 + 原子累加）、用量落库（ai_usage_record）、租户校验。
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final QuotaService quotaService;

    @GetMapping("/tenants/check")
    public R<PlatformClient.TenantBrief> checkTenant(@RequestParam("tenantId") Long tenantId) {
        Optional<PlatformClient.TenantBrief> brief = quotaService.checkTenant(tenantId);
        return brief.map(R::ok)
                .orElseGet(() -> R.fail(10003, "租户不存在或已停用"));
    }

    @PostMapping("/quota/check")
    public R<PlatformClient.QuotaResult> checkAndConsume(@RequestBody PlatformClient.QuotaRequest request) {
        return R.ok(quotaService.checkAndConsume(request));
    }

    @PostMapping("/usage/report")
    public R<Void> reportUsage(@RequestBody PlatformClient.UsageReport report) {
        quotaService.reportUsage(report);
        return R.ok();
    }
}
