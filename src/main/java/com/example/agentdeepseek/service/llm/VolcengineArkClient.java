package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 火山引擎方舟（Volcengine Ark）LLMClient 实现
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：火山引擎方舟平台（豆包全系，以及方舟上托管的 DeepSeek / GLM / Kimi 等），
 *       需要方舟 {@code thinking.type=enabled|disabled|auto} 语义</li>
 *   <li><b>不适用</b>：DeepSeek 官方 API（用 {@code deepseek}）；任意第三方 OpenAI 兼容中转站（用 {@code openai}）</li>
 *   <li><b>专属能力</b>：方舟多模态 {@code image_url}（自动把 detail=auto 归一为 high）；
 *       {@code deepseek-v4} 系列 reasoning_effort；联网搜索（{@code extraConfig.searchApiKey}）；
 *       余额查询（费用中心 QueryBalanceAcct，{@code extraConfig.accessKey/secretKey}）</li>
 * </ul>
 *
 * <h3>协议要点（官方文档：对话(Chat) API）</h3>
 * <ul>
 *   <li>端点：{@code {baseUrl}/chat/completions}，Base URL 默认为
 *       {@code https://ark.cn-beijing.volces.com/api/v3}（已含 {@code /api/v3}，故端点不再重复拼接）</li>
 *   <li>认证：{@code Authorization: Bearer <ARK_API_KEY>}（与 OpenAI 一致）</li>
 *   <li>思考开关：{@code thinking.type = enabled | disabled | auto}</li>
 *   <li>思考档位：{@code reasoning_effort = low | high | max}（仅 deepseek-v4 系列明确支持，其余模型不下发，避免 400）</li>
 *   <li>多模态：{@code content} 数组，图片块为 {@code {"type":"image_url","image_url":{"url":"...","detail":"low|high|xhigh"}}}
 *       ——注意方舟不接受 OpenAI 的 {@code detail=auto}，本类会自动归一为 {@code high}</li>
 *   <li>SSE：与 OpenAI Chat Completions 兼容，思考内容位于 {@code delta.reasoning_content}</li>
 * </ul>
 *
 * <h3>thinkingMode 映射</h3>
 * <table>
 *   <tr><td>non-thinking</td><td>→ thinking.type=disabled</td></tr>
 *   <tr><td>thinking</td><td>→ thinking.type=enabled（deepseek-v4 系列追加 reasoning_effort=high）</td></tr>
 *   <tr><td>thinking_max</td><td>→ thinking.type=enabled（deepseek-v4 系列追加 reasoning_effort=max）</td></tr>
 *   <tr><td>auto</td><td>→ thinking.type=auto（模型自行判断是否思考）</td></tr>
 * </table>
 */
@Slf4j
public class VolcengineArkClient extends AbstractLLMClient {

    /** 方舟默认 Base URL（华北·北京） */
    public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    /** 默认请求模板标识（供 ProviderBalanceService 等按模板判定能力使用） */
    public static final String TEMPLATE = "volcengine";

    /** 明确支持 reasoning_effort 档位的模型前缀（方舟文档：deepseek-v4-pro / deepseek-v4-flash） */
    private static final String REASONING_EFFORT_MODEL_PREFIX = "deepseek-v4";

    /** 方舟 chat 端点后缀 */
    private static final String CHAT_SUFFIX = "/chat/completions";

    private final String chatEndpoint;

    public VolcengineArkClient(WebClient webClient, ObjectMapper objectMapper, String providerCode, String baseUrl) {
        super(webClient, objectMapper, providerCode);
        this.chatEndpoint = resolveChatEndpoint(baseUrl);
        log.info("火山方舟客户端初始化: provider={}, chatEndpoint={}", providerCode, chatEndpoint);
    }

    @Override
    public String getChatEndpoint() {
        return chatEndpoint;
    }

    // ==================== 端点解析 ====================

    /**
     * 解析方舟 Chat Completions 完整端点（返回绝对 URL，避免 baseUrl 自带路径被 WebClient 覆盖）。
     *
     * <p>兼容三种常见配置：</p>
     * <ul>
     *   <li>{@code https://ark.cn-beijing.volces.com/api/v3} → {@code .../api/v3/chat/completions}</li>
     *   <li>{@code https://ark.cn-beijing.volces.com} → {@code .../api/v3/chat/completions}（自动补 /api/v3）</li>
     *   <li>已含 {@code /chat/completions} 的完整地址 → 原样使用（支持用户填代理网关）</li>
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
        // 已含 API 版本路径（/api/v3、/api/coding/v3 等）→ 直接拼端点
        if (base.contains("/api/v3") || base.matches(".*/v\\d+$")) {
            return base + CHAT_SUFFIX;
        }
        return base + "/api/v3" + CHAT_SUFFIX;
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        Map<String, Object> params = new HashMap<>();
        String mode = (thinkingMode == null || thinkingMode.isBlank()) ? "thinking" : thinkingMode.trim();

        String type = switch (mode) {
            case "non-thinking" -> "disabled";
            case "auto" -> "auto";
            default -> "enabled";
        };
        params.put("thinking", Map.of("type", type));
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
        // ===== 1. 思考档位：仅对明确支持的模型下发，其余模型不传，避免 400 InvalidParameter =====
        String mode = (thinkingMode == null || thinkingMode.isBlank()) ? "thinking" : thinkingMode.trim();
        if (model != null && model.toLowerCase().startsWith(REASONING_EFFORT_MODEL_PREFIX)) {
            if ("thinking".equals(mode)) {
                request.put("reasoning_effort", "high");
            } else if ("thinking_max".equals(mode)) {
                request.put("reasoning_effort", "max");
            }
        }

        // ===== 2. 图片解析粒度归一：方舟只接受 low/high/xhigh，OpenAI 风格的 auto 会被拒绝 =====
        normalizeImageDetail(messages);

        // ===== 3. 方舟不支持的参数（来自其他适配器的残留）清理 =====
        request.remove("max_completion_tokens");
    }

    /**
     * 把多模态图片块的 {@code detail=auto} 归一为 {@code high}（方舟取值域：low / high / xhigh）。
     */
    @SuppressWarnings("unchecked")
    private void normalizeImageDetail(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (!(content instanceof List<?> parts)) {
                continue;
            }
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> block)) {
                    continue;
                }
                if (!"image_url".equals(block.get("type"))) {
                    continue;
                }
                Object imageUrl = block.get("image_url");
                if (imageUrl instanceof Map<?, ?> iu) {
                    Object detail = iu.get("detail");
                    if (detail == null || "auto".equalsIgnoreCase(String.valueOf(detail))) {
                        ((Map<String, Object>) iu).put("detail", "high");
                    }
                }
            }
        }
    }
}
