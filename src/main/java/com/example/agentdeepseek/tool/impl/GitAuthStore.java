package com.example.agentdeepseek.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Git Token 持久化存储
 * AES 加密后写入 {projectRoot}/.git/git-auth.json
 */
@Slf4j
@Component
public class GitAuthStore {

    private static final String FILE_NAME = "git-auth.json";
    private static final String ALGORITHM = "AES";
    private static final byte[] SECRET_KEY_BYTES = "ZbAgentGitToken!".getBytes(StandardCharsets.UTF_8);

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

    private String encrypt(String plainText) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(padKey(SECRET_KEY_BYTES), ALGORITHM);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String decrypt(String encryptedText) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(padKey(SECRET_KEY_BYTES), ALGORITHM);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        byte[] decoded = Base64.getDecoder().decode(encryptedText);
        return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
    }

    private byte[] padKey(byte[] key) {
        if (key.length == 16) return key;
        byte[] padded = new byte[16];
        System.arraycopy(key, 0, padded, 0, Math.min(key.length, 16));
        return padded;
    }
}
