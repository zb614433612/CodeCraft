package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 官方 GPT 系列专用适配器（请求模板 {@code gpt}）。
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：OpenAI 官方 GPT / o 系列（{@code api.openai.com} 或官方代理），需要
 *       {@code reasoning_effort} 精细档位（none / minimal / low / medium / high / xhigh）、
 *       {@code max_completion_tokens}、流式用量统计</li>
 *   <li><b>不适用</b>：任何第三方 OpenAI 兼容服务（Azure / OpenRouter / 中转站 / vLLM / LM Studio / One-API）——
 *       它们往往不支持 {@code max_completion_tokens} / {@code reasoning_effort} / {@code stream_options}，
 *       请改用 {@code openai} 公共契约模板</li>
 *   <li><b>专属能力</b>：{@code max_tokens} 注入自动转 {@code max_completion_tokens}；
 *       推理模型自动移除 {@code temperature}；GPT-5 支持 {@code none}/{@code minimal}，o 系列不支持 {@code none}</li>
 * </ul>
 *
 * <h3>与 {@link OpenAIClient}（公共契约）的职责边界</h3>
 * <table>
 *   <tr><th></th><th>{@code gpt}（本类，官方专精）</th><th>{@code openai}（公共契约）</th></tr>
 *   <tr>
 *     <td>适用对象</td>
 *     <td>OpenAI 官方 GPT / o 系列（baseUrl 默认 api.openai.com）</td>
 *     <td>任意 OpenAI 兼容服务（Azure、OpenRouter、中转站、vLLM / LM Studio / One-API…）</td>
 *   </tr>
 *   <tr>
 *     <td>输出上限</td>
 *     <td>统一下发 {@code max_completion_tokens}（{@code max_tokens} 已废弃，GPT-5 / o 系列会直接 400）</td>
 *     <td>不下发默认值（各家上限未知，交给服务端）</td>
 *   </tr>
 *   <tr>
 *     <td>推理强度</td>
 *     <td>按模型下发 {@code reasoning_effort}（GPT-5 支持 none/minimal；o 系列不支持 none）</td>
 *     <td>不下发（第三方支持度不一）</td>
 *   </tr>
 *   <tr>
 *     <td>temperature</td>
 *     <td>推理模型 / 开启推理时自动剔除（官方会拒绝）</td>
 *     <td>仅推理模型剔除</td>
 *   </tr>
 *   <tr>
 *     <td>流式用量</td>
 *     <td>自动带 {@code stream_options.include_usage}</td>
 *     <td>不添加</td>
 *   </tr>
 * </table>
 *
 * <h3>thinkingMode 映射（reasoning_effort）</h3>
 * <table>
 *   <tr><td>non-thinking</td><td>GPT-5 系列 → {@code none}；o 系列 → 不下发（o 系列不支持关闭）</td></tr>
 *   <tr><td>thinking</td><td>→ {@code medium}</td></tr>
 *   <tr><td>thinking_max</td><td>→ {@code high}</td></tr>
 *   <tr><td>minimal / low / high / xhigh</td><td>原样透传（便于前端/Agent 精细控制档位）</td></tr>
 *   <tr><td>空 / auto</td><td>不下发（交给模型默认策略）</td></tr>
 * </table>
 */
@Slf4j
public class GptClient extends AbstractLLMClient {

    /** 官方默认 Base URL */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** 请求模板标识 */
    public static final String TEMPLATE = "gpt";

    private static final String CHAT_SUFFIX = "/chat/completions";
    private static final String OFFICIAL_HOST = "api.openai.com";

    /** 普通模型默认输出上限 */
    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 8192;

    /** 推理模型默认输出上限（含推理 token，需更大） */
    private static final int DEFAULT_REASONING_MAX_COMPLETION_TOKENS = 16384;

    private final String chatEndpoint;

    public GptClient(WebClient webClient, ObjectMapper objectMapper, String providerCode, String baseUrl) {
        super(webClient, objectMapper, providerCode);
        this.chatEndpoint = resolveChatEndpoint(baseUrl);
        log.info("GPT 官方客户端初始化: provider={}, endpoint={}", providerCode, chatEndpoint);
    }

    @Override
    public String getChatEndpoint() {
        return chatEndpoint;
    }

    // ==================== 端点解析 ====================

