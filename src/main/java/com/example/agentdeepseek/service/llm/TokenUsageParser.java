package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.config.pricing.HolidayCalendar;
import com.example.agentdeepseek.config.pricing.PeakPeriodPolicy;
import com.example.agentdeepseek.config.pricing.PeakPeriodProperties;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PeakPricingSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;

/**
 * Provider 用量（usage）解析与计费估算
 *
 * <p><b>为什么要分协议解析</b>：不是所有 Provider 都按 OpenAI 的 {@code usage} 对象给用量。
 * 靠「响应里有没有 usage 字段」猜不出协议差异，会对 Anthropic / Ollama 永远解析出 0；
 * 反过来对不返回用量的 Provider 强行编造数字，会把成本看板变成假账单。
 * 因此协议由 {@link LLMClient#getUsageProtocol()} 显式声明，这里按协议分派：</p>
 *
 * <table border="1">
 *   <caption>三种 usage 协议</caption>
 *   <tr><th>协议</th><th>承载位置</th><th>关键字段</th><th>能否估算成本</th></tr>
 *   <tr>
 *     <td>{@code openai}</td>
 *     <td>{@code data: [DONE]} 之前最后一个 chunk 的 {@code usage}</td>
 *     <td>prompt_tokens / completion_tokens / total_tokens /
 *         prompt_cache_hit_tokens / prompt_cache_miss_tokens /
 *         prompt_tokens_details.cached_tokens / completion_tokens_details.reasoning_tokens</td>
 *     <td>看是否配置了该模型单价</td>
 *   </tr>
 *   <tr>
 *     <td>{@code anthropic}</td>
 *     <td><b>两处</b>：{@code message_start.message.usage}（输入侧）+ {@code message_delta.usage}（输出侧）</td>
 *     <td>input_tokens / cache_read_input_tokens / cache_creation_input_tokens / output_tokens</td>
 *     <td>看是否配置了该模型单价</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ollama}</td>
 *     <td>NDJSON 的 {@code done=true} 收尾行</td>
 *     <td>prompt_eval_count / eval_count / prompt_eval_cached_count</td>
 *     <td><b>不估算</b>（本机运行，无 token 计费）</td>
 *   </tr>
 * </table>
 *
 * <p><b>成本口径</b>：只有「显式配置过单价的模型」才算出金额；未配置返回 {@code null}，
 * 由前端隐藏金额而不是显示一个错的价格（历史实现会回退到 DeepSeek 默认价，
 * 导致 GPT / Claude 按 DeepSeek 价计费，结论完全失真）。</p>
 */
@Slf4j
@Component
public class TokenUsageParser {

    /** 协议：OpenAI 兼容（大多数 Provider） */
    public static final String PROTOCOL_OPENAI = "openai";
    /** 协议：Anthropic Messages */
    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    /** 协议：Ollama 原生（NDJSON） */
    public static final String PROTOCOL_OLLAMA = "ollama";

    private final ObjectMapper objectMapper;
    private final TokenPricingConfig pricingConfig;

    /**
     * Provider 配置查询器（<b>刻意用 {@link ObjectProvider} 延迟获取</b>）。
     *
     * <p>为什么不在构造器直接注入 {@code LLMClientManager}：本类被 {@code DeepSeekServiceImpl}
     * 在流式回调里高频使用，而 {@code LLMClientManager} → {@code LLMWebClientManager} 链条较长；
     * 用 {@code ObjectProvider} 把解析推迟到真正需要时（且失败时能安全降级为
     * {@link ProviderProfile.PricingMode#TOKEN}），可避免任何潜在的启动期循环依赖。</p>
     */
    private final ObjectProvider<LLMClientManager> llmClientManagerProvider;

    /** 峰谷时段判定策略（仅当平台档案声明峰谷时才真正参与计价） */
    private final PeakPeriodPolicy peakPeriodPolicy;

