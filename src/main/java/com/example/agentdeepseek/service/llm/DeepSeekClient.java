package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * DeepSeek API 的 LLMClient 实现
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：DeepSeek 官方 API（{@code api.deepseek.com}），或需要 DeepSeek 原生
 *       {@code thinking.type} / {@code reasoning_effort} 语义的调用</li>
 *   <li><b>不适用</b>：方舟上的 DeepSeek 模型（用 {@code volcengine} 模板）；
 *       任何 OpenAI 兼容中转站 / 自建网关（用 {@code openai} 公共契约）</li>
 *   <li><b>专属能力</b>：{@code thinking.type=enabled/disabled} + {@code reasoning_effort=high/max}；
 *       {@code web_search} / {@code web_fetch} 在该 Provider 下自动改走 DeepSeek 官方工具 API</li>
 * </ul>
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

        // 统一档位归一化（none / low / medium / high / max / auto）
        String canonical = AbstractLLMClient.normalizeThinkingMode(thinkingMode);
        // DeepSeek 历史行为：未指定时按"开启思考"处理
        if (EFFORT_AUTO.equals(canonical)) {
            canonical = EFFORT_MEDIUM;
        }

        if (EFFORT_NONE.equals(canonical)) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "disabled");
            params.put("thinking", thinking);
            return params;
        }

        Map<String, Object> thinking = new HashMap<>();
        thinking.put("type", "enabled");
        params.put("thinking", thinking);

        // DeepSeek 官方 reasoning_effort 只有 none / low / high / max 四档（无 medium）：
        //   low    → low     浅思考（读文件/改文案/跑命令）
        //   medium → high    DeepSeek 的默认档就是 high，承接"thinking"语义
        //   high   → high
        //   max    → max     最深（疑难根因/协议设计）
        String effort = switch (canonical) {
            case EFFORT_LOW -> "low";
            case EFFORT_MAX -> "max";
            default -> "high";
        };
        params.put("reasoning_effort", effort);
        return params;
    }

    /**
     * DeepSeek 的 SSE 响应格式与 OpenAI 兼容（data: {...}），
     * 使用 AbstractLLMClient 默认的解析逻辑即可。
     * 唯一的差异是 DeepSeek 在 delta 中可能包含 reasoning_content。
     */
}
