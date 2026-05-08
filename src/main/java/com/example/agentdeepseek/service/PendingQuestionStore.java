package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.dto.PendingQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 待处理问题存储
 * 管理所有正在等待用户回答的问题
 */
@Slf4j
@Component
public class PendingQuestionStore {

    private final ConcurrentHashMap<String, PendingQuestion> store = new ConcurrentHashMap<>();

    /** 问题 TTL：5 分钟 */
    private static final long TTL_MS = 5 * 60 * 1000;

    public void put(String uuid, PendingQuestion pendingQuestion) {
        store.put(uuid, pendingQuestion);
        log.debug("存储待处理问题: uuid={}, question={}", uuid, pendingQuestion.getQuestion());
    }

    public PendingQuestion get(String uuid) {
        PendingQuestion pq = store.get(uuid);
        if (pq == null) return null;
        if (System.currentTimeMillis() - pq.getCreatedAt() > TTL_MS) {
            store.remove(uuid);
            log.warn("待处理问题已超时: uuid={}", uuid);
            return null;
        }
        return pq;
    }

    public void remove(String uuid) {
        store.remove(uuid);
    }

    /** 清理所有超时问题 */
    public void cleanup() {
        long now = System.currentTimeMillis();
        store.values().removeIf(pq -> now - pq.getCreatedAt() > TTL_MS);
    }
}
