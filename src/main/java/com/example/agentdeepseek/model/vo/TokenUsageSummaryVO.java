package com.example.agentdeepseek.model.vo;

import lombok.Data;

/**
 * LLM 用量汇总 VO（会话维度 / 时间维度通用）
 * <p>
 * 直接由 SQL 聚合返回（下划线别名自动映射驼峰），派生字段由服务层按单价表填充。
 * </p>
 */
@Data
public class TokenUsageSummaryVO {

    /** 请求次数（= 工具循环总轮次 + 独立请求数） */
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

    /** 估算费用（元，SQL 侧已按记录内的 cost_estimate 累加；无单价记录不计入） */
    private double costEstimate;

    /**
     * 「有单价、可估算成本」的请求条数。
     * <p>
     * 用它区分「真的花了 0 元」和「不知道多少钱」：
     * <ul>
     *   <li>{@code pricedRequestCount == 0 && requestCount > 0} → 金额不可用，前端显示「—」</li>
     *   <li>{@code pricedRequestCount < requestCount} → 部分请求未配置单价，金额是下限</li>
     * </ul>
     * </p>
     */
    private long pricedRequestCount;

    // ==================== 派生字段（服务层计算） ====================

    /** 缓存命中率（0~1）；-1 表示 Provider 未返回缓存字段，无数据 */
    private double cacheHitRate;

    /** 命中部分估算费用（元） */
    private double cacheHitCost;

    /** 未命中部分估算费用（元） */
    private double cacheMissCost;

    /** 输出部分估算费用（元） */
    private double outputCost;

    /**
     * 三段费用拆分是否可用。
     * <p>
     * 为 {@code false} 时（统计范围内没有任何模型配置单价）三段费用恒为 0，
     * 前端应隐藏「费用结构」图，避免显示一条全 0 的假结构。
     * </p>
     */
    private boolean breakdownAvailable;
}
