package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.config.ModelCapabilityConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型能力规则（配置驱动，替代硬编码模型名白名单）
 * <p>
 * <b>为什么改成配置驱动</b>：旧实现把 {@code o1/o3/o4/gpt-5} 硬编码在
 * {@code isReasoningModel()} 里，导致新模型（如 {@code gpt-6}）上线后：
 * ① 被判为非推理模型 → 输出上限被压到 8192，思考链被截断；
 * ② {@code non-thinking} 时无法关闭思考；③ {@code minimal} 档降级失效。
 * 每次模型迭代都要改代码 + 重新发版。
 * </p>
 * <p>
 * <b>现在的做法</b>：所有判定集中到本类，值由 {@link ModelCapabilityConfig}
 * （{@code llm.model-capabilities.*}）在启动时写入；新增模型只需改配置。
 * 未注入配置时使用与历史一致的内置默认值，保证单测与冷启动可用。
 * </p>
 * <p><b>匹配规则</b>：模型名（已归一：取 {@code /} 之后最后一段、转小写）按<b>前缀</b>匹配。
 * 例如配置 {@code gpt-5} 可命中 {@code gpt-5.4-mini}，配置 {@code gpt-6} 可命中 {@code gpt-6-turbo}。</p>
 *
 * <p><b>新增 Provider 适配时</b>：本类承担总清单的 B5（图片识别能力判定）与 B9（输出上限 /
 * 推理模型判定）。通常<b>不需要改 Java 代码</b>，只改 {@code application.yml}：</p>
 * <ul>
 *   <li>{@code llm.model-capabilities.reasoning-model-prefixes} —— 新推理模型前缀
 *       （命中后剔除 temperature、改用大额度输出）；</li>
 *   <li>{@code llm.model-capabilities.vision-rules.<providerCode>} —— 视觉支持与否；</li>
 *   <li>{@code llm.model-capabilities.default-allow-vision} —— 无规则时的兜底（默认宽松放行）。</li>
 * </ul>
 * <p>前端副本在 {@code provider-capability.ts} 的 {@code VISION_TEMPLATES / visionStateOf}，
 * 两边判定必须一致，否则会出现「界面说能发图、后端却拒绝」或反之。</p>
 */
@Slf4j
public final class ModelCapabilityRules {

    /** 内置默认：推理模型前缀（会拒绝 temperature、用 max_completion_tokens 大额度） */
    private static final List<String> DEFAULT_REASONING_PREFIXES =
            List.of("o1", "o3", "o4", "gpt-5", "gpt5", "gpt-6", "gpt6");

    /** 内置默认：支持 reasoning_effort=none（可显式关闭思考）的前缀 */
    private static final List<String> DEFAULT_EFFORT_NONE_PREFIXES =
            List.of("gpt-5", "gpt5", "gpt-6", "gpt6");

    /** 内置默认：支持 reasoning_effort=minimal 的前缀 */
    private static final List<String> DEFAULT_EFFORT_MINIMAL_PREFIXES =
            List.of("gpt-5", "gpt5");

    /** 内置默认：普通模型输出上限 */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;

    /** 内置默认：推理模型输出上限（含思考链，需更大） */
    private static final int DEFAULT_REASONING_MAX_OUTPUT_TOKENS = 16384;

    private static volatile List<String> reasoningModelPrefixes = DEFAULT_REASONING_PREFIXES;
    private static volatile List<String> effortNonePrefixes = DEFAULT_EFFORT_NONE_PREFIXES;
    private static volatile List<String> effortMinimalPrefixes = DEFAULT_EFFORT_MINIMAL_PREFIXES;
    private static volatile int defaultMaxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
    private static volatile int reasoningMaxOutputTokens = DEFAULT_REASONING_MAX_OUTPUT_TOKENS;

    /** 视觉能力规则：Provider 编码（支持逗号分隔别名 / 前缀匹配）→ 规则 */
    private static volatile Map<String, ModelCapabilityConfig.VisionRule> visionRules =
            ModelCapabilityConfig.defaultVisionRules();

    /** 未配置规则的 Provider 是否默认允许视觉（宽松放行，由 API 层兜底报错） */
    private static volatile boolean defaultAllowVision = true;

    private ModelCapabilityRules() {
    }

    /**
     * 由 {@link ModelCapabilityConfig} 在启动时调用，把配置写入规则表
     */
    public static void configure(ModelCapabilityConfig config) {
        if (config == null) {
            return;
        }
        if (config.getReasoningModelPrefixes() != null && !config.getReasoningModelPrefixes().isEmpty()) {
            reasoningModelPrefixes = List.copyOf(config.getReasoningModelPrefixes());
        }
        if (config.getEffortNoneSupportedPrefixes() != null && !config.getEffortNoneSupportedPrefixes().isEmpty()) {
            effortNonePrefixes = List.copyOf(config.getEffortNoneSupportedPrefixes());
        }
        if (config.getEffortMinimalSupportedPrefixes() != null && !config.getEffortMinimalSupportedPrefixes().isEmpty()) {
            effortMinimalPrefixes = List.copyOf(config.getEffortMinimalSupportedPrefixes());
        }
        if (config.getDefaultMaxOutputTokens() > 0) {
            defaultMaxOutputTokens = config.getDefaultMaxOutputTokens();
        }
        if (config.getReasoningMaxOutputTokens() > 0) {
            reasoningMaxOutputTokens = config.getReasoningMaxOutputTokens();
        }
        // 视觉能力规则（null / 空表时保留内置默认，避免误把"未配置"当成"全部禁止"）
        if (config.getVisionRules() != null && !config.getVisionRules().isEmpty()) {
            visionRules = Map.copyOf(config.getVisionRules());
        }
        defaultAllowVision = config.isDefaultAllowVision();

        log.info("模型能力规则已加载: 推理模型前缀={}, 支持 none 的前缀={}, 支持 minimal 的前缀={}, "
                        + "输出上限默认={}/推理={}, 视觉规则 Provider 数={}, 未配置 Provider 默认允许视觉={}",
                reasoningModelPrefixes, effortNonePrefixes, effortMinimalPrefixes,
                defaultMaxOutputTokens, reasoningMaxOutputTokens,
                visionRules.size(), defaultAllowVision);
    }

