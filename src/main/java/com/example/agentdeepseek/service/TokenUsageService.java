package com.example.agentdeepseek.service;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.LlmTokenUsageMapper;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.vo.TokenUsageGroupVO;
import com.example.agentdeepseek.model.vo.TokenUsageSummaryVO;
import com.example.agentdeepseek.model.vo.TokenUsageTurnVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM 成本统计服务
 * <p>
 * 数据来源是 {@code llm_token_usage} 表（每轮请求一条真实 usage），此服务负责：
 * <ul>
 *   <li>按时间范围 / 会话维度聚合</li>
 *   <li>填充派生指标（缓存命中率、命中/未命中/输出三段费用拆分）</li>
 *   <li>会话分组补充会话名（便于看板直接展示）</li>
 * </ul>
 * </p>
 *
 * <h3>成本口径（v3.1.1 起）</h3>
 * <ul>
 *   <li>{@code costEstimate} 是落库时按<b>该模型当时单价</b>算出的累加值；模型没有配置单价时
 *       落 {@code NULL}（SUM 自动忽略），并计入 {@code pricedRequestCount} 之外的请求</li>
 *   <li>{@code pricedRequestCount == 0 && requestCount > 0} 表示「这批请求都没配单价」，
 *       前端应显示「—」而不是 ¥0，避免把「不知道」误读成「免费」</li>
 *   <li>派生费用拆分（hit/miss/output）用参照单价近似计算，仅用于观察成本结构占比，
 *       与账单无一致义务——所以它不参与金额结论，只在 {@code breakdownAvailable=false} 时隐藏</li>
 * </ul>
 */
@Slf4j
@Service
public class TokenUsageService {

    /** 会话分组默认返回条数 */
    private static final int DEFAULT_TOP_LIMIT = 10;

    private final LlmTokenUsageMapper usageMapper;
    private final ConversationMapper conversationMapper;
    private final TokenPricingConfig pricingConfig;

    public TokenUsageService(LlmTokenUsageMapper usageMapper,
                             ConversationMapper conversationMapper,
                             TokenPricingConfig pricingConfig) {
        this.usageMapper = usageMapper;
        this.conversationMapper = conversationMapper;
        this.pricingConfig = pricingConfig;
    }

    /**
     * 汇总统计
     *
     * @param range          时间范围：today / 7d / 30d / all（conversationId 非空时忽略）
     * @param conversationId 指定会话（可为空）
     */
    public TokenUsageSummaryVO summary(String range, Long conversationId) {
        TokenUsageSummaryVO vo;
        try {
            if (conversationId != null) {
                vo = usageMapper.summarizeByConversation(conversationId);
            } else {
                vo = usageMapper.summarizeByTimeRange(resolveFrom(range), LocalDateTime.now().plusDays(1));
            }
        } catch (Exception e) {
            log.warn("用量汇总查询失败: range={}, conversationId={}, err={}", range, conversationId, e.getMessage());
            vo = null;
        }
        if (vo == null) {
            vo = new TokenUsageSummaryVO();
        }
        enrichSummary(vo, resolveReferencePrice(range, conversationId));
        return vo;
    }

    /**
     * 按模型分组统计
     */
    public List<TokenUsageGroupVO> groupByModel(String range) {
        List<TokenUsageGroupVO> list;
        try {
            list = usageMapper.summarizeByModel(resolveFrom(range));
        } catch (Exception e) {
            log.warn("按模型统计失败: {}", e.getMessage());
            return List.of();
        }
        if (list == null) {
            return List.of();
        }
        for (TokenUsageGroupVO vo : list) {
            vo.setGroupName(vo.getGroupKey());
            enrichGroup(vo);
        }
        return list;
    }

    /**
     * 按会话分组统计（按费用倒序取前 N）
     */
    public List<TokenUsageGroupVO> groupByConversation(String range, Integer limit) {
        int topN = limit == null || limit <= 0 ? DEFAULT_TOP_LIMIT : Math.min(limit, 100);
        List<TokenUsageGroupVO> list;
        try {
            list = usageMapper.summarizeTopConversations(resolveFrom(range), topN);
        } catch (Exception e) {
            log.warn("按会话统计失败: {}", e.getMessage());
            return List.of();
        }
        if (list == null) {
            return List.of();
        }
        for (TokenUsageGroupVO vo : list) {
            vo.setGroupName(resolveConversationName(vo.getGroupKey()));
            enrichGroup(vo);
        }
        return list;
    }

