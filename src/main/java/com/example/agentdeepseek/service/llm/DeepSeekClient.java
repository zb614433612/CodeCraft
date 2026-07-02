package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * DeepSeek API 的 LLMClient 实现
 *
 * <h3>DeepSeek 特有参数</h3>
 * <ul>
 *   <li>thinking.type = "enabled" | "disabled"</li>
 *   <li>reasoning_effort = "high" | "max"</li>
 *   <li>API 端点：/v1/chat/completions（兼容 OpenAI 格式）</li>
 * </ul>
 *
 * <h3>thinkingMode 映射</h3>
 * <table>
 *   <tr><td>non-thinking</td><td>→ thinking.type=disabled</td></tr>
 *   <tr><td>thinking</td><td>→ thinking.type=enabled + reasoning_effort=high</td></tr>
 *   <tr><td>thinking_max</td><td>→ thinking.type=enabled + reasoning_effort=max</td></tr>
 * </table>
 */
@Slf4j
public class DeepSeekClient extends AbstractLLMClient {

    public DeepSeekClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        Map<String, Object> params = new HashMap<>();

        // 默认 thinking 模式
        String effectiveMode = (thinkingMode == null || thinkingMode.isEmpty())
                ? "thinking" : thinkingMode;

        if ("non-thinking".equals(effectiveMode)) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "disabled");
            params.put("thinking", thinking);
        } else {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            params.put("thinking", thinking);
            params.put("reasoning_effort", "thinking_max".equals(effectiveMode) ? "max" : "high");
        }

        return params;
    }

    /**
     * DeepSeek 的 SSE 响应格式与 OpenAI 兼容（data: {...}），
     * 使用 AbstractLLMClient 默认的解析逻辑即可。
     * 唯一的差异是 DeepSeek 在 delta 中可能包含 reasoning_content。
     */
}
