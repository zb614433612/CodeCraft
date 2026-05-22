package com.example.agentdeepseek.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent事件总线
 * 作为 DeepSeekServiceImpl 和 AgentForkManager 之间的中介，
 * 持有 SSE 事件通道（conversationId → Sink），
 * 允许子Agent通过主Agent的事件通道发送 ask_user 事件到前端。
 * 打破 DeepSeekServiceImpl ↔ AgentForkManager 的循环依赖。
 */
@Slf4j
@Component
public class AgentEventBus {

    private final ObjectMapper objectMapper;

    /** 会话ID → SSE事件Sink映射 */
    private final ConcurrentHashMap<Long, Sinks.Many<String>> taskEventSinks = new ConcurrentHashMap<>();

    public AgentEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 注册会话的SSE事件通道
     */
    public void register(Long conversationId, Sinks.Many<String> sink) {
        if (conversationId != null && sink != null) {
            taskEventSinks.put(conversationId, sink);
        }
    }

    /**
     * 移除会话的SSE事件通道
     */
    public void unregister(Long conversationId) {
        if (conversationId != null) {
            taskEventSinks.remove(conversationId);
        }
    }

    /**
     * 获取会话的SSE事件通道
     */
    public Sinks.Many<String> getSink(Long conversationId) {
        return conversationId != null ? taskEventSinks.get(conversationId) : null;
    }

    /**
     * 通过主Agent的SSE事件通道发送 ask_user 事件
     *
     * @param conversationId 主Agent的会话ID
     * @param uuid           问题UUID
     * @param question       问题文本
     * @param askType        事件类型：permission / clarification
     * @return true 发送成功，false 发送失败（无事件通道）
     */
    public boolean emitAskUser(Long conversationId, String uuid, String question, String askType) {
        Sinks.Many<String> sink = taskEventSinks.get(conversationId);
        if (sink == null) {
            log.warn("会话 {} 无SSE事件通道，无法发送 ask_user 事件", conversationId);
            return false;
        }
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("event", "ask_user");
            event.put("uuid", uuid);
            event.put("question", question);
            event.put("askType", askType);
            String eventStr = objectMapper.writeValueAsString(event);
            Sinks.EmitResult result = sink.tryEmitNext(eventStr);
            if (result != Sinks.EmitResult.OK) {
                log.warn("发送 ask_user 事件失败: conversationId={}, result={}", conversationId, result);
                return false;
            }
            log.info("已发送 ask_user 事件: conversationId={}, uuid={}, question={}",
                    conversationId, uuid, question.length() > 50 ? question.substring(0, 50) + "..." : question);
            return true;
        } catch (Exception e) {
            log.error("创建 ask_user 事件失败", e);
            return false;
        }
    }

    /**
     * 检查会话是否有注册的事件通道
     */
    public boolean hasSink(Long conversationId) {
        return conversationId != null && taskEventSinks.containsKey(conversationId);
    }
}
