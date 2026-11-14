package com.example.agentdeepseek.config;

import com.example.agentdeepseek.service.llm.ModelCapabilityRules;
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
     * 内置默认视觉规则（与历史硬编码行为完全等价，升级时零行为变化）
     * <p>公开给 {@link ModelCapabilityRules} 在"配置未注入"（如单元测试）时作为兜底。</p>
     */
    public static Map<String, VisionRule> defaultVisionRules() {
        Map<String, VisionRule> rules = new LinkedHashMap<>();

        // DeepSeek 官方：flash / vision / vl 系列支持图像理解，chat / reasoner 不支持
        VisionRule deepseek = new VisionRule();
        deepseek.setIncludeKeywords(new ArrayList<>(List.of("flash", "vision", "-vl")));
        deepseek.setDefaultAllow(false);
        rules.put("deepseek", deepseek);

        // 火山方舟：方舟上的 deepseek 系已分化，不能用 "deepseek" 前缀一刀切（会误伤多模态版本）。
        // 实测（2026-11，/api/coding/v3 直接发 image_url 块）：
        //   · deepseek-v4.1-flash          → 支持，图片真实计入 prompt_tokens（35 → 224）
        //   · deepseek-v4.1-flash-economy  → 支持，图片真实计入 prompt_tokens（218）
        //   · deepseek-v4-pro              → 不支持，prompt_tokens 仅 86，模型自称「没有图片提供」
        //   · deepseek-v4-flash            → 不支持（官方未声明多模态，图片被静默丢弃）
        // 因此用「版本号 + 排除纯文本支」的组合：include 命中 v4.1，exclude 挡住 v4-pro / v4-flash。
        // ⚠ excludeKeywords 优先于 include，且 "v4.1-flash" 不含连续子串 "v4-flash"，故 economy 版不会被误伤。
        // 豆包 seed / glm-5.3-flash（原生多模态）/ kimi-k3.1-pro（多模态旗舰）等由 defaultAllow 宽松放行。
        VisionRule ark = new VisionRule();
        ark.setIncludeKeywords(new ArrayList<>(List.of("vision", "-vl", "vlm", "v4.1")));
        ark.setExcludeKeywords(new ArrayList<>(List.of("v4-pro", "v4-flash")));
        ark.setDefaultAllow(true);
        rules.put("volcengine,ark,doubao", ark);

        // 小米 MiMo：v2.5 系列与 Omni（全模态）支持，更早纯文本模型不支持
        VisionRule mimo = new VisionRule();
        mimo.setIncludeKeywords(new ArrayList<>(List.of("v2.5", "vision", "-vl", "omni")));
        mimo.setDefaultAllow(false);
        rules.put("mimo,xiaomi-mimo", mimo);

        // MiniMax：M3 与 VL / abab-vl 支持；M2.x 纯文本
        VisionRule minimax = new VisionRule();
        minimax.setIncludeKeywords(new ArrayList<>(List.of("m3", "vision", "-vl", "abab")));
        minimax.setExcludeKeywords(new ArrayList<>(List.of("-m2")));
        minimax.setExcludePrefixes(new ArrayList<>(List.of("m2")));
        minimax.setDefaultAllow(false);
        rules.put("minimax", minimax);

        // 通义千问：Qwen-VL / Omni 系列支持；Plus 系列自 qwen3.6-plus 起官方声明支持图片理解
        // （百炼文档原文：「推荐模型：qwen3.7-plus（支持图片理解）、qwen3.6-plus（支持图片理解）」）。
        // 旧注释所说「qwen-plus 一律纯文本」已随模型换代失效，故补 plus 关键词；
        // qwen-max 各代差异较大，不确定时不放行（避免"界面说能发图、实际图片被丢弃"）。
        VisionRule qwen = new VisionRule();
        qwen.setIncludeKeywords(new ArrayList<>(List.of("-vl", "vl-", "omni", "vision", "plus")));
        qwen.setDefaultAllow(false);
        rules.put("qwen,dashscope,tongyi", qwen);

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
