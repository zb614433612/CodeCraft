package com.example.agentdeepseek.p2p.security;

import com.example.agentdeepseek.p2p.protocol.P2pConstants;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 对称加密工具
 * <p>
 * 用于 P2P 消息的端到端加密（在 TLS 之上额外一层，实现应用层加密）。
 * </p>
 */
public class CryptoHelper {

    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    /**
     * 使用已有密钥创建
     */
    public CryptoHelper(byte[] keyBytes) {
        if (keyBytes.length != P2pConstants.AES_KEY_SIZE / 8) {
            throw new IllegalArgumentException("AES key must be " + P2pConstants.AES_KEY_SIZE / 8 + " bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = new SecureRandom();
    }

    /**
     * 随机生成新密钥
     */
    public static CryptoHelper generate() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(P2pConstants.AES_KEY_SIZE);
            return new CryptoHelper(keyGen.generateKey().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }

    /**
     * 从 Base64 字符串加载密钥
     */
    public static CryptoHelper fromBase64(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        return new CryptoHelper(keyBytes);
    }

    /**
     * 加密：返回 IV(12字节) + 密文 + GCM Tag(16字节)
     */
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[P2pConstants.GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(P2pConstants.GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] ciphertext = cipher.doFinal(plaintext);

            // 返回 IV + 密文（含 GCM Tag）
            return ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encryption failed", e);
        }
    }

    /**
     * 解密
     * @param encrypted IV(12字节) + 密文 + GCM Tag(16字节)
     */
    public byte[] decrypt(byte[] encrypted) {
        try {
            if (encrypted.length < P2pConstants.GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("Encrypted data too short");
            }

            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            byte[] iv = new byte[P2pConstants.GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(P2pConstants.GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decryption failed", e);
        }
    }

    /**
     * 导出密钥为 Base64 字符串（用于信令交换）
     */
    public String exportKey() {
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    /**
     * 获取密钥原始字节
     */
    public byte[] getKeyBytes() {
        return secretKey.getEncoded();
    }
}
