package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 协议的「公共契约」适配器。
 *
 * <p><b>设计目标</b>：任何符合 Anthropic Messages 规范的服务（Anthropic 官方、GLM / DeepSeek / MiniMax
 * 等提供的 {@code /anthropic} 兼容端点、各类 Claude 中转站）都能直接用本适配器接入，
 * 只需配置 baseUrl + apiKey + 模型名。</p>
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：任意 Anthropic Messages 兼容服务——Anthropic 官方、Claude 中转站、
 *       GLM / DeepSeek / MiniMax 的 {@code /anthropic} 兼容层</li>
 *   <li><b>不适用</b>：OpenAI Chat Completions 协议的服务（用 {@code openai} 公共契约或 {@code gpt} 官方专精）</li>
 *   <li><b>专属能力</b>：OpenAI→Anthropic 的<b>工具与图片块协议转换</b>（{@code image_url → image/source}）、
 *       system 顶层提取、{@code thinking} 仅在开启时下发、{@code max_tokens} 支持 {@code extraConfig.maxTokens} 覆盖</li>
 * </ul>
 *
 * <h3>与 OpenAI 协议的关键差异</h3>
 * <table>
 *   <tr><th>特性</th><th>OpenAI</th><th>Anthropic</th></tr>
 *   <tr><td>端点</td><td>/v1/chat/completions</td><td>/v1/messages</td></tr>
 *   <tr><td>认证头</td><td>Authorization: Bearer</td><td>x-api-key + anthropic-version</td></tr>
 *   <tr><td>system 消息</td><td>messages[0].role=system</td><td>顶层 system 字段</td></tr>
 *   <tr><td>图片块</td><td>{"type":"image_url","image_url":{"url"}}</td><td>{"type":"image","source":{"type":"base64|url"}}</td></tr>
 *   <tr><td>工具格式</td><td>{type,function:{name,description,parameters}}</td><td>{name,description,input_schema}</td></tr>
 *   <tr><td>SSE</td><td>choices[].delta</td><td>content_block_delta / thinking_delta</td></tr>
 * </table>
 *
 * <h3>通用化处理</h3>
 * <ul>
 *   <li><b>端点自适应</b>：{@code https://host} → {@code /v1/messages}；
 *       {@code https://host/anthropic}（GLM / MiniMax / DeepSeek 兼容层）→ {@code /anthropic/v1/messages}；
 *       已写全 {@code /messages} → 原样使用（绝对 URL）</li>
 *   <li><b>图片块协议转换</b>：把内部统一使用的 OpenAI 风格 {@code image_url} 块转换为 Anthropic 的
 *       {@code image/source} 块（data URL → base64，http(s) → url），使 Anthropic 兼容服务同样具备识图能力</li>
 *   <li><b>thinking 只在开启时下发</b>：{@code non-thinking} 时不发送 thinking 字段（很多兼容层不认识该字段会 400）</li>
 *   <li><b>max_tokens 可配置</b>：默认 8192，可用扩展配置 {@code maxTokens} 覆盖（各兼容服务上限不一）；
 *       思考模式自动放宽到「预算 + 8192」，满足 {@code max_tokens > budget_tokens} 的硬性要求</li>
 * </ul>
 */
@Slf4j
public class AnthropicClient extends AbstractLLMClient {

    private static final String MESSAGES_SUFFIX = "/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /** 默认输出上限（普通模式） */
    private static final int DEFAULT_MAX_TOKENS = 8192;

    /** 思考预算（extended thinking budget_tokens） */
    private static final int THINKING_BUDGET_TOKENS = 8000;

    /** 思考模式下在预算之外额外预留的回复空间 */
    private static final int THINKING_EXTRA_TOKENS = 8192;

    private final String chatEndpoint;

    /** 普通模式输出上限（可由扩展配置 maxTokens 覆盖） */
    private final int maxTokens;

    public AnthropicClient(WebClient webClient, ObjectMapper objectMapper, String providerCode,
                           String baseUrl, Integer configuredMaxTokens) {
        super(webClient, objectMapper, providerCode);
        this.chatEndpoint = resolveChatEndpoint(baseUrl);
        this.maxTokens = (configuredMaxTokens != null && configuredMaxTokens > 0)
                ? configuredMaxTokens : DEFAULT_MAX_TOKENS;
        log.info("Anthropic 兼容客户端初始化: provider={}, endpoint={}, maxTokens={}",
                providerCode, chatEndpoint, maxTokens);
    }

    @Override
    public String getChatEndpoint() {
        return chatEndpoint;
    }

    // ==================== 端点解析（公共契约核心） ====================

    /**
     * 解析 Messages 完整端点（绝对 URL）。
     *
     * <ul>
     *   <li>{@code https://api.anthropic.com} → {@code .../v1/messages}</li>
     *   <li>{@code https://api.anthropic.com/v1} → {@code .../v1/messages}</li>
     *   <li>{@code https://api.deepseek.com/anthropic} → {@code .../anthropic/v1/messages}</li>
     *   <li>已含 {@code /messages} → 原样使用</li>
     * </ul>
     *
     * <p>包级可见：便于单元测试覆盖各类兼容层写法。</p>
     */
    static String resolveChatEndpoint(String baseUrl) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        base = base.replaceAll("/+$", "");
        if (base.endsWith(MESSAGES_SUFFIX)) {
            return base;
        }
        // 已带版本段（/v1）→ 直接拼端点
        if (base.matches(".*/v\\d+$")) {
            return base + MESSAGES_SUFFIX;
        }
        // 兼容层前缀（/anthropic 等）或裸域名 → 统一补 /v1/messages
        return base + "/v1" + MESSAGES_SUFFIX;
    }

    /** baseUrl 是否指向 Anthropic 官方域名 */
    static boolean isOfficialHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host != null && "api.anthropic.com".equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        // Anthropic 的 thinking 放在顶层，由 customizeRequest 统一处理
        return Collections.emptyMap();
    }

    @Override
    protected void customizeRequest(Map<String, Object> request,
                                     List<Map<String, Object>> messages,
                                     String model,
                                     Double temperature,
                                     String thinkingMode,
                                     boolean stream,
                                     List<Map<String, Object>> tools) {
        String effectiveMode = (thinkingMode == null || thinkingMode.isBlank())
                ? "non-thinking" : thinkingMode.trim();
        boolean thinkingEnabled = !"non-thinking".equals(effectiveMode);

        // ===== 1. max_tokens 必填；思考模式下必须严格大于 budget_tokens =====
        if (thinkingEnabled) {
            int thinkingMaxTokens = Math.max(maxTokens, THINKING_BUDGET_TOKENS + THINKING_EXTRA_TOKENS);
            request.put("max_tokens", thinkingMaxTokens);
        } else {
            request.put("max_tokens", maxTokens);
        }

        // ===== 2. 提取 system 消息到顶层（Anthropic 不允许 messages 里出现 system 角色）=====
        List<Map<String, Object>> conversationMessages = new ArrayList<>();
        List<String> systemContents = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            if ("system".equals(role)) {
                Object content = msg.get("content");
                if (content instanceof String s) {
                    systemContents.add(s);
                } else if (content instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> itemMap) {
                            Object text = itemMap.get("text");
                            if (text instanceof String s) {
                                systemContents.add(s);
                            }
                        }
                    }
                }
            } else {
                conversationMessages.add(msg);
            }
        }

        // ===== 3. 图片块协议转换（OpenAI image_url → Anthropic image/source）=====
        convertImageBlocks(conversationMessages);

        if (!systemContents.isEmpty()) {
            request.put("system", systemContents.size() == 1
                    ? systemContents.get(0) : systemContents);
        }
        request.put("messages", conversationMessages);

        // ===== 4. thinking 参数：仅在开启时下发（兼容层不认识该字段会 400）=====
        if (thinkingEnabled) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", THINKING_BUDGET_TOKENS);
            request.put("thinking", thinking);
            // Anthropic 规定：开启 extended thinking 时 temperature 必须为 1
            request.remove("temperature");
        } else {
            request.remove("thinking");
        }

        // ===== 5. 工具格式转换：OpenAI → Anthropic =====
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> anthropicTools = new ArrayList<>();
            for (Map<String, Object> openaiTool : tools) {
                Object fnObj = openaiTool.get("function");
                if (fnObj instanceof Map<?, ?> fnMap) {
                    Map<String, Object> anthropicTool = new LinkedHashMap<>();
                    anthropicTool.put("name", fnMap.get("name"));
                    Object description = fnMap.get("description");
                    anthropicTool.put("description", description == null ? "" : description);
                    Object parameters = fnMap.get("parameters");
                    anthropicTool.put("input_schema", parameters != null ? parameters
                            : Map.of("type", "object", "properties", Map.of()));
                    anthropicTools.add(anthropicTool);
                }
            }
            request.put("tools", anthropicTools);
            // tool_choice 在部分兼容层不被支持，交由服务端默认（auto）
            request.remove("tool_choice");
        }
    }

    /**
     * 把 OpenAI 风格图片块转换为 Anthropic 图片块（就地修改消息）。
     *
     * <ul>
     *   <li>{@code {"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}}
     *       → {@code {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAA"}}}</li>
     *   <li>{@code {"type":"image_url","image_url":{"url":"https://..."}}}
     *       → {@code {"type":"image","source":{"type":"url","url":"https://..."}}}</li>
     * </ul>
     * 已符合 Anthropic 规范的 {@code image} 块与 Files API 的 {@code file} 块保持原样。
     *
     * <p>包级可见：便于单元测试覆盖。</p>
     */
    @SuppressWarnings("unchecked")
    static void convertImageBlocks(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (!(content instanceof List<?> parts)) {
                continue;
            }
            List<Object> converted = new ArrayList<>(parts.size());
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> block) || !"image_url".equals(block.get("type"))) {
                    converted.add(part);
                    continue;
                }
                Object imageUrl = block.get("image_url");
                String url = null;
                if (imageUrl instanceof Map<?, ?> iu && iu.get("url") != null) {
                    url = String.valueOf(iu.get("url"));
                } else if (imageUrl instanceof String s) {
                    url = s;
                }
                if (url == null || url.isBlank()) {
                    converted.add(part);
                    continue;
                }
                Map<String, Object> source = new LinkedHashMap<>();
                if (url.startsWith("data:")) {
                    // data:image/png;base64,AAAA
                    int comma = url.indexOf(',');
                    String meta = comma > 5 ? url.substring(5, comma) : "";
                    String data = comma > 0 ? url.substring(comma + 1) : "";
                    String mediaType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
                    if (mediaType.isBlank()) {
                        mediaType = "image/jpeg";
                    }
                    source.put("type", "base64");
                    source.put("media_type", mediaType);
                    source.put("data", data);
                } else {
                    source.put("type", "url");
                    source.put("url", url);
                }
                Map<String, Object> imageBlock = new LinkedHashMap<>();
                imageBlock.put("type", "image");
                imageBlock.put("source", source);
                converted.add(imageBlock);
            }
            msg.put("content", converted);
        }
    }

    // ==================== 响应解析（Anthropic 特有格式） ====================

    @Override
    public String extractContentFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            String type = node.path("type").asText("");
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                if ("text_delta".equals(delta.path("type").asText(""))) {
                    String text = delta.path("text").asText("");
                    return text.isEmpty() ? null : text;
                }
            }
            if ("content_block_start".equals(type)) {
                JsonNode block = node.path("content_block");
                if ("text".equals(block.path("type").asText(""))) {
                    String text = block.path("text").asText("");
                    return text.isEmpty() ? null : text;
                }
            }
            if ("ping".equals(type)) {
                return null;
            }
        } catch (Exception e) {
            log.trace("Anthropic SSE 块解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String extractReasoningFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            if ("content_block_delta".equals(node.path("type").asText(""))) {
                JsonNode delta = node.path("delta");
                if ("thinking_delta".equals(delta.path("type").asText(""))) {
                    return delta.path("thinking").asText(null);
                }
            }
        } catch (Exception e) {
            log.trace("Anthropic reasoning 解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String extractContentFromBlockingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText(""))) {
                        String text = block.path("text").asText("");
                        if (!text.isEmpty()) {
                            sb.append(text);
                        }
                    }
                }
                String result = sb.toString();
                return result.isEmpty() ? null : result.trim();
            }
        } catch (Exception e) {
            log.warn("Anthropic 阻塞响应解析失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 流式 / 阻塞请求（Anthropic 特有 Headers） ====================

    @Override
    public Flux<String> streamChat(Map<String, Object> requestBody) {
        // ★ 请求出口统一净化（见 ToolCallSequenceSanitizer）：安全副本，不改调用方对象
        Map<String, Object> safeBody = ToolCallSequenceSanitizer.sanitizeRequestBodyCopy(requestBody);
        return webClient.post()
                .uri(chatEndpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("anthropic-version", API_VERSION)
                .bodyValue(safeBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(DEFAULT_STREAM_TIMEOUT)
                .doOnError(org.springframework.web.reactive.function.client.WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("Anthropic 流式调用失败 [{}] status={}: {}",
                            getProviderCode(), e.getStatusCode().value(),
                            body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
                });
    }

    @Override
    public String blockingChat(Map<String, Object> requestBody, Duration timeout) {
        // ★ 非流式出口同样净化（幂等）
        Map<String, Object> safeBody = ToolCallSequenceSanitizer.sanitizeRequestBodyCopy(requestBody);
        try {
            Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_BLOCKING_TIMEOUT;
            return webClient.post()
                    .uri(chatEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("anthropic-version", API_VERSION)
                    .bodyValue(safeBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(effectiveTimeout);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("Anthropic 阻塞调用失败 [{}] status={}: {}",
                    getProviderCode(), e.getStatusCode().value(),
                    body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
            throw new RuntimeException("Anthropic API调用失败 [" + getProviderCode() + "]: " + e.getStatusCode() + " - "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body), e);
        } catch (Exception e) {
            log.error("Anthropic 阻塞调用异常 [{}]: {}", getProviderCode(), e.getMessage());
            throw new RuntimeException("Anthropic API调用失败 [" + getProviderCode() + "]: " + e.getMessage(), e);
        }
    }
}
