package com.aihub.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 开放 API Key 的生成与校验工具（放在 common，网关与平台服务共用）。
 *
 * <p><b>什么是 API Key？</b>除了"用户名密码登录"之外，程序间调用常用 API Key 鉴权：
 * 用户在平台控制台生成一串密钥（形如 {@code ak_xxx...}），调用时放在请求头里，
 * 服务端校验这串密钥就知道"是哪个租户在调用"。适合脚本、第三方集成场景。
 *
 * <p><b>存储策略：只存哈希，不存明文</b>。明文仅在签发时返回一次，
 * 之后任何人都无法从库里还原；遗忘只能重置。
 * （与 GitHub Personal Access Token、Stripe API Key 同一套思路。）
 *
 * <p>为什么用 HMAC-SHA256 而非 bcrypt（密码常用的慢哈希）：
 * 鉴权发生在每个请求上，bcrypt 的刻意慢（设计目的就是抗暴力破解）会直接拖垮吞吐。
 * Key 本身是 256 位随机值（不是人选的弱口令），不存在被暴力猜解的风险，
 * 因此用带服务端密钥的 HMAC 即可 —— 这样即使库被拖走，
 * 攻击者没有 HMAC 密钥也无法伪造出可用的 Key（普通 SHA-256 则可以拿拖走的哈希反查）。
 *
 * <p><b>类设计：</b>final + 私有构造器 + 全静态方法的无状态工具类。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
public final class ApiKeyCodec {

    /** Key 前缀，便于在日志/配置中一眼认出这是 AIHub 的 Key */
    public static final String PREFIX = "ak_";

    /** 随机部分字节数：24 字节 → 48 位十六进制字符，约 192 bit 熵（熵越大越难被猜中） */
    private static final int RANDOM_BYTES = 24;

    /** 密码学安全随机源，用于生成 Key */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 私有构造器：工具类禁止实例化 */
    private ApiKeyCodec() {
    }

    /** 生成一个新的 Key 明文（形如 {@code ak_<48位hex>}）。只在签发接口调用，明文只返回这一次 */
    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);   // 填充 24 个密码学安全随机字节
        // HexFormat（Java 17+）：把字节转成连续的小写十六进制字符串
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    /**
     * 计算 Key 的存储哈希（HMAC-SHA256，hex 格式）。签发时算一次存库，之后校验时重算比对。
     *
     * @param apiKey Key 明文
     * @param secret 服务端 HMAC 密钥（来自配置，不落库），相当于"库被拖走时的最后防线"
     */
    public static String hash(String apiKey, String secret) {
        if (apiKey == null) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        try {
            // Mac = Message Authentication Code（消息认证码）。HmacSHA256 = 用 SHA256 + 密钥做认证
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 API Key 哈希", e);
        }
    }

    /**
     * 取明文前缀用于展示（如 {@code ak_9f3c}），控制台里显示"ak_9f3c****"就是它。
     * 长度不足以展示时返回原值，避免 substring 越界抛异常。
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
     * 为什么不用 String.equals？equals 发现第一个不同字符就返回 false，
     * 攻击者可通过测量"比较耗时"逐字节猜出正确哈希（时序攻击）；
     * MessageDigest.isEqual 无论差在哪都耗时相同，堵死这条侧信道。
     *
     * @param rawKey       请求里带来的 Key 明文
     * @param expectedHash 库里存的哈希
     * @param secret       HMAC 密钥
     */
    public static boolean matches(String rawKey, String expectedHash, String secret) {
        if (rawKey == null || expectedHash == null) {
            return false;
        }
        // 把请求里的 Key 重新算一遍哈希，与库里的哈希做常数时间比较
        byte[] actual = hash(rawKey, secret).getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedHash.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }
}
