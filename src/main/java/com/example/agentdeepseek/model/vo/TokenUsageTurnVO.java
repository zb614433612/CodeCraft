package com.example.agentdeepseek.model.vo;

import lombok.Data;

/**
 * 单轮对话的用量汇总 VO（一次用户提问 = 一轮）
 * <p>
 * 一次提问在工具循环里可能发起多次 API 请求（每次一个 {@code iteration}），
 * 这里按 {@code turn_id} 聚合，让聊天页能在每条 AI 回复下展示
 * 「本轮花了多少钱 / 缓存命中率 / token 总量」。
 * </p>
 * <p>
 * 与 {@link TokenUsageSummaryVO} 的区别：那个是「时间范围 / 整个会话」口径，
 * 这个只覆盖一轮，且没有三段费用拆分（轮内看成本结构意义不大）。
 * </p>
 */
@Data
public class TokenUsageTurnVO {

    /** 轮次 ID（前端生成的 turnId，与消息上的 turnId 一致） */
    private String turnId;

    /** 本轮请求次数（工具循环发起过几次 API 调用） */
    private long requestCount;

    /** 输入 token 总数（命中 + 未命中） */
    private long promptTokens;

    /** 输出 token 总数（含思考链） */
    private long completionTokens;

    /** 输入 + 输出总 token */
    private long totalTokens;

    /** 缓存命中输入 token */
    private long cacheHitTokens;

    /** 缓存未命中输入 token */
    private long cacheMissTokens;

    /** 思考链 token（已包含在 completionTokens 内） */
    private long reasoningTokens;

    /** 估算费用（元，落库时已按单价算好，这里直接累加） */
    private double costEstimate;

    /**
     * 本轮「有单价、可估算成本」的请求条数。
     * <p>
     * 与 {@link #requestCount} 一起判断金额是否可信：
     * <ul>
     *   <li>{@code pricedRequestCount == 0} → 该模型未配置单价，前端<b>不显示金额</b>（只显示 token / 缓存）</li>
     *   <li>{@code 0 < pricedRequestCount < requestCount} → 部分轮次无单价，金额是下限</li>
     * </ul>
     * </p>
     */
    private long pricedRequestCount;

    /** 缓存命中率（0~1）；-1 表示 Provider 未返回缓存字段，无数据 */
    private double cacheHitRate;
}
