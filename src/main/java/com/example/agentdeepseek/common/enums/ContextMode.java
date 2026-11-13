package com.example.agentdeepseek.common.enums;

import java.util.Locale;

/**
 * 上下文模式（主对话历史注入策略）
 * <p>
 * <b>设计意图</b>：把「是否回传历史 reasoning」与「是否摘要历史工具结果」两个成本杠杆
 * 收敛成一个用户可理解的三档选项，而不是两个互相干扰的独立开关。
 * </p>
 *
 * <table border="1">
 *   <caption>三档差异</caption>
 *   <tr><th>模式</th><th>历史 reasoning</th><th>历史工具结果</th><th>适用</th></tr>
 *   <tr>
 *     <td>{@link #FULL}（全量）</td>
 *     <td>回传</td>
 *     <td>原样保留</td>
 *     <td>准确性最高、成本最高。需要模型复述"当时为什么这么想"的场景</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #BALANCED}（均衡，默认）</td>
 *     <td><b>不回传</b></td>
 *     <td>原样保留</td>
 *     <td>推荐。reasoning 双重计费最贵且对后续决策价值低；工具结果是决策证据，必须留</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #COMPACT}（精简）</td>
 *     <td><b>不回传</b></td>
 *     <td><b>摘要化</b></td>
 *     <td>救急用：上下文逼近模型上限时。注意摘要化会改写历史 → 前缀缓存失效一次</td>
 *   </tr>
 * </table>
 *
 * <p><b>成本背景</b>：reasoning 生成按输出计价（约 6 元/百万），下一轮作为历史回传时
 * 又按「未命中输入」再计一次（约 3 元/百万），合计约 9 元/百万 —— 是所有内容类型中单价最高的；
 * 而工具结果在命中缓存后仅约 0.025 元/百万，因此 BALANCED 只砍 reasoning、保工具结果，
 * 是「省钱但不丢准确性」的最优切分点。</p>
 */
public enum ContextMode {

    /** 全量：历史 reasoning 也回传（成本最高） */
    FULL("full", "全量", false, false),

    /** 均衡（默认）：剔除历史 reasoning，保留全部工具结果 */
    BALANCED("balanced", "均衡", true, false),

    /** 精简：剔除历史 reasoning + 摘要化历史工具结果（救急模式） */
    COMPACT("compact", "精简", true, true);

    /** 默认模式 */
    public static final ContextMode DEFAULT = BALANCED;

    /** 配置/接口取值（小写） */
    private final String value;

    /** 展示名 */
    private final String label;

    /** 是否剔除历史 reasoning_content（不下发给模型） */
    private final boolean stripHistoryReasoning;

    /** 是否摘要化历史工具结果（改写历史，会导致一次前缀缓存失效） */
    private final boolean compactToolResults;

    ContextMode(String value, String label, boolean stripHistoryReasoning, boolean compactToolResults) {
        this.value = value;
        this.label = label;
        this.stripHistoryReasoning = stripHistoryReasoning;
        this.compactToolResults = compactToolResults;
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    /** 是否不在请求中回传历史 reasoning_content */
    public boolean shouldStripHistoryReasoning() {
        return stripHistoryReasoning;
    }

    /** 是否摘要化历史工具结果（仅 COMPACT） */
    public boolean shouldCompactToolResults() {
        return compactToolResults;
    }

    /**
     * 容错解析（别名兼容 + 未知值回退默认）
     * <p>
     * 兼容旧配置：{@code strip-reasoning} / {@code no-reasoning} / {@code lean} 等价于 BALANCED；
     * {@code all} / {@code none}（全量语义）等价于 FULL。
     * </p>
     *
     * @param raw 配置值、接口入参（可为 null / 空 / 大小写混杂）
     * @return 解析结果；无法识别时返回 {@link #DEFAULT}
     */
    public static ContextMode from(String raw) {
        return from(raw, DEFAULT);
    }

    /**
     * 容错解析（可指定回退值）
     */
    public static ContextMode from(String raw, ContextMode fallback) {
        ContextMode resolved = tryParse(raw);
        return resolved != null ? resolved : fallback;
    }

    /**
     * 尝试解析，无法识别返回 {@code null}（供调用方区分"未识别"与"默认"以便记日志）
     */
    public static ContextMode tryParse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        return switch (key) {
            // ===== 全量 =====
            case "full", "all", "keep", "keep-all", "keep_all" -> FULL;
            // ===== 均衡（剔除 reasoning，保留工具结果）=====
            case "balanced", "balance", "lean", "strip-reasoning", "strip_reasoning",
                 "no-reasoning", "no_reasoning" -> BALANCED;
            // ===== 精简（额外摘要工具结果）=====
            case "compact", "compacted", "slim", "minimal" -> COMPACT;
            default -> null;
        };
    }

    /**
     * 转为小写字符串（用于日志、接口回显）
     */
    public String value() {
        return value;
    }
}
