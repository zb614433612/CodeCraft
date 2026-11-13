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
 * <p>
 * 数据来源：OpenAI 兼容协议响应中的 {@code usage} 对象。按官方文档，
 * <b>流式请求也会在 {@code data: [DONE]} 之前的最后一个 chunk 上携带完整 usage</b>，
 * 因此不需要额外设置 {@code stream_options.include_usage}。
 * </p>
 * <p>
 * 支持的字段（多 Provider 兼容，按优先级回退）：
 * <ul>
 *   <li>{@code prompt_tokens} / {@code completion_tokens} / {@code total_tokens}</li>
 *   <li>{@code prompt_cache_hit_tokens} / {@code prompt_cache_miss_tokens}（DeepSeek 原生）</li>
 *   <li>{@code prompt_tokens_details.cached_tokens}（OpenAI 风格，用于回推 hit/miss）</li>
 *   <li>{@code completion_tokens_details.reasoning_tokens}（思考链，部分 Provider 提供）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class TokenUsageParser {

    private final ObjectMapper objectMapper;
    private final TokenPricingConfig pricingConfig;

    public TokenUsageParser(ObjectMapper objectMapper, TokenPricingConfig pricingConfig) {
        this.objectMapper = objectMapper;
        this.pricingConfig = pricingConfig;
    }

    /**
     * 从一帧 SSE 数据中解析 usage
     *
     * @param sseData 单帧数据（已去掉 {@code data: } 前缀，或原始帧均可）
     * @return 解析结果；该帧不含有效 usage 时返回 {@code null}
     */
    public UsageSnapshot parse(String sseData) {
        if (sseData == null || sseData.isEmpty()) {
            return null;
        }
        String json = sseData.trim();
        if (json.startsWith("data:")) {
            json = json.substring(5).trim();
        }
        if (json.isEmpty() || "[DONE]".equals(json) || json.startsWith("event:")) {
            return null;
        }
        // 快速短路：绝大多数帧不含 usage，避免每帧都做完整 JSON 解析
        if (!json.contains("\"usage\"")) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode usage = root.path("usage");
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
            snapshot.setPromptTokens(promptTokens);
            snapshot.setCompletionTokens(completionTokens);
            snapshot.setTotalTokens(totalTokens);
            snapshot.setCacheHitTokens(cacheHit);
            snapshot.setCacheMissTokens(cacheMiss);
            snapshot.setReasoningTokens(reasoningTokens);
            snapshot.setCacheDataAvailable(cacheDataAvailable);
            return snapshot;
        } catch (Exception e) {
            log.trace("usage 解析失败（忽略该帧）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按单价表计算估算费用（元）
     */
    public double calculateCost(String model, UsageSnapshot snapshot) {
        if (snapshot == null) {
            return 0d;
        }
        TokenPricingConfig.PriceItem price = pricingConfig.resolve(model);
        double cost = snapshot.getCacheHitTokens() / 1_000_000d * price.getCacheHitInput()
                + snapshot.getCacheMissTokens() / 1_000_000d * price.getCacheMissInput()
                + snapshot.getCompletionTokens() / 1_000_000d * price.getOutput();
        // 保留 6 位小数（与 DECIMAL(14,6) 对齐）
        return Math.round(cost * 1_000_000d) / 1_000_000d;
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
        entity.setCostEstimate(calculateCost(model, snapshot));
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    /**
     * 单次请求的用量快照
     */
    @Data
    public static class UsageSnapshot {
        /** 输入 token 总数 */
        private int promptTokens;
        /** 输出 token 总数（含思考链） */
        private int completionTokens;
        /** 输入 + 输出总 token */
        private int totalTokens;
        /** 缓存命中输入 token */
        private int cacheHitTokens;
        /** 缓存未命中输入 token */
        private int cacheMissTokens;
        /** 思考链 token */
        private int reasoningTokens;
        /** Provider 是否真的返回了缓存明细（false 时 hit/miss 为保守估算值） */
        private boolean cacheDataAvailable;
    }
}
