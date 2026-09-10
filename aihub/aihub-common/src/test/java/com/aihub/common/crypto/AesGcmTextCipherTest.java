package com.aihub.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesGcmTextCipherTest {

    private static final String KEY = "aihub-dev-data-key";

    @Test
    void encryptThenDecryptReturnsOriginal() {
        String plain = "sk-very-secret-model-key-123456";
        String cipher = AesGcmTextCipher.encrypt(plain, KEY);
        assertNotEquals(plain, cipher, "密文不得等于明文");
        assertEquals(plain, AesGcmTextCipher.decrypt(cipher, KEY));
    }

    @Test
    void samePlainTextProducesDifferentCipherText() {
        // 每次加密使用随机 IV，杜绝"相同密钥密文相同"带来的模式分析风险
        String plain = "sk-same-input";
        assertNotEquals(AesGcmTextCipher.encrypt(plain, KEY),
                AesGcmTextCipher.encrypt(plain, KEY));
    }

    @Test
    void wrongKeyFailsLoudly() {
        String cipher = AesGcmTextCipher.encrypt("sk-abc", KEY);
        assertThrows(IllegalStateException.class,
                () -> AesGcmTextCipher.decrypt(cipher, "another-data-key"),
                "密钥不一致时必须失败，而不是解出乱码");
    }

    @Test
    void chineseTextRoundTrip() {
        String plain = "中文密钥内容测试：模型名称 火山方舟 DeepSeek";
        assertEquals(plain, AesGcmTextCipher.decrypt(AesGcmTextCipher.encrypt(plain, KEY), KEY));
    }
}