    /**
     * Spring 注入入口（<b>生产链路用这个</b>）。
     */
    @Autowired
    public TokenUsageParser(ObjectMapper objectMapper,
                            TokenPricingConfig pricingConfig,
                            ObjectProvider<LLMClientManager> llmClientManagerProvider,
                            PeakPeriodPolicy peakPeriodPolicy) {
        this.objectMapper = objectMapper;
        this.pricingConfig = pricingConfig;
        this.llmClientManagerProvider = llmClientManagerProvider;
        this.peakPeriodPolicy = peakPeriodPolicy;
    }

    /**
     * 兼容构造器（既有测试 / 无峰谷场景用）。
     *
     * <p>内部构造一个「总开关关闭」的时段策略 → 计价恒用基准价，
     * 与改造前行为<b>逐位一致</b>。这也是既有测试（{@code PricingModeGuardTest} /
     * {@code TokenUsageMultiProtocolTest}）无需改动的原因。</p>
     */
    public TokenUsageParser(ObjectMapper objectMapper,
                            TokenPricingConfig pricingConfig,
                            ObjectProvider<LLMClientManager> llmClientManagerProvider) {
        this(objectMapper, pricingConfig, llmClientManagerProvider, disabledPolicy(objectMapper));
    }

    /** 构造一个「不启用峰谷」的时段策略（峰谷判定恒为 false） */
    private static PeakPeriodPolicy disabledPolicy(ObjectMapper objectMapper) {
        PeakPeriodProperties props = new PeakPeriodProperties();
        props.setEnabled(false);
        return new PeakPeriodPolicy(props, new HolidayCalendar(props, objectMapper));
    }

    /**
     * 解析该 Provider 当前生效的计费模式（决定「要不要估算金额」）。
     *
     * <p>链路：{@code provider code} → {@link ProviderConfig}（取 {@code requestTemplate} 与
     * {@code baseUrl}）→ {@link ProviderRegistry} 档案 → {@code resolvePricingMode(baseUrl)}。</p>
     *
     * <p><b>为什么要看 baseUrl</b>：同一个 Provider 可能有两种计费形态 —— 火山方舟普通调用
     * （{@code /api/v3}）按 token 付费，而 Coding Plan（{@code /api/coding/v3}）是次数套餐。
     * 仅凭 template 区分不开。</p>
     *
     * <p><b>任何一步拿不到时都退回 {@link ProviderProfile.PricingMode#TOKEN}</b>：
     * 这只是「沿用旧行为（看单价表）」，不会凭空禁用已有的正确金额。</p>
     *
     * @param provider Provider 编码（如 {@code huoshan} / {@code deepseek}）
     */
    public ProviderProfile.PricingMode resolvePricingMode(String provider) {
        ProviderContext ctx = resolveProviderContext(provider);
        if (ctx == null) {
            return ProviderProfile.PricingMode.TOKEN;
        }
        try {
            return ctx.getProfile().resolvePricingMode(ctx.getBaseUrl());
        } catch (Exception e) {
            log.debug("解析计费模式失败，按 TOKEN 处理: provider={}, err={}", provider, e.getMessage());
            return ProviderProfile.PricingMode.TOKEN;
        }
    }

    /**
     * 统一入口：解析 Provider 的档案上下文（档案 + baseUrl）。
     *
     * <p>计价需要同时拿到「计费模式（档案）」「baseUrl（同一 Provider 两种计费形态）」
     * 「峰谷规格（档案）」，如果各自查一次配置会重复 3 次；这里收敛成一次查询。</p>
     *
     * <p><b>为什么用 {@code resolveFor} 而不是「template 精确匹配」</b>：旧代码是
     * 「template 空才用 code」+ 精确查找，遇到 {@code code=volcengine-custom} 这类命名会查不到档案
     * → 退回 TOKEN → 火山 Coding Plan 被按 token 估出虚假金额。计费判定错会直接产生错误金额，
     * 因此这里必须与其他消费方用同一个入口。</p>
     *
     * @return 档案上下文；Provider 已删除 / 未启用 / 识别不出时返回 {@code null}
     *         （调用方按「TOKEN + 无峰谷」的保守默认处理）
     */
    public ProviderContext resolveProviderContext(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        try {
            LLMClientManager manager = llmClientManagerProvider == null
                    ? null : llmClientManagerProvider.getIfAvailable();
            if (manager == null) {
                return null;
            }
            ProviderConfig config = manager.getProviderConfig(provider);
            if (config == null) {
                // Provider 已被删除 / 未启用：历史数据仍要显示，按单价表沿用旧行为
                return null;
            }
            ProviderProfile profile = ProviderRegistry.resolveFor(
                    config.getRequestTemplate(), config.getCode(), config.getBaseUrl());
            if (profile == null) {
                return null;
            }
            return new ProviderContext(profile, config.getBaseUrl());
        } catch (Exception e) {
            log.debug("解析 Provider 档案失败: provider={}, err={}", provider, e.getMessage());
            return null;
        }
    }

