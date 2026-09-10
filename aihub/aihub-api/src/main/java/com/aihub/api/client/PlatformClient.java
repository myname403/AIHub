package com.aihub.api.client;

import com.aihub.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * AI 服务 -> 平台服务 的远程契约。
 *
 * <p>架构约束（见 07 号文档第 2 节）：只允许「租户校验 / 配额扣减 / 审计上报」三类跨服务调用，
 * 且均为无事务调用。禁止跨服务 JOIN，禁止跨服务传播流式响应。
 */
@FeignClient(name = "aihub-platform-service", path = "/internal")
public interface PlatformClient {

    /** 校验租户是否有效（含租户状态） */
    @GetMapping("/tenants/check")
    R<TenantBrief> checkTenant(@RequestParam("tenantId") Long tenantId);

    /** 校验并扣减配额；幂等，由 requestId 保证 */
    @PostMapping("/quota/check")
    R<QuotaResult> checkAndConsume(@RequestBody QuotaRequest request);

    /** 上报用量与审计（AI 侧失败时落 outbox 异步补偿） */
    @PostMapping("/usage/report")
    R<Void> reportUsage(@RequestBody UsageReport report);

    /* ---------- DTO ---------- */

    record TenantBrief(Long tenantId, String name, String status) {
    }

    record QuotaRequest(String requestId, Long tenantId, String appId,
                        String dimension, long amount) {
    }

    record QuotaResult(boolean allowed, long remain, String reason) {
    }

    record UsageReport(Long tenantId, Long userId, String appId, String modelCode,
                       Integer tokenIn, Integer tokenOut, Long costMs, String action) {
    }
}
