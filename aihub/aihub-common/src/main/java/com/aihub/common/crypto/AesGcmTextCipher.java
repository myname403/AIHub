package com.aihub.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 文本加解密工具（用于模型 API Key 等敏感信息落库前的加密）。
 *
 * <p><b>背景知识 —— 为什么要加密？</b>
 * 用户配置的模型 API Key（如 DeepSeek 的 key）如果明文存进 MySQL，
 * 一旦数据库被拖库，所有用户的 key 直接泄露、被人盗刷。所以入库前必须加密，
 * 只有内存里使用时才解密。这叫"静态数据加密"（encryption at rest）。
 *
 * <p><b>为什么选 AES-GCM 而不是 AES-CBC？</b>
 * GCM 是"认证加密"：解密时如果密文被人篡改过一位，解密会直接失败而不是返回乱码 ——
 * 自带完整性校验。GCM 还需要随机 IV（初始化向量）和认证标签（TAG）。
 *
 * <p><b>安全约定：</b>
 * <ul>
 *   <li>密钥来自环境变量 / Nacos（{@code aihub.security.data-key}），不落库、不打日志</li>
 *   <li>每次加密使用随机 IV，密文格式 base64(iv || ciphertext)。同一明文每次加密结果都不同，防彩虹表</li>
 *   <li>密文只在服务内部解密，任何接口响应与日志中不得出现明文</li>
 * </ul>
 *
 * <p><b>类设计说明：</b>final 类 + 私有构造器 + 全静态方法 = 纯工具类，
 * 不需要创建实例（无状态），调用方直接 {@code AesGcmTextCipher.encrypt(...)}。
 * 详见学习文档《01-公共模块-aihub-common.md》。
 */
public final class AesGcmTextCipher {

    /** JCA 加密算法名："AES 算法，GCM 工作模式，NoPadding（不需要填充）"。字符串写错会在运行时抛异常 */
    private static final String ALGO = "AES/GCM/NoPadding";

    /** IV（初始化向量）长度：GCM 标准推荐 12 字节。IV 不需要保密，但绝不能重复 */
    private static final int IV_LENGTH = 12;

    /** 认证标签长度：128 bit（GCM 最强的完整性校验长度） */
    private static final int TAG_BITS = 128;

    /** 密码学安全的随机数生成器。注意：绝不能用 Random 代替，它的序列可预测 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 私有构造器：工具类禁止实例化（new 会编译报错） */
    private AesGcmTextCipher() {
    }

    /**
     * 加密：明文 → base64 字符串（格式为 base64(IV + 密文)）。
     *
     * @param plainText 明文，如 "sk-abc123..."
     * @param keyPhrase 密钥口令，来自配置中心，与解密方必须一致
     * @return 可直接存库的密文字符串
     * @throws IllegalStateException 加密失败（算法环境异常等），包装原始异常保留堆栈
     */
    public static String encrypt(String plainText, String keyPhrase) {
        try {
            // 第 1 步：生成 12 字节随机 IV。每次加密都用新的 IV，所以同一明文两次加密结果不同
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            // 第 2 步：获取 Cipher 实例并按"加密模式 + 密钥 + IV"初始化
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(keyPhrase), new GCMParameterSpec(TAG_BITS, iv));
            // 第 3 步：执行加密（doFinal 同时生成 GCM 认证标签，标签自动附加在密文尾部）
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // 第 4 步：把 IV 和密文拼在一起（解密时才能取出 IV）。IV 不是秘密，明文存放即可
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            // 第 5 步：二进制转 base64 字符串，方便存进数据库的 VARCHAR 列
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密：base64 密文 → 明文。是 encrypt 的逆过程。
     *
     * @param base64Cipher encrypt 返回的密文字符串
     * @param keyPhrase    密钥口令，必须与加密时完全一致
     * @return 原始明文
     * @throws IllegalStateException 解密失败：密钥不对、密文被篡改（GCM 校验不过）都会走到这里
     */
    public static String decrypt(String base64Cipher, String keyPhrase) {
        try {
            // 第 1 步：base64 解码回二进制，总长度 = 12 字节 IV + 密文
            byte[] all = Base64.getDecoder().decode(base64Cipher);
            // 第 2 步：按加密时的拼接顺序，把前 12 字节切出来作为 IV，其余是真正的密文
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            System.arraycopy(all, IV_LENGTH, cipherText, 0, cipherText.length);
            // 第 3 步：按"解密模式"初始化并解密。若密文被篡改，doFinal 会抛 AEADBadTagException
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(keyPhrase), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 提示语直接指出最常见原因：换了 data-key 导致旧密文解不开
            throw new IllegalStateException("解密失败（请检查 aihub.security.data-key 是否与加密时一致）", e);
        }
    }

    /**
     * 密钥派生：把任意长度的口令字符串，通过 SHA-256 哈希成固定 32 字节（256 位）的 AES 密钥。
     * AES-256 要求密钥必须恰好是 32 字节，用户配置的口令长度不定，所以先哈希一次"整形"。
     */
    private static SecretKeySpec deriveKey(String keyPhrase) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest(keyPhrase.getBytes(StandardCharsets.UTF_8));
            // SecretKeySpec 是 JCA 对"原始密钥字节"的标准包装，第二个参数声明算法用途
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }
}
