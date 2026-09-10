package com.aihub.gateway.apikey;

import com.aihub.common.security.ApiKeyCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 开放 API Key 校验客户端（网关 → 平台服务）。
 *
 * <p>网关不直连平台库（架构约束：禁止跨库），因此走内部接口校验。
 *
 * <p>传参说明：把 <b>Key 明文</b>发给平台侧而非本地算好的哈希——
 * 哈希需要 HMAC 密钥，把密钥铺到网关等于多一个泄露面。
 * 明文只在集群内网（HTTPS/mTLS 应覆盖）传输，平台侧负责哈希比对。
 */
@Slf4j
@Component
public class ApiKeyVerifier {

    private final WebClient webClient;

    /** 校验超时：鉴权在请求主链路上，不能让平台服务拖死网关 */
    private final Duration timeout;

    public ApiKeyVerifier(WebClient.Builder builder,
                          @Value("${aihub.apikey.verify-url:http://aihub-platform-service}") String baseUrl,
                          @Value("${aihub.apikey.verify-timeout-ms:2000}") long timeoutMs) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * 校验 Key。
     *
     * @return 校验通过返回租户信息；无效/过期/平台不可用均返回 empty
     */
    public Mono<VerifiedKey> verify(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Mono.empty();
        }
        return webClient.post()
                .uri("/internal/apikey/verify")
                .bodyValue(new VerifyRequest(plainKey))
                .retrieve()
                .bodyToMono(VerifyResponse.class)
                .timeout(timeout)
                .flatMap(resp -> {
                    if (resp == null || resp.code() != 0 || resp.data() == null) {
                        return Mono.empty();
                    }
                    return Mono.just(resp.data());
                })
                .onErrorResume(e -> {
                    // 平台服务不可用时拒绝而非放行：鉴权失败必须 fail-closed
                    log.warn("API Key 校验失败（拒绝请求）err={}", e.getMessage());
                    return Mono.empty();
                });
    }

    /* ---------------- 内部协议 ---------------- */

    private record VerifyRequest(String apiKey) {
    }

    private record VerifyResponse(int code, String message, VerifiedKey data) {
    }

    /** 与平台侧 ApiKeyVerifyResult 字段对齐 */
    public record VerifiedKey(Long keyId, Long tenantId, Long appId, String name) {
    }
}
