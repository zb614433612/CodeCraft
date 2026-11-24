package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
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
 * <b>它做什么</b>（三档观测，全部只读，不改发送内容）：
 * </p>
 * <ol>
 *   <li><b>system 段指纹</b>：把「开头连续的所有 system 消息」拼起来算一个指纹（遇到第一条非 system 即停）。
 *       为什么是「开头连续」而不是「全部」：末尾的语言强制指令 / 知识库摘要本来就每轮追加在尾部，
 *       把它们算进来只会制造无关噪音；而技能目录是拼在第一条 system 上的，属于真正的头部内容。</li>
 *   <li><b>工具定义指纹</b>：tools JSON 顺序参与计算，顺序变化即视为变化。</li>
 *   <li><b>逐消息前缀指纹</b>：每次请求前把「每条消息的 role + content + tool_call_id + tool_calls +
 *       reasoning_content」压成轻量指纹存进会话级列表，下次请求逐条比对，直接点名
 *       <b>第一条不一致的消息 index / role / 长度差</b>。
 *       这一档专治「跨轮第一枪命中率暴跌」：system 没变、tools 没变，但历史里某条被重建改写了 ——
 *       旧监控对这类断裂完全是瞎的。</li>
 * </ol>
 * <p>
 * <b>怎么读日志</b>：
 * </p>
 * <ul>
 *   <li>{@code [前缀追踪] ... 前缀一致}（DEBUG）= 正常增长，无需处理；</li>
 *   <li>{@code [缓存告警] 消息前缀断裂 ... index=N role=tool 长度: 16126 → 16270（差 +144）}
 *       = 第 N 条（从 0 开始）开始断，通常就是「内存态发出的内容 vs DB 重建的内容」口径不一致；</li>
 *   <li>{@code [缓存告警] 系统提示词前缀变更} = 头部 system 真的变了，优先查技能目录 / 知识库摘要位置。</li>
 * </ul>
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

    /** 单会话最多追踪的消息条数（超长会话只比对前 N 条，控制每轮哈希开销） */
    private static final int MAX_TRACKED_MESSAGES = 4000;

    /** 字段分隔符：\u0001 不会出现在正常文本里，避免拼接歧义 */
    private static final char FIELD_SEP = '\u0001';

    /** system 段之间的分隔符 */
    private static final char SECTION_SEP = '\u0002';

    /** conversationId → 上一次的 system 段指纹 */
    private final Map<Long, String> lastSystemFingerprints = new ConcurrentHashMap<>();

    /** conversationId → 上一次的 tools 段指纹 */
    private final Map<Long, String> lastToolsFingerprints = new ConcurrentHashMap<>();

    /** conversationId → 上一次请求的逐条消息指纹（用于定位断裂点） */
    private final Map<Long, List<MessageFp>> lastMessageFingerprints = new ConcurrentHashMap<>();

    /**
     * 单条消息的轻量指纹。
     * <p>只用「role + 长度 + 内容 hash」三要素：足够判定同一条消息是否被改写，
     * 且每条只占几十字节，长会话（数千条消息）也不会把堆撑起来。</p>
     */
    private record MessageFp(String role, int length, int hash) {
    }

    /**
     * 检查并记录前缀指纹（system 段 + tools 段）
     *
     * @param conversationId 会话 ID（null 时跳过）
     * @param iteration      当前工具循环轮次（仅用于日志定位）
     * @param systemPrompt   当前请求头部 system 段的拼接文本（见 {@code extractSystemPromptForFingerprint}）
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
                lastMessageFingerprints.clear();
                log.debug("前缀指纹追踪表已清理（超过 {} 个会话）", MAX_TRACKED_CONVERSATIONS);
            }
        } catch (Exception e) {
            // 观测能力绝不能影响主流程
            log.trace("前缀指纹计算失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 检查并记录「逐消息前缀指纹」，定位第一条被改写的消息。
     *
     * <p><b>判定语义</b>：以上一次请求的消息序列为基准，逐条比对本次请求的前
     * {@code min(上次条数, 本次条数)} 条：</p>
     * <ul>
     *   <li>全部一致 → 属于正常增长（工具循环追加 / 本轮追加 user 消息），只在消息数缩减时打 DEBUG；</li>
     *   <li>某条不一致 → WARN 打印该条 index / role / 长度差，它就是缓存断裂点。</li>
     * </ul>
     *
     * <p><b>为什么按「上一次请求」而不是「上一轮开头」比对</b>：工具循环里每次迭代都会走这里，
     * 相邻两次迭代天然是「前缀 + 追加」关系；一旦跨轮重建把历史改写，第一个撞上的就是
     * 那条被改写的消息 —— 不需要额外维护「轮次边界」。</p>
     *
     * @param conversationId 会话 ID（null 时跳过）
     * @param iteration      当前工具循环轮次（仅用于日志定位）
     * @param messages       本次即将发送的完整消息列表
     */
    public void checkMessages(Long conversationId, int iteration, List<Map<String, Object>> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            int size = Math.min(messages.size(), MAX_TRACKED_MESSAGES);
            List<MessageFp> current = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Map<String, Object> msg = messages.get(i);
                String role = msg == null ? "?" : String.valueOf(msg.get("role"));
                String text = flatten(msg);
                current.add(new MessageFp(role, text.length(), text.hashCode()));
            }

            List<MessageFp> prev = lastMessageFingerprints.put(conversationId, current);
            if (prev == null || prev.isEmpty()) {
                return;
            }

            int common = Math.min(prev.size(), current.size());
            int firstDiff = -1;
            for (int i = 0; i < common; i++) {
                if (!prev.get(i).equals(current.get(i))) {
                    firstDiff = i;
                    break;
                }
            }

            if (firstDiff < 0) {
                if (current.size() < prev.size()) {
                    log.debug("[前缀追踪] 会话={} 轮次={} 消息数 {} → {}（压缩/裁剪），前 {} 条前缀一致",
                            conversationId, iteration, prev.size(), current.size(), common);
                }
                return;
            }

            MessageFp before = prev.get(firstDiff);
            MessageFp now = current.get(firstDiff);
            log.warn("[缓存告警] 消息前缀断裂 —— 会话={} 轮次={} 首条不一致 index={}（从 0 计）"
                            + "role: {} → {}，长度: {} → {}（差 {}），hash: {} → {}。"
                            + "index 之前 {} 条一致；该 index 起本轮内容全部按未命中重新 prefill。"
                            + "排查方向：① 该条是 role=tool 时，核对「内存发出的 content」与「落库后重建的 content」是否同一份；"
                            + "② role=assistant 时，核对 reasoning_content 是否在同档模式下取值恒定；"
                            + "③ 其他情况核对是否有时间戳/统计数字被写进了消息正文。",
                    conversationId, iteration, firstDiff,
                    before.role(), now.role(),
                    before.length(), now.length(), now.length() - before.length(),
                    Integer.toHexString(before.hash()), Integer.toHexString(now.hash()),
                    firstDiff);
        } catch (Exception e) {
            // 观测能力绝不能影响主流程
            log.trace("消息前缀指纹计算失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 把一条消息摊平成参与指纹的文本。
     * <p>必须包含所有会进请求体的字段：content（可能是多模态块列表）、tool_call_id、
     * tool_calls（JsonNode，toString 稳定）、reasoning_content。少算任何一个都会漏报。</p>
     */
    private String flatten(Map<String, Object> msg) {
        if (msg == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(msg.get("role")).append(FIELD_SEP);
        sb.append(msg.get("content")).append(FIELD_SEP);
        sb.append(msg.get("tool_call_id")).append(FIELD_SEP);
        sb.append(msg.get("tool_calls")).append(FIELD_SEP);
        sb.append(msg.get("reasoning_content"));
        return sb.toString();
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
        lastMessageFingerprints.remove(conversationId);
    }

    /**
     * 拼接「开头连续的所有 system 消息」作为 system 段指纹的输入。
     * <p>遇到第一条非 system 消息立刻停止：末尾追加的语言指令 / 知识库摘要属于尾部动态内容，
     * 纳入进来只会每轮告警一次，反而把真正的头部变更淹掉。</p>
     */
    public static String joinLeadingSystemMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        int count = 0;
        for (Map<String, Object> msg : messages) {
            if (msg == null || !"system".equals(msg.get("role"))) {
                break;
            }
            if (count > 0) {
                sb.append(SECTION_SEP);
            }
            Object content = msg.get("content");
            if (content instanceof String text) {
                sb.append(text);
            } else if (content != null) {
                sb.append(content);
            }
            count++;
        }
        return sb.toString();
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
