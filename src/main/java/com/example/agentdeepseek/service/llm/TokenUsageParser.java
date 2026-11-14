package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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

    public TokenUsageParser(ObjectMapper objectMapper, TokenPricingConfig pricingConfig) {
        this.objectMapper = objectMapper;
        this.pricingConfig = pricingConfig;
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
        if (snapshot == null) {
            return null;
        }
        TokenPricingConfig.PriceItem price = pricingConfig.resolve(model);
        if (price == null) {
            return null;
        }
        double cacheHitCost = snapshot.getCacheHitTokens() / 1_000_000d * price.getCacheHitInput();
        double cacheWriteCost = snapshot.getCacheCreationTokens() / 1_000_000d * price.cacheWritePrice();
        double cacheMissCost = snapshot.getCacheMissTokens() / 1_000_000d * price.getCacheMissInput();
        double outputCost = snapshot.getCompletionTokens() / 1_000_000d * price.getOutput();
        // 保留 6 位小数（与 DECIMAL(14,6) 对齐）
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
    public LlmTokenUsage toEntity(Long conversationId, String turnId, Long messageId, int iteration,
                                  String provider, String model, UsageSnapshot snapshot) {
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
        // null = 该模型未配置单价，不估算（前端隐藏金额，而不是显示一个错的价格）
        entity.setCostEstimate(calculateCost(model, snapshot));
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
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