    /**
     * 会话内明细（按轮次正序）
     */
    public List<LlmTokenUsage> detailByConversation(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        try {
            List<LlmTokenUsage> list = usageMapper.selectByConversationId(conversationId);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("会话用量明细查询失败: conversationId={}, err={}", conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 会话内按「轮次」聚合（聊天页每条 AI 回复下方的成本 / 命中率 / token）
     * <p>
     * 查询失败（例如老库未成功迁移 turn_id 列）时返回空列表而非抛错：
     * 聊天页的用量展示是增强信息，不能因为统计不可用而让整个页面报错。
     * </p>
     */
    public List<TokenUsageTurnVO> turnUsage(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<TokenUsageTurnVO> list;
        try {
            list = usageMapper.summarizeByTurn(conversationId);
        } catch (Exception e) {
            log.warn("按轮次统计失败: conversationId={}, err={}", conversationId, e.getMessage());
            return List.of();
        }
        if (list == null) {
            return List.of();
        }
        for (TokenUsageTurnVO vo : list) {
            long denom = vo.getCacheHitTokens() + vo.getCacheMissTokens();
            vo.setCacheHitRate(denom > 0 ? (double) vo.getCacheHitTokens() / denom : -1d);
            vo.setCostEstimate(round6(vo.getCostEstimate()));
        }
        return list;
    }

    // ==================== 内部方法 ====================

    /**
     * 填充汇总派生指标（命中率 + 三段费用拆分）
     *
     * @param price 参照单价（可为 null：表示范围内没有任何模型配置单价，
     *              此时三段拆分为 0 且 {@code breakdownAvailable=false}，前端隐藏结构条）
     */
    private void enrichSummary(TokenUsageSummaryVO vo, TokenPricingConfig.PriceItem price) {
        int denom = (int) (vo.getCacheHitTokens() + vo.getCacheMissTokens());
        vo.setCacheHitRate(denom > 0 ? (double) vo.getCacheHitTokens() / denom : -1d);

        if (price == null) {
            vo.setCacheHitCost(0d);
            vo.setCacheMissCost(0d);
            vo.setOutputCost(0d);
            vo.setBreakdownAvailable(false);
            return;
        }
        vo.setCacheHitCost(round6(vo.getCacheHitTokens() / 1_000_000d * price.getCacheHitInput()));
        vo.setCacheMissCost(round6(vo.getCacheMissTokens() / 1_000_000d * price.getCacheMissInput()));
        vo.setOutputCost(round6(vo.getCompletionTokens() / 1_000_000d * price.getOutput()));
        vo.setBreakdownAvailable(true);
    }

    /**
     * 解析「用于费用结构拆分的参照单价」。
     *
     * <p>优先用全局兜底价（{@code llm.pricing.default}）；未配置时退化为
     * 「本次统计范围内第一个配了单价的模型」——否则混合模型场景下结构条会全 0，
     * 而总金额却有值，自相矛盾。</p>
     *
     * <p>只在没有全局兜底价时才额外查一次模型列表，常见单 Provider 场景无额外开销。</p>
     */
    private TokenPricingConfig.PriceItem resolveReferencePrice(String range, Long conversationId) {
        TokenPricingConfig.PriceItem globalPrice = pricingConfig.resolve(null);
        if (globalPrice != null) {
            return globalPrice;
        }
        try {
            if (conversationId != null) {
                List<LlmTokenUsage> recent = usageMapper.selectRecentByConversationId(conversationId, 20);
                if (recent != null) {
                    for (LlmTokenUsage usage : recent) {
                        TokenPricingConfig.PriceItem price = pricingConfig.resolve(usage.getModel());
                        if (price != null) {
                            return price;
                        }
                    }
                }
                return null;
            }
            List<TokenUsageGroupVO> byModel = usageMapper.summarizeByModel(resolveFrom(range));
            if (byModel != null) {
                for (TokenUsageGroupVO group : byModel) {
                    TokenPricingConfig.PriceItem price = pricingConfig.resolve(group.getGroupKey());
                    if (price != null) {
                        return price;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析参照单价失败（忽略，结构条将隐藏）: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 填充分组派生指标
     */
    private void enrichGroup(TokenUsageGroupVO vo) {
        int denom = (int) (vo.getCacheHitTokens() + vo.getCacheMissTokens());
        vo.setCacheHitRate(denom > 0 ? (double) vo.getCacheHitTokens() / denom : -1d);
        if (vo.getGroupName() == null || vo.getGroupName().isEmpty()) {
            vo.setGroupName(vo.getGroupKey());
        }
    }

    /**
     * 解析会话名（会话已删除时回退为「会话#id」）
     */
    private String resolveConversationName(String conversationIdStr) {
        if (conversationIdStr == null || conversationIdStr.isEmpty()) {
            return "";
        }
        try {
            Long cid = Long.parseLong(conversationIdStr);
            return conversationMapper.selectById(cid)
                    .map(c -> c.getName() == null || c.getName().isEmpty() ? "会话#" + cid : c.getName())
                    .orElse("会话#" + cid + "（已删除）");
        } catch (Exception e) {
            return "会话#" + conversationIdStr;
        }
    }

    /**
     * 时间范围 → 起始时间
     */
    private LocalDateTime resolveFrom(String range) {
        String r = range == null ? "today" : range.trim().toLowerCase();
        return switch (r) {
            case "7d", "week" -> LocalDateTime.now().minusDays(7);
            case "30d", "month" -> LocalDateTime.now().minusDays(30);
            case "all" -> LocalDateTime.of(1970, 1, 1, 0, 0);
            default -> LocalDate.now().atStartOfDay();
        };
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