    /**
     * 解析 Chat Completions 完整端点（绝对 URL）。
     *
     * <p>默认官方 {@code https://api.openai.com/v1}；若用户填官方代理/自定义网关（仍走 OpenAI 官方模型），
     * 支持：裸域名补 {@code /v1}、已含 {@code /v1} 不重复、已含完整 {@code /chat/completions} 原样使用。</p>
     *
     * <p>包级可见：便于单元测试覆盖。</p>
     */
    static String resolveChatEndpoint(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        base = base.replaceAll("/+$", "");
        if (base.endsWith(CHAT_SUFFIX)) {
            return base;
        }
        if (base.matches(".*/v\\d+$")) {
            return base + CHAT_SUFFIX;
        }
        return base + "/v1" + CHAT_SUFFIX;
    }

    /** baseUrl 是否指向 OpenAI 官方域名（仅用于日志提示） */
    static boolean isOfficialHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host != null && OFFICIAL_HOST.equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** 模型名归一：取最后一段（兼容 openai/gpt-5、azure/gpt-5 这类前缀写法） */
    static String normalizeModelKey(String model) {
        if (model == null) {
            return "";
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /** 是否为推理模型（o1 / o3 / o4 / gpt-5 系列） */
    static boolean isReasoningModel(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return false;
        }
        String m = modelKey.toLowerCase(Locale.ROOT);
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")
                || m.startsWith("gpt-5") || m.startsWith("gpt5");
    }

    /** 是否为 GPT-5 系列（支持 reasoning_effort=none / minimal） */
    static boolean isGpt5Family(String modelKey) {
        if (modelKey == null) {
            return false;
        }
        String m = modelKey.toLowerCase(Locale.ROOT);
        return m.startsWith("gpt-5") || m.startsWith("gpt5");
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        // reasoning_effort 需要结合模型能力（o 系列不支持 none），统一在 customizeRequest 处理
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
        boolean reasoningModel = isReasoningModel(modelKey);
        boolean gpt5 = isGpt5Family(modelKey);

        // ===== 1. 推理强度（reasoning_effort）=====
        String effort = resolveReasoningEffort(thinkingMode, reasoningModel, gpt5);
        if (effort != null) {
            request.put("reasoning_effort", effort);
        }

        // ===== 2. 输出上限：官方统一 max_completion_tokens（max_tokens 已废弃，推理模型会 400）=====
        Object injectedMaxTokens = request.remove("max_tokens");
        if (injectedMaxTokens != null) {
            // 兼容 post-build 注入（如 PromptOptimizeService）
            request.put("max_completion_tokens", injectedMaxTokens);
        } else {
            request.putIfAbsent("max_completion_tokens",
                    reasoningModel ? DEFAULT_REASONING_MAX_COMPLETION_TOKENS : DEFAULT_MAX_COMPLETION_TOKENS);
        }

        // ===== 3. temperature：推理模型、或开启推理（非 none）时官方会拒绝 =====
        if (reasoningModel || (effort != null && !"none".equalsIgnoreCase(effort))) {
            request.remove("temperature");
        }

        // ===== 4. 流式调用附带用量统计（官方支持 stream_options.include_usage）=====
        if (stream) {
            Map<String, Object> streamOptions = new HashMap<>();
            streamOptions.put("include_usage", true);
            request.putIfAbsent("stream_options", streamOptions);
        }

        // ===== 5. 清理其他适配器残留的内部标记 =====
        request.remove("_remove_temperature");
        request.remove("_remove_top_p");
    }

    /**
     * 解析 reasoning_effort。
     *
     * @param thinkingMode   Agent 的思考模式
     * @param reasoningModel 是否推理模型（o 系列 / gpt-5）
     * @param gpt5           是否 GPT-5 系列（唯一支持 none / minimal 的官方系列）
     * @return effort 取值；null 表示不下发
     */
    static String resolveReasoningEffort(String thinkingMode, boolean reasoningModel, boolean gpt5) {
        String mode = thinkingMode == null ? "" : thinkingMode.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty() || "auto".equals(mode)) {
            return null;
        }
        switch (mode) {
            case "non-thinking":
                // GPT-5 系列可显式关闭推理；o 系列不支持 none，只能不下发
                return gpt5 ? "none" : null;
            case "thinking":
                return "medium";
            case "thinking_max":
                return "high";
            case "minimal":
                // 仅 GPT-5 系列支持 minimal，其他推理模型降级为 low
                return gpt5 ? "minimal" : (reasoningModel ? "low" : null);
            case "low":
            case "medium":
            case "high":
            case "xhigh":
                return mode;
            default:
                return null;
        }
    }
}
