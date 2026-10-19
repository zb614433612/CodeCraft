package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 火山引擎方舟（Volcengine Ark）LLMClient 实现
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：火山引擎方舟平台（豆包全系，以及方舟上托管的 DeepSeek / GLM / Kimi 等），
 *       需要方舟 {@code thinking.type=enabled|disabled|auto} 语义</li>
 *   <li><b>不适用</b>：DeepSeek 官方 API（用 {@code deepseek}）；任意第三方 OpenAI 兼容中转站（用 {@code openai}）</li>
 *   <li><b>专属能力</b>：方舟多模态 {@code image_url}（detail 归一到方舟取值域）；
 *       {@code reasoning_effort} 档位（默认仅 deepseek-v4 系列，可扩展）；联网搜索（{@code extraConfig.searchApiKey}）；
 *       余额查询（费用中心 QueryBalanceAcct，{@code extraConfig.accessKey/secretKey}）</li>
 * </ul>
 *
 * <h3>协议要点（官方文档：对话(Chat) API）</h3>
 * <ul>
 *   <li>端点：{@code {baseUrl}/chat/completions}，Base URL 默认为
 *       {@code https://ark.cn-beijing.volces.com/api/v3}（已含 {@code /api/v3}，故端点不再重复拼接）</li>
 *   <li>认证：{@code Authorization: Bearer <ARK_API_KEY>}（与 OpenAI 一致）</li>
 *   <li>思考开关：{@code thinking.type = enabled | disabled | auto}</li>
 *   <li>思考档位：{@code reasoning_effort = low | high | max}（方舟仅部分模型支持；
 *       默认只对 {@code deepseek-v4} 系列下发，其余模型不下发以避免 400，可用扩展配置放开）</li>
 *   <li>多模态：{@code content} 数组，图片块为 {@code {"type":"image_url","image_url":{"url":"...","detail":"low|high|xhigh"}}}
 *       ——注意方舟不接受 OpenAI 的 {@code detail=auto}，本类会把 auto / 缺省归一为配置值（缺省 {@code high}）</li>
 *   <li>SSE：与 OpenAI Chat Completions 兼容，思考内容位于 {@code delta.reasoning_content}</li>
 * </ul>
 *
 * <h3>thinkingMode 映射</h3>
 * <table>
 *   <tr><td>non-thinking</td><td>→ thinking.type=disabled</td></tr>
 *   <tr><td>thinking</td><td>→ thinking.type=enabled（命中档位白名单时追加 reasoning_effort=high）</td></tr>
 *   <tr><td>thinking_max</td><td>→ thinking.type=enabled（命中档位白名单时追加 reasoning_effort=max）</td></tr>
 *   <tr><td>auto</td><td>→ thinking.type=auto（模型自行判断是否思考）</td></tr>
 * </table>
 *
 * <h3>可配置项（Provider「扩展配置」extraConfig）</h3>
 * <table border="1">
 *   <tr><th>字段</th><th>取值</th><th>缺省</th><th>作用</th></tr>
 *   <tr>
 *     <td>{@code imageDetail}</td><td>{@code low} / {@code high} / {@code xhigh}</td><td>{@code high}</td>
 *     <td>多模态图片解析粒度；{@code auto} 等非法值自动回落缺省值并告警（方舟取值域不含 auto）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code reasoningEffortModels}</td><td>JSON 数组或逗号分隔字符串，如 {@code ["deepseek-v4","doubao-seed-1-6"]}</td>
 *     <td>{@code ["deepseek-v4"]}</td>
 *     <td>reasoning_effort 白名单（按模型前缀匹配）；不命中的模型不下发该参数，避免 400</td>
 *   </tr>
 *   <tr>
 *     <td>{@code reasoningEffortEnabled}</td><td>{@code true} / {@code false}</td><td>{@code false}</td>
 *     <td>{@code true} = 对所有模型下发 reasoning_effort（覆盖白名单，仅在确认平台全量支持时使用）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code reasoningEffortHigh}</td><td>字符串</td><td>{@code high}</td>
 *     <td>thinking 模式下发的档位文案（部分模型用 medium）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code reasoningEffortMax}</td><td>字符串</td><td>{@code max}</td>
 *     <td>thinking_max 模式下发的档位文案</td>
 *   </tr>
 * </table>
 */
@Slf4j
public class VolcengineArkClient extends AbstractLLMClient {

    /** 方舟默认 Base URL（华北·北京） */
    public static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    /** 默认请求模板标识（供 ProviderBalanceService 等按模板判定能力使用） */
    public static final String TEMPLATE = "volcengine";

    /** 缺省支持 reasoning_effort 档位的模型前缀（方舟文档：deepseek-v4-pro / deepseek-v4-flash） */
    private static final List<String> DEFAULT_REASONING_EFFORT_MODELS = List.of("deepseek-v4");

    /** 方舟图片解析粒度合法取值域 */
    private static final Set<String> VALID_IMAGE_DETAILS = Set.of("low", "high", "xhigh");

    /** 缺省图片解析粒度（方舟不接受 auto，high 是通用安全值） */
    private static final String DEFAULT_IMAGE_DETAIL = "high";

    /** 缺省档位文案 */
    private static final String DEFAULT_EFFORT_HIGH = "high";
    private static final String DEFAULT_EFFORT_MAX = "max";

    /** 方舟 chat 端点后缀 */
    private static final String CHAT_SUFFIX = "/chat/completions";

    private final String chatEndpoint;

    /** 多模态图片解析粒度（low / high / xhigh） */
    private final String imageDetail;

    /** reasoning_effort 白名单（模型前缀，小写） */
    private final List<String> reasoningEffortModels;

