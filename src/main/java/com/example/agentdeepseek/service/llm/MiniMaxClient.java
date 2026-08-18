package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * MiniMax API 的 LLMClient 实现（OpenAI 兼容协议）
 *
 * <h3>MiniMax API 协议（官方文档：/docs/api-reference/text-chat-openai）</h3>
 * <ul>
 *   <li>端点：/v1/chat/completions，Base URL：https://api.minimaxi.com（国际站，不带 /v1）</li>
 *   <li>认证：Authorization: Bearer $MINIMAX_API_KEY（与 DeepSeek/OpenAI 一致）</li>
 *   <li>思考模式：thinking.type = "enabled" | "disabled" | "adaptive"（M3 支持；M2.x 无法关闭）</li>
 *   <li>输出控制：max_completion_tokens（max_tokens 已弃用）</li>
 *   <li>reasoning_split：将 thinking 拆分到 reasoning_content / reasoning_details 字段</li>
 *   <li>SSE 格式与 OpenAI 完全一致（chat.completion.chunk）</li>
 * </ul>
 *
 * <h3>可用模型</h3>
 * <table>
 *   <tr><td>MiniMax-M3</td><td>旗舰模型，Coding/Agentic SOTA、1M 上下文、多模态</td></tr>
 *   <tr><td>MiniMax-M2.7 / M2.5 / M2.1 / M2</td><td>前代系列（highspeed 为高速版）</td></tr>
 * </table>
 *
 * <p>技术实现：复用 AbstractLLMClient 的 OpenAI 兼容 SSE 解析，仅覆盖 thinking 与 max_completion_tokens 适配。</p>
 */
@Slf4j
public class MiniMaxClient extends AbstractLLMClient {

    /** 默认输出上限 Token 数（M3 推荐 131072，其他模型推荐 65536，取保守值） */
    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 65536;

    public MiniMaxClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    /**
     * MiniMax 的 thinking 格式（官方文档）：
     * <ul>
     *   <li>MiniMax-M3 支持 enabled / disabled / adaptive，省略时默认 adaptive</li>
     *   <li>M2.x 系列无法关闭 thinking（disabled 会被服务端忽略）</li>
     * </ul>
     * <p>
     * thinkingMode 映射：
     * </p>
     * <table>
     *   <tr><td>non-thinking</td><td>→ thinking.type=disabled</td></tr>
     *   <tr><td>thinking</td><td>→ thinking.type=enabled</td></tr>
     *   <tr><td>thinking_max</td><td>→ thinking.type=adaptive（自适应深度思考）</td></tr>
     * </table>
     */
    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        String effectiveMode = (thinkingMode == null || thinkingMode.isEmpty())
                ? "non-thinking" : thinkingMode;

        String type = switch (effectiveMode) {
            case "thinking" -> "enabled";
            case "thinking_max" -> "adaptive";
            default -> "disabled"; // non-thinking
        };
        return Map.of("thinking", Map.of("type", type));
    }

    /**
     * MiniMax 使用 max_completion_tokens 而非 max_tokens（后者已弃用）。
     * <p>
     * 同时开启 reasoning_split（输出格式开关，不影响 thinking 开关）：
     * 将思考内容拆分到 reasoning_content 字段，避免 &lt;think&gt; 标签混入正文 content，
     * 且与 AbstractLLMClient 默认的 reasoning 解析（delta.reasoning_content）天然兼容。
     * </p>
     */
    @Override
    protected void customizeRequest(Map<String, Object> request,
                                     List<Map<String, Object>> messages,
                                     String model,
                                     Double temperature,
                                     String thinkingMode,
                                     boolean stream,
                                     List<Map<String, Object>> tools) {
        // 防御：post-build 注入的 max_tokens 转换为 max_completion_tokens
        if (request.containsKey("max_tokens")) {
            Object maxTokens = request.remove("max_tokens");
            request.put("max_completion_tokens", maxTokens);
        } else {
            request.putIfAbsent("max_completion_tokens", DEFAULT_MAX_COMPLETION_TOKENS);
        }

        // 将 thinking 拆分到 reasoning_content（输出格式开关，不影响 thinking 开关）
        request.putIfAbsent("reasoning_split", true);
    }
}
