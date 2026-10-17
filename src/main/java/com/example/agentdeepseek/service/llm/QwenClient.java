package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通义千问（阿里云百炼 DashScope / QwenCloud）LLMClient 实现。
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：阿里云百炼 / DashScope / QwenCloud 的千问模型（含 Qwen-VL / Qwen3-VL / Omni），
 *       需要 Qwen 扩展参数 {@code enable_thinking} / {@code enable_search}</li>
 *   <li><b>不适用</b>：其他 OpenAI 兼容服务（用 {@code openai}）；需要 {@code reasoning_effort} 全档位的
 *       官方 GPT / o 系列（用 {@code gpt}）；百炼上托管的第三方模型若需其原生参数，也建议评估专用模板</li>
 *   <li><b>专属能力</b>：{@code enable_thinking} + {@code thinking_budget}；对话级 {@code enable_search}
 *       （{@code extraConfig.enableSearch}）；余额查询（阿里云 BssOpenApi，{@code extraConfig.accessKey/secretKey}）</li>
 * </ul>
 *
 * <h3>协议要点（官方：OpenAI 兼容 Chat 接口）</h3>
 * <ul>
 *   <li>端点：{@code {baseUrl}/chat/completions}，Base URL 形如
 *       {@code https://dashscope.aliyuncs.com/compatible-mode/v1} 或
 *       {@code https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1}</li>
 *   <li>认证：{@code Authorization: Bearer <DASHSCOPE_API_KEY>}（按地域创建，跨地域会 401）</li>
 *   <li>Qwen 扩展参数（非 OpenAI 标准，直接放请求体顶层）：
 *       <ul>
 *         <li>{@code enable_thinking}：Boolean，开启深度思考（部分模型要求流式）</li>
 *         <li>{@code thinking_budget}：Integer，思考过程 token 上限</li>
 *         <li>{@code enable_search}：Boolean，启用联网搜索（替代 OpenAI 的 web_search_options，后者不支持）</li>
 *       </ul>
 *   </li>
 *   <li>SSE：与 OpenAI Chat Completions 兼容，思考内容位于 {@code delta.reasoning_content}</li>
 * </ul>
 *
 * <h3>thinkingMode 映射</h3>
 * <table>
 *   <tr><td>non-thinking</td><td>→ enable_thinking=false</td></tr>
 *   <tr><td>thinking</td><td>→ enable_thinking=true（+ thinking_budget，如配置）</td></tr>
 *   <tr><td>thinking_max</td><td>→ enable_thinking=true + thinking_budget（取配置或默认最大值）</td></tr>
 *   <tr><td>空 / auto</td><td>→ 不下发（交给模型默认策略）</td></tr>
 * </table>
 */
@Slf4j
public class QwenClient extends AbstractLLMClient {

    /** 百炼默认 Base URL（北京地域，旧域名仍可用） */
    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 请求模板标识 */
    public static final String TEMPLATE = "qwen";

    private static final String CHAT_SUFFIX = "/chat/completions";

    /** thinking_max 且未配置 budget 时使用的思考上限 */
    private static final int DEFAULT_MAX_THINKING_BUDGET = 8192;

    private final String chatEndpoint;

    /** 是否开启对话级联网搜索（enable_search，由 Provider 扩展配置控制） */
    private final boolean enableSearch;

    /** 思考预算（可空；为空时不主动限制） */
    private final Integer thinkingBudget;

    public QwenClient(WebClient webClient, ObjectMapper objectMapper, String providerCode, String baseUrl,
                      boolean enableSearch, Integer thinkingBudget) {
        super(webClient, objectMapper, providerCode);
        this.chatEndpoint = resolveChatEndpoint(baseUrl);
        this.enableSearch = enableSearch;
        this.thinkingBudget = thinkingBudget;
        log.info("通义千问客户端初始化: provider={}, endpoint={}, enableSearch={}, thinkingBudget={}",
                providerCode, chatEndpoint, enableSearch, thinkingBudget);
    }

    @Override
    public String getChatEndpoint() {
        return chatEndpoint;
    }

    // ==================== 端点解析 ====================

    /**
     * 解析 DashScope Chat Completions 完整端点（绝对 URL）。
     *
     * <ul>
     *   <li>{@code https://dashscope.aliyuncs.com/compatible-mode/v1} → {@code .../compatible-mode/v1/chat/completions}</li>
     *   <li>{@code https://dashscope.aliyuncs.com} → 自动补 {@code /compatible-mode/v1}</li>
     *   <li>已含 {@code /chat/completions} → 原样使用（代理网关 / 中转站）</li>
     * </ul>
     *
     * <p>包级可见：便于单元测试覆盖。</p>
     */
    static String resolveChatEndpoint(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        base = base.replaceAll("/+$", "");
        if (base.endsWith(CHAT_SUFFIX)) {
            return base;
        }
        if (base.contains("/compatible-mode/v1") || base.matches(".*/v\\d+$")) {
            return base + CHAT_SUFFIX;
        }
        return base + "/compatible-mode/v1" + CHAT_SUFFIX;
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        Map<String, Object> params = new HashMap<>();
        String mode = thinkingMode == null ? "" : thinkingMode.trim();

        if (mode.isEmpty() || "auto".equals(mode)) {
            // 不下发：由模型默认策略决定（Qwen3 混合模型默认不思考，思考模型默认思考）
            return params;
        }
        if ("non-thinking".equals(mode)) {
            params.put("enable_thinking", false);
            return params;
        }
        params.put("enable_thinking", true);
        if (thinkingBudget != null && thinkingBudget > 0) {
            params.put("thinking_budget", thinkingBudget);
        } else if ("thinking_max".equals(mode)) {
            params.put("thinking_budget", DEFAULT_MAX_THINKING_BUDGET);
        }
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
        // ===== 1. 对话级联网搜索（Qwen 扩展参数；OpenAI 的 web_search_options 不被支持） =====
        if (enableSearch) {
            request.put("enable_search", true);
            Map<String, Object> searchOptions = new HashMap<>();
            searchOptions.put("forced_search", false);
            request.put("search_options", searchOptions);
        }

        // ===== 2. 图片块 detail 字段 DashScope 未声明，剔除避免严格校验 =====
        stripImageDetail(messages);

        // ===== 3. 清理其他 Provider 的残留参数（Qwen 用 max_tokens） =====
        request.remove("max_completion_tokens");
    }
}
