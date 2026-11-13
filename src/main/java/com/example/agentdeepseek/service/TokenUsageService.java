package com.example.agentdeepseek.service;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.LlmTokenUsageMapper;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.vo.TokenUsageGroupVO;
import com.example.agentdeepseek.model.vo.TokenUsageSummaryVO;
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
 * <p>
 * <b>口径说明</b>：{@code costEstimate} 是落库时按当时单价算出的累加值（准确）；
 * 派生费用拆分（hit/miss/output）用默认单价近似计算，仅用于观察成本结构占比，
 * 与账单无一致义务。
 * </p>
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
        enrichSummary(vo);
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

    // ==================== 内部方法 ====================

    /**
     * 填充汇总派生指标（命中率 + 三段费用拆分）
     */
    private void enrichSummary(TokenUsageSummaryVO vo) {
        int denom = (int) (vo.getCacheHitTokens() + vo.getCacheMissTokens());
        vo.setCacheHitRate(denom > 0 ? (double) vo.getCacheHitTokens() / denom : -1d);

        TokenPricingConfig.PriceItem price = pricingConfig.resolve(null);
        vo.setCacheHitCost(round6(vo.getCacheHitTokens() / 1_000_000d * price.getCacheHitInput()));
        vo.setCacheMissCost(round6(vo.getCacheMissTokens() / 1_000_000d * price.getCacheMissInput()));
        vo.setOutputCost(round6(vo.getCompletionTokens() / 1_000_000d * price.getOutput()));
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
