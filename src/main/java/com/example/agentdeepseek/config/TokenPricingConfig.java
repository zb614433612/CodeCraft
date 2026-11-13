package com.example.agentdeepseek.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 计费单价配置（成本估算用）
 * <p>
 * <b>单位：元 / 百万 tokens</b>（与官方价格页口径一致）。
 * 这里算出的费用仅用于「成本结构可视化」，<b>不是账单</b>——最终以官方平台账单为准。
 * </p>
 * <p>
 * 为什么必须把"命中/未命中/输出"三者分开：
 * deepseek-v4-pro 的缓存命中输入约 0.025 元/百万、未命中约 3 元/百万、输出约 6 元/百万，
 * 命中与未命中相差约 120 倍。用单一"输入均价"算出来的数字会完全误导优化方向。
 * </p>
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * llm:
 *   pricing:
 *     default:
 *       cache-hit-input: 0.025
 *       cache-miss-input: 3.0
 *       output: 6.0
 *     models:
 *       deepseek-v4-pro: { cache-hit-input: 0.025, cache-miss-input: 3.0, output: 6.0 }
 *       deepseek-flash:  { cache-hit-input: 0.02,  cache-miss-input: 1.0, output: 2.0 }
 * </pre>
 * </p>
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "llm.pricing")
public class TokenPricingConfig {

    /** 默认单价（未在 models 中命中时使用） */
    private PriceItem defaultPrice = new PriceItem(0.025, 3.0, 6.0);

    /** 按模型的单价覆盖表，key 为模型名（支持前缀匹配） */
    private Map<String, PriceItem> models = new LinkedHashMap<>();

    /**
     * 单价项（元 / 百万 tokens）
     */
    @Data
    public static class PriceItem {
        /** 缓存命中输入单价 */
        private double cacheHitInput;
        /** 缓存未命中输入单价 */
        private double cacheMissInput;
        /** 输出单价 */
        private double output;

        public PriceItem() {
        }

        public PriceItem(double cacheHitInput, double cacheMissInput, double output) {
            this.cacheHitInput = cacheHitInput;
            this.cacheMissInput = cacheMissInput;
            this.output = output;
        }
    }

    /**
     * 解析某模型的单价
     * <p>匹配顺序：精确匹配 → 前缀匹配（key 是模型名前缀）→ 默认单价。</p>
     */
    public PriceItem resolve(String model) {
        if (model == null || model.isEmpty()) {
            return defaultPrice;
        }
        if (models == null || models.isEmpty()) {
            return defaultPrice;
        }
        PriceItem exact = models.get(model);
        if (exact != null) {
            return exact;
        }
        // 前缀匹配：如配置 deepseek 可命中 deepseek-v4-pro
        for (Map.Entry<String, PriceItem> entry : models.entrySet()) {
            String key = entry.getKey();
            if (key != null && !key.isEmpty() && model.startsWith(key)) {
                return entry.getValue();
            }
        }
        return defaultPrice;
    }
}
