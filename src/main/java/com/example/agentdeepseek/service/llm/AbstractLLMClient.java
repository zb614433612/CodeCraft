package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLMClient 抽象基类
 * <p>
 * 提供流式/非流式调用的通用实现，子类只需关注：
 * </p>
 * <ol>
 *   <li>{@link #buildRequestBody} — 构建 Provider 特有的请求体</li>
 *   <li>{@link #getChatEndpoint()} — API 端点路径</li>
 *   <li>{@link #extractContentFromStreamChunk} / {@link #extractReasoningFromStreamChunk} — SSE 解析</li>
 *   <li>{@link #extractContentFromBlockingResponse} — 阻塞响应解析</li>
 * </ol>
 */
@Slf4j
public abstract class AbstractLLMClient implements LLMClient {

    protected final WebClient webClient;
    protected final ObjectMapper objectMapper;
    protected final String providerCode;

    /** SSE data 行前缀 */
    protected static final String DATA_PREFIX = "data: ";

    /** 默认流式请求超时 */
    protected static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(300);

    /** 默认阻塞请求超时 */
    protected static final Duration DEFAULT_BLOCKING_TIMEOUT = Duration.ofSeconds(120);

    protected AbstractLLMClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.providerCode = providerCode;
    }

    // ==================== 思考档位归一化（跨 Provider 统一口径） ====================

    /** 规范档位：关闭思考 */
    public static final String EFFORT_NONE = "none";
    /** 规范档位：浅思考 */
    public static final String EFFORT_LOW = "low";
    /** 规范档位：中等（多数 Provider 的"默认思考"档） */
    public static final String EFFORT_MEDIUM = "medium";
    /** 规范档位：深度思考 */
    public static final String EFFORT_HIGH = "high";
    /** 规范档位：最深（如 DeepSeek max / 部分 Provider 的 xhigh） */
    public static final String EFFORT_MAX = "max";
    /** 规范档位：未指定（auto，是否思考与档位完全交给模型默认策略） */
    public static final String EFFORT_AUTO = "";

    /**
     * 把各 Provider / 配置里五花八门的思考模式字符串归一化成 5 个规范档位
     * <p>
     * <b>为什么需要它</b>：Agent 配置与请求参数里可能出现
     * {@code non-thinking / thinking_low / thinking / thinking_max / low / medium / high / xhigh / minimal / auto}
     * 等多种写法，而各家 Provider 支持的档位与语义并不同。若各适配器各自做字符串比较，
     * 每新增一个档位就会漏掉一批适配器（历史教训：新增 {@code thinking_low} 时 MiniMax 适配器
     * 因为 {@code switch} 的 {@code default} 分支直接把思考关掉了）。统一归一化后，
     * 适配器只需处理 5 个规范值 + auto。
     * </p>
     *
     * <table border="1">
     *   <caption>归一化映射</caption>
     *   <tr><th>输入（大小写不敏感）</th><th>规范档位</th></tr>
     *   <tr><td>non-thinking / none / disabled / off / false</td><td>{@link #EFFORT_NONE}</td></tr>
     *   <tr><td>thinking_low / low / minimal</td><td>{@link #EFFORT_LOW}</td></tr>
     *   <tr><td>thinking / medium</td><td>{@link #EFFORT_MEDIUM}</td></tr>
     *   <tr><td>thinking_high / high</td><td>{@link #EFFORT_HIGH}</td></tr>
     *   <tr><td>thinking_max / max / xhigh</td><td>{@link #EFFORT_MAX}</td></tr>
     *   <tr><td>空 / auto / default / 无法识别</td><td>{@link #EFFORT_AUTO}</td></tr>
     * </table>
     *
     * <p>注意：{@code thinking} 归一为 {@code medium}（"默认思考档"），各适配器再映射到自己的默认值
     * ——例如 DeepSeek 侧会映射为 {@code reasoning_effort=high}，OpenAI 侧映射为 {@code medium}。</p>
     *
     * @param thinkingMode Agent 配置或请求传入的思考模式（可为 null / 空 / 混合大小写）
     * @return 规范档位（{@link #EFFORT_NONE}~{@link #EFFORT_MAX} 或 {@link #EFFORT_AUTO}）
     */
    public static String normalizeThinkingMode(String thinkingMode) {
        if (thinkingMode == null) {
            return EFFORT_AUTO;
        }
        String mode = thinkingMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.isEmpty()) {
            return EFFORT_AUTO;
        }
        return switch (mode) {
            // ===== 关闭 =====
            case "non-thinking", "nonthinking", "none", "disabled", "off", "false" -> EFFORT_NONE;
            // ===== 浅思考 =====
            case "thinking_low", "thinking-low", "low", "minimal" -> EFFORT_LOW;
            // ===== 默认思考（多数 Provider 的中等档）=====
            case "thinking", "thinking_default", "thinking-default", "medium" -> EFFORT_MEDIUM;
            // ===== 深度思考 =====
            case "thinking_high", "thinking-high", "high" -> EFFORT_HIGH;
            // ===== 最深 =====
            case "thinking_max", "thinking-max", "max", "xhigh" -> EFFORT_MAX;
            // ===== auto / 未知 → 交给模型默认策略 =====
            default -> EFFORT_AUTO;
        };
    }

    /**
     * 规范档位是否为"关闭思考"
     */
    public static boolean isThinkingDisabled(String canonicalEffort) {
        return EFFORT_NONE.equals(canonicalEffort);
    }

    /**
     * 规范档位是否为"明确开启思考"（不含 auto）
     */
    public static boolean isThinkingExplicitlyEnabled(String canonicalEffort) {
        return canonicalEffort != null && !canonicalEffort.isEmpty() && !EFFORT_NONE.equals(canonicalEffort);
    }

    // ==================== 抽象方法：子类实现 ====================

    /**
     * 获取 Provider 编码
     */
    @Override
    public String getProviderCode() {
        return this.providerCode;
    }

    /**
     * 构建 Provider 特有的扩展参数
     * <p>
     * 如 DeepSeek 的 thinking / reasoning_effort，
     * OpenAI 的 reasoning / max_tokens，Anthropic 的 thinking 等
     * </p>
     *
     * @param thinkingMode 思考模式
     * @return 扩展参数 Map（会被合并到请求体中）
     */
    protected abstract Map<String, Object> buildThinkingParams(String thinkingMode);

    // ==================== 通用实现 ====================

    @Override
    public WebClient getWebClient() {
        return webClient;
    }

    @Override
    public Map<String, Object> buildRequestBody(
            List<Map<String, Object>> messages,
            String model,
            Double temperature,
            String thinkingMode,
            boolean stream,
            List<Map<String, Object>> tools) {

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", stream);

        if (temperature != null) {
            request.put("temperature", temperature);
        }

        // 合并 Provider 特有参数
        Map<String, Object> thinkingParams = buildThinkingParams(thinkingMode);
        if (thinkingParams != null && !thinkingParams.isEmpty()) {
            request.putAll(thinkingParams);
        }

        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
            request.put("tool_choice", "auto");
        }

        // 子类可进一步定制
        customizeRequest(request, messages, model, temperature, thinkingMode, stream, tools);

        return request;
    }

    /**
     * 子类覆盖此方法做进一步定制（如添加 max_tokens、top_p 等）
     * 默认空实现
     */
    protected void customizeRequest(Map<String, Object> request,
                                     List<Map<String, Object>> messages,
                                     String model,
                                     Double temperature,
                                     String thinkingMode,
                                     boolean stream,
                                     List<Map<String, Object>> tools) {
        // 默认不做额外定制
    }

    /**
     * 移除多模态图片块中的 {@code detail} 字段。
     *
     * <p>部分 OpenAI 兼容平台（MiMo / MiniMax 等）并未声明 {@code image_url.detail}，
     * 下发未知字段存在被严格校验拒绝的风险；这些 Provider 的适配器在构造请求时调用本方法剔除。</p>
     *
     * @param messages 消息列表（就地修改）
     */
    @SuppressWarnings("unchecked")
    protected static void stripImageDetail(List<Map<String, Object>> messages) {
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
                    ((Map<String, Object>) iu).remove("detail");
                }
            }
        }
    }

    @Override
    public Flux<String> streamChat(Map<String, Object> requestBody) {
        // ★ 请求出口统一净化：会把「工具已下发但结果缺失」的脏序列修复成合法序列（幂等、纯内存）。
        //   这里是所有 Provider 流式请求的必经之路，任何历史脏数据都无法阻断对话。
        Map<String, Object> safeBody = ToolCallSequenceSanitizer.sanitizeRequestBodyCopy(requestBody);
        return webClient.post()
                .uri(getChatEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(safeBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(DEFAULT_STREAM_TIMEOUT)
                .doOnError(WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("LLM 流式调用失败 [{}] status={}: {}",
                            getProviderCode(), e.getStatusCode().value(),
                            body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
                });
    }

    @Override
    public String blockingChat(Map<String, Object> requestBody, Duration timeout) {
        // ★ 同流式：非流式请求出口同样净化（评委/子Agent/复盘等路径统一兜底）
        Map<String, Object> safeBody = ToolCallSequenceSanitizer.sanitizeRequestBodyCopy(requestBody);
        try {
            Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_BLOCKING_TIMEOUT;
            return webClient.post()
                    .uri(getChatEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(safeBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(effectiveTimeout);
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("LLM 阻塞调用失败 [{}] status={}: {}",
                    getProviderCode(), e.getStatusCode().value(),
                    body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
            throw new RuntimeException("LLM API调用失败 [" + getProviderCode() + "]: "
                    + e.getStatusCode() + " - "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body), e);
        } catch (Exception e) {
            log.error("LLM 阻塞调用异常 [{}]: {}", getProviderCode(), e.getMessage());
            throw new RuntimeException("LLM API调用失败 [" + getProviderCode() + "]: " + e.getMessage(), e);
        }
    }

    // ==================== 默认响应解析（子类可覆盖） ====================

    @Override
    public String extractContentFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty() || "[DONE]".equals(sseChunk.trim())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).path("delta");
                JsonNode content = delta.path("content");
                return content.isNull() ? null : content.asText();
            }
        } catch (Exception e) {
            log.trace("SSE 块解析失败: {}", sseChunk.length() > 100 ? sseChunk.substring(0, 100) : sseChunk);
        }
        return null;
    }

    @Override
    public String extractReasoningFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty() || "[DONE]".equals(sseChunk.trim())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).path("delta");
                // DeepSeek 的 reasoning_content
                JsonNode reasoning = delta.path("reasoning_content");
                return reasoning.isNull() ? null : reasoning.asText();
            }
        } catch (Exception e) {
            log.trace("SSE reasoning 解析失败: {}", e.getMessage());
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
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText("");
                return content.isEmpty() ? null : content.trim();
            }
        } catch (Exception e) {
            log.warn("阻塞响应解析失败: {}", e.getMessage());
        }
        return null;
    }
}
