package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 任务上下文注册表（多 Agent 并行任务）
 * <p>
 * 维护活跃任务的 TaskContext 索引，支持按 conversationId 取消（兼容现有会话级取消，
 * 且能置位 cancelFlag 使工具循环真正停止）。
 * <p>
 * 注意：一个会话同一时刻可能存在<b>多个</b>活跃上下文（主任务 + fork 的子 Agent 共享同一 conversationId），
 * 因此会话索引使用 {@link Set} 而非单值，取消时遍历全部置位，避免"只取消最后一个注册者"导致的父任务幽灵运行。
 * </p>
 * <p>
 * 注册的生命周期：任务开始（startBackgroundTask）时 register，任务结束（complete/fail/cancel）时 unregister。
 * </p>
 */
@Slf4j
@Component
public class TaskContextRegistry {

    /** taskId → TaskContext */
    private final ConcurrentHashMap<Long, TaskContext> byTaskId = new ConcurrentHashMap<>();

    /** conversationId → 该会话的所有活跃上下文集合（主任务 + 子Agent 可并存） */
    private final ConcurrentHashMap<Long, Set<TaskContext>> byConversationId = new ConcurrentHashMap<>();

    /** 注册任务上下文（同一会话可注册多个：主任务 + 子Agent） */
    public void register(TaskContext ctx) {
        if (ctx == null || ctx.getTaskId() == null) return;
        byTaskId.put(ctx.getTaskId(), ctx);
        if (ctx.getConversationId() != null) {
            byConversationId.computeIfAbsent(ctx.getConversationId(), k -> new CopyOnWriteArraySet<>()).add(ctx);
        }
    }

    /**
     * 防覆盖注册（putIfAbsent 语义）：仅当 byTaskId 中尚无该 taskId 的上下文时才注册。
     * <p>
     * 背景：同一 taskId 可能被多个线程同时注册。原 put 覆盖语义下，后注册者会顶掉先注册者的 ctx，
     * 导致先注册者的取消标志失效。putIfAbsent 使「先到先得」，后注册者自然注册失败，互不干扰。
     *
     * @return true=本次注册成功（map 中现在是本 ctx）；false=已有并发注册（本 ctx 未入 map）
     */
    public boolean registerIfAbsent(TaskContext ctx) {
        if (ctx == null || ctx.getTaskId() == null) return false;
        TaskContext prev = byTaskId.putIfAbsent(ctx.getTaskId(), ctx);
        if (prev != null) return false;
        if (ctx.getConversationId() != null) {
            byConversationId.computeIfAbsent(ctx.getConversationId(), k -> new CopyOnWriteArraySet<>()).add(ctx);
        }
        return true;
    }

    /** 注销任务上下文 */
    public void unregister(Long taskId) {
        if (taskId == null) return;
        TaskContext ctx = byTaskId.remove(taskId);
        if (ctx != null && ctx.getConversationId() != null) {
            // M3 修复：remove→isEmpty→remove(map) 是 check-then-act，竞态下可能误删新注册的上下文。
            // computeIfPresent 原子完成「移除 + 判空 + 移除条目」，并发 register 不会丢失。
            byConversationId.computeIfPresent(ctx.getConversationId(), (convId, set) -> {
                set.remove(ctx);
                return set.isEmpty() ? null : set;
            });
        }
    }

    /** 按会话ID获取（Low4 修复：返回快照副本，调用方遍历期间并发 register/unregister 不影响快照） */
    public Set<TaskContext> getByConversationId(Long conversationId) {
        if (conversationId == null) return Set.of();
        Set<TaskContext> set = byConversationId.get(conversationId);
        if (set == null || set.isEmpty()) return Set.of();
        return new java.util.HashSet<>(set);
    }

    /** 按会话ID请求取消（置位该会话所有活跃上下文的 cancelFlag，含子Agent） */
    public boolean cancelByConversationId(Long conversationId) {
        Set<TaskContext> contexts = getByConversationId(conversationId);
        if (contexts.isEmpty()) return false;
        for (TaskContext ctx : contexts) {
            ctx.cancel();
        }
        return true;
    }

    /**
     * 按会话ID取消并注销（取消路径专用）
     * 取消时 dispose 订阅不会触发 complete 回调，必须在这里同步注销，防止注册表泄漏
     */
    public void cancelAndUnregisterByConversationId(Long conversationId) {
        Set<TaskContext> contexts = getByConversationId(conversationId);
        for (TaskContext ctx : contexts) {
            ctx.cancel();
            unregister(ctx.getTaskId());
        }
    }
}
