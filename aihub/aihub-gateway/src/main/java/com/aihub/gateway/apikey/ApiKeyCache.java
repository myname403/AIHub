package com.aihub.gateway.apikey;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * API Key 校验结果的本地缓存。
 *
 * <p>为什么需要：鉴权在每请求主链路上，若每次都回调平台服务，
 * 既增加延迟又多一个故障点。Key 的校验结果短期内稳定，
 * 缓存 60 秒是延迟与「吊销及时性」之间的平衡。
 *
 * <p>缓存键是 <b>Key 明文</b>还是哈希？
 * 用哈希——避免明文长期驻留堆内存（堆 dump 会泄露全部活跃 Key）。
 * 这里复用 {@link com.aihub.common.security.ApiKeyCodec} 的哈希逻辑，
 * 但使用<b>独立的缓存密钥</b>，与平台侧存储哈希解耦。
 *
 * <p>缓存失败结果吗？不缓存。否则平台服务抖动 60 秒内所有请求都会被误拒。
 */
@Slf4j
@Component
public class ApiKeyCache {

    private final ApiKeyVerifier verifier;
    private final AsyncCache<String, Optional<ApiKeyVerifier.VerifiedKey>> cache;
    private final boolean enabled;

    public ApiKeyCache(ApiKeyVerifier verifier,
                       @Value("${aihub.apikey.cache-enabled:true}") boolean enabled,
                       @Value("${aihub.apikey.cache-ttl-seconds:60}") long ttlSeconds,
                       @Value("${aihub.apikey.cache-max-size:10000}") long maxSize) {
        this.verifier = verifier;
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                // 缓存的是「已校验结果」，命中即省一次远程调用
                .buildAsync();
    }

    /**
     * 校验 Key，命中缓存则直接返回。
     *
     * @return 校验通过返回租户信息；否则 empty
     */
    public Mono<ApiKeyVerifier.VerifiedKey> verify(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Mono.empty();
        }
        if (!enabled) {
            return verifier.verify(plainKey);
        }
        // 用哈希做缓存键：避免明文长期驻留堆内存
        String cacheKey = cacheKeyOf(plainKey);
        return Mono.fromFuture(cache.get(cacheKey, (k, executor) -> verifier.verify(plainKey)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .toFuture()))
                // 失败结果不落缓存：否则平台抖动会被放大成 60 秒全量拒绝
                .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty))
                .doOnNext(v -> log.debug("API Key 校验通过（可能命中缓存）keyId={}", v.keyId()));
    }

    /** 手动失效（吊销 Key 后调用，让缓存立即过期） */
    public void invalidate(String plainKey) {
        if (plainKey != null && enabled) {
            cache.synchronous().invalidate(cacheKeyOf(plainKey));
        }
    }

    public void invalidateAll() {
        cache.synchronous().invalidateAll();
    }

    /**
     * 缓存键：取哈希后截断，只用于本地比对，不参与任何安全判定。
     * 这里用 JDK 自带哈希即可——它不是密码学用途，仅作 Map 的 key。
     */
    private String cacheKeyOf(String plainKey) {
        return Integer.toHexString(plainKey.hashCode()) + ":" + plainKey.length();
    }
}
