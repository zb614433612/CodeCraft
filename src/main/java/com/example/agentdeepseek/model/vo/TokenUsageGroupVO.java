package com.example.agentdeepseek.model.vo;

import lombok.Data;

/**
 * LLM 用量分组统计 VO
 * <p>
 * 用于「按模型」「按会话」两种分组维度：{@code groupKey} 在按模型时是模型名，
 * 按会话时是 conversationId 的字符串形式。
 * </p>
 */
@Data
public class TokenUsageGroupVO {

    /** 分组键：模型名 或 会话ID（字符串） */
    private String groupKey;

    /** 分组显示名（按会话时由服务层填充会话名，按模型时与 groupKey 相同） */
    private String groupName;

    /** 请求次数 */
    private long requestCount;

    /** 输入 token 总数 */
    private long promptTokens;

    /** 输出 token 总数 */
    private long completionTokens;

    /** 输入 + 输出总 token */
    private long totalTokens;

    /** 缓存命中输入 token */
    private long cacheHitTokens;

    /** 缓存未命中输入 token */
    private long cacheMissTokens;

    /** 估算费用（元） */
    private double costEstimate;

    /** 缓存命中率（0~1）；-1 表示无数据 */
    private double cacheHitRate;
}
