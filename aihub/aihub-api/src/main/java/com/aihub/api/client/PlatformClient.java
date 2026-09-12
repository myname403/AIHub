package com.aihub.api.client;

import com.aihub.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * AI 服务 → 平台服务 的远程契约（Feign 接口）。
 *
 * <p><b>什么是 Feign？</b>Spring Cloud 的声明式 HTTP 客户端：你只写一个接口 +
 * 注解，Feign 在运行时自动生成实现，帮你完成「服务发现 → 负载均衡 → 发 HTTP →
 * 解析 JSON → 返回对象」全过程。调用远程接口就像调用本地方法一样：
 * <pre>{@code
 *   // 注入即用，无需手写 RestTemplate/WebClient + URL 拼接
 *   R<QuotaResult> r = platformClient.checkAndConsume(request);
 * }</pre>
 *
 * <p><b>工作流程：</b>AI 服务调用本接口方法 → Feign 拦截器（见 FeignTenantConfig）
 * 先往请求里塞租户/traceId 头 → 从 Nacos 注册中心按服务名
 * {@code aihub-platform-service} 找到可用实例 → 发 HTTP 请求到对应 path
 * → 收到 JSON 反序列化成 R 对象。
 *
 * <p><b>为什么接口和 DTO 都放在独立的 aihub-api 模块？</b>
 * 调用方（ai 服务）和服务提供方（platform 服务）都要引用"一模一样"的契约。
 * 放独立模块，两边依赖同一份 jar，改接口时编译器立刻暴露两端不一致 ——
 * 这就是"契约优先"（contract-first）设计。
 *
 * <p><b>架构约束（见 docs/07-微服务与中间件设计.md 第 2 节）：</b>
 * 只允许「租户校验 / 配额扣减 / 审计上报」三类跨服务调用，且均为无事务调用。
 * 禁止跨服务 JOIN，禁止跨服务传播流式响应。
 * 详见学习文档《02-契约模块-aihub-api.md》。
 */
@FeignClient(
        name = "aihub-platform-service",   // 目标服务名：必须在 Nacos 注册中心里叫这个名字（application.yml 的 spring.application.name）
        path = "/internal"                 // 所有方法的公共路径前缀：实际请求 = http://服务名/internal/xxx
)
public interface PlatformClient {

    /** 校验租户是否有效（含租户状态）。GET /internal/tenants/check?tenantId=1001 */
    @GetMapping("/tenants/check")
    R<TenantBrief> checkTenant(@RequestParam("tenantId") Long tenantId);

    /** 校验并扣减配额；幂等，由 requestId 保证 —— 网络重试导致同一 requestId 重复到达时只扣一次 */
    @PostMapping("/quota/check")
    R<QuotaResult> checkAndConsume(@RequestBody QuotaRequest request);

    /**
     * 多维批量扣减（M5）：一次调用同时扣 request / token 等多个维度，
     * 避免 AI 侧为每个维度各发一次远程调用。
     *
     * <p>语义：任一维度超限即整体拒绝；被拒绝时不产生扣减（要么全扣、要么全不扣）。
     */
    @PostMapping("/quota/consume")
    R<QuotaResult> consume(@RequestBody QuotaConsumeRequest request);

    /** 上报用量与审计（AI 侧失败时落 outbox 异步补偿）——统计 token 消耗、记录用户行为 */
    @PostMapping("/usage/report")
    R<Void> reportUsage(@RequestBody UsageReport report);

    /**
     * 校验开放 API Key（网关调用，M5）。
     *
     * <p>Key 明文经 HTTPS 传到内网，平台侧按 HMAC 哈希比对（见 ApiKeyCodec.hash）；
     * 返回结果里只有租户与授权范围，不含任何密钥 material。
     */
    @PostMapping("/apikey/verify")
    R<ApiKeyVerifyResult> verifyApiKey(@RequestBody ApiKeyVerifyRequest request);

    /* ==================== DTO（数据传输对象） ==================== */
    // 用 Java 17 的 record：一行定义不可变数据类，自动生成构造器/getter/equals/hashCode/toString。
    // record 的字段是 final 的，天然不可变，特别适合跨服务传输的值对象。

    /** 租户摘要：checkTenant 的返回数据 */
    record TenantBrief(Long tenantId, String name, String status) {
    }

    /** API Key 校验请求：只带 Key 明文，其他信息服务端自己查 */
    record ApiKeyVerifyRequest(String apiKey) {
    }

    /** Key 校验结果：租户由 Key 反查得到，调用方无法通过传参指定（防越权关键设计） */
    record ApiKeyVerifyResult(Long keyId, Long tenantId, Long appId, String name) {
    }

    /** 单维度配额扣减请求：requestId 用于幂等去重 */
    record QuotaRequest(String requestId, Long tenantId, String appId,
                        String dimension, long amount) {
    }

    /** 单个维度的扣减项：dimension 取值见 {@link QuotaDimensions} 常量 */
    record QuotaItem(String dimension, long amount) {
    }

    /** 多维批量扣减请求 */
    record QuotaConsumeRequest(String requestId, Long tenantId, String appId,
                               List<QuotaItem> items) {
    }

    /** 扣减结果：allowed=是否允许；remain=剩余额度；reason=拒绝原因（可读提示） */
    record QuotaResult(boolean allowed, long remain, String reason) {
    }

    /** 用量上报：tokenIn/tokenOut 供计费统计，costMs 供性能监控，action 区分动作类型 */
    record UsageReport(Long tenantId, Long userId, String appId, String modelCode,
                       Integer tokenIn, Integer tokenOut, Long costMs, String action) {
    }
}
