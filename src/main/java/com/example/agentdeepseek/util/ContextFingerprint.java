package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文前缀指纹监控（保护 Prompt 缓存命中率）
 * <p>
 * <b>为什么需要它</b>：DeepSeek 的上下文缓存按「前缀单元完全匹配」命中——只要前缀里有一个字节变化，
 * 从该点之后全部失效并按「未命中输入」计价（deepseek-v4-pro 约为命中单价的 120 倍）。
 * 一个 200K 历史在会话中途被改写，单次损失就接近 0.6 元。
 * </p>
 * <p>
 * <b>它做什么</b>：每次请求前对「系统提示词」与「工具定义 JSON」各算一个指纹，与上一次比对；
 * 一旦变化立即 WARN，把"谁把缓存改坏了"从"账单爆炸后靠猜"变成"日志里一眼看到"。
 * </p>
 * <p>
 * <b>不该变化的东西</b>（出现即告警是正常预期）：
 * 时间戳/日期/UUID、会话ID、实时状态（git status、进程列表）、每轮统计数字、
 * 工具集增删、工具定义顺序调整。
 * </p>
 */
@Slf4j
@Component
public class ContextFingerprint {

    /** 最多追踪的会话数（超限时整体清理，避免长时间运行内存膨胀） */
    private static final int MAX_TRACKED_CONVERSATIONS = 800;

    /** conversationId → 上一次的 system 段指纹 */
    private final Map<Long, String> lastSystemFingerprints = new ConcurrentHashMap<>();

    /** conversationId → 上一次的 tools 段指纹 */
    private final Map<Long, String> lastToolsFingerprints = new ConcurrentHashMap<>();

    /**
     * 检查并记录前缀指纹
     *
     * @param conversationId 会话 ID（null 时跳过）
     * @param iteration      当前工具循环轮次（仅用于日志定位）
     * @param systemPrompt   当前请求使用的系统提示词（messages 里第一条 system 消息内容）
     * @param toolsJson      当前请求下发的工具定义 JSON（顺序参与计算，顺序变化即视为变化）
     */
    public void check(Long conversationId, int iteration, String systemPrompt, String toolsJson) {
        if (conversationId == null) {
            return;
        }
        try {
            String systemFp = sha256(systemPrompt);
            String toolsFp = sha256(toolsJson);

            String prevSystem = lastSystemFingerprints.put(conversationId, systemFp);
            String prevTools = lastToolsFingerprints.put(conversationId, toolsFp);

            if (prevSystem != null && !prevSystem.equals(systemFp)) {
                log.warn("[缓存告警] 系统提示词前缀变更 —— 会话={} 轮次={} {} → {}。"
                                + "从变更点起缓存将全部未命中（未命中单价约为命中的 120 倍）。"
                                + "请排查是否有时间戳/随机值/实时状态被注入 system prompt，或技能目录/项目地图等动态内容位置不对。",
                        conversationId, iteration, shortHash(prevSystem), shortHash(systemFp));
            }
            if (prevTools != null && !prevTools.equals(toolsFp)) {
                log.warn("[缓存告警] 工具定义前缀变更 —— 会话={} 轮次={} {} → {}。"
                                + "常见原因：工具集增删（Agent 配置被热改）或工具定义顺序被重新排序。"
                                + "建议配置变更走「新会话生效」，不要在会话中途热改。",
                        conversationId, iteration, shortHash(prevTools), shortHash(toolsFp));
            }

            if (lastSystemFingerprints.size() > MAX_TRACKED_CONVERSATIONS) {
                lastSystemFingerprints.clear();
                lastToolsFingerprints.clear();
                log.debug("前缀指纹追踪表已清理（超过 {} 个会话）", MAX_TRACKED_CONVERSATIONS);
            }
        } catch (Exception e) {
            // 观测能力绝不能影响主流程
            log.trace("前缀指纹计算失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 会话结束时清理该会话的追踪记录
     */
    public void forget(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        lastSystemFingerprints.remove(conversationId);
        lastToolsFingerprints.remove(conversationId);
    }

    private String sha256(String text) {
        if (text == null) {
            text = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极端兜底：退化为长度 + hashCode
            return text.length() + "-" + Integer.toHexString(text.hashCode());
        }
    }

    private String shortHash(String hash) {
        return hash != null && hash.length() > 8 ? hash.substring(0, 8) : hash;
    }
}
