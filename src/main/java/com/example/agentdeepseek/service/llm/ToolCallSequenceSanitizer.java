package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具调用序列净化器（幂等 / 纯内存 / 不改数据库 / 不修改入参）。
 *
 * <h3>为什么需要它</h3>
 * <p>OpenAI / DeepSeek 兼容接口对 assistant.tool_calls 与 role=tool 消息的配对有硬性约束：</p>
 * <ol>
 *   <li>assistant 里声明的每一个 tool_call，都<b>必须</b>有一条 tool_call_id 相同的 tool 消息；</li>
 *   <li>每一条 role=tool 消息，都<b>必须</b>有一条紧邻其前、声明了该 tool_call_id 的 assistant 消息；</li>
 *   <li>tool_call 必须有非空 id，重复 id 同样非法。</li>
 * </ol>
 * <p>任一条件不满足，接口会直接返回 400，该会话从此无法继续对话。</p>
 *
 * <h3>脏数据从哪来（历史遗留场景）</h3>
 * <ul>
 *   <li>工具调用已下发并落库（assistant.tool_calls），工具结果还没落库时任务被中断 / SSE 连接断开 /
 *       应用被强制关闭 / 进程崩溃 → 只剩 assistant 的 tool_calls；</li>
 *   <li>工具结果已落库但上游 assistant 的 tool_calls 被历史修复逻辑 / 上下文裁剪移除 → 只剩孤立 tool 消息；</li>
 *   <li>上下文压缩、滑动窗口裁剪把 assistant 与 tool 消息切到了不同分支。</li>
 * </ul>
 *
 * <h3>净化策略（宁可丢信息，绝不阻断聊天）</h3>
 * <ol>
 *   <li><b>丢弃孤立 tool 消息</b>：找不到「同轮内、id 匹配、尚未被消费」的 assistant tool_call 的 tool 消息；</li>
 *   <li><b>剔除无结果的 tool_call</b>：assistant.tool_calls 里没有对应 tool 结果的调用项直接移除；
 *       全部被移除时清空 tool_calls（assistant 退化为普通文本消息，内容仍保留）；</li>
 *   <li><b>丢弃空 assistant 消息</b>：清洗后既无 content 又无 tool_calls 的 assistant 消息（接口同样不接受）；</li>
 *   <li><b>剔除非法 tool_call</b>：无 id / id 为 "null" / 重复 id 的调用项直接移除。</li>
 * </ol>
 * <p>对已经合法的序列调用不会产生任何变更（幂等），可安全地在每次请求前重复执行。</p>
 *
 * <h3>实现约束</h3>
 * <ul>
 *   <li><b>不修改入参</b>：需要改写某条消息时先做浅拷贝（copy-on-write），原 {@link Map} / {@link List}
 *       对象保持不变，避免同一份上下文被复用到别处时出现隐式副作用；</li>
 *   <li><b>不可变集合安全</b>：返回新列表，调用方可用 {@link #sanitizeRequestBodyCopy(Map)} 直接发新副本。</li>
 * </ul>
 */
@Slf4j
public final class ToolCallSequenceSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** settle 结果：无需改动 */
    private static final int SETTLE_UNCHANGED = 0;
    /** settle 结果：已改写该 assistant（tool_calls 精简或清空） */
    private static final int SETTLE_MODIFIED = 1;
    /** settle 结果：整条 assistant 被丢弃 */
    private static final int SETTLE_DROPPED = 2;

    private ToolCallSequenceSanitizer() {
    }

    /**
     * 返回「净化后的请求体副本」——<b>推荐在所有真正发包的出口使用</b>。
     *
     * <p>不依赖调用方传入的 Map / List 是否可变：副本一定是 HashMap，messages 若被修复则是新列表。
     * 原请求体与其中的消息对象都不会被修改。</p>
     *
     * @param requestBody 原始请求体（可为 null）
     * @return 净化后的可写副本；入参为 null 时返回 null
     */
    public static Map<String, Object> sanitizeRequestBodyCopy(Map<String, Object> requestBody) {
        if (requestBody == null) {
            return null;
        }
        Map<String, Object> copy = new HashMap<>(requestBody);
        Object messages = copy.get("messages");
        if (messages instanceof List<?> list) {
            List<Object> sanitized = sanitizeCore(list);
            if (sanitized != null) {
                copy.put("messages", sanitized);
            }
        }
        return copy;
    }

    /**
     * 原地净化请求体中的 messages（调用方持有可变的请求体与消息列表时使用）。
     *
     * <p>若 messages 是不可变集合，则把修复后的新列表写回 requestBody，保证出站数据依然合法。</p>
     *
     * @param requestBody LLM 请求体（含 messages 字段时才会处理）
     */
    public static void sanitizeRequestBody(Map<String, Object> requestBody) {
        if (requestBody == null || requestBody.isEmpty()) {
            return;
        }
        Object messages = requestBody.get("messages");
        if (!(messages instanceof List<?> list)) {
            return;
        }
        List<Object> sanitized = sanitizeCore(list);
        if (sanitized == null) {
            return;
        }
        try {
            replaceAll(list, sanitized);
        } catch (UnsupportedOperationException e) {
            requestBody.put("messages", sanitized);
            log.warn("messages 为不可变列表，已用净化后的副本替换请求体引用（{} 条 → {} 条）",
                    list.size(), sanitized.size());
        }
    }

    /**
     * 原地净化消息序列（尽量保持调用方持有的 List 引用）。
     *
     * @param messages 待发送的消息列表
     */
    public static void sanitize(List<?> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Object> sanitized = sanitizeCore(messages);
        if (sanitized == null) {
            return;
        }
        try {
            replaceAll(messages, sanitized);
        } catch (UnsupportedOperationException e) {
            // 不抛异常：净化本身绝不能成为请求失败的原因
            log.error("messages 为不可变列表，无法原地净化，请改用 sanitizeRequestBodyCopy（{} 条 → {} 条）",
                    messages.size(), sanitized.size());
        }
    }

    /**
     * 核心净化逻辑（copy-on-write，不修改入参）。
     *
     * @return 需要变更时返回修复后的新列表；序列本来就合法时返回 {@code null}（零副作用）
     */
    private static List<Object> sanitizeCore(List<?> messages) {
        List<Object> out = new ArrayList<>(messages.size());

        int pendingAssistantIdx = -1;                                // 待结算 assistant 在 out 中的下标
        Map<String, Boolean> pendingIds = Collections.emptyMap();     // tool_call_id → 是否已收到 tool 结果

        int droppedToolMessages = 0;
        int droppedToolCalls = 0;
        int droppedAssistant = 0;
        boolean changed = false;

        for (Object rawObj : messages) {
            if (!(rawObj instanceof Map)) {
                out.add(rawObj);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) rawObj;
            String role = asText(msg.get("role")).toLowerCase(Locale.ROOT);

            // ---------- tool 消息：必须能配到「同轮、id 匹配、尚未消费」的 tool_call ----------
            if ("tool".equals(role)) {
                String tcId = asText(msg.get("tool_call_id"));
                boolean matched = pendingAssistantIdx >= 0
                        && pendingIds.containsKey(tcId)
                        && !Boolean.TRUE.equals(pendingIds.get(tcId));
                if (matched) {
                    pendingIds.put(tcId, Boolean.TRUE);
                    out.add(msg);
                } else {
                    // 孤立 tool 消息 / 重复 tool 结果 → 丢弃（保留它只会让接口 400）
                    droppedToolMessages++;
                    changed = true;
                }
                continue;
            }

            // ---------- 其他消息：先结算上一条 assistant 的 tool_calls ----------
            if (pendingAssistantIdx >= 0) {
                int settle = settleAssistant(out, pendingAssistantIdx, pendingIds);
                if (settle == SETTLE_MODIFIED) {
                    changed = true;
                } else if (settle == SETTLE_DROPPED) {
                    changed = true;
                    droppedAssistant++;
                }
                pendingAssistantIdx = -1;
                pendingIds = Collections.emptyMap();
            }

            if ("assistant".equals(role)) {
                Map<String, Object> working = msg;
                LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
                JsonNode tcNode = toJsonNode(msg.get("tool_calls"));

                if (tcNode != null && tcNode.isArray()) {
                    ArrayNode kept = MAPPER.createArrayNode();
                    for (JsonNode tc : tcNode) {
                        String id = asText(tc.path("id").asText(""));
                        // 无 id / id 为 null：接口不允许，直接剔除
                        if (id.isEmpty() || "null".equalsIgnoreCase(id)) {
                            continue;
                        }
                        // 重复 id：只保留第一个
                        if (ids.containsKey(id)) {
                            continue;
                        }
                        ids.put(id, Boolean.FALSE);
                        kept.add(tc);
                    }
                    int removed = tcNode.size() - kept.size();
                    if (removed > 0) {
                        working = new HashMap<>(msg);
                        if (kept.isEmpty()) {
                            working.remove("tool_calls");
                        } else {
                            working.put("tool_calls", kept);
                        }
                        droppedToolCalls += removed;
                        changed = true;
                    }
                } else if (msg.containsKey("tool_calls")) {
                    // 非数组 / JSON 解析失败：一律移除，避免脏结构被发送
                    working = new HashMap<>(msg);
                    working.remove("tool_calls");
                    droppedToolCalls++;
                    changed = true;
                }

                boolean hasContent = hasText(working.get("content"));
                boolean hasToolCalls = working.get("tool_calls") != null;
                // 既无文本也无工具调用：空 assistant 消息接口同样拒绝，整条丢弃
                if (!hasContent && !hasToolCalls) {
                    droppedAssistant++;
                    changed = true;
                    continue;
                }

                out.add(working);
                if (hasToolCalls) {
                    pendingAssistantIdx = out.size() - 1;
                    pendingIds = ids;
                }
                continue;
            }

            // user / system / 其他角色：原样保留
            out.add(msg);
        }

        if (pendingAssistantIdx >= 0) {
            int settle = settleAssistant(out, pendingAssistantIdx, pendingIds);
            if (settle == SETTLE_MODIFIED) {
                changed = true;
            } else if (settle == SETTLE_DROPPED) {
                changed = true;
                droppedAssistant++;
            }
        }

        if (!changed) {
            return null;
        }
        log.warn("工具调用序列净化：丢弃孤立 tool 消息 {} 条，剔除不可用 tool_call {} 个，丢弃空 assistant 消息 {} 条"
                        + "（{} 条 → {} 条）。这些脏数据通常来自「工具已下发但结果未落库时任务被中断」，"
                        + "已按「宁可丢信息、不让会话卡死」策略清理。",
                droppedToolMessages, droppedToolCalls, droppedAssistant, messages.size(), out.size());
        return out;
    }

    /**
     * 结算一条 assistant 的 tool_calls：只保留「已有 tool 结果」的调用项；全部无结果时清空 tool_calls，
     * 若连文本也没有则整条消息丢弃。
     *
     * <p>安全性：整条删除只会发生在「没有任何 tool 结果」的情况，此时该 assistant 后面不可能存在
     * 已保留的 tool 消息（孤立 tool 已在前面被丢弃），因此不会制造新的孤立项。</p>
     *
     * @return {@link #SETTLE_UNCHANGED} / {@link #SETTLE_MODIFIED} / {@link #SETTLE_DROPPED}
     */
    private static int settleAssistant(List<Object> out, int idx, Map<String, Boolean> ids) {
        if (idx < 0 || idx >= out.size()) {
            return SETTLE_UNCHANGED;
        }
        Object raw = out.get(idx);
        if (!(raw instanceof Map)) {
            return SETTLE_UNCHANGED;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) raw;
        Object tcObj = msg.get("tool_calls");
        if (tcObj == null) {
            return SETTLE_UNCHANGED;
        }
        JsonNode node = toJsonNode(tcObj);
        if (node == null || !node.isArray()) {
            Map<String, Object> copy = new HashMap<>(msg);
            copy.remove("tool_calls");
            out.set(idx, copy);
            return SETTLE_MODIFIED;
        }

        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode tc : node) {
            String id = asText(tc.path("id").asText(""));
            if (Boolean.TRUE.equals(ids.get(id))) {
                kept.add(tc);
            }
        }
        if (kept.size() == node.size()) {
            return SETTLE_UNCHANGED;
        }

        Map<String, Object> copy = new HashMap<>(msg);
        if (kept.isEmpty()) {
            copy.remove("tool_calls");
            if (!hasText(copy.get("content"))) {
                // 该 assistant 既无可用 tool_calls 又无文本 → 整条丢弃
                out.remove(idx);
                return SETTLE_DROPPED;
            }
            out.set(idx, copy);
            return SETTLE_MODIFIED;
        }
        ArrayNode arr = MAPPER.createArrayNode();
        kept.forEach(arr::add);
        copy.put("tool_calls", arr);
        out.set(idx, copy);
        return SETTLE_MODIFIED;
    }

    /** 泛型擦除下的原地替换（保持调用方持有的同一个 List 引用） */
    @SuppressWarnings("unchecked")
    private static void replaceAll(List<?> target, List<Object> source) {
        List<Object> list = (List<Object>) target;
        list.clear();
        list.addAll(source);
    }

    private static JsonNode toJsonNode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        try {
            return MAPPER.valueToTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean hasText(Object value) {
        if (value == null) {
            return false;
        }
        // 字符串（含纯空白）按"无内容"处理：空 assistant / 空 content 都会被接口拒绝
        if (value instanceof CharSequence cs) {
            String s = cs.toString();
            return !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim());
        }
        // 多模态 content（数组/对象）只要非空即视为有内容
        String s = String.valueOf(value);
        return !s.isEmpty() && !"null".equalsIgnoreCase(s);
    }
}
