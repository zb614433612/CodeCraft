package com.example.agentdeepseek.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本地 Token 存储（基于 Caffeine 内嵌缓存，替代 Redis）
 * Token 和验证码均存储在本地内存中，支持自动过期
 */
@Slf4j
@Component
public class TokenStore {

    /** Token 过期时间（秒） */
    private static final long TOKEN_EXPIRE_SECONDS = 7200; // 2小时

    /** 验证码过期时间（秒） */
    private static final long CODE_EXPIRE_SECONDS = 60; // 1分钟

    /** 验证码缓存 */
    private final Cache<String, String> codeCache = Caffeine.newBuilder()
            .expireAfterWrite(CODE_EXPIRE_SECONDS, TimeUnit.SECONDS)
            .maximumSize(1000)
            .build();

    /** Token 用户信息缓存（key=token, value=Map<field, value>） */
    private final Cache<String, Map<String, String>> tokenCache = Caffeine.newBuilder()
            .expireAfterWrite(TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS)
            .maximumSize(10000)
            .build();

    @PostConstruct
    public void init() {
        log.info("TokenStore 初始化完成（Caffeine 本地缓存），Token 过期={}s，验证码过期={}s",
                TOKEN_EXPIRE_SECONDS, CODE_EXPIRE_SECONDS);
    }

    // ==================== 验证码 ====================

    public void saveRandomCode(String username, String code) {
        codeCache.put(username, code);
    }

    public String getRandomCode(String username) {
        return codeCache.getIfPresent(username);
    }

    public void deleteRandomCode(String username) {
        codeCache.invalidate(username);
    }

    // ==================== Token ====================

    public void saveToken(String token, Map<String, String> userInfo) {
        tokenCache.put(token, new HashMap<>(userInfo));
    }

    public boolean hasToken(String token) {
        return tokenCache.getIfPresent(token) != null;
    }

    public String getTokenField(String token, String field) {
        Map<String, String> userInfo = tokenCache.getIfPresent(token);
        if (userInfo != null) {
            return userInfo.get(field);
        }
        return null;
    }

    /** 刷新 Token 过期时间（Caffeine 会自动处理，此方法保留兼容） */
    public void refreshToken(String token) {
        // Caffeine 在写入时自动重置过期时间，此处只需重新 put 即可刷新
        Map<String, String> userInfo = tokenCache.getIfPresent(token);
        if (userInfo != null) {
            tokenCache.put(token, userInfo);
        }
    }

    public void deleteToken(String token) {
        tokenCache.invalidate(token);
    }
}
