package com.aihub.api.client;

import com.aihub.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

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

    /**
     * 多维批量扣减（M5）：一次调用同时扣 request / token 等多个维度，
     * 避免 AI 侧为每个维度各发一次远程调用。
     *
     * <p>语义：任一维度超限即整体拒绝；被拒绝时不产生扣减。
     */
    @PostMapping("/quota/consume")
    R<QuotaResult> consume(@RequestBody QuotaConsumeRequest request);

    /** 上报用量与审计（AI 侧失败时落 outbox 异步补偿） */
    @PostMapping("/usage/report")
    R<Void> reportUsage(@RequestBody UsageReport report);

    /**
     * 校验开放 API Key（网关调用，M5）。
     *
     * <p>Key 明文经 HTTPS 传到内网，平台侧按 HMAC 哈希比对；
     * 返回结果里只有租户与授权范围，不含任何密钥material。
     */
    @PostMapping("/apikey/verify")
    R<ApiKeyVerifyResult> verifyApiKey(@RequestBody ApiKeyVerifyRequest request);

    /* ---------- DTO ---------- */

    record TenantBrief(Long tenantId, String name, String status) {
    }

    record ApiKeyVerifyRequest(String apiKey) {
    }

    /** Key 校验结果：租户由 Key 反查得到，调用方无法通过传参指定 */
    record ApiKeyVerifyResult(Long keyId, Long tenantId, Long appId, String name) {
    }

    record QuotaRequest(String requestId, Long tenantId, String appId,
                        String dimension, long amount) {
    }

    /** 单个维度的扣减项 */
    record QuotaItem(String dimension, long amount) {
    }

    record QuotaConsumeRequest(String requestId, Long tenantId, String appId,
                               List<QuotaItem> items) {
    }

    record QuotaResult(boolean allowed, long remain, String reason) {
    }

    record UsageReport(Long tenantId, Long userId, String appId, String modelCode,
                       Integer tokenIn, Integer tokenOut, Long costMs, String action) {
    }
}
