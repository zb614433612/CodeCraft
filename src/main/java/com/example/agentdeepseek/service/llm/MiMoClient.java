package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Xiaomi MiMo API 的 LLMClient 实现
 *
 * <h3>MiMo API 协议（完全兼容 OpenAI）</h3>
 * <ul>
 *   <li>端点：/v1/chat/completions</li>
 *   <li>认证：api-key: $MIMO_API_KEY 或 Authorization: Bearer $MIMO_API_KEY</li>
 *   <li>思考模式：thinking.type = "disabled" / "enabled"</li>
 *   <li>输出控制：max_completion_tokens（非 max_tokens）</li>
 *   <li>SSE 格式与 OpenAI 完全一致</li>
 * </ul>
 *
 * <h3>可用模型（V2.5 系列）</h3>
 * <table>
 *   <tr><td>mimo-v2.5-pro</td><td>旗舰推理模型，1T 参数 / 42B 激活 / 1M 上下文</td></tr>
 *   <tr><td>mimo-v2.5</td><td>原生全模态感知，支持图像/视频/音频输入</td></tr>
 * </table>
 *
 * <p>技术实现：继承 OpenAIClient，仅覆盖 max_completion_tokens 适配。</p>
 */
@Slf4j
public class MiMoClient extends AbstractLLMClient {

    public MiMoClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    /**
     * MiMo 的 thinking 格式和 DeepSeek 完全一致
     * <p>
     * 文档规格：
     * <ul>
     *   <li>mimo-v2.5-pro / mimo-v2.5 默认 thinking.type=enabled</li>
     *   <li>思考模式下不支持自定义 temperature 和 top_p（由 MiMo 端强制忽略）</li>
     * </ul>
     * 这里显式发送 thinking.type=disabled 来关闭思考（工具调用场景更合适），
     * 同时在思考模式下主动移除 temperature/top_p 避免多余参数。
     * </p>
     */
    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        String effectiveMode = (thinkingMode == null || thinkingMode.isEmpty())
                ? "non-thinking" : thinkingMode;

        Map<String, Object> params = new HashMap<>();
        if ("non-thinking".equals(effectiveMode)) {
            params.put("thinking", Map.of("type", "disabled"));
        } else {
            params.put("thinking", Map.of("type", "enabled"));
            // MiMo 思考模式下不支持自定义 temperature/top_p，标记移除
            // 实际移除在 customizeRequest 中执行（因为 temperature 由父类设置）
            params.put("_remove_temperature", true);
            params.put("_remove_top_p", true);
        }
        return params;
    }

    /**
     * MiMo 使用 max_completion_tokens 而非 max_tokens
     * <p>
     * 文档规格：
     * <ul>
     *   <li>mimo-v2.5-pro 默认 131072</li>
     *   <li>mimo-v2.5 默认 32768</li>
     * </ul>
     * 这里统一设为 65536（折中值），既能容纳长回复，又不浪费 token 配额。
     * </p>
     * <p>
     * 同时防御 post-build 注入的 max_tokens（如 PromptOptimizeService 绕过 customizeRequest），
     * 确保 MiMo 始终使用 max_completion_tokens 字段。
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
        // 防御：如果有 max_tokens 被 post-build 注入，转换为 max_completion_tokens
        if (request.containsKey("max_tokens")) {
            Object maxTokens = request.remove("max_tokens");
            request.put("max_completion_tokens", maxTokens);
        } else {
            // 默认输出上限（MiMo v2.5-pro 默认 131072，这里设保守值）
            request.put("max_completion_tokens", 65536);
        }

        // 思考模式下 MiMo 不支持自定义 temperature/top_p，移除这些多余参数
        if (Boolean.TRUE.equals(request.get("_remove_temperature"))) {
            request.remove("temperature");
            request.remove("_remove_temperature");
        }
        if (Boolean.TRUE.equals(request.get("_remove_top_p"))) {
            request.remove("top_p");
            request.remove("_remove_top_p");
        }
    }
}
