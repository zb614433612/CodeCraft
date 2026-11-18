package com.example.agentdeepseek.model.vo;

import lombok.Data;

/**
 * 按「模型 + 计价时段」分组的 token 汇总（费用结构拆分专用）。
 *
 * <h3>为什么需要它</h3>
 * <p>
 * 成本看板的「费用结构」三段条（命中输入 / 未命中输入 / 输出）原本用<b>单一参照价</b>
 * 把整个范围的所有 token 重算一遍。峰谷计价上线后，同一个范围里同时存在
 * 高峰记录与空闲记录，用单一价算出来的结构必然失真。
 * </p>
 * <p>
 * 因此改为：先按 {@code (model, pricing_period)} 分组取真实 token 量，
 * 每组用<b>该组时段的单价</b>算三段，最后累加 —— 组内不存在跨时段混算。
 * </p>
 */
@Data
public class TokenUsagePeriodBreakdownVO {

    /** 模型名 */
    private String model;

    /** 计价时段：{@code peak} / {@code off-peak}（历史数据按 off-peak 归一） */
    private String pricingPeriod;

    /** 缓存命中输入 token */
    private long cacheHitTokens;

    /** 缓存未命中输入 token */
    private long cacheMissTokens;

    /** 输出 token（含思考链） */
    private long completionTokens;
}
