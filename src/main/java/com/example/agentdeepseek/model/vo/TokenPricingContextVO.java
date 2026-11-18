package com.example.agentdeepseek.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 峰谷计价上下文（前端展示用）。
 *
 * <h3>为什么需要这个接口</h3>
 * <p>
 * 峰谷计价上线后，同一个任务在「高峰」与「空闲」时段的金额可能相差数倍。
 * 如果界面只显示一个数字、不说明「现在处于哪个时段」，用户会怀疑统计坏了，
 * 或者误以为「换个模型就贵了」。本 VO 把「当前时段 / 下次切换时间 /
 * 各模型两档单价」一次性给到前端，让金额变化变成<b>可解释</b>的。
 * </p>
 *
 * <h3>语义约定</h3>
 * <ul>
 *   <li>{@code enabled=false} 时，{@code period / nextSwitchAt / windows} 一律为 null，
 *       前端不展示任何时段相关 UI（该平台全天一个价）。</li>
 *   <li>{@code period} 取值为 {@code peak} / {@code off-peak}。</li>
 *   <li>{@code models} 只列出<b>显式配置了单价</b>的模型；未配单价的模型不出现在这里。</li>
 * </ul>
 */
@Data
public class TokenPricingContextVO {

    /** 判定时区（如 {@code Asia/Shanghai}） */
    private String zone;

    /** 服务器当前时刻（ISO-8601，带时区） */
    private String now;

    /** 当前时段：{@code peak} / {@code off-peak} / {@code null}（未启用峰谷） */
    private String period;

    /** 下次峰↔谷切换时刻（ISO-8601）；未启用或 8 天内无切换时为 null */
    private String nextSwitchAt;

    /** 高峰窗口列表（{@code HH:mm-HH:mm}，左闭右开） */
    private List<String> windows = new ArrayList<>();

    /** 峰谷计价是否生效（总开关 + 平台档案 + 模型配置三者皆满足） */
    private boolean enabled;

    /** 兜底倍率（模型未单独配高峰价时，高峰价 = 基准价 × 本值） */
    private double peakMultiplier;

    /** 各模型的空闲价 / 高峰价 */
    private List<ModelPrice> models = new ArrayList<>();

    /** 单模型的价位信息 */
    @Data
    public static class ModelPrice {
        /** 模型名（配置键） */
        private String model;

        /** 空闲时段单价 */
        private Price offPeak;

        /** 高峰时段单价（未启用峰谷时与 offPeak 相同） */
        private Price peak;

        /** 当前生效档位：{@code peak} / {@code off-peak} */
        private String effective;
    }

    /** 三档单价（元 / 百万 tokens） */
    @Data
    public static class Price {
        /** 缓存命中输入单价 */
        private double cacheHitInput;
        /** 缓存未命中输入单价 */
        private double cacheMissInput;
        /** 输出单价 */
        private double output;
    }
}
