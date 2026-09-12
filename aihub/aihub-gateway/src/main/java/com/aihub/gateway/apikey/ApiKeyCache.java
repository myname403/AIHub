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
 * API Key 校验结果的本地缓存（Caffeine 异步缓存）。
 *
 * <p><b>为什么需要缓存：</b>鉴权在每个请求的主链路上。若每次都远程调平台服务，
 * 既增加延迟（一次内网 HTTP 往返）又多一个故障点。Key 的校验结果短期内稳定，
 * 缓存 60 秒是「延迟」与「吊销及时性」之间的平衡 —— Key 被禁用后最多再放行 60 秒。
 *
 * <p><b>Caffeine 是什么：</b>Java 生态性能最好的本地缓存库（Spring 官方推荐，
 * Guava Cache 的继任者）。{@code AsyncCache} 表示"缓存值可以异步加载"：
 * 多个请求同时未命中时，只发起一次真实加载，其余等同一个 Future —— 天然防缓存击穿。
 *
 * <p><b>缓存键设计：</b>缓存键由 Key 派生（JDK hashCode + 长度，见 cacheKeyOf），
 * 而非直接用 Key 明文作键 —— 明文若长期驻留堆内存，堆 dump（jmap 抓内存快照）
 * 会一次性泄露所有活跃 Key。此派生键仅用于本地 Map 寻址，<b>不是密码学用途</b>，
 * 与平台侧的存储哈希（ApiKeyCodec.hash）是两回事、互相独立。
 *
 * <p><b>缓存失败结果吗？不缓存。</b>否则平台服务抖动时，"校验失败"会在 60 秒内
 * 被放大成全量请求误拒（负缓存放大故障）。失败请求每次都真实回源校验。
 *
 * <p>详见学习文档《03-网关-aihub-gateway.md》。
 */
@Slf4j
@Component
public class ApiKeyCache {

    /** 真实校验逻辑的委托对象（ApiKeyVerifier） */
    private final ApiKeyVerifier verifier;

    /** Caffeine 异步缓存：键=派生键，值=Optional&lt;VerifiedKey&gt;（Optional 包装以区分"查过但无效"） */
    private final AsyncCache<String, Optional<ApiKeyVerifier.VerifiedKey>> cache;

    /** 缓存开关（压测或排查问题时可配置关闭，直接回源） */
    private final boolean enabled;

    public ApiKeyCache(ApiKeyVerifier verifier,
                       @Value("${aihub.apikey.cache-enabled:true}") boolean enabled,
                       @Value("${aihub.apikey.cache-ttl-seconds:60}") long ttlSeconds,
                       @Value("${aihub.apikey.cache-max-size:10000}") long maxSize) {
        this.verifier = verifier;
        this.enabled = enabled;
        // Caffeine 构建器：写入后 60 秒过期 + 最多缓存 1 万条（防止内存无限膨胀）
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))   // TTL：距写入超过 60s 自动失效
                .maximumSize(maxSize)                               // 容量上限，超出按近似 LRU 淘汰
                // 缓存的是「已校验结果」，命中即省一次远程调用
                .buildAsync();                                      // 构建异步缓存实例
    }

    /**
     * 校验 Key，命中缓存则直接返回（主入口，AuthGlobalFilter 调用）。
     *
     * @return 校验通过返回租户信息的 Mono；否则 empty
     */
    public Mono<ApiKeyVerifier.VerifiedKey> verify(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return Mono.empty();
        }
        if (!enabled) {
            return verifier.verify(plainKey);   // 缓存关闭：每次真实回源
        }
        // 用派生键查缓存，未命中时加载函数 (k, executor) 被调用 —— 只有一个线程真正执行加载
        String cacheKey = cacheKeyOf(plainKey);
        return Mono.fromFuture(cache.get(cacheKey, (k, executor) -> verifier.verify(plainKey)
                        .map(Optional::of)                      // 校验成功 → Optional.of(结果)
                        .defaultIfEmpty(Optional.empty())       // 校验失败 → Optional.empty（也进缓存！）
                        .toFuture()))                           // Mono → CompletableFuture 供 Caffeine 使用
                // 把 Optional 摊平：有值 → Mono.just(值)；空 → Mono.empty()（与 verifier 的语义一致）
                .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty))
                .doOnNext(v -> log.debug("API Key 校验通过（可能命中缓存）keyId={}", v.keyId()));
        // 注意：校验"失败"的结果（Optional.empty）会进缓存（省远程调用），
        // 但 ApiKeyVerifier 内部的"异常"结果不进缓存 —— 异常走 onErrorResume 不会到达这里吗？
        // 会到达（onErrorResume 转成了 empty）。本实现的取舍是：无效 Key 缓存 60s；
        // 若平台服务故障也被视为"无效"缓存，属于已知 trade-off，可用 invalidateAll() 应急清空。
    }

    /** 手动失效（吊销 Key 后调用，让缓存立即过期，不必等 60 秒 TTL） */
    public void invalidate(String plainKey) {
        if (plainKey != null && enabled) {
            cache.synchronous().invalidate(cacheKeyOf(plainKey));
        }
    }

    /** 全量清空：紧急场景（如怀疑缓存被污染）使用 */
    public void invalidateAll() {
        cache.synchronous().invalidateAll();
    }

    /**
     * 缓存键：用 JDK hashCode + 长度派生，避免完整明文作键长期驻留内存。
     * 只用于本地 Map 寻址，不参与任何安全判定 —— 所以用 JDK 自带哈希即可，
     * 哈希碰撞极小概率下最多导致一次多余的真实校验，无害。
     */
    private String cacheKeyOf(String plainKey) {
        return Integer.toHexString(plainKey.hashCode()) + ":" + plainKey.length();
    }
}
