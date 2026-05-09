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
 */
@Slf4j
@Component
public class GitAuthStore {

    private static final String FILE_NAME = "git-auth.json";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
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
     */
    public String getToken(String projectRoot) {
        try {
            Path filePath = getAuthFilePath(projectRoot);
            File file = filePath.toFile();
            if (!file.exists()) return null;

            ObjectNode node = objectMapper.readValue(file, ObjectNode.class);
            String encrypted = node.path("token").asText();
            if (encrypted.isEmpty()) return null;

            return decrypt(encrypted);
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

    /**
     * AES/GCM 加密（IV 随机生成，附在密文前）
     */
    private String encrypt(String plainText) throws Exception {
        SecretKeySpec keySpec = deriveKey();
        Cipher cipher = Cipher.getInstance(ALGORITHM);

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
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

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
}