    /** 是否对所有模型下发 reasoning_effort（覆盖白名单） */
    private final boolean reasoningEffortForAllModels;

    /** thinking 档位文案 / thinking_max 档位文案 */
    private final String reasoningEffortHigh;
    private final String reasoningEffortMax;

    public VolcengineArkClient(WebClient webClient, ObjectMapper objectMapper, String providerCode, String baseUrl) {
        this(webClient, objectMapper, providerCode, baseUrl, Options.defaults());
    }

    public VolcengineArkClient(WebClient webClient, ObjectMapper objectMapper, String providerCode, String baseUrl,
                               Options options) {
        super(webClient, objectMapper, providerCode);
        this.chatEndpoint = resolveChatEndpoint(baseUrl);

        Options effective = options != null ? options : Options.defaults();
        this.imageDetail = normalizeImageDetailOption(effective.imageDetail());
        this.reasoningEffortModels = normalizeModelPrefixes(effective.reasoningEffortModels());
        this.reasoningEffortForAllModels = effective.reasoningEffortForAllModels();
        this.reasoningEffortHigh = defaultIfBlank(effective.reasoningEffortHigh(), DEFAULT_EFFORT_HIGH);
        this.reasoningEffortMax = defaultIfBlank(effective.reasoningEffortMax(), DEFAULT_EFFORT_MAX);

        log.info("火山方舟客户端初始化: provider={}, chatEndpoint={}, imageDetail={}, reasoningEffortModels={}, reasoningEffortAll={}",
                providerCode, chatEndpoint, imageDetail, reasoningEffortModels, reasoningEffortForAllModels);
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
        // ===== 1. 思考档位：仅对白名单模型下发，其余模型不传，避免 400 InvalidParameter =====
        String mode = (thinkingMode == null || thinkingMode.isBlank()) ? "thinking" : thinkingMode.trim();
        if ("thinking".equals(mode) || "thinking_max".equals(mode)) {
            if (supportsReasoningEffort(model)) {
                request.put("reasoning_effort", "thinking_max".equals(mode) ? reasoningEffortMax : reasoningEffortHigh);
            } else if (log.isDebugEnabled() && model != null && !model.isBlank()) {
                log.debug("模型 {} 不在 reasoning_effort 白名单 {} 中，跳过档位下发", model, reasoningEffortModels);
            }
        }

        // ===== 2. 图片解析粒度归一：方舟只接受 low/high/xhigh，OpenAI 风格的 auto 会被拒绝 =====
        normalizeImageDetail(messages);

        // ===== 3. 方舟不支持的参数（来自其他适配器的残留）清理 =====
        request.remove("max_completion_tokens");
    }

    /**
     * 判断模型是否支持（允许下发）reasoning_effort。
     *
     * <p>{@code reasoningEffortEnabled=true} 时对所有有模型名的请求放开；否则按前缀白名单匹配。</p>
     */
    private boolean supportsReasoningEffort(String model) {
        if (model == null || model.isBlank()) {
            // 模型名为空时不下发：方舟无法据此判定档位合法性，保守跳过
            return false;
        }
        if (reasoningEffortForAllModels) {
            return true;
        }
        String lower = model.toLowerCase(Locale.ROOT);
        for (String prefix : reasoningEffortModels) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把多模态图片块的 {@code detail} 归一为配置值（方舟取值域：low / high / xhigh）。
     *
     * <p>缺省或 {@code auto} 一律替换为 {@code imageDetail}；显式配置的 low/high/xhigh 原样保留。</p>
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
                        ((Map<String, Object>) iu).put("detail", imageDetail);
                    }
                }
            }
        }
    }

    // ==================== 配置归一 ====================

    /** 归一图片粒度：非法值告警并回落缺省值 */
    private static String normalizeImageDetailOption(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_IMAGE_DETAIL;
        }
        String detail = value.trim().toLowerCase(Locale.ROOT);
        if (VALID_IMAGE_DETAILS.contains(detail)) {
            return detail;
        }
        log.warn("扩展配置 imageDetail={} 非法（方舟仅接受 low/high/xhigh），已回落为 {}", value, DEFAULT_IMAGE_DETAIL);
        return DEFAULT_IMAGE_DETAIL;
    }

    /** 归一模型前缀白名单：去空、去重、小写；全空时回落缺省 */
    private static List<String> normalizeModelPrefixes(List<String> models) {
        if (models == null || models.isEmpty()) {
            return DEFAULT_REASONING_EFFORT_MODELS;
        }
        List<String> result = new ArrayList<>();
        for (String model : models) {
            if (model == null || model.isBlank()) {
                continue;
            }
            String prefix = model.trim().toLowerCase(Locale.ROOT);
            if (!result.contains(prefix)) {
                result.add(prefix);
            }
        }
        return result.isEmpty() ? DEFAULT_REASONING_EFFORT_MODELS : List.copyOf(result);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    // ==================== 扩展配置载体 ====================

    /**
     * 方舟专属扩展配置（由 {@code LLMClientManager} 从 Provider 的 extraConfig 解析后传入）。
     *
     * <p>全部字段允许为 null：null 表示「用缺省值」，不要在构造阶段做业务默认值判断。</p>
     */
    public record Options(String imageDetail,
                          List<String> reasoningEffortModels,
                          boolean reasoningEffortForAllModels,
                          String reasoningEffortHigh,
                          String reasoningEffortMax) {

        /** 全部缺省：imageDetail=high、白名单=deepseek-v4、档位 high/max */
        public static Options defaults() {
            return new Options(null, null, false, null, null);
        }
    }
}
