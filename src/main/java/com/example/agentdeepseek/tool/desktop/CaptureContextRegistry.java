package com.example.agentdeepseek.tool.desktop;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话级"最近一次截图上下文"注册表（M5 截图工具写入 / M6 键鼠工具读取）。
 *
 * <ul>
 *   <li>内存存储（进程内）；单机桌面场景，条目小且低频。</li>
 *   <li>过期：超过 {@link #TTL_MS} 视为失效（防止用陈旧截图的坐标操作界面）。</li>
 *   <li>容量：{@link #MAX_ENTRIES} 上限，按插入顺序淘汰最旧会话条目（防极端多会话泄漏）。</li>
 * </ul>
 */
@Component
public class CaptureContextRegistry {

    /** 条目过期时间（毫秒）：30 分钟。超过则视为失效（get 返回 null） */
    private static final long TTL_MS = 30 * 60 * 1000L;

    /** 容量上限（会话数） */
    private static final int MAX_ENTRIES = 200;

    private final Map<Long, CaptureContext> contexts = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CaptureContext> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * 记录会话最近一次截图上下文（覆盖旧值）
     */
    public void put(Long conversationId, CaptureContext context) {
        if (conversationId == null || context == null) {
            return;
        }
        contexts.put(conversationId, context);
    }

    /**
     * 读取会话最近一次截图上下文（过期/不存在返回 null）
     */
    public CaptureContext get(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        CaptureContext ctx = contexts.get(conversationId);
        if (ctx == null) {
            return null;
        }
        if (System.currentTimeMillis() - ctx.capturedAtEpochMs() > TTL_MS) {
            contexts.remove(conversationId);
            return null;
        }
        return ctx;
    }

    /**
     * 移除会话的截图上下文（会话删除等场景可选调用）
     */
    public void remove(Long conversationId) {
        if (conversationId != null) {
            contexts.remove(conversationId);
        }
    }
}
