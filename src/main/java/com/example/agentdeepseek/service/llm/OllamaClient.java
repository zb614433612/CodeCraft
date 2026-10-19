package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * Ollama 本地模型的 LLMClient 实现
 *
 * <h3>适用范围（选型指引）</h3>
 * <ul>
 *   <li><b>适用</b>：本机 / 内网 Ollama 服务（{@code /api/chat}），运行 llama、qwen、deepseek-r1 等
 *       开源权重模型，离线或数据不出网的场景</li>
 *   <li><b>不适用</b>：任何云端 OpenAI 兼容 API；Ollama Cloud / vLLM / LM Studio 的
 *       {@code /v1/chat/completions} 端点请用 {@code openai} 公共契约</li>
 *   <li><b>专属能力</b>：原生 {@code /api/chat} 协议；{@code options} 内合并采样参数；
 *       {@code think=true}（0.6+ 推理模型）；保留 {@code tools}（0.4+ 支持函数调用）；
 *       <b>多模态</b>：OpenAI 风格 {@code content} 数组自动转换为 Ollama 的
 *       {@code messages[].images[]}（纯 base64，见 {@link #convertImageBlocksToImages}）</li>
 * </ul>
 *
 * <h3>与 OpenAI/DeepSeek 的关键差异</h3>
 * <table>
 *   <tr><th>特性</th><th>OpenAI/DeepSeek</th><th>Ollama</th></tr>
 *   <tr><td>端点</td><td>/v1/chat/completions</td><td>/api/chat</td></tr>
 *   <tr><td>认证</td><td>Authorization: Bearer xxx</td><td>无需认证（本地服务）</td></tr>
 *   <tr><td>temperature</td><td>顶层字段</td><td>放在 options 子对象中</td></tr>
 *   <tr><td>thinking</td><td>支持</td><td>顶层 think=true/false（0.6+ 推理模型）</td></tr>
 *   <tr><td>tools</td><td>支持 function calling</td><td>0.4+ 已支持；旧实现会剔除 tools，现已保留</td></tr>
 *   <tr><td>SSE 格式</td><td>data: {"choices":[...]}</td><td>每行一个完整 JSON，含 message.content</td></tr>
 *   <tr><td>响应 content</td><td>choices[0].message.content</td><td>message.content (string)</td></tr>
 *   <tr><td>图片</td><td>content 数组 + {"type":"image_url","image_url":{"url":"data:..."}}</td>
 *       <td>message.images[]（纯 base64，<b>不带</b> data: 前缀）+ content 为纯文本</td></tr>
 * </table>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>本地开发环境无需联网</li>
 *   <li>运行 llama3、qwen、mistral、gemma 等开源模型</li>
 *   <li>代码审查/分析等无需高级推理的任务</li>
 * </ul>
 */
@Slf4j
public class OllamaClient extends AbstractLLMClient {

    private static final String CHAT_ENDPOINT = "/api/chat";

    public OllamaClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    @Override
    public String getChatEndpoint() {
        return CHAT_ENDPOINT;
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        // Ollama 0.6+ 通过顶层 think 开关控制推理模型（qwen3 / deepseek-r1 等）是否输出思考过程。
        // 仅在需要思考时显式下发 true：对不支持 think 的模型不传该字段，避免服务端报错。
        Map<String, Object> params = new HashMap<>();
        if (thinkingMode != null && !thinkingMode.isBlank() && !"non-thinking".equals(thinkingMode)) {
            params.put("think", true);
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
        // ===== 0. 多模态：OpenAI 风格 content 数组 → Ollama 的 images[]（base64） =====
        // 内部链路（FileVisionService）统一产出 image_url 块，而 Ollama 只认 message.images[]，
        // 不转换就发出去会被服务端忽略（本地视觉模型收不到图，且无任何报错），故必须在此适配。
        ImageConversion conversion = convertImageBlocksToImages(messages);
        if (conversion.skipped() > 0) {
            log.warn("Ollama 图片转换：{} 张已转为 images，{} 张已跳过（Ollama REST 仅接受 base64 或本地文件，"
                    + "远程 URL / Files API 句柄不可用）", conversion.converted(), conversion.skipped());
        } else if (conversion.converted() > 0) {
            log.info("Ollama 图片转换：{} 张已转为 images（base64）", conversion.converted());
        }

        // ===== 1. Ollama 的 temperature 放在 options 子对象中（合并而非覆盖，保留其他采样参数） =====
        Map<String, Object> options = new HashMap<>();
        Object existingOptions = request.get("options");
        if (existingOptions instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    options.put(key, entry.getValue());
                }
            }
        }
        if (request.containsKey("temperature")) {
            Object temp = request.remove("temperature");
            if (temp instanceof Number num) {
                options.put("temperature", num.doubleValue());
            }
        }
        if (!options.isEmpty()) {
            request.put("options", options);
        }

        // ===== 2. tools：现代 Ollama（0.4+）已支持 function calling，保留工具定义 =====
        // 旧实现无条件 remove("tools")，导致本地模型彻底失去工具能力；不支持的模型会忽略该字段。
        if (tools == null || tools.isEmpty()) {
            request.remove("tools");
        }
        // tool_choice 不在 Ollama 协议内，仍需剔除
        request.remove("tool_choice");

        // ===== 3. 移除 Anthropic/OpenAI 特有字段 =====
        request.remove("reasoning_effort");
        request.remove("max_tokens");
        request.remove("max_completion_tokens");
    }

    // ==================== 多模态协议转换 ====================

    /**
     * 把 OpenAI 风格的 {@code content} 数组转换为 Ollama 的 {@code images[]} 形态（就地修改消息）。
     *
     * <h3>为什么需要</h3>
     * <p>内部视觉链路（{@code FileVisionService}）对所有 Provider 统一产出 OpenAI 兼容块：
     * {@code {"type":"text","text":"..."}} 与
     * {@code {"type":"image_url","image_url":{"url":"data:image/png;base64,AAA","detail":"auto"}}}。
     * 而 Ollama 的 {@code /api/chat} 协议里图片是消息级的独立数组：
     * {@code {"role":"user","content":"纯文本","images":["AAA"]}}（官方文档要求 REST 传 base64，
     * 不带 {@code data:} 前缀）。不做转换时 {@code content} 数组会被服务端忽略，
     * 视觉模型收不到图却不报错——属于静默失败，故必须在此适配。</p>
     *
     * <h3>转换规则</h3>
     * <ul>
     *   <li>{@code text} 块 → 按出现顺序用 {@code \n} 合并为 content 字符串（无文本时保留空串）</li>
     *   <li>{@code image_url} 块：{@code data:<mime>;base64,<payload>} → 取 {@code <payload>}；
     *       裸 base64 原样保留；{@code http(s)} 远程 URL → 跳过（Ollama REST 不接受 URL）</li>
     *   <li>{@code file} 块（Files API 的 {@code file_id}）→ 跳过（Ollama 无 Files API，
     *       正常情况下 {@code FileVisionService} 已按 base64 降级，此处仅兜底）</li>
     *   <li>无图片时移除 {@code images} 字段，纯文本消息保持原样</li>
     * </ul>
     *
     * <p><b>幂等</b>：content 已是字符串的消息不受影响，重复调用不会二次转换。</p>
     * <p>包级可见：便于单元测试覆盖。</p>
     *
     * @param messages 消息列表（就地修改）
     * @return 转换统计（成功张数 / 跳过张数）
     */
    static ImageConversion convertImageBlocksToImages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ImageConversion(0, 0);
        }
        int converted = 0;
        int skipped = 0;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (!(content instanceof List<?> parts)) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            List<String> images = new ArrayList<>();
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> block)) {
                    continue;
                }
                Object type = block.get("type");
                if ("text".equals(type)) {
                    Object textValue = block.get("text");
                    if (textValue == null) {
                        continue;
                    }
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(textValue);
                } else if ("image_url".equals(type)) {
                    String base64 = extractBase64Payload(block.get("image_url"));
                    if (base64 == null) {
                        skipped++;
                    } else {
                        images.add(base64);
                        converted++;
                    }
                } else if ("file".equals(type)) {
                    skipped++;
                }
            }
            msg.put("content", text.toString());
            if (images.isEmpty()) {
                msg.remove("images");
            } else {
                msg.put("images", images);
            }
        }
        return new ImageConversion(converted, skipped);
    }

    /**
     * 从 {@code image_url} 取值中提取纯 base64。
     *
     * <p>支持两种入参形态：{@code Map}（{@code {"url":"..."}}）与裸字符串。
     * 返回 {@code null} 表示不可用（远程 URL / 空值 / 非 base64 内联的 data URL）。</p>
     */
    static String extractBase64Payload(Object imageUrl) {
        String url;
        if (imageUrl instanceof Map<?, ?> iu) {
            Object raw = iu.get("url");
            url = raw == null ? null : String.valueOf(raw);
        } else if (imageUrl instanceof CharSequence cs) {
            url = cs.toString();
        } else {
            url = null;
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = trimmed.indexOf(',');
            if (comma < 0) {
                return null;
            }
            String meta = trimmed.substring(5, comma).toLowerCase(Locale.ROOT);
            if (!meta.contains(";base64")) {
                // data:image/svg+xml,<svg .../> 之类非 base64 内联，Ollama 无法消费
                return null;
            }
            String payload = trimmed.substring(comma + 1).replaceAll("\\s", "");
            return payload.isEmpty() ? null : payload;
        }
        if (trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            // Ollama REST 只接受 base64 或本地文件路径，远程 URL 需调用方先行下载
            return null;
        }
        return trimmed.replaceAll("\\s", "");
    }

    /**
     * 图片协议转换统计（不承载图片数据本身，仅用于日志与测试断言）
     */
    record ImageConversion(int converted, int skipped) {
    }

    // ==================== 响应解析（Ollama 特有格式） ====================

    @Override
    public String extractContentFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            // Ollama 流式: {"model":"...","message":{"role":"assistant","content":"..."},"done":false}
            JsonNode message = node.path("message");
            String content = message.path("content").asText("");
            return content.isEmpty() ? null : content;
        } catch (Exception e) {
            log.trace("Ollama SSE 块解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String extractReasoningFromStreamChunk(String sseChunk) {
        // Ollama 没有独立的 reasoning_content 字段
        return null;
    }

    @Override
    public String extractContentFromBlockingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return null;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // Ollama 阻塞: {"model":"...","message":{"role":"assistant","content":"..."},"done":true}
            JsonNode message = root.path("message");
            String content = message.path("content").asText("");
            return content.isEmpty() ? null : content.trim();
        } catch (Exception e) {
            log.warn("Ollama 阻塞响应解析失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 流式请求（Ollama 无认证头，格式相同） ====================

    @Override
    public Flux<String> streamChat(Map<String, Object> requestBody) {
        // ★ 请求出口统一净化（见 ToolCallSequenceSanitizer）：安全副本
        Map<String, Object> safeBody = ToolCallSequenceSanitizer.sanitizeRequestBodyCopy(requestBody);
        return webClient.post()
                .uri(CHAT_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(safeBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(DEFAULT_STREAM_TIMEOUT)
                .doOnError(org.springframework.web.reactive.function.client.WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("Ollama 流式调用失败 status={}: {}",
                            e.getStatusCode().value(),
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
                    .uri(CHAT_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(safeBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(effectiveTimeout);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("Ollama 阻塞调用失败 status={}: {}",
                    e.getStatusCode().value(),
                    body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
            throw new RuntimeException("Ollama API调用失败: " + e.getStatusCode() + " - "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body), e);
        } catch (Exception e) {
            log.error("Ollama 阻塞调用异常: {}", e.getMessage());
            throw new RuntimeException("Ollama API调用失败: " + e.getMessage(), e);
        }
    }
}