    /**
     * 是否为推理模型（拒绝 temperature、使用 max_completion_tokens 大额度）
     *
     * @param modelKey 已归一化的模型名（{@code /} 之后最后一段、小写）；未归一也可，内部会再归一
     */
    public static boolean isReasoningModel(String modelKey) {
        return matchesAny(normalize(modelKey), reasoningModelPrefixes);
    }

    /**
     * 是否支持 {@code reasoning_effort=none}（可显式关闭思考）
     * <p>旧方法名 {@code isGpt5Family} 的语义已演化为本方法。</p>
     */
    public static boolean supportsEffortNone(String modelKey) {
        return matchesAny(normalize(modelKey), effortNonePrefixes);
    }

    /**
     * 是否支持 {@code reasoning_effort=minimal}（比 low 更浅的档）
     */
    public static boolean supportsEffortMinimal(String modelKey) {
        return matchesAny(normalize(modelKey), effortMinimalPrefixes);
    }

    /**
     * 输出上限（{@code max_completion_tokens}）
     */
    public static int maxOutputTokens(boolean reasoningModel) {
        return reasoningModel ? reasoningMaxOutputTokens : defaultMaxOutputTokens;
    }

    // ==================== 视觉（图像输入）能力 ====================

    /**
     * 模型是否支持图像输入（视觉能力）
     * <p>
     * 判定来源：{@code llm.model-capabilities.vision-rules}（配置驱动）。
     * 旧实现把各 Provider 的视觉模型判定硬编码在 {@code FileVisionService} 里，
     * 新模型上线（如 Qwen4-VL、MiMo v3）必须改代码；现在只需改配置。
     * </p>
     * <p>判定顺序：模型名为空 → 放行；命中 exclude（关键词/前缀）→ 不支持；
     * 命中 include 关键词 → 支持；都未命中 → 该 Provider 规则的 defaultAllow；
     * 该 Provider 无规则 → 全局 defaultAllowVision。</p>
     *
     * @param providerCode Provider 编码（如 deepseek / volcengine / qwen / minimax）
     * @param model        模型名（大小写不敏感）
     */
    public static boolean supportsVisionModel(String providerCode, String model) {
        if (model == null || model.isBlank()) {
            return true;   // 未知模型放行，避免误伤
        }
        String m = model.trim().toLowerCase(Locale.ROOT);
        String code = providerCode == null ? "" : providerCode.trim().toLowerCase(Locale.ROOT);

        ModelCapabilityConfig.VisionRule rule = findVisionRule(code);
        if (rule == null) {
            return defaultAllowVision;
        }
        // 排除项优先（如方舟上的 deepseek 文本模型、MiniMax 的 M2.x 纯文本）
        if (containsAny(m, rule.getExcludeKeywords()) || startsWithAny(m, rule.getExcludePrefixes())) {
            return false;
        }
        if (containsAny(m, rule.getIncludeKeywords())) {
            return true;
        }
        return rule.isDefaultAllow();
    }

    /**
     * 按 Provider 编码查视觉规则
     * <p>key 支持逗号分隔别名（{@code "qwen,dashscope,tongyi"}）与前缀匹配（{@code minimax} 命中 {@code minimax-coding}）。</p>
     */
    public static ModelCapabilityConfig.VisionRule findVisionRule(String providerCode) {
        Map<String, ModelCapabilityConfig.VisionRule> rules = visionRules;
        if (providerCode == null || providerCode.isBlank() || rules == null || rules.isEmpty()) {
            return null;
        }
        String code = providerCode.trim().toLowerCase(Locale.ROOT);

        // 1) 精确匹配
        ModelCapabilityConfig.VisionRule exact = rules.get(code);
        if (exact != null) {
            return exact;
        }
        // 2) 别名组 / 前缀匹配
        for (Map.Entry<String, ModelCapabilityConfig.VisionRule> entry : rules.entrySet()) {
            String keyGroup = entry.getKey();
            if (keyGroup == null || keyGroup.isBlank()) {
                continue;
            }
            for (String alias : keyGroup.split(",")) {
                String key = alias.trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty() && code.startsWith(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            if (text.contains(kw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String text, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String pfx : prefixes) {
            if (pfx == null || pfx.isBlank()) {
                continue;
            }
            if (text.startsWith(pfx.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 模型名归一：取最后一段（兼容 {@code openai/gpt-5}、{@code azure/o3-mini} 这类前缀写法）并转小写
     */
    public static String normalize(String model) {
        if (model == null) {
            return "";
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /**
     * 重置为内置默认（仅供单元测试使用）
     */
    public static void resetToDefaults() {
        reasoningModelPrefixes = DEFAULT_REASONING_PREFIXES;
        effortNonePrefixes = DEFAULT_EFFORT_NONE_PREFIXES;
        effortMinimalPrefixes = DEFAULT_EFFORT_MINIMAL_PREFIXES;
        defaultMaxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
        reasoningMaxOutputTokens = DEFAULT_REASONING_MAX_OUTPUT_TOKENS;
        visionRules = ModelCapabilityConfig.defaultVisionRules();
        defaultAllowVision = true;
    }

    private static boolean matchesAny(String modelKey, List<String> prefixes) {
        if (modelKey == null || modelKey.isBlank() || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            if (modelKey.startsWith(prefix.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
