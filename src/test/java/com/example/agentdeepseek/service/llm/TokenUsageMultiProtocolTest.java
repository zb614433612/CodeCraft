package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * TokenUsageParser 多协议解析自测（main 方法式，项目统一风格）
 *
 * <p>覆盖三种 usage 承载协议与「无单价不估算」的核心规则：</p>
 * <ol>
 *   <li>OpenAI 兼容：单帧完整 usage（DeepSeek 原生缓存字段 / OpenAI cached_tokens / usage:null 忽略）</li>
 *   <li>Anthropic：输入侧只在 message_start、输出侧在 message_delta → 必须跨帧合并</li>
 *   <li>Ollama：NDJSON 的 done=true 收尾行</li>
 *   <li>计费：模型未配置单价返回 null（不是 0），已配置则按三段单价计算</li>
 * </ol>
 */
public class TokenUsageMultiProtocolTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TokenPricingConfig pricing = buildPricing();
        TokenUsageParser parser = new TokenUsageParser(mapper, pricing);

        testOpenAiDeepSeekNativeCache(parser);
        testOpenAiCachedTokensStyle(parser);
        testOpenAiIgnoresNullUsageFrame(parser);
        testAnthropicCrossFrameMerge(parser);
        testAnthropicUsageAtTopLevel(parser);
        testOllamaDoneFrame(parser);
        testOllamaIgnoresNonDoneFrame(parser);
        testCostUnavailableWhenModelNotPriced(parser);
        testCostCalculatedWhenModelPriced(parser);
        testCacheWritePriceFallback();
        testDataPayloadExtraction();

        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 1. OpenAI 兼容协议 ====================

    private static void testOpenAiDeepSeekNativeCache(TokenUsageParser parser) {
        String frame = "data: {\"choices\":[{\"delta\":{}}],\"usage\":{"
                + "\"prompt_tokens\":1000,\"completion_tokens\":200,\"total_tokens\":1200,"
                + "\"prompt_cache_hit_tokens\":900,\"prompt_cache_miss_tokens\":100,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":80}}}";
        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null, frame, "openai");
        check("openai/DeepSeek: 解析成功", snap != null);
        check("openai/DeepSeek: 输入 1000", snap != null && snap.getPromptTokens() == 1000);
        check("openai/DeepSeek: 命中 900", snap != null && snap.getCacheHitTokens() == 900);
        check("openai/DeepSeek: 未命中 100", snap != null && snap.getCacheMissTokens() == 100);
        check("openai/DeepSeek: 思考链 80", snap != null && snap.getReasoningTokens() == 80);
        check("openai/DeepSeek: 缓存明细可用", snap != null && snap.isCacheDataAvailable());
        check("openai/DeepSeek: 协议标记", snap != null && "openai".equals(snap.getProtocol()));
    }

    private static void testOpenAiCachedTokensStyle(TokenUsageParser parser) {
        String frame = "{\"usage\":{\"prompt_tokens\":500,\"completion_tokens\":50,\"total_tokens\":550,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":300}}}";
        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null, frame, "openai");
        check("openai/cached_tokens: 命中 300", snap != null && snap.getCacheHitTokens() == 300);
        check("openai/cached_tokens: 未命中回推 200", snap != null && snap.getCacheMissTokens() == 200);
    }

    private static void testOpenAiIgnoresNullUsageFrame(TokenUsageParser parser) {
        // MiniMax 等 Provider 的中间帧会带 usage: null，不能当成有效数据覆盖掉真实收尾帧
        TokenUsageParser.UsageSnapshot first = parser.parseFrame(null,
                "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}", "openai");
        TokenUsageParser.UsageSnapshot merged = parser.parseFrame(first, "{\"usage\":null}", "openai");
        check("openai/usage:null: 保留已有快照", merged != null && merged.getTotalTokens() == 15);
        TokenUsageParser.UsageSnapshot empty = parser.parseFrame(null, "{\"usage\":null}", "openai");
        check("openai/usage:null: 无前值时返回 null", empty == null);
    }

    // ==================== 2. Anthropic 协议 ====================

    private static void testAnthropicCrossFrameMerge(TokenUsageParser parser) {
        // 输入侧只出现在 message_start（含缓存读/写），输出侧在 message_delta
        String start = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{"
                + "\"input_tokens\":25,\"cache_creation_input_tokens\":100,"
                + "\"cache_read_input_tokens\":800,\"output_tokens\":1}}}";
        String delta = "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":250}}";

        TokenUsageParser.UsageSnapshot s1 = parser.parseFrame(null, start, "anthropic");
        check("anthropic: start 帧解析成功", s1 != null);
        check("anthropic: 输入=25+800+100", s1 != null && s1.getPromptTokens() == 925);
        check("anthropic: 命中=800", s1 != null && s1.getCacheHitTokens() == 800);
        check("anthropic: 缓存写入=100", s1 != null && s1.getCacheCreationTokens() == 100);
        check("anthropic: 未命中=25+100", s1 != null && s1.getCacheMissTokens() == 125);

        TokenUsageParser.UsageSnapshot s2 = parser.parseFrame(s1, delta, "anthropic");
        check("anthropic: 合并后仍有输入 token", s2 != null && s2.getPromptTokens() == 925);
        check("anthropic: 合并后输出=250", s2 != null && s2.getCompletionTokens() == 250);
        check("anthropic: 合并后总量=1175", s2 != null && s2.getTotalTokens() == 1175);
        check("anthropic: 协议标记", s2 != null && "anthropic".equals(s2.getProtocol()));
        check("anthropic: 缓存明细可用", s2 != null && s2.isCacheDataAvailable());
    }

    private static void testAnthropicUsageAtTopLevel(TokenUsageParser parser) {
        // 部分兼容层把 usage 放在顶层而非 message.usage
        String frame = "{\"type\":\"message_start\",\"usage\":{\"input_tokens\":40,\"output_tokens\":2}}";
        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null, frame, "anthropic");
        check("anthropic/顶层 usage: 输入 40", snap != null && snap.getPromptTokens() == 40);
        check("anthropic/顶层 usage: 输出 2", snap != null && snap.getCompletionTokens() == 2);
    }

    // ==================== 3. Ollama 协议 ====================

    private static void testOllamaDoneFrame(TokenUsageParser parser) {
        String frame = "{\"model\":\"gemma4\",\"done\":true,\"prompt_eval_count\":11,"
                + "\"eval_count\":18,\"prompt_eval_cached_count\":8}";
        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null, frame, "ollama");
        check("ollama: 输入 11", snap != null && snap.getPromptTokens() == 11);
        check("ollama: 输出 18", snap != null && snap.getCompletionTokens() == 18);
        check("ollama: 命中 8", snap != null && snap.getCacheHitTokens() == 8);
        check("ollama: 未命中 3", snap != null && snap.getCacheMissTokens() == 3);
        check("ollama: 协议标记", snap != null && "ollama".equals(snap.getProtocol()));
    }

    private static void testOllamaIgnoresNonDoneFrame(TokenUsageParser parser) {
        String frame = "{\"model\":\"gemma4\",\"message\":{\"content\":\"hi\"},\"done\":false}";
        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null, frame, "ollama");
        check("ollama: 非 done 帧返回 null", snap == null);
    }

    // ==================== 4. 计费 ====================

    private static void testCostUnavailableWhenModelNotPriced(TokenUsageParser parser) {
        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null,
                "{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":100,\"total_tokens\":1100}}", "openai");
        Double cost = parser.calculateCost("gpt-4o", snap);
        check("计费: 未配置单价的模型返回 null（不估算）", cost == null);

        LlmTokenUsage entity = parser.toEntity(1L, "turn-1", null, 1, "openai", "gpt-4o", snap);
        check("计费: 落库实体的 cost 为 null", entity.getCostEstimate() == null);
        check("计费: token 仍照常落库", entity.getTotalTokens() == 1100);
    }

    private static void testCostCalculatedWhenModelPriced(TokenUsageParser parser) {
        // deepseek-test 配置：命中 0.025 / 未命中 3.0 / 输出 6.0（元每百万）
        // 注意 JSON 数字不能带下划线分隔符，必须写 1000000
        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null,
                "{\"usage\":{\"prompt_tokens\":1000000,\"completion_tokens\":1000000,"
                        + "\"total_tokens\":2000000,\"prompt_cache_hit_tokens\":600000,"
                        + "\"prompt_cache_miss_tokens\":400000}}", "openai");
        Double cost = parser.calculateCost("deepseek-test", snap);
        // 0.6*0.025 + 0.4*3.0 + 1.0*6.0 = 0.015 + 1.2 + 6 = 7.215
        check("计费: 已配置单价返回金额", cost != null);
        check("计费: 金额 = 7.215（实际 " + cost + "）", cost != null && Math.abs(cost - 7.215) < 1e-6);
    }

    private static void testCacheWritePriceFallback() {
        TokenPricingConfig.PriceItem withWrite =
                new TokenPricingConfig.PriceItem(0.1, 1.0, 2.0, 1.25);
        check("单价: 显式 cache-write 生效", Math.abs(withWrite.cacheWritePrice() - 1.25) < 1e-9);
        TokenPricingConfig.PriceItem withoutWrite =
                new TokenPricingConfig.PriceItem(0.1, 1.0, 2.0);
        check("单价: 缺省 cache-write 退化为未命中价",
                Math.abs(withoutWrite.cacheWritePrice() - 1.0) < 1e-9);
    }

    // ==================== 5. SSE 负载提取 ====================

    private static void testDataPayloadExtraction() {
        check("提取: data: 前缀",
                "{\"a\":1}".equals(TokenUsageParser.extractDataPayload("data: {\"a\":1}")));
        check("提取: 纯 JSON 原样",
                "{\"a\":1}".equals(TokenUsageParser.extractDataPayload("{\"a\":1}")));
        check("提取: event+data 整块只取 data",
                "{\"a\":1}".equals(TokenUsageParser.extractDataPayload("event: message_start\ndata: {\"a\":1}")));
        check("提取: event: 行返回空", TokenUsageParser.extractDataPayload("event: ping").isEmpty());
    }

    // ==================== 辅助 ====================

    private static TokenPricingConfig buildPricing() {
        TokenPricingConfig config = new TokenPricingConfig();
        // defaultPrice 保持 null（默认不兜底），确保「未配单价 = 不估算」被真实覆盖到
        LinkedHashMap<String, TokenPricingConfig.PriceItem> models = new LinkedHashMap<>();
        models.put("deepseek-test", new TokenPricingConfig.PriceItem(0.025, 3.0, 6.0));
        config.setModels(models);
        return config;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name);
        }
    }
}
