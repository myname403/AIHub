package com.aihub.platform.internal.controller;

import com.aihub.api.client.PlatformClient;
import com.aihub.common.result.R;
import com.aihub.platform.apikey.service.ApiKeyService;
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
 * 内部接口 —— AI 服务（和网关）通过 Feign 调用的服务间通道。
 *
 * <p><b>与 /api/** 的本质区别：</b>/api/** 面向浏览器（走网关、带 JWT、拦截器定租户）；
 * /internal/** 面向服务间调用（网关不暴露该前缀，路径无法从公网到达）。
 * 租户信息直接从请求体参数取（QuotaRequest.tenantId，由 AI 侧 Feign 拦截器从
 * 上游上下文写入），因此 WebMvcConfig 把 /internal/** 排除在租户拦截器之外。
 *
 * <p><b>契约对应关系：</b>本类的每个方法 = aihub-api 模块 PlatformClient 接口的
 * 一个方法（路径、参数、返回值一一对应）。改任何一边必须同步改另一边 ——
 * 这就是契约模块存在的意义：编译器帮你保证一致。
 *
 * <p>M5：配额真实扣减（策略 + 原子累加）、用量落库（ai_usage_record）、租户校验。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Slf4j          // Lombok：生成 log 字段
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final QuotaService quotaService;

    private final ApiKeyService apiKeyService;

    /** 对应 PlatformClient.checkTenant：校验租户存在且启用（AI 服务在关键动作前调用） */
    @GetMapping("/tenants/check")
    public R<PlatformClient.TenantBrief> checkTenant(@RequestParam("tenantId") Long tenantId) {
        Optional<PlatformClient.TenantBrief> brief = quotaService.checkTenant(tenantId);
        // Optional 的函数式处理：有值 → R.ok(...)；空 → R.fail(...)
        // 注意直接用数字 10003 而不是 ResultCode.FORBIDDEN.getCode()——两种写法等价，枚举更规范
        return brief.map(R::ok)
                .orElseGet(() -> R.fail(10003, "租户不存在或已停用"));
    }

    /**
     * 校验开放 API Key（网关调用）。
     *
     * <p>只回传 Key 归属的租户与授权范围，<b>不回传哈希</b>——
     * 哈希泄露虽不能反推明文，但会让"撞库比对"成为可能，能不给就不给。
     * 校验失败返回 10002，网关据此返回 401。
     */
    @PostMapping("/apikey/verify")
    public R<PlatformClient.ApiKeyVerifyResult> verifyApiKey(
            @RequestBody PlatformClient.ApiKeyVerifyRequest request) {
        return apiKeyService.verify(request == null ? null : request.apiKey())
                .map(p -> {
                    // 校验通过后更新使用统计（lastUsedAt/usedCount）。
                    // touch 内部吞异常：统计失败不能影响鉴权结果（鉴权已通过）
                    apiKeyService.touch(p.keyId());
                    return R.ok(new PlatformClient.ApiKeyVerifyResult(
                            p.keyId(), p.tenantId(), p.appId(), p.name()));
                })
                .orElseGet(() -> R.fail(10002, "API Key 无效、已停用或已过期"));
    }

    /** 对应 PlatformClient.checkAndConsume：单维度扣减（内部转调多维版本） */
    @PostMapping("/quota/check")
    public R<PlatformClient.QuotaResult> checkAndConsume(@RequestBody PlatformClient.QuotaRequest request) {
        return R.ok(quotaService.checkAndConsume(request));
    }

    /** 多维批量扣减（M5）：一次调用扣 request + token 等多个维度 */
    @PostMapping("/quota/consume")
    public R<PlatformClient.QuotaResult> consume(@RequestBody PlatformClient.QuotaConsumeRequest request) {
        return R.ok(quotaService.consume(request));
    }

    /** 对应 PlatformClient.reportUsage：落一条用量明细（供看板聚合） */
    @PostMapping("/usage/report")
    public R<Void> reportUsage(@RequestBody PlatformClient.UsageReport report) {
        quotaService.reportUsage(report);
        return R.ok();
    }
}