    /**
     * 解析该 Provider 的峰谷规格。
     *
     * @return 档案声明的规格；档案不可用时返回 {@code null}（调用方按「无峰谷」处理）
     */
    public PeakPricingSpec resolvePeakPricing(String provider) {
        ProviderContext ctx = resolveProviderContext(provider);
        return ctx == null ? null : ctx.getProfile().getPeakPricing();
    }

    /** Provider 档案上下文（档案 + baseUrl），避免计价链路重复查配置 */
    public static final class ProviderContext {
        private final ProviderProfile profile;
        private final String baseUrl;

        public ProviderContext(ProviderProfile profile, String baseUrl) {
            this.profile = profile;
            this.baseUrl = baseUrl;
        }

        public ProviderProfile getProfile() {
            return profile;
        }

        public String getBaseUrl() {
            return baseUrl;
        }
    }

    /**
     * 一次计价的结果快照：金额 + 时段 + 生效单价。
     * <p>落库时三项一起写，保证「这个金额是按哪个时段、哪套单价算的」事后可追溯。</p>
     */
    private static final class Quote {
        private final Double cost;
        private final String period;
        private final TokenPricingConfig.PriceItem price;
        private final boolean peak;

        private Quote(Double cost, String period, TokenPricingConfig.PriceItem price, boolean peak) {
            this.cost = cost;
            this.period = period;
            this.price = price;
            this.peak = peak;
        }
    }

    // ==================== 统一入口（跨帧合并） ====================

