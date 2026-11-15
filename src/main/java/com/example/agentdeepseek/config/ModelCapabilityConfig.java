package com.example.agentdeepseek.config;

import com.example.agentdeepseek.service.llm.ModelCapabilityRules;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile;
import com.example.agentdeepseek.service.llm.profile.ProviderRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型能力声明（配置驱动，替代硬编码模型名白名单）
 *
 * <p><b>解决什么问题</b>：{@code GptClient} / {@code OpenAIClient} 里原本用
 * {@code startsWith("o1"/"gpt-5")} 这类硬编码判断推理模型。每上一个新模型（如 {@code gpt-6}）
 * 都要改代码发版，否则会：输出上限被压到 8192（思考链被截断）、{@code non-thinking} 关不掉、
 * {@code minimal} 档失效、{@code temperature} 不被剔除而触发官方拒绝。</p>
 *
 * <p><b>怎么用</b>：在 {@code application.yml} 里声明模型名前缀即可，新增模型只改配置：</p>
 * <pre>
 * llm:
 *   model-capabilities:
 *     reasoning-model-prefixes: ["o1", "o3", "o4", "gpt-5", "gpt-6"]
 *     effort-none-supported-prefixes: ["gpt-5", "gpt-6"]
 *     effort-minimal-supported-prefixes: ["gpt-5"]
 *     default-max-output-tokens: 8192
 *     reasoning-max-output-tokens: 16384
 * </pre>
 *
 * <p>匹配方式是<b>前缀匹配</b>（模型名已归一为 {@code /} 之后最后一段并转小写），
 * 因此配置 {@code gpt-6} 可同时命中 {@code gpt-6}、{@code gpt-6-mini}、{@code gpt-6-turbo} 等。</p>
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "llm.model-capabilities")
public class ModelCapabilityConfig {

    /**
     * 推理模型前缀（命中后：剔除 temperature、使用 max_completion_tokens 大额度）
     */
    private List<String> reasoningModelPrefixes = new ArrayList<>(
            List.of("o1", "o3", "o4", "gpt-5", "gpt5", "gpt-6", "gpt6"));

    /**
     * 支持 {@code reasoning_effort=none}（可显式关闭思考）的模型前缀
     */
    private List<String> effortNoneSupportedPrefixes = new ArrayList<>(
            List.of("gpt-5", "gpt5", "gpt-6", "gpt6"));

    /**
     * 支持 {@code reasoning_effort=minimal}（比 low 更浅档）的模型前缀
     */
    private List<String> effortMinimalSupportedPrefixes = new ArrayList<>(
            List.of("gpt-5", "gpt5"));

    /**
     * 普通模型默认输出上限（{@code max_completion_tokens}）
     */
    private int defaultMaxOutputTokens = 8192;

    /**
     * 推理模型默认输出上限（含思考链，需要更大）
     */
    private int reasoningMaxOutputTokens = 16384;

    /**
     * 视觉（图像输入）能力规则表
     * <p>
     * key 为 Provider 编码，支持<b>逗号分隔多个别名</b>（如 {@code "qwen,dashscope,tongyi"}），
     * 也支持<b>前缀匹配</b>（配置 {@code minimax} 可命中 {@code minimax-coding-plan}）。
     * 未命中任何 key 的 Provider 走 {@link #defaultAllowVision}（宽松放行，由 API 层兜底报错）。
     * </p>
     */
    private Map<String, VisionRule> visionRules = defaultVisionRules();

    /**
     * 未配置规则的 Provider 是否默认允许图像输入（宽松放行 = true）
     */
    private boolean defaultAllowVision = true;

    /**
     * 视觉能力规则
     */
    @Data
    public static class VisionRule {
        /** 命中任一关键词（contains，小写）→ 支持视觉 */
        private List<String> includeKeywords = new ArrayList<>();
        /** 命中任一关键词（contains，小写）→ 不支持视觉（优先级高于 include） */
        private List<String> excludeKeywords = new ArrayList<>();
        /** 命中任一前缀（startsWith，小写）→ 不支持视觉（优先级高于 include） */
        private List<String> excludePrefixes = new ArrayList<>();
        /** 该 Provider 在"include / exclude 都未命中"时的兜底结果 */
        private boolean defaultAllow = true;
    }

