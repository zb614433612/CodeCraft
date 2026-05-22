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

            // content 字段
            String content = (String) msg.getOrDefault("content", "");
            total += estimate(content);

            // reasoning_content 字段（DeepSeek 思考链）
            String reasoning = (String) msg.getOrDefault("reasoning_content", "");
            if (reasoning != null && !reasoning.isEmpty()) {
                total += estimate(reasoning);
            }

            // tool_calls 字段（JSON 格式，额外开销）
            if (msg.containsKey("tool_calls")) {
                total += 15;
            }

            // tool_call_id 字段
            if (msg.containsKey("tool_call_id")) {
                total += 3;
            }
        }
        return Math.max(total, 1);
    }
}
