package com.example.agentdeepseek.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Git Token 持久化存储
 * AES/GCM 加密后写入 {projectRoot}/.git/git-auth.json
 * 加密密钥优先从环境变量 ZB_AGENT_GIT_AUTH_SECRET 读取，无环境变量时使用内置默认密钥（向后兼容）
 *
 * 兼容说明：旧版使用 AES/ECB/PKCS5Padding 加密，读取时会自动检测并迁移到 GCM 格式
 */
@Slf4j
@Component
public class GitAuthStore {

    private static final String FILE_NAME = "git-auth.json";
    private static final String ALGORITHM_GCM = "AES/GCM/NoPadding";
    private static final String ALGORITHM_ECB = "AES/ECB/PKCS5Padding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String ENV_KEY_NAME = "ZB_AGENT_GIT_AUTH_SECRET";
    private static final byte[] FALLBACK_KEY_BYTES = "ZbAgentGitToken!".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;

    public GitAuthStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 保存 Token
     */
    public void saveToken(String projectRoot, String token) {
        try {
            String encrypted = encrypt(token);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("token", encrypted);

            Path filePath = getAuthFilePath(projectRoot);
            Files.createDirectories(filePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), node);

            log.info("Git Token 已保存到: {}", filePath);
        } catch (Exception e) {
            log.error("保存 Git Token 失败", e);
            throw new RuntimeException("保存 Git Token 失败: " + e.getMessage());
        }
    }

    /**
     * 读取 Token
     * 优先 GCM 解密，失败时自动回退 ECB 并迁移到 GCM 格式
     */
    public String getToken(String projectRoot) {
        try {
            Path filePath = getAuthFilePath(projectRoot);
            File file = filePath.toFile();
            if (!file.exists()) return null;

            ObjectNode node = objectMapper.readValue(file, ObjectNode.class);
            String encrypted = node.path("token").asText();
            if (encrypted.isEmpty()) return null;

            // 优先尝试 GCM 解密
            try {
                return decrypt(encrypted);
            } catch (Exception e) {
                log.debug("GCM 解密失败，尝试 ECB 回退: {}", e.getMessage());
            }

            // ECB 回退解密（兼容旧版数据）
            String plainText = decryptLegacy(encrypted);
            if (plainText != null) {
                // 迁移：用 GCM 重新加密并保存
                try {
                    String newEncrypted = encrypt(plainText);
                    node.put("token", newEncrypted);
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, node);
                    log.info("Git Token 已从 ECB 迁移到 GCM 加密格式");
                } catch (Exception ex) {
                    log.warn("迁移 Token 加密格式失败: {}", ex.getMessage());
                }
            }
            return plainText;
        } catch (Exception e) {
            log.error("读取 Git Token 失败", e);
            return null;
        }
    }

    /**
     * 检查 Token 是否存在
     */
    public boolean hasToken(String projectRoot) {
        return getToken(projectRoot) != null;
    }

    /**
     * 清除 Token
     */
    public void clearToken(String projectRoot) {
        try {
            Path filePath = getAuthFilePath(projectRoot);
            Files.deleteIfExists(filePath);
            log.info("Git Token 已清除: {}", filePath);
        } catch (Exception e) {
            log.error("清除 Git Token 失败", e);
        }
    }

    private Path getAuthFilePath(String projectRoot) {
        return Paths.get(projectRoot, ".git", FILE_NAME);
    }

    // ===== AES/GCM 加密解密（新格式） =====

    /**
     * AES/GCM 加密（IV 随机生成，附在密文前）
     */
    private String encrypt(String plainText) throws Exception {
        SecretKeySpec keySpec = deriveKey();
        Cipher cipher = Cipher.getInstance(ALGORITHM_GCM);

        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(iv);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        // IV + 密文 拼接后 Base64
        byte[] combined = new byte[GCM_IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
        System.arraycopy(encrypted, 0, combined, GCM_IV_LENGTH, encrypted.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * AES/GCM 解密（从密文前 12 字节提取 IV）
     */
    private String decrypt(String encryptedText) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedText);
        if (combined.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("密文太短");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, combined.length - GCM_IV_LENGTH);

        SecretKeySpec keySpec = deriveKey();
        Cipher cipher = Cipher.getInstance(ALGORITHM_GCM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    // ===== AES/ECB 加密解密（旧格式，仅用于向后兼容读取） =====

    /**
     * ECB 解密（旧版格式兼容）
     */
    private String decryptLegacy(String encryptedText) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(padKey(FALLBACK_KEY_BYTES), "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM_ECB);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("ECB 回退解密也失败，Token 可能已损坏: {}", e.getMessage());
            return null;
        }
    }

    // ===== 密钥派生 =====

    /**
     * 派生 AES 密钥：
     * 1. 优先读取环境变量 ZB_AGENT_GIT_AUTH_SECRET → SHA-256 取前 16 字节
     * 2. 无环境变量时使用内置默认密钥（向后兼容）
     */
    private SecretKeySpec deriveKey() {
        byte[] keyBytes;
        String envKey = System.getenv(ENV_KEY_NAME);
        if (envKey != null && !envKey.isEmpty()) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(envKey.getBytes(StandardCharsets.UTF_8));
                keyBytes = new byte[16];
                System.arraycopy(hash, 0, keyBytes, 0, 16);
            } catch (NoSuchAlgorithmException e) {
                log.warn("SHA-256 不可用，使用默认密钥");
                keyBytes = FALLBACK_KEY_BYTES;
            }
        } else {
            keyBytes = FALLBACK_KEY_BYTES;
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] padKey(byte[] key) {
        if (key.length == 16) return key;
        byte[] padded = new byte[16];
        System.arraycopy(key, 0, padded, 0, Math.min(key.length, 16));
        return padded;
    }
}