    /**
     * 内置默认视觉规则 —— <b>直接从 {@link ProviderRegistry} 派生</b>（不再在本类硬编码一份）。
     *
     * <h3>为什么改成派生（T5）</h3>
     * <p>
     * 改动前同一套视觉规则存在<b>三份副本</b>：
     * </p>
     * <ol>
     *   <li>{@code application.yml} 的 {@code llm.model-capabilities.vision-rules}；</li>
     *   <li><b>本方法</b>里的硬编码默认值（与 ① 内容完全重复）；</li>
     *   <li>前端 {@code provider-capability.ts} 的 {@code visionStateOf}（5 套正则）。</li>
     * </ol>
     * <p>
     * 后果是「改一处、漏两处」，而漏掉不报错 —— 表现为「界面说能发图，实际图片被静默丢弃」
     * （火山 {@code deepseek-v4-pro} 就是这么踩出来的）。
     * 现在默认值只有 {@link ProviderRegistry} 一处，本方法只做「{@code VisionSpec} → {@code VisionRule}」的形状转换。
     * </p>
     *
     * <h3>yml 的定位：可选覆盖层</h3>
     * <p>
     * {@code application.yml} 里的 {@code vision-rules} 仍然有效，但语义从「唯一配置」变为
     * <b>「需要临时覆盖某平台规则时的可选覆盖层」</b>：</p>
     * <ul>
     *   <li>yml <b>不配</b>或配空 → 用本方法产出的 Registry 默认值（推荐）；</li>
     *   <li>yml <b>配了</b> → 以 yml 为准（{@link ModelCapabilityRules#configure} 会整体替换）。</li>
     * </ul>
     * <p>
     * ⚠️ 注意 yml 是<b>整体替换</b>而非逐项合并：配了就要把需要保留的平台全写上，
     * 否则没写的平台会退回「全局 defaultAllowVision」。
     * </p>
     *
     * <h3>顺序必须保持</h3>
     * <p>{@link ModelCapabilityRules#findVisionRule} 做的是<b>前缀匹配</b>且按 map 插入顺序遍历，
     * 因此这里必须按 {@code ProviderRegistry.all()} 的顺序产出（deepseek → 火山 → mimo → minimax → qwen …），
     * 否则 {@code minimax} 与 {@code mimo} 这类同前缀的 code 可能命中不同规则。</p>
     */
    public static Map<String, VisionRule> defaultVisionRules() {
        Map<String, VisionRule> rules = new LinkedHashMap<>();
        for (ProviderProfile profile : ProviderRegistry.all()) {
            ProviderProfile.VisionSpec vision = profile.getVision();
            if (vision == null || !vision.isHasModelRule()) {
                continue;   // 无模型级规则的平台（gpt/openai/anthropic/ollama/custom）走全局 defaultAllowVision
            }
            VisionRule rule = new VisionRule();
            rule.setIncludeKeywords(new ArrayList<>(vision.getIncludeKeywords()));
            rule.setExcludeKeywords(new ArrayList<>(vision.getExcludeKeywords()));
            rule.setExcludePrefixes(new ArrayList<>(vision.getExcludePrefixes()));
            rule.setDefaultAllow(vision.isDefaultAllow());
            // key 用「主名 + 全部别名」逗号拼接，与改动前的 key 形式一致（如 volcengine,ark,doubao），
            // 保证 findVisionRule 的前缀匹配行为不变
            rules.put(String.join(",", profile.allNames()), rule);
        }
        return rules;
    }

    /**
     * 启动时把配置写入 {@link ModelCapabilityRules}（适配器通过静态方法查询，避免逐处注入）
     */
    @PostConstruct
    public void init() {
        ModelCapabilityRules.configure(this);
    }
}
