package com.example.agentdeepseek.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent事件总线
 * 作为 DeepSeekServiceImpl 和 AgentForkManager 之间的中介，
 * 持有 SSE 事件通道（conversationId → Sink），
 * 允许子Agent通过主Agent的事件通道发送 ask_user 事件到前端。
 * 打破 DeepSeekServiceImpl ↔ AgentForkManager 的循环依赖。
 * <p>
 * v2（方案B·游标续传）：Sink 内事件统一封装为 {@link TaskEventEnvelope}，
 * 每个会话维护单调递增 seq 计数器，所有事件（含 ask_user）经 {@link #emit} 统一发射点分配 seq。
 * 重连时前端携带 cursor，后端 skipWhile(seq &lt;= cursor) 只推增量，历史内容由 DB 兜底，
 * 根治「重放历史 ask_user 重复弹窗」「重放历史 content 重复追加」两个 bug。
 * </p>
 * <p>
 * v2.1（M7/Low3 修复，Phase 18）：
 * - 原「sink Map + seq counter Map」双 Map 在 register/unregister/emit 之间存在 check-then-act 竞态
 *   （如 emit 拿到 sink 但 counter 未 put → seq 从 1 重置，与旧游标冲突）。
 *   合并为单 Map&lt;conversationId, EventChannel&gt;（sink + lastSeq 一个 holder），操作原子化。
 * - seq 改用<b>全局单调递增</b>计数器（跨会话共享）：解决「任务代 gap」——旧任务代的 seq 在
 *   register 时重置为 0，前端带着旧游标重连新任务代会跳过新任务代前 seq 个事件。
 *   全局 seq 保证新任务代第一个事件的 seq 永远大于任何旧游标，游标续传不丢事件。
 * </p>
 */
@Slf4j
@Component
public class AgentEventBus {

    private final ObjectMapper objectMapper;

    /** 会话事件通道：单 Map 原子操作（sink + lastSeq 一个 holder），消除双 Map check-then-act 竞态 */
    private final ConcurrentHashMap<Long, EventChannel> channels = new ConcurrentHashMap<>();

    /** 全局单调递增 seq（跨会话共享，跨任务代不重置——Low3 修复） */
    private final AtomicLong globalSeq = new AtomicLong(0);

    /** 事件通道 holder：Sink + 该会话最后一次分配到的全局 seq */
    private static class EventChannel {
        final Sinks.Many<TaskEventEnvelope> sink;
        volatile long lastSeq;
        EventChannel(Sinks.Many<TaskEventEnvelope> sink) {
            this.sink = sink;
        }
    }

    public AgentEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 注册会话的SSE事件通道
     */
    public void register(Long conversationId, Sinks.Many<TaskEventEnvelope> sink) {
        if (conversationId != null && sink != null) {
            channels.put(conversationId, new EventChannel(sink));
        }
    }

    /**
     * 移除会话的SSE事件通道
     */
    public void unregister(Long conversationId) {
        if (conversationId != null) {
            channels.remove(conversationId);
        }
    }

    /**
     * 获取会话的SSE事件通道
     */
    public Sinks.Many<TaskEventEnvelope> getSink(Long conversationId) {
        EventChannel channel = conversationId != null ? channels.get(conversationId) : null;
        return channel != null ? channel.sink : null;
    }

    /**
     * 统一事件发射点：分配全局单调递增 seq 并封装为信封发射
     * <p>
     * M4 修复：加 synchronized 保证「seq 分配 + lastSeq 更新 + tryEmitNext」原子性——
     * 并发 emit 同一会话时，若三步非原子，replay sink 中出现乱序 seq，
     * 会破坏重连游标 skipWhile(seq &lt;= cursor) 的单调性假设。
     * 事件发射是轻量操作（内存写入），锁开销可忽略。
     * </p>
     * <p>
     * M2 修复：<b>成功入 buffer 后才推进 lastSeq 游标</b>——原实现先写 lastSeq 再 tryEmitNext，
     * 若 emit 失败（buffer 满等），游标已推进但事件未入 buffer，
     * 订阅方携带该游标重连时 skipWhile(seq &lt;= cursor) 会跳过「本应推送但未入 buffer 的事件」→ 丢事件。
     * 成功后再推进则游标永远 ≤ 已入 buffer 的最大 seq，重连至多多推（幂等），不会丢。
     * </p>
     *
     * @return Sinks.EmitResult.OK 表示成功；无事件通道返回 FAIL_ZERO_SUBSCRIBER
     */
    public synchronized Sinks.EmitResult emit(Long conversationId, String payload) {
        EventChannel channel = conversationId != null ? channels.get(conversationId) : null;
        if (channel == null) {
            return Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER;
        }
        long seq = globalSeq.incrementAndGet();
        Sinks.EmitResult result = channel.sink.tryEmitNext(new TaskEventEnvelope(seq, payload));
        // M2：仅发射成功才推进游标（见类注释）
        if (result == Sinks.EmitResult.OK) {
            channel.lastSeq = seq;
        }
        return result;
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
        Sinks.Many<TaskEventEnvelope> sink = getSink(conversationId);
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
            Sinks.EmitResult result = emit(conversationId, eventStr);
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
        return conversationId != null && channels.containsKey(conversationId);
    }

    /**
     * 获取会话当前最新事件序号（重连游标：前端携带此值，后端跳过 seq &lt;= cursor 的历史事件）
     * 全局 seq 方案下返回该会话最后一次发射的全局 seq；未注册过返回 0
     */
    public long getLatestSeq(Long conversationId) {
        EventChannel channel = conversationId != null ? channels.get(conversationId) : null;
        return channel != null ? channel.lastSeq : 0;
    }
}
