package com.example.agentdeepseek.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * LLM 请求上下文转储器 —— 排查「上下文缺失 / 正文丢失 / 前缀断裂」的取证工具。
 *
 * <h3>为什么需要它</h3>
 * <p>日志只能给出长度、token 和「首条不一致 index」这类统计，无法回答三个关键问题：</p>
 * <ol>
 *   <li>这一轮到底发了哪些消息？</li>
 *   <li>每条 assistant 消息的 {@code content}（正文）是否真的带上了？</li>
 *   <li>同一条消息在相邻两次迭代之间是否被改写（前缀断裂的真凶）？</li>
 * </ol>
 *
 * <h3>落盘规则</h3>
 * <ul>
 *   <li><b>一轮一个文件</b>：同一 turnId 下的全部工具循环迭代追加到同一个文件，
 *       文件名 {@code turn-<yyyyMMdd-HHmmss>-c<会话号>-<turnId>.txt}；</li>
 *   <li><b>只保留最近 {@value #KEEP_FILES} 个文件</b>：按文件名（内含时间戳）从旧到新删除多余文件；</li>
 *   <li><b>增量 + 变化高亮</b>：当轮首次迭代全量输出；后续迭代只输出「新增」与「与上次不同」的消息，
 *       并用 ⚠️ 标注长度变化 —— 既能还原每次出站内容，又能一眼看出哪条消息被改写；</li>
 *   <li><b>绝不影响主链路</b>：任何异常只 WARN，不抛出。</li>
 * </ul>
 *
 * <h3>开关与位置</h3>
 * <ul>
 *   <li>{@code -Dllm.context-dump.enabled=false} 完全关闭（默认开启）；</li>
 *   <li>目录：{@code <LOG_PATH>/context-dumps}；LOG_PATH 默认 {@code ./logs}，
 *       桌面端即 {@code <userData>/runtime/logs/context-dumps}；</li>
 *   <li>单字段（content / reasoning_content / tool_calls）输出上限 {@value #MAX_FIELD_CHARS} 字符，
 *       超出部分截断并标注总长 —— 防止超长思考链与工具结果把文件撑爆。</li>
 * </ul>
 */
@Slf4j
public final class LlmContextDumper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter NAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter READ_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 保留的文件数（最近的 N 轮） */
    private static final int KEEP_FILES = 3;
    /** 单字段输出上限（字符） */
    private static final int MAX_FIELD_CHARS = 20000;
    /** 完全关闭开关：-Dllm.context-dump.enabled=false */
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("llm.context-dump.enabled", "true"));

    private static final Object LOCK = new Object();
    /** turnKey → 当轮文件路径 */
    private static final Map<String, Path> TURN_FILES = new ConcurrentHashMap<>();
    /** turnKey → 上次迭代各条消息的指纹（用于识别「同一条消息被改写」） */
    private static final Map<String, List<String>> LAST_FINGERPRINTS = new ConcurrentHashMap<>();
    /** turnKey → 上次迭代的消息条数 */
    private static final Map<String, Integer> LAST_SIZES = new ConcurrentHashMap<>();
    /** 内存中最多跟踪的轮次数（超出整体清空，避免长跑进程膨胀） */
    private static final int MAX_TRACKED_TURNS = 200;

    private LlmContextDumper() {
    }

    /**
     * 转储一次真实出站的请求（线程安全、幂等、异常自吞）。
     *
     * @param conversationId  会话 ID（可为 null）
     * @param turnId          轮次 ID（同一轮的工具循环共用，决定文件归属）
     * @param iteration       工具循环迭代号
     * @param messagesObj     实际发送的消息列表（建议传净化后的 {@code requestBody.get("messages")}）
     * @param toolsObj        实际发送的 tools 定义（仅记录数量，schema 不落盘）
     * @param requestBodyChars 请求体字符数（用于与日志中的 [Perf] 对照）
     */
    public static void dump(Long conversationId, String turnId, int iteration,
                            Object messagesObj, Object toolsObj, int requestBodyChars) {
        if (!ENABLED || !(messagesObj instanceof List<?> messages) || messages.isEmpty()) {
            return;
        }
        try {
            String convPart = conversationId == null ? "unknown" : String.valueOf(conversationId);
            String turnPart = turnId == null || turnId.isBlank() ? "no-turn" : turnId;
            String turnKey = convPart + "|" + turnPart;

            Path dir = dumpDir();
            Files.createDirectories(dir);

            Path file;
            synchronized (LOCK) {
                file = TURN_FILES.get(turnKey);
                if (file == null || !Files.isRegularFile(file)) {
                    String name = "turn-" + LocalDateTime.now().format(NAME_TS)
                            + "-c" + safe(convPart) + "-" + safe(turnPart) + ".txt";
                    file = dir.resolve(name);
                    TURN_FILES.put(turnKey, file);
                }
                cleanupOldFiles(dir, file);
                evictIfTooManyTurns();
            }

            List<String> lastFingerprints = LAST_FINGERPRINTS.getOrDefault(turnKey, List.of());
            int lastSize = LAST_SIZES.getOrDefault(turnKey, 0);

            StringBuilder sb = new StringBuilder(1 << 14);
            sb.append('\n')
                    .append("================================================================================\n");
            sb.append("会话=").append(convPart)
                    .append("  轮次=").append(turnPart)
                    .append("  迭代=").append(iteration)
                    .append("  时间=").append(LocalDateTime.now().format(READ_TS))
                    .append("  请求体=").append(requestBodyChars).append(" 字符")
                    .append("  消息数=").append(messages.size())
                    .append('\n');
            sb.append("工具定义=").append(describeTools(toolsObj)).append('\n');
            sb.append("--------------------------------------------------------------------------------\n");

            // 消息数变少（压缩/裁剪）时基线不可比，退回全量输出
            boolean fullDump = lastSize <= 0 || messages.size() < lastSize;
            if (fullDump) {
                sb.append("【全量输出】当轮首次记录");
                if (lastSize > 0) {
                    sb.append("（上次 ").append(lastSize).append(" 条 → 本次 ")
                            .append(messages.size())
                            .append(" 条，条数减少说明发生了压缩/裁剪，故重新全量输出）");
                }
                sb.append('\n');
            } else {
                sb.append("【增量输出】上次 ").append(lastSize).append(" 条 → 本次 ")
                        .append(messages.size())
                        .append(" 条：仅输出新增消息与「与上次不同」的消息\n");
            }

            int unchanged = 0;
            int added = 0;
            int changed = 0;
            for (int i = 0; i < messages.size(); i++) {
                Object msgObj = messages.get(i);
                String fp = fingerprint(msgObj);
                boolean isNew = i >= lastSize;
                boolean isChanged = !isNew && i < lastFingerprints.size() && !fp.equals(lastFingerprints.get(i));
                if (!fullDump && !isNew && !isChanged) {
                    unchanged++;
                    continue;
                }
                if (isNew) {
                    added++;
                } else if (isChanged) {
                    changed++;
                }
                appendMessage(sb, i, msgObj, isChanged ? lastFingerprints.get(i) : null);
            }
            if (unchanged > 0) {
                sb.append("...（前 ").append(unchanged).append(" 条与上次迭代逐字一致，已省略）...\n");
            }

            appendSummary(sb, messages);
            sb.append("== 本次输出：新增 ").append(added).append(" 条，变化 ").append(changed)
                    .append(" 条，省略 ").append(unchanged).append(" 条 ==\n");

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // 更新基线，供下一次迭代比对
            List<String> current = new ArrayList<>(messages.size());
            for (Object m : messages) {
                current.add(fingerprint(m));
            }
            LAST_FINGERPRINTS.put(turnKey, current);
            LAST_SIZES.put(turnKey, messages.size());
        } catch (Exception e) {
            log.warn("[上下文转储] 写入失败（不影响主链路）: {}", e.getMessage());
        }
    }

    // ==================== 内部实现 ====================

    /** 转储目录：<LOG_PATH>/context-dumps（LOG_PATH 默认 ./logs，桌面端为 runtime/logs） */
    private static Path dumpDir() {
        String logPath = System.getProperty("LOG_PATH", "./logs");
        return Paths.get(logPath).toAbsolutePath().normalize().resolve("context-dumps");
    }

    /** 只保留最近 {@value #KEEP_FILES} 个转储文件（当前写入的必须是其中之一） */
    private static void cleanupOldFiles(Path dir, Path current) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("turn-") && n.endsWith(".txt");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            int deleteCount = files.size() - KEEP_FILES;
            for (int i = 0; i < deleteCount; i++) {
                Path old = files.get(i);
                if (old.equals(current)) {
                    continue;
                }
                Files.deleteIfExists(old);
                // 同步清理该文件对应的内存基线，避免「文件已删、基线还在」造成误报
                List<String> removedKeys = new ArrayList<>();
                for (Map.Entry<String, Path> e : TURN_FILES.entrySet()) {
                    if (e.getValue().equals(old)) {
                        removedKeys.add(e.getKey());
                    }
                }
                for (String k : removedKeys) {
                    TURN_FILES.remove(k);
                    LAST_FINGERPRINTS.remove(k);
                    LAST_SIZES.remove(k);
                }
                log.info("[上下文转储] 已删除旧文件: {}", old.getFileName());
            }
        } catch (Exception e) {
            log.debug("[上下文转储] 清理旧文件失败: {}", e.getMessage());
        }
    }

    /** 跟踪表过大时整体清空（只影响增量基线，不影响已落盘文件） */
    private static void evictIfTooManyTurns() {
        if (LAST_SIZES.size() > MAX_TRACKED_TURNS) {
            LAST_FINGERPRINTS.clear();
            LAST_SIZES.clear();
            TURN_FILES.clear();
            log.debug("[上下文转储] 跟踪轮次超过 {}，已清空增量基线", MAX_TRACKED_TURNS);
        }
    }

    /** 输出单条消息（含 content / tool_calls / reasoning_content 摘要与原文） */
    private static void appendMessage(StringBuilder sb, int index, Object msgObj, String prevFingerprint) {
        if (!(msgObj instanceof Map<?, ?> msg)) {
            sb.append('[').append(index).append("] <非 Map 消息> ")
                    .append(clip(String.valueOf(msgObj))).append('\n');
            return;
        }
        String role = String.valueOf(msg.get("role"));
        Object contentObj = msg.get("content");
        String contentText = contentObj == null ? null : renderText(contentObj);

        sb.append('[').append(String.format("%3d", index)).append("] role=").append(role);
        if (contentObj == null) {
            sb.append("  content=NULL ⚠️");
        } else {
            sb.append("  content_len=").append(contentText == null ? 0 : contentText.length());
        }
        Object reasoning = msg.get("reasoning_content");
        if (reasoning != null) {
            sb.append("  reasoning_len=").append(String.valueOf(reasoning).length());
        }
        Object toolCallId = msg.get("tool_call_id");
        if (toolCallId != null) {
            sb.append("  tool_call_id=").append(toolCallId);
        }
        if (prevFingerprint != null) {
            sb.append("  ⚠️与上次不同(上次指纹: ").append(prevFingerprint).append(')');
        }
        sb.append('\n');

        if (contentObj != null) {
            sb.append("      content: ").append(clip(contentText)).append('\n');
        }
        Object toolCalls = msg.get("tool_calls");
        if (toolCalls != null) {
            sb.append("      tool_calls: ").append(clip(json(toolCalls))).append('\n');
        }
        if (reasoning != null) {
            sb.append("      reasoning_content: ").append(clip(String.valueOf(reasoning))).append('\n');
        }
    }

    /** 输出 role 分布与 assistant 正文统计（一眼判断「本轮有没有正文」） */
    private static void appendSummary(StringBuilder sb, List<?> messages) {
        int system = 0;
        int user = 0;
        int assistant = 0;
        int assistantWithContent = 0;
        int assistantWithoutContent = 0;
        int tool = 0;
        int other = 0;
        for (Object o : messages) {
            if (!(o instanceof Map<?, ?> m)) {
                other++;
                continue;
            }
            String role = String.valueOf(m.get("role")).toLowerCase();
            if ("system".equals(role)) {
                system++;
            } else if ("user".equals(role)) {
                user++;
            } else if ("tool".equals(role)) {
                tool++;
            } else if ("assistant".equals(role)) {
                assistant++;
                Object c = m.get("content");
                if (c == null || String.valueOf(c).trim().isEmpty()) {
                    assistantWithoutContent++;
                } else {
                    assistantWithContent++;
                }
            } else {
                other++;
            }
        }
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("== 汇总：system=").append(system)
                .append("  user=").append(user)
                .append("  assistant=").append(assistant)
                .append("（有正文 ").append(assistantWithContent)
                .append(" / 无正文 ").append(assistantWithoutContent).append("）")
                .append("  tool=").append(tool)
                .append("  其他=").append(other)
                .append(" ==\n");
    }

    /** 消息指纹：role + content 长度 + content hash + reasoning 长度 + tool_calls 长度 */
    private static String fingerprint(Object msgObj) {
        if (!(msgObj instanceof Map<?, ?> msg)) {
            return "raw:" + String.valueOf(msgObj).hashCode();
        }
        String role = String.valueOf(msg.get("role"));
        Object content = msg.get("content");
        int contentLen = content == null ? -1 : String.valueOf(content).length();
        int contentHash = content == null ? 0 : String.valueOf(content).hashCode();
        Object reasoning = msg.get("reasoning_content");
        int reasoningLen = reasoning == null ? -1 : String.valueOf(reasoning).length();
        Object toolCalls = msg.get("tool_calls");
        int toolCallsLen = toolCalls == null ? -1 : String.valueOf(toolCalls).length();
        return role + "|c" + contentLen + "|h" + Integer.toHexString(contentHash)
                + "|r" + reasoningLen + "|t" + toolCallsLen;
    }

    private static String describeTools(Object toolsObj) {
        if (toolsObj instanceof Collection<?> collection) {
            return "数量=" + collection.size() + "（schema 不落盘）";
        }
        return toolsObj == null ? "null" : "type=" + toolsObj.getClass().getSimpleName();
    }

    /** 文本化：字符串原样、其他结构转 JSON */
    private static String renderText(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof CharSequence cs) {
            return cs.toString();
        }
        return json(obj);
    }

    @SuppressWarnings("StringBufferReplaceableByString")
    private static String json(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    /** 截断超长字段，保留总长提示 */
    private static String clip(String text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= MAX_FIELD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_FIELD_CHARS)
                + "  ...[已截断，原文共 " + text.length() + " 字符]";
    }

    /** 文件名安全化（去掉 Windows 非法字符） */
    private static String safe(String s) {
        if (s == null) {
            return "null";
        }
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
