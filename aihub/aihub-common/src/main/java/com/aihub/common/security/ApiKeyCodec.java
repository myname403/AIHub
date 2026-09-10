package com.aihub.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 开放 API Key 的生成与校验（common，网关与平台服务共用）。
 *
 * <p>存储策略：<b>只存哈希，不存明文</b>。明文仅在签发时返回一次，
 * 之后任何人都无法从库里还原；遗忘只能重置。
 *
 * <p>为什么用 HMAC-SHA256 而非 bcrypt：
 * 鉴权发生在每个请求上，bcrypt 的刻意慢会直接拖垮吞吐。
 * Key 本身是 256 位随机值（不是人选的弱口令），不存在被暴力猜解的风险，
 * 因此用带服务端密钥的 HMAC 即可——这样即使库被拖走，
 * 攻击者没有 HMAC 密钥也无法伪造出可用的 Key。
 */
public final class ApiKeyCodec {

    /** Key 前缀，便于在日志/配置中一眼认出这是 AIHub 的 Key */
    public static final String PREFIX = "ak_";

    /** 随机部分字节数：24 字节 → 48 位十六进制字符，约 192 bit 熵 */
    private static final int RANDOM_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyCodec() {
    }

    /** 生成一个新的 Key 明文（形如 {@code ak_<48位hex>}） */
    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    /**
     * 计算 Key 的存储哈希（HMAC-SHA256，hex）。
     *
     * @param secret 服务端 HMAC 密钥（来自配置，不落库）
     */
    public static String hash(String apiKey, String secret) {
        if (apiKey == null) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 API Key 哈希", e);
        }
    }

    /**
     * 取明文前缀用于展示（如 {@code ak_9f3c}）。
     * 长度不足以展示时返回原值，避免越界。
     */
    public static String prefixOf(String apiKey) {
        if (apiKey == null || apiKey.length() <= PREFIX.length() + 4) {
            return apiKey;
        }
        return apiKey.substring(0, PREFIX.length() + 4);
    }

    /**
     * 定时安全比较（防时序侧信道）。
     *
     * <p>注：正常流程是「查库按 hash 匹配」，本方法用于需要内存比对的场景。
     */
    public static boolean matches(String rawKey, String expectedHash, String secret) {
        if (rawKey == null || expectedHash == null) {
            return false;
        }
        byte[] actual = hash(rawKey, secret).getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedHash.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }
}
