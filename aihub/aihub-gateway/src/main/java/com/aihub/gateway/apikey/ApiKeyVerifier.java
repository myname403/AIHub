package com.aihub.gateway.apikey;

import com.aihub.common.security.ApiKeyCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 开放 API Key 校验客户端（网关 → 平台服务，WebFlux 非阻塞 HTTP 客户端）。
 *
 * <p><b>为什么网关要调远程接口校验：</b>网关不直连平台库（架构约束：禁止跨库访问），
 * 各服务数据各自为政，跨库读取会破坏服务边界。因此走平台暴露的内部接口
 * {@code POST /internal/apikey/verify}。
 *
 * <p><b>WebClient 是什么：</b>Spring 5 起推荐的 HTTP 客户端，支持响应式（非阻塞）。
 * 传统 RestTemplate 发请求会阻塞线程直到响应回来；WebClient 发完就返回 Mono，
 * 响应到达时再回调处理 —— 网关这种高并发场景必须非阻塞。
 * （这里没用 Feign，因为 Feign 基于阻塞模型，与 WebFlux 不兼容。）
 *
 * <p>传参说明：把 <b>Key 明文</b>发给平台侧而非本地算好的哈希——
 * 哈希需要 HMAC 密钥，把密钥铺到网关等于多一个泄露面（多一份配置、多一个泄露点）。
 * 明文只在集群内网（HTTPS/mTLS 应覆盖）传输，平台侧负责哈希比对。
 * 详见学习文档《03-网关-aihub-gateway.md》。
 */
@Slf4j
@Component
public class ApiKeyVerifier {

    /** 非阻塞 HTTP 客户端（构造器里用 Builder 配置好 baseUrl） */
    private final WebClient webClient;

    /** 校验超时：鉴权在请求主链路上，不能让平台服务拖死网关 */
    private final Duration timeout;

    /**
     * @param builder  Spring 自动提供的 WebClient.Builder（可注入说明 spring-webflux 在 classpath）
     * @param baseUrl  平台服务地址；默认值走注册中心的服务名（由负载均衡解析成实际 IP）
     * @param timeoutMs 超时毫秒数，默认 2000 —— 超过即视为校验失败
     */
    public ApiKeyVerifier(WebClient.Builder builder,
                          @Value("${aihub.apikey.verify-url:http://aihub-platform-service}") String baseUrl,
                          @Value("${aihub.apikey.verify-timeout-ms:2000}") long timeoutMs) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * 校验 Key（异步非阻塞）。
     *
     * @return 校验通过返回租户信息的 Mono；无效/过期/平台不可用均返回 empty（Mono 的"没有结果"）
     */
    public Mono<VerifiedKey> verify(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Mono.empty();   // 快速失败：空 Key 直接返回"无结果"
        }
        // 链式调用逐段解释：
        return webClient.post()                        // ① 发 POST
                .uri("/internal/apikey/verify")        // ② 完整地址 = baseUrl + uri
                .bodyValue(new VerifyRequest(plainKey)) // ③ 请求体：record 自动转 JSON
                .retrieve()                            // ④ 准备接收响应
                .bodyToMono(VerifyResponse.class)      // ⑤ 响应 JSON → VerifyResponse 对象（Mono 异步到达）
                .timeout(timeout)                      // ⑥ 2 秒没回来就抛 TimeoutException
                .flatMap(resp -> {                     // ⑦ 响应到达后的处理
                    if (resp == null || resp.code() != 0 || resp.data() == null) {
                        return Mono.empty();           // 业务失败（code!=0）→ empty，等同"校验不通过"
                    }
                    return Mono.just(resp.data());     // 成功 → 把租户信息往下游传
                })
                .onErrorResume(e -> {
                    // 平台服务不可用时拒绝而非放行：鉴权失败必须 fail-closed
                    // （fail-closed 原则：不确定时宁可拒绝，绝不能"出错就放行"造成鉴权裸奔）
                    log.warn("API Key 校验失败（拒绝请求）err={}", e.getMessage());
                    return Mono.empty();
                });
    }

    /* ---------------- 内部协议（与平台侧契约字段对齐） ---------------- */

    /** 请求体：只传 Key 明文 */
    private record VerifyRequest(String apiKey) {
    }

    /** 响应体：复用统一响应结构（code/message/data） */
    private record VerifyResponse(int code, String message, VerifiedKey data) {
    }

    /** 与平台侧 ApiKeyVerifyResult 字段对齐：只有租户与应用信息，无任何密钥材料 */
    public record VerifiedKey(Long keyId, Long tenantId, Long appId, String name) {
    }
}
