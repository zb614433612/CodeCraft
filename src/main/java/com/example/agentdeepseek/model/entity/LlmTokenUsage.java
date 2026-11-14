package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LLM 调用用量记录实体
 * <p>
 * 每次向模型发起一次请求（含工具循环内的每一轮）落一条，用于成本可观测：
 * <ul>
 *   <li><b>真实用量</b>：直接取 Provider 响应 usage 字段，不做估算</li>
 *   <li><b>缓存明细</b>：命中/未命中分别记账（DeepSeek 两者单价相差约 120 倍，
 *       只看总量会严重误判成本结构）</li>
 *   <li><b>思考链</b>：reasoning token 按输出计价，且下一轮会作为未命中输入再次计费
 *       （双重计费），必须单独可见</li>
 * </ul>
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LlmTokenUsage {

    /** 主键 ID */
    private Long id;

    /** 会话 ID（可空：后台任务/定时任务无归属会话时为空） */
    private Long conversationId;

    /**
     * 轮次 ID（前端生成，一次用户提问一个）。
     * <p>
     * 工具循环会把同一次提问拆成多次 API 请求（每次一个 {@code iteration}），
     * 只有靠 turnId 才能把它们归并成「这一轮对话花了多少钱／命中率多少」。
     * 后台任务、定时任务无 turnId（可空），它们只参与会话/时间维度汇总。
     * </p>
     */
    private String turnId;

    /** 关联的 assistant 消息 ID（可空：落库时尚未生成消息 ID） */
    private Long messageId;

    /** 工具循环迭代轮次（0=非工具循环或未知） */
    private int iteration;

    /** Provider 编码，如 deepseek / openai / anthropic */
    private String provider;

    /** 模型名，如 deepseek-v4-pro */
    private String model;

    /** 输入 token 总数（命中 + 未命中） */
    private int promptTokens;

    /** 输出 token 总数（含思考链） */
    private int completionTokens;

    /** 输入 + 输出总 token */
    private int totalTokens;

    /** 缓存命中输入 token（prompt_cache_hit_tokens） */
    private int cacheHitTokens;

    /** 缓存未命中输入 token（prompt_cache_miss_tokens） */
    private int cacheMissTokens;

    /** 思考链 token（completion_tokens_details.reasoning_tokens） */
    private int reasoningTokens;

    /**
     * 估算费用（元），按<b>该模型配置的单价</b>计算。
     * <p>
     * <b>null 的语义是「该模型没有配置单价，本端不估算」，不是「花了 0 元」。</b>
     * 前端据此隐藏金额、只显示 token 与缓存命中率——把未知成本显示成 ¥0
     * 会让用户误以为这个 Provider 免费。
     * </p>
     */
    private Double costEstimate;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /**
     * 缓存命中率（0~1）
     * <p>hit / (hit + miss)；两者都为 0（Provider 未返回缓存字段）时返回 -1 表示"无数据"。</p>
     */
    public double getCacheHitRate() {
        int denom = cacheHitTokens + cacheMissTokens;
        if (denom <= 0) {
            return -1d;
        }
        return (double) cacheHitTokens / denom;
    }

    /**
     * 该条记录是否有效（Provider 确实返回了 usage）
     */
    public boolean isValid() {
        return totalTokens > 0 || promptTokens > 0 || completionTokens > 0;
    }
}
