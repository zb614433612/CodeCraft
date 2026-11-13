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
     * 写入「本轮工具循环」的 assistant 消息：<b>无条件</b>回传真实思考内容。
     * <p>
     * 用于刚由模型生成、马上要带着 tool 结果继续下一轮迭代的 assistant 消息。
     * 该消息是思维链的「当前一环」，置空会让模型丢失刚刚的推理上下文。
     * </p>
     *
     * @param message   目标消息（调用方需已设置 {@code role=assistant}）
     * @param reasoning 模型本轮返回的思考内容；为 {@code null} 时写入空串（字段不能缺）
     */
    public static void putCurrentTurn(Map<String, Object> message, String reasoning) {
        if (message == null) {
            return;
        }
        message.put(FIELD, reasoning == null ? "" : reasoning);
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