    /**
     * 解析单帧并合并到已有快照。
     *
     * <p><b>为什么必须「合并」而不是「覆盖」</b>：Anthropic 把用量拆成两处——
     * 输入侧只在 {@code message_start} 出现，输出侧在 {@code message_delta} 出现。
     * 如果像 OpenAI 那样「后到的帧覆盖前面的」，输入 token 会全部丢失。
     * OpenAI / Ollama 的单帧即完整，合并时等价于覆盖。</p>
     *
     * @param previous 已累积的快照（首帧传 null）
     * @param sseData  单帧原始数据（带或不带 {@code data: } 前缀均可）
     * @param protocol {@link LLMClient#getUsageProtocol()} 的返回值；null / 未知按 OpenAI 处理
     * @return 合并后的快照；该帧不含有效用量时返回 previous（原样）
     */
    public UsageSnapshot parseFrame(UsageSnapshot previous, String sseData, String protocol) {
        String p = protocol == null ? PROTOCOL_OPENAI : protocol.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            return switch (p) {
                case PROTOCOL_ANTHROPIC -> parseAnthropicFrame(previous, sseData);
                case PROTOCOL_OLLAMA -> parseOllamaFrame(previous, sseData);
                default -> {
                    UsageSnapshot snap = parse(sseData);
                    yield snap != null ? snap : previous;
                }
            };
        } catch (Exception e) {
            log.trace("usage 帧解析异常（忽略该帧，协议={}）: {}", p, e.getMessage());
            return previous;
        }
    }

    // ==================== 协议一：OpenAI 兼容 ====================

    /**
     * 从一帧 OpenAI 兼容协议的 SSE 数据中解析 usage
     *
     * @param sseData 单帧数据（已去掉 {@code data: } 前缀，或原始帧均可）
     * @return 解析结果；该帧不含有效 usage 时返回 {@code null}
     */
    public UsageSnapshot parse(String sseData) {
        JsonNode root = readJsonPayload(sseData);
        if (root == null || !root.has("usage")) {
            return null;
        }
        JsonNode usage = root.path("usage");
        // MiniMax 等 Provider 的中间帧会带 usage: null，不能当成「有数据」覆盖掉收尾帧
        if (usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return null;
        }

        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        int totalTokens = usage.path("total_tokens").asInt(0);

        // ===== 缓存命中 / 未命中 =====
        int cacheHit = -1;
        int cacheMiss = -1;
        if (usage.hasNonNull("prompt_cache_hit_tokens")) {
            cacheHit = usage.path("prompt_cache_hit_tokens").asInt(0);
        }
        if (usage.hasNonNull("prompt_cache_miss_tokens")) {
            cacheMiss = usage.path("prompt_cache_miss_tokens").asInt(0);
        }
        if (cacheHit < 0 && usage.path("prompt_tokens_details").hasNonNull("cached_tokens")) {
            // OpenAI 风格：只给 cached_tokens，未命中用总数回推
            cacheHit = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            cacheMiss = Math.max(promptTokens - cacheHit, 0);
        }
        boolean cacheDataAvailable = cacheHit >= 0;
        if (!cacheDataAvailable) {
            // Provider 未返回缓存明细：按"全部未命中"保守估算（宁可估高，避免低估成本）
            cacheHit = 0;
            cacheMiss = promptTokens;
        } else if (cacheMiss < 0) {
            cacheMiss = Math.max(promptTokens - cacheHit, 0);
        }

        // ===== 思考链 token =====
        int reasoningTokens = 0;
        JsonNode completionDetails = usage.path("completion_tokens_details");
        if (completionDetails.hasNonNull("reasoning_tokens")) {
            reasoningTokens = completionDetails.path("reasoning_tokens").asInt(0);
        }

        if (totalTokens <= 0) {
            totalTokens = promptTokens + completionTokens;
        }
        if (totalTokens <= 0) {
            return null;
        }

        UsageSnapshot snapshot = new UsageSnapshot();
        snapshot.setProtocol(PROTOCOL_OPENAI);
        snapshot.setPromptTokens(promptTokens);
        snapshot.setCompletionTokens(completionTokens);
        snapshot.setTotalTokens(totalTokens);
        snapshot.setCacheHitTokens(cacheHit);
        snapshot.setCacheMissTokens(cacheMiss);
        snapshot.setCacheCreationTokens(0);
        snapshot.setReasoningTokens(reasoningTokens);
        snapshot.setCacheDataAvailable(cacheDataAvailable);
        return snapshot;
    }

    // ==================== 协议二：Anthropic Messages ====================

    /**
     * 解析一帧 Anthropic SSE 并合并。
     *
     * <pre>
     * event: message_start
     * data: {"type":"message_start","message":{"usage":{
     *          "input_tokens":25,"cache_creation_input_tokens":0,
     *          "cache_read_input_tokens":1000,"output_tokens":1}}}
     *
     * event: message_delta
     * data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},
     *        "usage":{"output_tokens":250}}
     * </pre>
     *
     * <p><b>语义要点</b>：Anthropic 的 {@code input_tokens} <b>不包含</b>缓存读写部分——
     * 总输入 = {@code input_tokens} + {@code cache_read_input_tokens} + {@code cache_creation_input_tokens}。
     * 这与 OpenAI 的 {@code prompt_tokens}（已含 cached_tokens）相反，直接照搬会低估输入。</p>
     */
    private UsageSnapshot parseAnthropicFrame(UsageSnapshot previous, String sseData) {
        JsonNode root = readJsonPayload(sseData);
        if (root == null) {
            return previous;
        }
        String type = root.path("type").asText("");
        UsageSnapshot merged = previous != null ? previous : new UsageSnapshot();

        JsonNode usage;
        if ("message_start".equals(type)) {
            // 输入侧只在 message_start 出现；也有兼容层把 usage 放在顶层，两种都取
            usage = root.path("message").path("usage");
            if (usage.isMissingNode() || !usage.isObject()) {
                usage = root.path("usage");
            }
        } else {
            usage = root.path("usage");
        }
        if (usage.isMissingNode() || usage.isNull() || !usage.isObject()) {
            return previous;
        }

        boolean touched = false;

        // ===== 输入侧（命中 / 未命中 / 缓存写入）=====
        if (usage.hasNonNull("input_tokens") || usage.hasNonNull("cache_read_input_tokens")
                || usage.hasNonNull("cache_creation_input_tokens")) {
            int plainInput = usage.path("input_tokens").asInt(0);
            int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
            int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
            merged.setCacheHitTokens(cacheRead);
            merged.setCacheCreationTokens(cacheCreation);
            // 缓存写入本质是「首次未命中但被持久化」，与普通未命中一起计入 miss，再由单价区分
            merged.setCacheMissTokens(plainInput + cacheCreation);
            merged.setPromptTokens(plainInput + cacheRead + cacheCreation);
            // 明确给出 cache_read_input_tokens 字段即视为「Provider 有缓存明细」（值为 0 也有效）
            merged.setCacheDataAvailable(usage.hasNonNull("cache_read_input_tokens")
                    || usage.hasNonNull("cache_creation_input_tokens"));
            touched = true;
        }

        // ===== 输出侧（message_delta 携带累计值，取最新）=====
        if (usage.hasNonNull("output_tokens")) {
            merged.setCompletionTokens(usage.path("output_tokens").asInt(0));
            touched = true;
        }

        if (!touched) {
            return previous;
        }

        merged.setProtocol(PROTOCOL_ANTHROPIC);
        merged.setTotalTokens(merged.getPromptTokens() + merged.getCompletionTokens());
        if (merged.getTotalTokens() <= 0) {
            return previous;
        }
        return merged;
    }

    // ==================== 协议三：Ollama 原生 NDJSON ====================

    /**
     * 解析一帧 Ollama NDJSON 并合并。
     *
     * <pre>
     * {"model":"llama3.1","message":{"content":"Hi"},"done":false}
     * {"model":"llama3.1","done":true,"prompt_eval_count":11,"eval_count":18,
     *  "prompt_eval_cached_count":8}
     * </pre>
     *
     * <p>用量只在 {@code done=true} 的收尾行；中途断开拿不到（Provider 行为，非本端可补）。</p>
     */
    private UsageSnapshot parseOllamaFrame(UsageSnapshot previous, String sseData) {
        JsonNode root = readJsonPayload(sseData);
        if (root == null || !root.path("done").asBoolean(false)) {
            return previous;
        }
        if (!root.hasNonNull("prompt_eval_count") && !root.hasNonNull("eval_count")) {
            return previous;
        }
        int promptTokens = root.path("prompt_eval_count").asInt(0);
        int completionTokens = root.path("eval_count").asInt(0);
        int cacheHit = root.hasNonNull("prompt_eval_cached_count")
                ? root.path("prompt_eval_cached_count").asInt(0) : 0;
        boolean cacheDataAvailable = root.hasNonNull("prompt_eval_cached_count");

        UsageSnapshot snapshot = new UsageSnapshot();
        snapshot.setProtocol(PROTOCOL_OLLAMA);
        snapshot.setPromptTokens(promptTokens);
        snapshot.setCompletionTokens(completionTokens);
        snapshot.setTotalTokens(promptTokens + completionTokens);
        snapshot.setCacheHitTokens(cacheHit);
        snapshot.setCacheMissTokens(Math.max(promptTokens - cacheHit, 0));
        snapshot.setCacheCreationTokens(0);
        snapshot.setReasoningTokens(0);
        snapshot.setCacheDataAvailable(cacheDataAvailable);
        return snapshot.getTotalTokens() > 0 ? snapshot : previous;
    }

    // ==================== 计费 ====================

    /**
     * 按单价表计算估算费用（元）。
     *
     * @return 费用；<b>该模型未在 {@code llm.pricing.models} 中配置单价时返回 {@code null}</b>
     *         —— 表示「本端无法估算」，由调用方落库存 null，前端不显示金额。
     */
    public Double calculateCost(String model, UsageSnapshot snapshot) {
        // 兼容入口：不传计费模式时按 TOKEN（沿用旧行为，供既有单测直接调用）
        return calculateCost(model, snapshot, ProviderProfile.PricingMode.TOKEN);
    }

    /**
     * 按单价表计算估算费用，并受<b>计费模式</b>约束。
     *
     * <p><b>为什么要多一个 mode 参数</b>：{@link ProviderProfile.PricingMode#QUOTA}（套餐 / 次数制，
     * 如火山 Coding Plan 200 元 9 万次）与 {@link ProviderProfile.PricingMode#FREE}（本地 Ollama）
     * <b>根本不存在「元 / 百万 token」单价</b>，因此必须无条件返回 {@code null} —
     * 即便有人误给这类模型配了单价，也不能产生一个虚假金额。</p>
     *
     * @param model    模型名
     * @param snapshot 用量快照
     * @param mode     计费模式（null 视为 TOKEN）
     * @return 费用；不可估算时返回 {@code null}
     */
    public Double calculateCost(String model, UsageSnapshot snapshot, ProviderProfile.PricingMode mode) {
        if (snapshot == null) {
            return null;
        }
        // QUOTA / FREE：不是「没配单价」，而是「这个 Provider 不该按 token 估价」
        if (!pricingConfig.isEstimable(model, mode)) {
            return null;
        }
        return computeCost(snapshot, pricingConfig.resolve(model));
    }

    /**
     * <b>峰谷感知的计价入口（生产链路请一律用这个）</b>。
     *
     * <p>与旧重载的区别：旧重载只吃 {@code model + mode}，无法知道「这次调用发生在高峰还是空闲」，
     * 因此只能用基准价；本方法从 {@code provider} 解析出<b>平台档案</b>，
     * 由档案声明的 {@link PeakPricingSpec} 决定是否分峰谷，再按 {@code requestTime} 选价。</p>
     *
     * <h3>为什么时间要由调用方传入</h3>
     * <p>
     * 流式请求可能跑几分钟，横跨 12:00 / 18:00 的峰谷边界。落库时刻（{@code doFinally}）与
     * 请求发起时刻可能落在不同时段，而 Provider 是按<b>请求发起时刻</b>计费 ——
     * 所以这里必须用「请求发起时间」，不能内部 {@code now()}。
     * </p>
     *
     * @param provider    Provider 编码（用于解析档案中的峰谷规格）
     * @param model       模型名
     * @param snapshot    用量快照
     * @param requestTime 请求发起时刻（{@code null} 时按平台时区的当前时间）
     * @return 费用；不可估算（未配单价 / 套餐制 / 免费）时返回 {@code null}
     */
    public Double calculateCost(String provider, String model, UsageSnapshot snapshot, ZonedDateTime requestTime) {
        return quote(provider, model, snapshot, requestTime).cost;
    }

    /**
     * 一次性算出「金额 + 时段 + 生效单价」，供落库写入计价快照。
     *
     * <p>把四处信息（档案 / 计费模式 / 峰谷规格 / 单价）收敛在一次解析里，
     * 避免落库链路重复查 Provider 配置。</p>
     */
    private Quote quote(String provider, String model, UsageSnapshot snapshot, ZonedDateTime requestTime) {
        ProviderContext ctx = resolveProviderContext(provider);
        ProviderProfile.PricingMode mode = ProviderProfile.PricingMode.TOKEN;
        PeakPricingSpec peakSpec = null;
        if (ctx != null && ctx.getProfile() != null) {
            try {
                mode = ctx.getProfile().resolvePricingMode(ctx.getBaseUrl());
            } catch (Exception e) {
                // 计费模式判定失败时沿用旧行为（按单价表），不会凭空禁用已有金额
                mode = ProviderProfile.PricingMode.TOKEN;
            }
            peakSpec = ctx.getProfile().getPeakPricing();
        }
        ZonedDateTime at = requestTime != null ? requestTime : peakPeriodPolicy.nowOf(peakSpec);
        boolean peak = peakPeriodPolicy.isPeak(peakSpec, at);
        String period = peakPeriodPolicy.periodOf(peakSpec, at);

        if (!pricingConfig.isEstimable(model, mode)) {
            return new Quote(null, period, null, peak);
        }
        TokenPricingConfig.PriceItem price = pricingConfig.resolveFor(
                model, peak, peakPeriodPolicy.peakMultiplier());
        return new Quote(computeCost(snapshot, price), period, price, peak);
    }

    /**
     * 四项相加（保证旧 / 新两个入口的算法<b>逐位一致</b>）。
     * <p>保留 6 位小数，与 {@code DECIMAL(14,6)} 对齐。</p>
     */
    private Double computeCost(UsageSnapshot snapshot, TokenPricingConfig.PriceItem price) {
        if (snapshot == null || price == null) {
            return null;
        }
        double cacheHitCost = snapshot.getCacheHitTokens() / 1_000_000d * price.getCacheHitInput();
        double cacheWriteCost = snapshot.getCacheCreationTokens() / 1_000_000d * price.cacheWritePrice();
        double cacheMissCost = snapshot.getCacheMissTokens() / 1_000_000d * price.getCacheMissInput();
        double outputCost = snapshot.getCompletionTokens() / 1_000_000d * price.getOutput();
        return Math.round((cacheHitCost + cacheWriteCost + cacheMissCost + outputCost) * 1_000_000d)
                / 1_000_000d;
    }

    /**
     * 构造可落库的用量实体
     *
     * @param conversationId 会话 ID（可空）
     * @param turnId         轮次 ID（前端生成，可空；空值只参与会话/时间维度汇总）
     * @param messageId      关联消息 ID（当前落库时机拿不到，传 null）
     * @param iteration      工具循环迭代轮次
     * @param provider       Provider 编码
     * @param model          模型名
     * @param snapshot       本次请求的用量快照
     */
    /**
     * 构造可落库的用量实体（兼容入口：按落库时刻判定时段）。
     *
     * @param conversationId 会话 ID（可空）
     * @param turnId         轮次 ID（前端生成，可空；空值只参与会话/时间维度汇总）
     * @param messageId      关联消息 ID（当前落库时机拿不到，传 null）
     * @param iteration      工具循环迭代轮次
     * @param provider       Provider 编码
     * @param model          模型名
     * @param snapshot       本次请求的用量快照
     */
    public LlmTokenUsage toEntity(Long conversationId, String turnId, Long messageId, int iteration,
                                  String provider, String model, UsageSnapshot snapshot) {
        return toEntity(conversationId, turnId, messageId, iteration, provider, model, snapshot, null);
    }

    /**
     * 构造可落库的用量实体（<b>峰谷感知版，生产链路请用这个</b>）。
     *
     * <p>除金额外，额外写入三个「计价快照」字段，解决「历史记录按哪套价算的，事后查不出来」：</p>
     * <ul>
     *   <li>{@code pricingPeriod}：{@code peak} / {@code off-peak} / {@code null}（平台无峰谷）</li>
     *   <li>{@code pricingVersion}：计价口径版本，用于区分新旧两批数据（历史不回算）</li>
     *   <li>{@code unitPriceSnapshot}：当时生效的三档单价（JSON，审计用）</li>
     * </ul>
     *
     * @param requestTime <b>请求发起时刻</b>（不是落库时刻：流式请求可能跨峰谷边界，
     *                    Provider 按发起时刻计费）；{@code null} 时按平台时区当前时间
     */
    public LlmTokenUsage toEntity(Long conversationId, String turnId, Long messageId, int iteration,
                                  String provider, String model, UsageSnapshot snapshot,
                                  ZonedDateTime requestTime) {
        LlmTokenUsage entity = new LlmTokenUsage();
        entity.setConversationId(conversationId);
        entity.setTurnId(turnId);
        entity.setMessageId(messageId);
        entity.setIteration(iteration);
        entity.setProvider(provider);
        entity.setModel(model);
        entity.setPromptTokens(snapshot.getPromptTokens());
        entity.setCompletionTokens(snapshot.getCompletionTokens());
        entity.setTotalTokens(snapshot.getTotalTokens());
        entity.setCacheHitTokens(snapshot.getCacheHitTokens());
        entity.setCacheMissTokens(snapshot.getCacheMissTokens());
        entity.setReasoningTokens(snapshot.getReasoningTokens());

        // 一次解析拿到「金额 + 时段 + 生效单价」（null = 不可估算：未配单价 / 套餐制 / 本地免费）
        // 前端据此隐藏金额，而不是显示一个错的价格或 ¥0
        Quote quote = quote(provider, model, snapshot, requestTime);
        entity.setCostEstimate(quote.cost);
        entity.setPricingPeriod(quote.period);
        entity.setPricingVersion(PRICING_VERSION);
        entity.setUnitPriceSnapshot(describePrice(quote.price, quote.period));
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    /**
     * 计价口径版本号。
     * <p>历史数据<b>一律不回算</b>（改了就等于篡改当时的账），靠本字段区分
     * 「2026-11-18 之前按旧价算的」与「之后按峰谷价算的」两批记录。</p>
     */
    public static final String PRICING_VERSION = "2026-11-18-peak-v1";

    /** 单价快照（紧凑 JSON，字段长度上限 200 以免撑爆列宽）；不可估算时为 null */
    private String describePrice(TokenPricingConfig.PriceItem price, String period) {
        if (price == null) {
            return null;
        }
        try {
            String json = "{\"period\":\"" + (period == null ? "none" : period)
                    + "\",\"hit\":" + price.getCacheHitInput()
                    + ",\"miss\":" + price.getCacheMissInput()
                    + ",\"output\":" + price.getOutput() + "}";
            return json.length() > 200 ? json.substring(0, 200) : json;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 把一帧 SSE 数据解析成 JSON。
     * <p>兼容三种输入：纯 JSON、{@code data: {...}} 单行、{@code event: xxx\ndata: {...}} 整块。</p>
     */
    private JsonNode readJsonPayload(String sseData) {
        if (sseData == null || sseData.isEmpty()) {
            return null;
        }
        String json = extractDataPayload(sseData);
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.trace("usage 帧 JSON 解析失败（忽略该帧）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 SSE 帧中取出 data 负载（去掉 {@code event:} / {@code data:} 行前缀）
     */
    static String extractDataPayload(String sseData) {
        String text = sseData.trim();
        if (text.contains("\n")) {
            // 整块形式：只取 data: 行
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\n")) {
                String l = line.trim();
                if (l.startsWith("data:")) {
                    sb.append(l.substring(5).trim());
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        if (text.startsWith("data:")) {
            return text.substring(5).trim();
        }
        if (text.startsWith("event:")) {
            return "";
        }
        return text;
    }

    /**
     * 单次请求的用量快照
     */
    @Data
    public static class UsageSnapshot {
        /** 承载协议（openai / anthropic / ollama），便于排查「为什么这条没成本」 */
        private String protocol = PROTOCOL_OPENAI;
        /** 输入 token 总数（命中 + 未命中 + 缓存写入） */
        private int promptTokens;
        /** 输出 token 总数（含思考链） */
        private int completionTokens;
        /** 输入 + 输出总 token */
        private int totalTokens;
        /** 缓存命中输入 token */
        private int cacheHitTokens;
        /** 缓存未命中输入 token */
        private int cacheMissTokens;
        /**
         * 缓存写入 token（仅 Anthropic 有：首次写入 prompt cache，
         * 计费约为普通输入的 1.25 倍；OpenAI 系没有这个概念，恒为 0）
         */
        private int cacheCreationTokens;
        /** 思考链 token */
        private int reasoningTokens;
        /** Provider 是否真的返回了缓存明细（false 时 hit/miss 为保守估算值） */
        private boolean cacheDataAvailable;
    }
}
