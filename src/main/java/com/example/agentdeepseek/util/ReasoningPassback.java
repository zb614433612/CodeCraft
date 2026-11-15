package com.example.agentdeepseek.util;

import com.example.agentdeepseek.common.enums.ContextMode;

import java.util.Map;

/**
 * assistant 消息 {@code reasoning_content} 回传策略（全局唯一收口点）
 *
 * <h3>为什么必须有这个类</h3>
 * <p>
 * DeepSeek 在 <b>thinking 模式</b>（尤其请求体携带 {@code tools} 时）会对历史消息做<b>强校验</b>：
 * 只要有一条 assistant 消息缺少 {@code reasoning_content} 字段，就直接返回 400：
 * </p>
 * <pre>
 * The `reasoning_content` in the thinking mode must be passed back to the API.
 * </pre>
 * <p>
 * 官方文档：<a href="https://api-docs.deepseek.com/guides/thinking_mode">guides/thinking_mode</a>
 * </p>
 *
 * <h3>关键认知</h3>
 * <p>
 * 「字段是否出现」<b>不是可选项</b>，「字段值是什么」才是可优化的：
 * </p>
 * <ul>
 *   <li>实测与业界一致结论：<b>空字符串也能通过校验</b>（400 只在字段缺失时触发）。
 *       空串的 token 成本约为 3~5 个 token，相比回传整段思考链（动辄数千 token，且按
 *       「未命中输入」计价约 3 元/百万）几乎为零。</li>
 *   <li>因此成本优化的正确形态是「<b>置空占位</b>」而不是「<b>不下发</b>」。</li>
 * </ul>
 *
 * <h3>两档取值</h3>
 * <table border="1">
 *   <caption>取值规则</caption>
 *   <tr><th>场景</th><th>字段值</th><th>理由</th></tr>
 *   <tr>
 *     <td>本轮刚生成、即将续跑工具循环的 assistant(tool_calls)</td>
 *     <td><b>真实思考内容</b></td>
 *     <td>思维链要在多步工具调用之间连续，置空会削弱模型的推理衔接</td>
 *   </tr>
 *   <tr>
 *     <td>历史 assistant 消息（从 DB 重建 / 上一轮对话）</td>
 *     <td>由 {@link ContextMode} 决定：full=真实值，balanced/compact=空串</td>
 *     <td>历史思考链对后续决策参考价值低，置空即可满足校验</td>
 *   </tr>
 * </table>
 *
 * <p><b>注意</b>：{@code reasoning_content} 为空的 assistant 消息同样要写入空串 ——
 * 模型在长工具链后段有时只返回裸 tool_call、不带任何思考内容，
 * 此时「字段缺失」一样会触发 400。</p>
 */
public final class ReasoningPassback {

    /** 字段名常量：避免各处字符串字面量漂移 */
    public static final String FIELD = "reasoning_content";

    private ReasoningPassback() {
    }

    /**
     * 写入「本轮工具循环」的 assistant 消息：<b>与历史同一套取值规则</b>（推荐）。
     *
     * <h3>⚠️ 为什么当前轮也必须按 mode 取值（前缀缓存一致性）</h3>
     * <p>
     * DeepSeek 的上下文缓存按「请求前缀」命中：只有<b>本次请求的 prompt 以某次已缓存请求的
     * prompt 为前缀</b>时才能复用。而「本轮 assistant」在下一轮会被 {@link #putHistory}
     * 重新下发一次 —— 如果两次取值不同（本轮真实思考链、下轮空串），
     * 那么<b>下一次请求的 prompt 就不再以本次请求为前缀</b>，从差异点起全部按「未命中输入」
     * 重新 prefill（单价之差可达数十倍，长上下文时表现为首个 token 等十几秒到几十秒）。
     * </p>
     * <p>
     * 实测数据（本项目 conversation 129）：同一轮内相邻请求命中率 97%+，跨轮第一条请求
     * 反复出现 {@code cache_hit_tokens = 0}，且 miss 量恰好等于「上一整轮工具循环产生的
     * 内容量」—— 正是本方法旧实现（无条件真实值）与 {@link #putHistory} 不一致造成的。
     * </p>
     * <p>
     * 因此取值的唯一正确口径是：<b>同一条消息在任何一次请求里都必须得到相同的值</b>。
     * 由此得到两档自洽语义：
     * </p>
     * <ul>
     *   <li>{@code full}：全部消息都下发真实思考链（缓存稳定，但每轮多付 reasoning 的未命中输入费用）；</li>
     *   <li>{@code balanced / compact}：全部消息都下发空串占位（缓存稳定，且 reasoning 不再随请求上行）。</li>
     * </ul>
     * <p>
     * 代价说明：balanced / compact 下模型在本轮工具循环中看不到自己上一步的「思考文字」，
     * 但完整的 {@code tool_calls} 与工具结果仍在上下文里，续跑依据充分。
     * 若确实需要最强推理衔接，请显式切到 {@code full}。
     * </p>
     *
     * @param message   目标消息（调用方需已设置 {@code role=assistant}）
     * @param reasoning 模型本轮返回的思考内容；为 {@code null} 时写入空串（字段不能缺）
     * @param mode      本次请求实际生效的上下文模式（务必传「请求生效值」，不要传全局配置默认值）
     */
    public static void putCurrentTurn(Map<String, Object> message, String reasoning, ContextMode mode) {
        if (message == null) {
            return;
        }
        // 与 putHistory 完全同一套判定：保证同一条消息在任何时刻取值恒定
        boolean keepRealValue = mode == null || !mode.shouldStripHistoryReasoning();
        message.put(FIELD, keepRealValue && reasoning != null ? reasoning : "");
    }

