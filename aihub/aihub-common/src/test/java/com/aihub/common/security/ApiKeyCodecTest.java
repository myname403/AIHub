package com.aihub.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开放 API Key 编解码测试。
 *
 * <p>安全组件，重点验证：随机性、哈希确定性、明文不可从哈希反推（单向性）、
 * 密钥不同则哈希不同（HMAC 的防伪能力）。
 */
class ApiKeyCodecTest {

    private static final String SECRET = "test-secret-please-change-me";

    @Test
    void generatedKeyHasPrefixAndEnoughEntropy() {
        String key = ApiKeyCodec.generate();

        assertTrue(key.startsWith(ApiKeyCodec.PREFIX), "应带 ak_ 前缀便于辨识");
        // ak_ + 24 字节的 hex = 3 + 48 = 51
        assertEquals(51, key.length(), "随机部分应为 48 位十六进制");
        assertTrue(key.substring(3).matches("[0-9a-f]{48}"));
    }

    @Test
    void generatedKeysAreUnique() {
        assertNotEquals(ApiKeyCodec.generate(), ApiKeyCodec.generate());
        assertNotEquals(ApiKeyCodec.generate(), ApiKeyCodec.generate());
    }

    @Test
    void hashIsDeterministic() {
        String key = "ak_abcdef123456";

        assertEquals(ApiKeyCodec.hash(key, SECRET), ApiKeyCodec.hash(key, SECRET),
                "同 Key 同密钥必须得到同哈希，否则无法按哈希查库");
    }

    @Test
    void hashLengthIs64HexChars() {
        // HMAC-SHA256 = 32 字节 = 64 hex，恰好放得下 key_hash VARCHAR(128)
        assertEquals(64, ApiKeyCodec.hash("ak_x", SECRET).length());
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        assertNotEquals(ApiKeyCodec.hash("ak_aaa", SECRET), ApiKeyCodec.hash("ak_bbb", SECRET));
    }

    /**
     * 关键安全属性：哈希必须依赖服务端密钥。
     * 否则库被拖走后，攻击者可直接构造出可用的 Key。
     */
    @Test
    void hashDependsOnServerSecret() {
        String key = "ak_same_key";

        assertNotEquals(ApiKeyCodec.hash(key, "secret-A"), ApiKeyCodec.hash(key, "secret-B"),
                "换密钥必须得到不同哈希——这是库泄露后的最后一道防线");
    }

    @Test
    void hashDoesNotContainPlainText() {
        String key = "ak_deadbeef12345678";
        String hash = ApiKeyCodec.hash(key, SECRET);

        assertFalse(hash.contains("deadbeef"), "哈希不得泄露明文片段");
        assertFalse(hash.contains("ak_"), "哈希不应保留前缀");
    }

    @Test
    void hashRejectsNullKey() {
        assertThrows(IllegalArgumentException.class, () -> ApiKeyCodec.hash(null, SECRET));
    }

    @Test
    void prefixOfShowsFirstFewChars() {
        assertEquals("ak_9f3c", ApiKeyCodec.prefixOf("ak_9f3c8e21ab"));
    }

    /** 前缀只用于展示，绝不能包含足够多的明文以供反推 */
    @Test
    void prefixIsShortEnoughToBeSafe() {
        String key = ApiKeyCodec.generate();
        String prefix = ApiKeyCodec.prefixOf(key);

        assertEquals(7, prefix.length());
        // 前缀只暴露 4 个随机十六进制字符 = 16 bit，远不足以暴力枚举出 48 位的余下部分
        assertTrue(prefix.length() < key.length() - 30);
    }

    @Test
    void prefixOfHandlesShortInput() {
        assertEquals("ak_x", ApiKeyCodec.prefixOf("ak_x"), "过短时原样返回，不越界");
        assertEquals(null, ApiKeyCodec.prefixOf(null));
    }

    /* ---------------- matches ---------------- */

    @Test
    void matchesAcceptsCorrectKey() {
        String key = ApiKeyCodec.generate();
        String hash = ApiKeyCodec.hash(key, SECRET);

        assertTrue(ApiKeyCodec.matches(key, hash, SECRET));
    }

    @Test
    void matchesRejectsWrongKey() {
        String hash = ApiKeyCodec.hash(ApiKeyCodec.generate(), SECRET);

        assertFalse(ApiKeyCodec.matches("ak_wrong", hash, SECRET));
    }

    @Test
    void matchesRejectsNulls() {
        assertFalse(ApiKeyCodec.matches(null, "hash", SECRET));
        assertFalse(ApiKeyCodec.matches("ak_x", null, SECRET));
    }
}
