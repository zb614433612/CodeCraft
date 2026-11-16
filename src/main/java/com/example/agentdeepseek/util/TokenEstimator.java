package com.example.agentdeepseek.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 精确 Token 估算工具类
 * 基于 JTokkit（Java 版 tiktoken），支持 DeepSeek / OpenAI 系列模型的 token 计数。
 * 用于上下文压缩前的精确用量判断，替代原有的字符数/3 粗略估算。
 */
@Slf4j
public class TokenEstimator {

    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();

    /**
     * 估算单段文本的 token 数
     * @param text 输入文本
     * @return token 数量
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            Encoding enc = registry.getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
            return enc.countTokens(text);
        } catch (Exception e) {
            // 保底：字符数/2（比 /3 更保守）
            log.debug("JTokkit 估算失败，使用保底算法: {}", e.getMessage());
            return text.length() / 2 + 1;
        }
    }

    /**
     * 估算 API 消息列表的总 token 数
     * 包含每条消息的角色开销、content、reasoning_content、tool_calls 等
     * @param messages API 格式的消息列表（Map 包含 role/content/reasoning_content/tool_calls 等键）
     * @return 总 token 数
     */
    public static int estimateMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;
        for (Map<String, Object> msg : messages) {
            // 每条消息约 3~5 token 的基础开销（role 字段等）
            total += 4;

            // content 字段（M3：支持多模态数组——text 块按文本估算，file/image 块按 ~1024 token/块）
            total += estimateContent(msg.get("content"));

            // reasoning_content 字段（DeepSeek 思考链）
            String reasoning = (String) msg.getOrDefault("reasoning_content", "");
            if (reasoning != null && !reasoning.isEmpty()) {
                total += estimate(reasoning);
            }

            // tool_calls 字段（含 function.name 与 function.arguments）
            //
            // ⚠️ 不能只加固定值（2026-11-16 修复）：此前这里写 `total += 15`，把整个
            // tool_calls 当成 15 token 的固定开销。但实测 tool_calls 的 JSON 里
            // `function.arguments` 才是体积大头 —— file_writer 写一个完整文件时，
            // 全部文件内容都在 arguments 字符串里，动辄数万字符（≈上万 token）。
            // 严重低估的直接后果：estimateMessages 是所有裁剪/压缩决策的依据
            // （trimToTokenBudget / trimToolLoopToBudget / CompactionService.decide），
            // 低估 → 判定「没超限」→ 不裁剪 → 请求真的超限 → API 400，整轮失败。
            // 因此这里按实际 JSON 文本精确估算，只保留极小的结构开销。
            if (msg.containsKey("tool_calls")) {
                Object toolCalls = msg.get("tool_calls");
                int toolCallsTokens = estimateToolCalls(toolCalls);
                // 无 tool_calls 内容时（null/空数组）仍保留最小结构开销
                total += toolCallsTokens > 0 ? toolCallsTokens : 15;
            }

            // tool_call_id 字段
            if (msg.containsKey("tool_call_id")) {
                total += 3;
            }
        }
        return Math.max(total, 1);
    }

    /**
     * 估算 content 字段的 token 数（兼容 String 与多模态内容块数组）
     * <p>
     * 数组形态（M3 图像注入后）：text 块按文本精确估算；file / image_url 等图片块按
     * ~1024 token/块 估算（DeepSeek 文档口径；仅预算守卫用途，非计费口径）。
     * </p>
     *
     * @param content 消息 content（String / List&lt;Map&gt; / null）
     * @return token 估算值
     */
    private static int estimateContent(Object content) {
        if (content == null) {
            return 0;
        }
        if (content instanceof String text) {
            return estimate(text);
        }
        if (content instanceof List<?> parts) {
            int sum = 0;
            for (Object item : parts) {
                if (item instanceof Map<?, ?> block) {
                    Object type = block.get("type");
                    if ("text".equals(type)) {
                        Object text = block.get("text");
                        sum += estimate(text == null ? "" : String.valueOf(text));
                    } else {
                        sum += 1024; // 图片类块（file / image_url / image）
                    }
                }
            }
            return sum;
        }
        return estimate(String.valueOf(content));
    }

    /**
     * 估算 {@code tool_calls} 字段的 token 数（兼容 JsonNode / List / String）。
     *
     * <p>历史上有两种形态：{@code ContextBuilder} 组装时用 {@code ObjectNode}
     * （Jackson JsonNode），部分路径也用 {@code List<Map>}。这里统一转成 JSON 文本
     * 再估算，保证不因类型不同而漏算。</p>
     *
     * <p>为什么必须精确估算：{@code function.arguments} 是工具入参的完整字符串，
     * 写文件时可达数万字符。截断/压缩决策依赖这个值，低估会导致超限请求被放行。</p>
     *
     * @param toolCalls tool_calls 字段值（可为 null）
     * @return token 估算值；无法估算时返回 0（调用方会退回最小结构开销）
     */
    private static int estimateToolCalls(Object toolCalls) {
        if (toolCalls == null) {
            return 0;
        }
        // Jackson JsonNode：直接取 JSON 文本长度估算
        if (toolCalls instanceof com.fasterxml.jackson.databind.JsonNode node) {
            if (node.isNull() || node.isMissingNode()) {
                return 0;
            }
            // 空数组/空对象没有实际内容，交给调用方的最小开销兜底
            if ((node.isArray() || node.isObject()) && node.isEmpty()) {
                return 0;
            }
            return estimate(node.toString());
        }
        // List / Map 形态：转字符串后估算
        if (toolCalls instanceof java.util.Collection<?> || toolCalls instanceof java.util.Map<?, ?>) {
            return estimate(String.valueOf(toolCalls));
        }
        if (toolCalls instanceof String text) {
            return estimate(text);
        }
        return estimate(String.valueOf(toolCalls));
    }
}
