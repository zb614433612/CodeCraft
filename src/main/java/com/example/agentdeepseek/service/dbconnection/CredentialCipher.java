package com.example.agentdeepseek.service.dbconnection;

import com.example.agentdeepseek.p2p.security.CryptoHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 数据库连接密码加解密（Phase 20）
 * <p>
 * AES-256-GCM（复用 P2P CryptoHelper）：db_connection.password_encrypted 列加密存储，
 * API 与列表绝不回显明文。密钥来源：application.yml codecraft.db-connection.credential-key
 * （Base64 32 字节）；未配置时使用内置默认密钥并告警（生产环境建议配置）。
 * </p>
 */
@Slf4j
@Component
public class CredentialCipher {

    /** 内置默认密钥（32 字节 "0123456789abcdef0123456789abcdef" 的 Base64；仅开发兜底，生产必须配置） */
    private static final String DEFAULT_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final CryptoHelper cryptoHelper;

    public CredentialCipher(@Value("${codecraft.db-connection.credential-key:}") String credentialKeyBase64) {
        String keyBase64 = (credentialKeyBase64 == null || credentialKeyBase64.isBlank())
                ? DEFAULT_KEY_BASE64 : credentialKeyBase64.trim();
        if ((credentialKeyBase64 == null || credentialKeyBase64.isBlank())) {
            log.warn("codecraft.db-connection.credential-key 未配置，使用内置默认密钥加密数据库连接密码（生产环境请配置 32 字节 Base64 密钥）");
        }
        this.cryptoHelper = CryptoHelper.fromBase64(keyBase64);
    }

    /** 加密明文密码 → Base64 存储串 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        byte[] encrypted = cryptoHelper.encrypt(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /** 解密存储串 → 明文密码（失败返回 null 并告警） */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            byte[] decrypted = cryptoHelper.decrypt(Base64.getDecoder().decode(stored));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("数据库连接密码解密失败（密钥变更或数据损坏？）: {}", e.getMessage());
            return null;
        }
    }
}
