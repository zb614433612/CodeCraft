package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI API 的 LLMClient 实现
 *
 * <h3>与 DeepSeek 的关键差异</h3>
 * <ul>
 *   <li>不支持 thinking.type 参数（OpenAI 无此概念）</li>
 *   <li>o1/o3 系列推理模型通过 reasoning_effort 控制</li>
 *   <li>支持 max_tokens 参数控制输出长度</li>
 *   <li>SSE 和响应格式与标准 OpenAI 兼容协议完全一致</li>
 * </ul>
 *
 * <h3>thinkingMode 映射</h3>
 * <table>
 *   <tr><td>non-thinking</td><td>→ 不传 reasoning_effort（标准模式）</td></tr>
 *   <tr><td>thinking</td><td>→ reasoning_effort=medium</td></tr>
 *   <tr><td>thinking_max</td><td>→ reasoning_effort=high</td></tr>
 * </table>
 */
@Slf4j
public class OpenAIClient extends AbstractLLMClient {

    /** 默认 max_tokens（OpenAI 非推理模型建议） */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    public OpenAIClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        // OpenAI 不支持 DeepSeek 的 thinking.type 格式
        // o1/o3 系列推理模型通过 reasoning_effort 控制
        String effectiveMode = (thinkingMode == null || thinkingMode.isEmpty())
                ? "non-thinking" : thinkingMode;

        Map<String, Object> params = new HashMap<>();

        if (!"non-thinking".equals(effectiveMode)) {
            // 推理模型用的 reasoning_effort
            params.put("reasoning_effort", "thinking_max".equals(effectiveMode) ? "high" : "medium");
        }
        // non-thinking 时不传 reasoning_effort，使用标准行为

        return params;
    }

    @Override
    protected void customizeRequest(Map<String, Object> request,
                                     List<Map<String, Object>> messages,
                                     String model,
                                     Double temperature,
                                     String thinkingMode,
                                     boolean stream,
                                     List<Map<String, Object>> tools) {
        // OpenAI 支持 max_tokens 限制输出
        // 对非推理模型（非 o1/o3）添加默认 max_tokens
        if (model != null && !model.startsWith("o1") && !model.startsWith("o3")) {
            if (!request.containsKey("max_tokens")) {
                request.put("max_tokens", DEFAULT_MAX_TOKENS);
            }
        }
        // o1/o3 系列不支持 temperature 参数
        if (model != null && (model.startsWith("o1") || model.startsWith("o3"))) {
            request.remove("temperature");
            // o1 系列的 max_tokens 叫 max_completion_tokens
            request.remove("max_tokens");
            request.put("max_completion_tokens", DEFAULT_MAX_TOKENS);
        }
    }

    /**
     * OpenAI 的 SSE 格式与标准 OpenAI 兼容协议一致，
     * 直接复用 AbstractLLMClient 默认解析逻辑。
     */
}
