package com.example.agentdeepseek.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 补充需求消息存储
 * <p>
 * 当用户在 AI 执行过程中主动发送补充需求时，
 * 补充消息暂存于此队列中，由 ToolLoop 在下一轮迭代开始时取出并注入。
 * </p>
 */
@Slf4j
@Component
public class SupplementStore {

    /** conversationId → 补充消息队列 */
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>> store = new ConcurrentHashMap<>();

    /**
     * 向指定会话的补充队列中放入一条消息
     * @param conversationId 会话ID
     * @param message 补充消息内容
     */
    public void offer(Long conversationId, String message) {
        store.computeIfAbsent(conversationId, k -> new ConcurrentLinkedQueue<>()).offer(message);
        log.info("补充需求已入队: conversationId={}, message={}", conversationId,
                message.length() > 100 ? message.substring(0, 100) + "..." : message);
    }

    /**
     * 从指定会话的补充队列中取出一条消息（非阻塞）
     * @param conversationId 会话ID
     * @return 补充消息，队列为空时返回 null
     */
    public String poll(Long conversationId) {
        ConcurrentLinkedQueue<String> queue = store.get(conversationId);
        if (queue == null) return null;
        String msg = queue.poll();
        if (msg != null) {
            log.info("补充需求已出队: conversationId={}", conversationId);
        }
        return msg;
    }

    /**
     * 清除指定会话的补充队列（任务结束/取消时调用）
     * @param conversationId 会话ID
     */
    public void clear(Long conversationId) {
        store.remove(conversationId);
        log.debug("补充需求队列已清除: conversationId={}", conversationId);
    }
}
