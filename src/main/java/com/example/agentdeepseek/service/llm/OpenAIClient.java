package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI Chat Completions 协议的「公共契约」适配器（请求模板 {@code openai} / {@code custom}）。
 *
 * <p><b>设计目标</b>：任何符合 OpenAI Chat Completions 规范的服务（Azure OpenAI、OpenRouter 与各类中转站、
 * vLLM / LM Studio / One-API 等自建网关）都可以直接用本适配器接入，只需配好 baseUrl + apiKey + 模型名。</p>
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：任意 OpenAI Chat Completions 兼容服务（Azure / OpenRouter / 中转站 / vLLM / LM Studio / One-API），
 *       以及 {@code custom} 自定义网关默认走的兼容模式</li>
 *   <li><b>不适用</b>：OpenAI 官方 GPT / o 系列需要 {@code reasoning_effort} 精细控制时（用 {@code gpt} 模板）；
 *       DeepSeek / 方舟 / 千问 / MiMo / MiniMax 等需要厂商专有参数时（用各自专用模板）</li>
 *   <li><b>策略</b>：不下发 {@code max_tokens} / {@code reasoning_effort} / {@code stream_options}，避免兼容服务 400；
 *       推理模型（o 系列 / gpt-5）按协议自动切 {@code max_completion_tokens} 并移除 {@code temperature}</li>
 * </ul>
 *
 * <h3>与 {@link GptClient}（{@code gpt} 模板）的职责边界</h3>
 * <table>
 *   <tr><th></th><th>{@code openai}（本类，公共契约）</th><th>{@code gpt}（官方专精）</th></tr>
 *   <tr>
 *     <td>适用对象</td>
 *     <td>任意 OpenAI 兼容服务</td>
 *     <td>OpenAI 官方 GPT / o 系列</td>
 *   </tr>
 *   <tr>
 *     <td>输出上限</td>
 *     <td>不下发默认值（各家上限未知，交给服务端默认，避免 400）</td>
 *     <td>统一下发 {@code max_completion_tokens}</td>
 *   </tr>
 *   <tr>
 *     <td>推理强度</td>
 *     <td>不下发 {@code reasoning_effort}（第三方支持度不一）</td>
 *     <td>按模型精细下发（含 none / minimal 差异）</td>
 *   </tr>
 *   <tr>
 *     <td>流式用量</td>
 *     <td>不添加 {@code stream_options}</td>
 *     <td>自动带 {@code stream_options.include_usage}</td>
 *   </tr>
 * </table>
 *
 * <h3>本类保留的「协议级」处理</h3>
 * <ul>
 *   <li><b>端点自适应</b>：裸域名 → {@code /v1/chat/completions}；已含 {@code /v1} 不重复；
 *       已写全 {@code /chat/completions} 原样使用（绝对 URL，避免 baseUrl 路径被覆盖）</li>
 *   <li><b>推理模型协议差异</b>：o1 / o3 / o4 / gpt-5 系列必须用 {@code max_completion_tokens} 且不接受
 *       {@code temperature} —— 这是协议事实，与厂商标识无关，故保留判定</li>
 *   <li><b>模型名归一</b>：{@code openai/gpt-4o}、{@code azure/o3-mini} 按最后一段判定，避免中转站命名误判</li>
 * </ul>
 */
@Slf4j
public class OpenAIClient extends AbstractLLMClient {

    private static final String CHAT_SUFFIX = "/chat/completions";
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** 推理模型默认输出上限（max_completion_tokens） */
    private static final int DEFAULT_REASONING_MAX_COMPLETION_TOKENS = 16384;

    private final String chatEndpoint;

    public OpenAIClient(WebClient webClient, ObjectMapper objectMapper, String providerCode, String baseUrl) {
        super(webClient, objectMapper, providerCode);
        this.chatEndpoint = resolveChatEndpoint(baseUrl);
        log.info("OpenAI 兼容客户端初始化（公共契约）: provider={}, endpoint={}", providerCode, chatEndpoint);
    }

    @Override
    public String getChatEndpoint() {
        return chatEndpoint;
    }

    // ==================== 端点解析（公共契约核心） ====================

    /**
     * 解析 Chat Completions 完整端点（绝对 URL）。
     *
     * <p>包级可见：便于单元测试覆盖各类中转站写法。</p>
     */
    static String resolveChatEndpoint(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        base = base.replaceAll("/+$", "");
        if (base.endsWith(CHAT_SUFFIX)) {
            return base;
        }
        // 已带版本段（/v1、/v2、/openai/v1 等）→ 直接拼端点
        if (base.matches(".*/v\\d+$")) {
            return base + CHAT_SUFFIX;
        }
        return base + "/v1" + CHAT_SUFFIX;
    }

    /** 模型名归一：取最后一段（兼容 openai/gpt-4o、azure/o3-mini 这类前缀写法） */
    static String normalizeModelKey(String model) {
        if (model == null) {
            return "";
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        // 公共契约：不下发 reasoning_effort（第三方兼容服务支持度不一，容易 400）
        // 需要官方推理档位控制时请使用 gpt 模板（GptClient）
        return new HashMap<>();
    }

    @Override
    protected void customizeRequest(Map<String, Object> request,
                                     List<Map<String, Object>> messages,
                                     String model,
                                     Double temperature,
                                     String thinkingMode,
                                     boolean stream,
                                     List<Map<String, Object>> tools) {
        String modelKey = normalizeModelKey(model);

        if (isReasoningModel(modelKey)) {
            // 推理模型：不支持 temperature / max_tokens，必须用 max_completion_tokens（协议级差异）
            request.remove("temperature");
            request.remove("max_tokens");
            request.putIfAbsent("max_completion_tokens", DEFAULT_REASONING_MAX_COMPLETION_TOKENS);
        } else {
            // 普通模型：不主动下发 max_tokens（兼容服务上限未知），交给服务端默认值
            request.remove("max_completion_tokens");
        }

        // 清理其他适配器可能残留的内部标记字段
        request.remove("_remove_temperature");
        request.remove("_remove_top_p");
    }

    /**
     * 是否为「推理模型」（使用 max_completion_tokens、拒绝 temperature / max_tokens）。
     * <p>覆盖 o1 / o3 / o4 系列与 gpt-5 系列；入参应为 {@link #normalizeModelKey(String)} 归一后的模型名。</p>
     */
    static boolean isReasoningModel(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return false;
        }
        String m = modelKey.toLowerCase(Locale.ROOT);
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")
                || m.startsWith("gpt-5") || m.startsWith("gpt5");
    }
}
