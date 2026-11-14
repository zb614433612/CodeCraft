package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.util.ReasoningPassback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLMClient 抽象基类 —— <b>适配器「钩子总表」</b>
 *
 * <p>基类已实现通用链路：请求体骨架、流式 / 阻塞发送、超时、SSE 拆帧、
 * 错误体提取、认证头注入。子类只需实现 <b>1 个 abstract 方法</b> + 按需覆写 <b>8 个钩子</b>。</p>
 *
 * <p><b>关键认知</b>：这些钩子的默认值几乎全是「关闭 / 通用」，
 * <b>漏覆写不会编译报错、不会抛异常，只会静默缺能力</b>（成本看板空、发图 400、第二轮 400）。
 * 新增适配器时请对着下面第 3 节的矩阵，找最像的现有适配器照抄。</p>
 *
 * <p>完整适配流程（含散落在其它类里的分支）见 {@link LLMClient} 类头的 A/B/C/D 四组清单。</p>
 *
 * <h3>1. 必须实现（abstract）</h3>
 * <ul>
 *   <li>{@link #buildThinkingParams(String)} —— 把归一化后的 5 档思考档位
 *       （none/low/medium/high/max 或空串 auto）翻译成该厂商的字段。
 *       <b>不要</b>在这里比较原始字符串（{@code thinking_low} 等），归一化已在
 *       {@link #normalizeThinkingMode} 完成。</li>
 * </ul>
 *
 * <h3>2. 可覆写钩子（默认值 → 何时覆写 → 漏了怎样）</h3>
 * <table border="1">
 *   <caption>钩子总表</caption>
 *   <tr><th>钩子</th><th>默认值</th><th>何时覆写</th><th>漏掉的症状</th></tr>
 *   <tr>
 *     <td>{@link #getChatEndpoint()}</td>
 *     <td>{@code /v1/chat/completions}</td>
 *     <td>端点不是 OpenAI 风格（如 Anthropic {@code /v1/messages}、Ollama {@code /api/chat}），
 *         或需要从 baseUrl 推导（含 {@code /anthropic} 前缀等）</td>
 *     <td>404</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #getUsageProtocol()}</td>
 *     <td>{@code openai}</td>
 *     <td>用量不在 OpenAI 的 {@code usage} 对象里：
 *         Anthropic 拆 {@code message_start}/{@code message_delta}、Ollama 在 {@code done=true} 行</td>
 *     <td>成本看板无数据（解析器按错误协议取不到字段）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #requiresStreamUsageOption()}</td>
 *     <td>{@code false}（不下发 {@code stream_options}）</td>
 *     <td>厂商<b>默认不返回</b>流式 usage、必须显式 {@code include_usage}：
 *         OpenAI 官方 / 千问 / 方舟 / MiniMax / MiMo</td>
 *     <td>该 Provider 的成本永远为空</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #requiresReasoningPadding()}</td>
 *     <td>{@code false}（不注入 {@code reasoning_content}）</td>
 *     <td>厂商使用 {@code reasoning_content} 字段体系且<b>强校验</b>（DeepSeek 系）</td>
 *     <td>thinking 模型 400：{@code reasoning_content must be passed back}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #getRequestHeaders()}</td>
 *     <td>空 Map</td>
 *     <td>协议有<b>必需</b>头（Anthropic 的 {@code anthropic-version}）</td>
 *     <td>端点对了但 400 / 401</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #customizeRequest}</td>
 *     <td>空实现</td>
 *     <td>需要塞厂商专有字段（{@code enable_search}、{@code thinking.budget_tokens}、
 *         {@code max_completion_tokens} 等）</td>
 *     <td>厂商能力用不上（搜索 / 深思考 / 长输出）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #buildRequestBody}</td>
 *     <td>通用骨架（model/messages/stream/temperature/tools/thinking）</td>
 *     <td>协议结构与 OpenAI 差异过大（Anthropic：system 顶层化、工具 schema 改写），
 *         需整体重写</td>
 *     <td>请求体格式不对，直接 400</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #stripImageDetail}</td>
 *     <td>不处理</td>
 *     <td>厂商不认 OpenAI 的 {@code image_url.detail} 字段（Qwen / MiniMax / MiMo 会剔除）</td>
 *     <td>发图 400（未知字段）</td>
 *   </tr>
 * </table>
 *
 * <h3>3. 现有适配器适配矩阵（新增时找最像的抄）</h3>
 * <table border="1">
 *   <caption>钩子覆写现状（✓ = 已覆写 / 已开启；— = 用默认值）</caption>
 *   <tr>
 *     <th>适配器</th><th>requestTemplate</th><th>usage<br>协议</th><th>stream<br>_options</th>
 *     <th>reasoning<br>占位</th><th>自定义<br>端点</th><th>专属头</th><th>备注</th>
 *   </tr>
 *   <tr>
 *     <td>{@code DeepSeekClient}</td><td>deepseek</td><td>openai</td><td>—</td>
 *     <td>✓</td><td>—</td><td>—</td>
 *     <td>usage 原生返回，故不开 stream_options</td>
 *   </tr>
 *   <tr>
 *     <td>{@code GptClient}</td><td>gpt / gpt-official / chatgpt</td><td>openai</td><td>✓</td>
 *     <td>—</td><td>✓</td><td>—</td>
 *     <td>官方专精：max_completion_tokens + reasoning_effort 全档</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OpenAIClient}</td><td>openai / custom</td><td>openai</td><td>—</td>
 *     <td>—</td><td>✓</td><td>—</td>
 *     <td><b>公共契约</b>：故意不下发厂商专有参数，避免第三方网关 400</td>
 *   </tr>
 *   <tr>
 *     <td>{@code AnthropicClient}</td><td>anthropic</td><td>anthropic</td><td>—</td>
 *     <td>—</td><td>✓</td><td>✓</td>
 *     <td>Messages 协议：system 顶层化 + 图片块转换 + maxTokens 可配</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OllamaClient}</td><td>ollama</td><td>ollama</td><td>—</td>
 *     <td>—</td><td>✓</td><td>—</td>
 *     <td>NDJSON，图片转 {@code messages[].images[]}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code QwenClient}</td><td>qwen / dashscope / tongyi</td><td>openai</td><td>✓</td>
 *     <td>✓</td><td>✓</td><td>—</td>
 *     <td>enable_thinking / enable_search / 剔除 image detail</td>
 *   </tr>
 *   <tr>
 *     <td>{@code VolcengineArkClient}</td><td>volcengine / ark / doubao</td><td>openai</td><td>✓</td>
 *     <td>✓</td><td>✓</td><td>—</td>
 *     <td>方舟 thinking.type + reasoning_effort 白名单 + imageDetail</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MiniMaxClient}</td><td>minimax*</td><td>openai</td><td>✓</td>
 *     <td>✓</td><td>—</td><td>—</td>
 *     <td>历史坑：{@code thinking_low} 曾被 default 分支关掉思考</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MiMoClient}</td><td>mimo / xiaomi-mimo</td><td>openai</td><td>✓</td>
 *     <td>✓</td><td>—</td><td>—</td>
 *     <td>多模态；余额需浏览器 Cookie</td>
 *   </tr>
 * </table>
 *
 * <h3>4. 除本类之外还要改的地方</h3>
 * <p>适配器只负责「请求与响应的协议翻译」；以下能力由<b>别的类</b>独立判定，
 * 新增 Provider 时必须一并检查（详见 {@link LLMClient} 的 B / C 组清单）：</p>
 * <ul>
 *   <li>图片识别能力（模型级）→ {@code ModelCapabilityRules} + {@code application.yml}</li>
 *   <li>文件上传 → {@code FilesApiClientManager}</li>
 *   <li>联网搜索 → {@code SearchTool}</li>
 *   <li>余额查询 → {@code ProviderBalanceService}</li>
 *   <li>注册 / 选型 → {@code LLMClientManager}</li>
 * </ul>
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

        // ===== 流式用量统计：按 Provider 能力下发 stream_options.include_usage =====
        // 为什么必须「按 Provider」而不是统一加：这是 OpenAI 的扩展字段，
        //   - 官方 / 千问 / 方舟 / MiniMax / MiMo 明确支持，不下发就**永远拿不到 usage**（成本看板全空）；
        //   - 第三方中转站 / 自建网关可能严格校验未知字段而 400（OpenAIClient 因此保持不下发）；
        //   - Anthropic / Ollama 压根不是这套协议，加了是脏字段。
        // 所以默认关闭，由明确支持的适配器覆盖 requiresStreamUsageOption() 打开。
        if (stream && requiresStreamUsageOption()) {
            request.putIfAbsent("stream_options", buildIncludeUsageOption());
        }

        // ★ 请求出口兜底：assistant 消息的 reasoning_content 字段绝不能缺失
        //   DeepSeek 对 thinking 模型强校验该字段（缺失即 400），且**关闭思考时同样校验**
        //   （关闭思考时模型天然没有思考内容，字段更容易缺失）。这里按 Provider 能力补齐空串占位。
        //   ⚠️ 主链路与工具循环直接用 webClient.bodyValue 发请求、不走本方法，
        //      那两处必须显式调用 LLMClient#ensureReasoningContent（见 DeepSeekServiceImpl）。
        if (requiresReasoningPadding()) {
            List<Map<String, Object>> padded = padReasoningContent(messages);
            if (padded != messages) {
                request.put("messages", padded);
                log.debug("[{}] 请求出口补齐 reasoning_content 字段（空串占位，防 400）", getProviderCode());
            }
        }

        return request;
    }

    /**
     * 是否需要下发 {@code stream_options.include_usage} 才能拿到流式 usage。
     *
     * <p>按官方文档，以下 Provider 在流式请求中<b>默认不返回</b> {@code usage}，
     * 必须显式开启（返回位置是 {@code data: [DONE]} 之前那个 {@code choices} 为空的收尾帧）：</p>
     * <ul>
     *   <li>OpenAI 官方（{@code gpt} 模板）</li>
     *   <li>阿里云百炼 / 千问（{@code qwen} 模板）——官方文档明确「默认不返回用量」</li>
     *   <li>火山方舟（{@code volcengine} 模板）</li>
     *   <li>MiniMax（{@code minimax} 模板）</li>
     *   <li>小米 MiMo（{@code mimo} 模板）</li>
     * </ul>
     *
     * <p>不覆盖（保持 {@code false}）的：</p>
     * <ul>
     *   <li>{@code DeepSeekClient} —— 官方行为是「无论是否设置，
     *       {@code [DONE]} 之前的最后一个块都会给出完整用量」，加了只是徒增每帧字节</li>
     *   <li>{@code OpenAIClient}（公共契约）—— 面向任意第三方兼容服务，
     *       未知字段在严格校验的网关上会 400；宁可没有成本数据，也不能把请求打挂</li>
     *   <li>{@code AnthropicClient} / {@code OllamaClient} —— 不是 OpenAI 协议，另有协议承载</li>
     * </ul>
     */
    protected boolean requiresStreamUsageOption() {
        return false;
    }

    /**
     * 构造 {@code stream_options} 参数（独立方法便于单元测试与子类复用）
     */
    protected static Map<String, Object> buildIncludeUsageOption() {
        Map<String, Object> streamOptions = new HashMap<>();
        streamOptions.put("include_usage", true);
        return streamOptions;
    }

    /**
     * Provider 专属请求头，默认无。
     * <p>子类若所在协议有<b>必需</b>头（如 Anthropic 的 {@code anthropic-version}）应覆盖。</p>
     */
    @Override
    public Map<String, String> getRequestHeaders() {
        return Map.of();
    }

    /**
     * 请求出口：是否需要为每条 assistant 消息补齐 {@code reasoning_content}（缺失时补空串）。
     * <p>
     * DeepSeek 对 thinking 模型强校验该字段，缺失直接 400；<b>即使请求显式关闭了思考也一样</b>
     * —— 因为模型本身仍是思考模型，服务端仍按该协议校验。使用 {@code reasoning_content}
     * 字段体系的 Provider 应覆盖本方法返回 {@code true}；默认 {@code false} 表示不向不使用该字段的
     * Provider（OpenAI 官方 / Anthropic / Ollama）注入未知字段，避免被严格校验拒绝。
     * </p>
     */
    protected boolean requiresReasoningPadding() {
        return false;
    }

    /**
     * 为 assistant 消息补齐 {@code reasoning_content} 字段（<b>仅在缺失时补空串，绝不覆盖已有值</b>）。
     * <p>
     * 采用 copy-on-write：只复制需要补齐的消息 Map，不修改调用方持有的列表与对象，
     * 与 {@link ToolCallSequenceSanitizer} 的约定保持一致。
     * </p>
     *
     * @param messages 原始消息列表（可为 null）
     * @return 补齐后的新列表；无需补齐时原样返回入参引用（调用方可用引用比较判断是否发生拷贝）
     */
    protected static List<Map<String, Object>> padReasoningContent(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        boolean needPad = false;
        for (Map<String, Object> msg : messages) {
            if (ReasoningPassback.needsPadding(msg)) {
                needPad = true;
                break;
            }
        }
        if (!needPad) {
            return messages;
        }

        List<Map<String, Object>> copy = new ArrayList<>(messages.size());
        int padded = 0;
        for (Map<String, Object> msg : messages) {
            if (ReasoningPassback.needsPadding(msg)) {
                Map<String, Object> item = new HashMap<>(msg);
                item.put(ReasoningPassback.FIELD, "");
                copy.add(item);
                padded++;
            } else {
                copy.add(msg);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("补齐 reasoning_content 空串占位 {} 条（共 {} 条消息，防 400）", padded, messages.size());
        }
        return copy;
    }

    /**
     * 请求发送前的最后一道兜底（{@link LLMClient#ensureReasoningContent} 实现）。
     * <p>
     * <b>为什么调用方必须显式调用</b>：{@code DeepSeekServiceImpl} 的主链路与工具循环续跑
     * 都是 {@code webClient.post().bodyValue(requestBody)} 直接发送，<b>不会经过</b>
     * {@link #buildRequestBody} / {@link #streamChat}，只挂在那两处等于没生效。
     * </p>
     *
     * @param requestBody 原始请求体
     * @return 补齐后的副本；无需补齐时返回入参本身
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> ensureReasoningContent(Map<String, Object> requestBody) {
        if (requestBody == null || !requiresReasoningPadding()) {
            return requestBody;
        }
        Object rawMessages = requestBody.get("messages");
        if (!(rawMessages instanceof List<?> list)) {
            return requestBody;
        }
        List<Map<String, Object>> messages = (List<Map<String, Object>>) list;
        List<Map<String, Object>> padded = padReasoningContent(messages);
        if (padded == messages) {
            return requestBody;
        }
        Map<String, Object> copy = new HashMap<>(requestBody);
        copy.put("messages", padded);
        // WARN：补齐意味着「某条调用路径漏写了字段」，本身值得关注。
        // 正常情况下各调用点应已通过 ReasoningPassback 写入（真实值或空串）。
        log.warn("[{}] 发送前补齐 reasoning_content 字段（空串占位，防 400）。"
                        + "若频繁出现说明有调用点漏写，请检查工具循环 / 子Agent 的消息构建",
                getProviderCode());
        return copy;
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
        // ★ reasoning_content 兜底（子类若走本方法发请求，这里也一并保证）
        safeBody = ensureReasoningContent(safeBody);
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
        // ★ reasoning_content 兜底（子 Agent 阻塞调用同样可能缺字段）
        safeBody = ensureReasoningContent(safeBody);
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
