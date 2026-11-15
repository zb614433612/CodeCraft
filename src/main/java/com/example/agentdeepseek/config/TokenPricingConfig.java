package com.example.agentdeepseek.config;

import com.example.agentdeepseek.service.llm.profile.ProviderProfile;
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
 *
 * <h3>⚠️ 未配置单价 = 不估算成本（不是按 0 元算）</h3>
 * <p>
 * {@link #resolve(String)} 对未匹配的模型返回 {@code null}，表示「本端不知道该模型多少钱」。
 * 调用方据此把 {@code cost_estimate} 落成 {@code NULL}，前端隐藏金额、只显示 token 与缓存命中率。
 * </p>
 * <p>
 * <b>为什么不再回退默认价</b>：历史实现把未匹配模型一律按 DeepSeek 价计算，
 * 结果 GPT / Claude 按 DeepSeek 单价算出一个看似合理、实则完全错误的数字——
 * 错误的成本数字比没有成本数字更危险，它会误导「该不该换模型」这类决策。
 * </p>
 *
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * llm:
 *   pricing:
 *     models:
 *       deepseek-v4-pro: { cache-hit-input: 0.025, cache-miss-input: 3.0, output: 6.0 }
 *       deepseek-flash:  { cache-hit-input: 0.02,  cache-miss-input: 1.0, output: 2.0 }
 *       # 美元计价 Provider 请先按汇率换算成人民币再填；不填则只统计 token、不显示金额
 *       # claude: { cache-hit-input: ..., cache-write-input: ..., cache-miss-input: ..., output: ... }
 * </pre>
 * </p>
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "llm.pricing")
public class TokenPricingConfig {

    /**
     * 全局兜底单价（默认 <b>不配置</b>，即 {@code null}）。
     * <p>
     * 只在「你确实希望所有未知模型都按同一价估算」时才显式配置；
     * 例如纯 DeepSeek 环境里想让新增的 deepseek-* 模型自动继承同一价，
     * 更精确的做法是用 {@code models} 里的前缀键（{@code deepseek: {...}}）。
     * </p>
     */
    private PriceItem defaultPrice;

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
        /**
         * 缓存写入单价（仅 Anthropic 的 {@code cache_creation_input_tokens} 适用）。
         * <p>缺省（0）时按 {@link #cacheMissInput} 计——Anthropic 官方约为普通输入的 1.25 倍，
         * 需要精确估算时显式填写。</p>
         */
        private double cacheWriteInput;

        public PriceItem() {
        }

        public PriceItem(double cacheHitInput, double cacheMissInput, double output) {
            this.cacheHitInput = cacheHitInput;
            this.cacheMissInput = cacheMissInput;
            this.output = output;
        }

        public PriceItem(double cacheHitInput, double cacheMissInput, double output, double cacheWriteInput) {
            this(cacheHitInput, cacheMissInput, output);
            this.cacheWriteInput = cacheWriteInput;
        }

        /**
         * 缓存写入的实际单价：未显式配置时退化为「未命中输入单价」
         */
        public double cacheWritePrice() {
            return cacheWriteInput > 0 ? cacheWriteInput : cacheMissInput;
        }
    }

    /**
     * 解析某模型的单价
     * <p>匹配顺序：精确匹配 → 前缀匹配（key 是模型名前缀）→ {@link #defaultPrice}。</p>
     *
     * @return 单价；<b>该模型未配置（且没有全局兜底价）时返回 {@code null}</b>，
     *         表示「不估算成本」，而不是「成本为 0」
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

    /**
     * 该模型是否配置了单价（可估算成本）
     */
    public boolean isPriced(String model) {
        return resolve(model) != null;
    }

    // ==================== 计费模式（T4：根治「套餐制被按 token 估价」） ====================

    /**
     * 该模型在给定计费模式下「是否应当估算金额」。
     *
     * <h3>为什么需要「计费模式」这一层</h3>
     * <p>
     * 历史 bug：本配置只能表达「元 / 百万 token」一种形态，于是
     * <b>「这个模型没配单价」</b> 与 <b>「这个 Provider 根本不该按 token 估价」</b>
     * 被混成了同一件事。
     * </p>
     * <p>
     * 实测后果：火山方舟 Coding Plan 是「200 元 / 9 万次」的<b>次数套餐</b>，
     * 并不存在「元 / 百万 token」这种单价；但成本看板在混合范围（既有 DeepSeek 又有火山）里
     * 取了 DeepSeek 的单价当参照，把火山的 <b>5396 万 token</b> 算出了一笔
     * 「看起来合理、实际完全错误」的金额。
     * </p>
     * <p>
     * 因此：{@link ProviderProfile.PricingMode#QUOTA}（套餐 / 次数制）与
     * {@link ProviderProfile.PricingMode#FREE}（本地免费）一律<b>不估算</b>，
     * <b>即使有人误给这类模型配了单价</b> —— 误配不应当产生一个虚假金额。
     * </p>
     *
     * @param model 模型名
     * @param mode  计费模式（null 视为 {@link ProviderProfile.PricingMode#TOKEN}）
     * @return true 表示「可以按单价估算金额」
     */
    public boolean isEstimable(String model, ProviderProfile.PricingMode mode) {
        if (mode != null && mode != ProviderProfile.PricingMode.TOKEN) {
            return false;
        }
        return isPriced(model);
    }
}