    /**
     * 兼容重载：等价于 {@code full} 语义（无条件回传真实思考内容）。
     *
     * @deprecated <b>主链路不要再用这个重载</b>。它强制真实值，一旦与「历史重建按
     *             balanced 置空」混用，就会让跨轮请求失去前缀关系、缓存全量失效
     *             （长上下文时首 token 退化到 20~30 秒）。请改用
     *             {@link #putCurrentTurn(Map, String, ContextMode)} 并传入本次请求
     *             实际生效的模式（工具循环里用 {@link #modeOf(Map)} 取）。
     *             保留它只为「显式 full 语义」与既有单测兼容。
     */
    @Deprecated
    public static void putCurrentTurn(Map<String, Object> message, String reasoning) {
        putCurrentTurn(message, reasoning, ContextMode.FULL);
    }

    /**
     * 从 API 请求内部字段（{@code _contextMode}）解析本次请求实际生效的上下文模式。
     *
     * <p>工具循环复用同一个 {@code apiRequest}，其中 {@code _contextMode} 由主链路按
     * 「请求参数 &gt; sys_config &gt; 全局配置 &gt; 默认」的优先级解析后写入。
     * 用它而不是 {@code deepSeekConfig.resolveContextMode()}，才能保证历史组装
     * （{@link com.example.agentdeepseek.service.impl.ContextBuilder}）与当前轮下发
     * 用的是<b>同一档</b>模式 —— 否则会出现「历史置空、当前轮真实值」的第三种形态。</p>
     *
     * @param apiRequest 工具循环的 apiRequest（可为 null）
     * @return 解析出的模式；无法解析时返回 {@link ContextMode#DEFAULT}
     */
    public static ContextMode modeOf(Map<String, Object> apiRequest) {
        if (apiRequest == null) {
            return ContextMode.DEFAULT;
        }
        Object raw = apiRequest.get("_contextMode");
        if (raw instanceof String text) {
            return ContextMode.from(text, ContextMode.DEFAULT);
        }
        return ContextMode.DEFAULT;
    }

    /**
     * 写入「历史」assistant 消息：按上下文模式决定字段值是真实值还是空串占位。
     *
     * @param message   目标消息（调用方需已设置 {@code role=assistant}）
     * @param reasoning DB 中保存的思考内容（可为 null / 空）
     * @param mode      生效的上下文模式；{@code null} 视为 full（保守：不丢信息）
     */
    public static void putHistory(Map<String, Object> message, String reasoning, ContextMode mode) {
        if (message == null) {
            return;
        }
        boolean keepRealValue = mode == null || !mode.shouldStripHistoryReasoning();
        message.put(FIELD, keepRealValue && reasoning != null ? reasoning : "");
    }

    /**
     * 就地剥离消息中的真实思考内容（<b>保留字段本身</b>，只把值改为空串）。
     * <p>
     * 供 compact 模式精简历史使用：不能 {@code remove} 字段，否则会重新触发 400。
     * </p>
     *
     * @param message 目标消息
     * @return {@code true} 表示确实剥离了非空的思考内容
     */
    public static boolean stripToPlaceholder(Map<String, Object> message) {
        if (message == null || !message.containsKey(FIELD)) {
            return false;
        }
        Object value = message.get(FIELD);
        if (value instanceof String text && !text.isEmpty()) {
            message.put(FIELD, "");
            return true;
        }
        return false;
    }

    /**
     * 判断消息是否为「需要补齐字段」的 assistant 消息（role=assistant 且缺字段）。
     * <p>
     * 供最终发送前的兜底净化器使用：任何路径漏写都会被补成空串，
     * 作为「400 不再复发」的最后一道防线。
     * </p>
     */
    public static boolean needsPadding(Map<String, Object> message) {
        return message != null
                && "assistant".equals(message.get("role"))
                && !message.containsKey(FIELD);
    }
}
