package com.example.agentdeepseek.service;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.config.pricing.PeakPeriodPolicy;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.LlmTokenUsageMapper;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.vo.TokenUsageGroupVO;
import com.example.agentdeepseek.model.vo.TokenPricingContextVO;
import com.example.agentdeepseek.model.vo.TokenUsagePeriodBreakdownVO;
import com.example.agentdeepseek.model.vo.TokenUsageSummaryVO;
import com.example.agentdeepseek.model.vo.TokenUsageTurnVO;
import com.example.agentdeepseek.service.llm.TokenUsageParser;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PeakPricingSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final TokenUsageParser tokenUsageParser;
    /** 峰谷时段策略（用于「费用结构」按各时段单价拆分 + 展示当前时段） */
    private final PeakPeriodPolicy peakPeriodPolicy;

    public TokenUsageService(LlmTokenUsageMapper usageMapper,
                             ConversationMapper conversationMapper,
                             TokenPricingConfig pricingConfig,
                             TokenUsageParser tokenUsageParser,
                             PeakPeriodPolicy peakPeriodPolicy) {
        this.usageMapper = usageMapper;
        this.conversationMapper = conversationMapper;
        this.pricingConfig = pricingConfig;
        this.tokenUsageParser = tokenUsageParser;
        this.peakPeriodPolicy = peakPeriodPolicy;
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
        // 一次性取出范围内的 Provider 列表：既作为「计费模式闸门」的输入，
        // 也用于把 pricingMode / 峰谷规格透传给前端（让用户看懂金额为何不可用、当前按哪档价）
        List<String> providers = collectProvidersInRange(range, conversationId);
        enrichSummary(vo,
                resolveReferencePricePair(range, conversationId, providers),
                loadPeriodBreakdown(range, conversationId));
        vo.setPricingMode(resolveEffectivePricingMode(providers).name());
        applyPeakContext(vo, providers);
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
        /*
          会话级计费模式：只查一次、所有轮共用。
          按轮聚合的 SQL 里没有 provider 维度（GROUP BY turn_id），
          而同一会话通常固定用一个 Provider，取会话级结论足够且更省查询。
         */
        String pricingMode = resolveEffectivePricingMode(
                collectProvidersInRange(null, conversationId)).name();
        for (TokenUsageTurnVO vo : list) {
            long denom = vo.getCacheHitTokens() + vo.getCacheMissTokens();
            vo.setCacheHitRate(denom > 0 ? (double) vo.getCacheHitTokens() / denom : -1d);
            vo.setCostEstimate(round6(vo.getCostEstimate()));
            vo.setPricingMode(pricingMode);
        }
        return list;
    }

    // ==================== 内部方法 ====================

    /**
     * 填充汇总派生指标（命中率 + 三段费用拆分 + 峰谷费用分布）。
     *
     * <h3>为什么三段拆分要按「模型 + 时段」分组做</h3>
     * <p>
     * 峰谷计价上线后，同一个统计范围里会同时存在<b>高峰记录</b>与<b>空闲记录</b>，
     * 两者的输出单价可能相差一倍。若仍用「单一参照价」把范围内所有 token 重算一遍，
     * 三段结构必然失真（总额来自 {@code SUM(cost_estimate)} 仍是对的，但结构条会指向错误结论）。
     * 因此这里按 {@code (model, pricing_period)} 分组，每组用该时段单价算三段后累加。
     * </p>
     *
     * @param pair      参照单价对（{@code null} 表示范围内存在未配单价的模型 / 套餐制 Provider，
     *                  或存在多套不同单价 → 隐藏结构条，避免给出一个看似合理实则错误的结构）
     * @param breakdown 按 (模型, 时段) 分组的真实 token 量
     */
    private void enrichSummary(TokenUsageSummaryVO vo, PricePair pair,
                               List<TokenUsagePeriodBreakdownVO> breakdown) {
        int denom = (int) (vo.getCacheHitTokens() + vo.getCacheMissTokens());
        vo.setCacheHitRate(denom > 0 ? (double) vo.getCacheHitTokens() / denom : -1d);

        if (pair == null || breakdown == null || breakdown.isEmpty()) {
            clearBreakdown(vo);
            return;
        }
        double hitCost = 0d;
        double missCost = 0d;
        double outputCost = 0d;
        double peakCost = 0d;
        double offPeakCost = 0d;
        for (TokenUsagePeriodBreakdownVO row : breakdown) {
            TokenPricingConfig.PriceItem price = pair.forPeriod(row.getPricingPeriod());
            if (price == null) {
                clearBreakdown(vo);
                return;
            }
            double h = row.getCacheHitTokens() / 1_000_000d * price.getCacheHitInput();
            double m = row.getCacheMissTokens() / 1_000_000d * price.getCacheMissInput();
            double o = row.getCompletionTokens() / 1_000_000d * price.getOutput();
            hitCost += h;
            missCost += m;
            outputCost += o;
            if (isPeakPeriod(row.getPricingPeriod())) {
                peakCost += h + m + o;
            } else {
                offPeakCost += h + m + o;
            }
        }
        vo.setCacheHitCost(round6(hitCost));
        vo.setCacheMissCost(round6(missCost));
        vo.setOutputCost(round6(outputCost));
        vo.setBreakdownAvailable(true);
        vo.setPeakCostEstimate(round6(peakCost));
        vo.setOffPeakCostEstimate(round6(offPeakCost));
    }

    /** 结构条不可用：三段清零并置标记（前端据此隐藏「费用结构」） */
    private void clearBreakdown(TokenUsageSummaryVO vo) {
        vo.setCacheHitCost(0d);
        vo.setCacheMissCost(0d);
        vo.setOutputCost(0d);
        vo.setPeakCostEstimate(0d);
        vo.setOffPeakCostEstimate(0d);
        vo.setBreakdownAvailable(false);
    }

    /**
     * 解析「用于费用结构拆分的参照单价」。
     *
     * <p>优先用全局兜底价（{@code llm.pricing.default}）；未配置时必须满足
     * <b>「统计范围内所有模型都有单价，且单价完全一致」</b>才返回单价，否则返回 {@code null}。</p>
     *
     * <h3>⚠️ 为什么从「取第一个配了单价的模型」改成「要求单价唯一」</h3>
     * <p>
     * 旧实现只要范围内<b>任意一个</b>模型配了单价，就拿它的价当作整个汇总的参照价，
     * 把这批数据里<b>所有</b> token 都按这个价算三段费用。混合场景下这会得出一个
     * 「数字看起来合理、实际完全错误」的成本结构 —— 实测踩到的真实案例：
     * 同一个时间范围内既有 DeepSeek 官方（已配单价）又有火山方舟 Coding Plan
     * （{@code deepseek-v4.1-flash} 未配单价），于是火山的 5396 万 token 被按
     * <b>DeepSeek 官网单价</b>算出了「费用结构」，在页面上显示成一笔并不存在的花费。
     * </p>
     * <p>
     * 火山方舟 Coding Plan 是按<b>次数套餐</b>计费（200 元/月、9 万次额度）的订阅制，
     * 根本不存在「元 / 百万 token」这种单价；硬套别家单价属于凭空编造成本。
     * 因此现在的规则是：<b>只要范围内有一个模型没配单价，就整体不拆结构</b>
     * （{@code breakdownAvailable=false}，前端隐藏「费用结构」段），
     * 只保留 token 与缓存命中率这类<b>真实观测值</b>。
     * </p>
     *
     * <p>只在没有全局兜底价时才额外查一次模型列表，常见单 Provider 场景无额外开销。</p>
     */
    private PricePair resolveReferencePricePair(String range, Long conversationId,
                                                List<String> providers) {
        /*
          第一道闸：计费模式 —— 优先级高于「全局兜底价」。

          范围内只要混入了「不是按 token 计价」的 Provider（套餐制 QUOTA / 本地免费 FREE），
          就整体不拆费用结构 —— 因为这类 Provider 根本没有「元 / 百万 token」这种单价。
          连全局兜底价也不能放行：兜底价表达的是「未知模型按此价估算」，
          而不是「连套餐制也要按 token 估价」的授权。

          实测踩过的真实 bug（本闸门就是它的根治）：
          同一天里既有 DeepSeek 官方（已配单价）又有火山 Coding Plan（200 元 / 9 万次的次数套餐），
          旧逻辑取了 DeepSeek 的单价当「参照价」，把火山那 5396 万 token 也算成了一笔钱，
          在成本看板上显示出一个看似合理、实际完全不存在花费的数字。
         */
        if (hasNonTokenPricingProvider(providers)) {
            return null;
        }
        TokenPricingConfig.PriceItem globalPrice = pricingConfig.resolve(null);
        if (globalPrice != null) {
            // 显式配了全局兜底价：作者已声明「所有未知模型都按此价估算」，可安全用于结构拆分
            return new PricePair(globalPrice,
                    globalPrice.effective(true, peakPeriodPolicy.peakMultiplier()));
        }
        try {
            List<String> models = collectModelsInRange(range, conversationId);
            if (models.isEmpty()) {
                return null;
            }
            TokenPricingConfig.PriceItem refBase = null;
            TokenPricingConfig.PriceItem refPeak = null;
            for (String model : models) {
                TokenPricingConfig.PriceItem base = pricingConfig.resolve(model);
                if (base == null) {
                    // 范围内存在「未配置单价」的模型：绝不能拿别家单价给它算 ——
                    // 那会得出一个看似合理实则完全错误的成本结构。
                    log.debug("费用结构不可用：模型 [{}] 未配置单价（不套用其他模型的单价）", model);
                    return null;
                }
                // 峰谷价：未开启 peak-pricing 的模型会原样返回 base（结构上无需特判）
                TokenPricingConfig.PriceItem peak = pricingConfig.resolveFor(
                        model, true, peakPeriodPolicy.peakMultiplier());
                if (refBase == null) {
                    refBase = base;
                    refPeak = peak;
                } else if (!samePrice(refBase, base) || !samePrice(refPeak, peak)) {
                    // 混合单价：单一结构条无法同时表达两套价（峰谷下还要再加两档），
                    // 隐藏比给一个加权平均值更诚实
                    log.debug("费用结构不可用：范围内存在多套单价（当前 [{}]）", model);
                    return null;
                }
            }
            return refBase == null ? null : new PricePair(refBase, refPeak);
        } catch (Exception e) {
            log.debug("解析参照单价失败（忽略，结构条将隐藏）: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 统计范围内是否混入了「非按 token 计价」的 Provider（套餐制 QUOTA / 本地免费 FREE）。
     *
     * <p>这是 T4 的核心闸门：把「能不能估算金额」的判定依据从
     * <b>「模型有没有配单价」</b>改成 <b>「这个 Provider 是什么计费形态」</b>。</p>
     *
     * <p>判定数据来自 {@link ProviderProfile.PricingMode}（经
     * {@code TokenUsageParser#resolvePricingMode} 按 provider code → 配置 → 档案 → baseUrl 解析），
     * 而不是本地猜测。</p>
     *
     * @param providers 范围内出现过的 Provider 编码（由 {@link #collectProvidersInRange} 取得）
     * @return true 表示存在套餐制/免费 Provider → 金额与费用结构都不可用
     */
    private boolean hasNonTokenPricingProvider(List<String> providers) {
        return resolveEffectivePricingMode(providers) != ProviderProfile.PricingMode.TOKEN;
    }

    /**
     * 把范围内多个 Provider 的计费模式聚合为一个「本批数据的有效模式」。
     *
     * <p>聚合规则：只要有 QUOTA 就返回 QUOTA（套餐制是「不能估价」的最强理由，
     * 且前端文案据此给出最准确的解释）；否则只要有 FREE 就返回 FREE；否则 TOKEN。</p>
     *
     * <p>混合场景（如同时有 DeepSeek 与火山 Coding Plan）返回 QUOTA —— 与
     * {@link #resolveReferencePrice} 的闸门一致：此时整批金额都不可用，
     * 前端应说明「含套餐制 Provider」而不是「某个模型未配置单价」。</p>
     */
    private ProviderProfile.PricingMode resolveEffectivePricingMode(List<String> providers) {
        ProviderProfile.PricingMode effective = ProviderProfile.PricingMode.TOKEN;
        if (providers == null || providers.isEmpty()) {
            return effective;
        }
        for (String provider : providers) {
            ProviderProfile.PricingMode mode = tokenUsageParser.resolvePricingMode(provider);
            if (mode == null || mode == ProviderProfile.PricingMode.TOKEN) {
                continue;
            }
            if (mode == ProviderProfile.PricingMode.QUOTA) {
                log.debug("费用结构不可用：Provider [{}] 为套餐/次数制（不按 token 估价）", provider);
                return ProviderProfile.PricingMode.QUOTA;
            }
            // FREE：本地免费模型，无金额但不是「配置缺失」
            effective = ProviderProfile.PricingMode.FREE;
        }
        return effective;
    }

    /**
     * 收集统计范围内出现过的 Provider 编码（去重、忽略空值）。
     *
     * <p>会话维度复用全量明细（不能只取最近 N 条：靠后的记录可能属于另一个 Provider，
     * 漏判会让套餐制记录混进按 token 估价的汇总里）；时间维度走专用的
     * {@code selectDistinctProviders}，避免把整表 token 都读上来。</p>
     */
    private List<String> collectProvidersInRange(String range, Long conversationId) {
        if (conversationId != null) {
            List<LlmTokenUsage> all = usageMapper.selectByConversationId(conversationId);
            if (all == null || all.isEmpty()) {
                return List.of();
            }
            Set<String> seen = new LinkedHashSet<>();
            for (LlmTokenUsage usage : all) {
                if (usage.getProvider() != null && !usage.getProvider().isBlank()) {
                    seen.add(usage.getProvider());
                }
            }
            return new ArrayList<>(seen);
        }
        try {
            List<String> providers = usageMapper.selectDistinctProviders(
                    resolveFrom(range), LocalDateTime.now().plusDays(1));
            if (providers == null || providers.isEmpty()) {
                return List.of();
            }
            List<String> result = new ArrayList<>(providers.size());
            for (String provider : providers) {
                if (provider != null && !provider.isBlank()) {
                    result.add(provider);
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("查询范围内 Provider 列表失败（忽略，按可估算处理）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 收集统计范围内的所有模型名（去重），用于判断「单价是否唯一」。
     *
     * <p>会话维度取该会话全部记录的模型（不能用 recent N 条：靠后的记录可能属于另一个模型，
     * 漏判会让火山记录混进 DeepSeek 单价的结构里）；时间维度直接复用按模型分组的 groupKey。</p>
     */
    private List<String> collectModelsInRange(String range, Long conversationId) {
        if (conversationId != null) {
            List<LlmTokenUsage> all = usageMapper.selectByConversationId(conversationId);
            if (all == null || all.isEmpty()) {
                return List.of();
            }
            Set<String> seen = new LinkedHashSet<>();
            for (LlmTokenUsage usage : all) {
                if (usage.getModel() != null && !usage.getModel().isBlank()) {
                    seen.add(usage.getModel());
                }
            }
            return new ArrayList<>(seen);
        }
        List<TokenUsageGroupVO> byModel = usageMapper.summarizeByModel(resolveFrom(range));
        if (byModel == null || byModel.isEmpty()) {
            return List.of();
        }
        List<String> models = new ArrayList<>(byModel.size());
        for (TokenUsageGroupVO group : byModel) {
            if (group.getGroupKey() != null && !group.getGroupKey().isBlank()) {
                models.add(group.getGroupKey());
            }
        }
        return models;
    }

    /**
     * 两套单价是否完全一致（三段费率都相同才算一致）。
     *
     * <p>只比较参与结构拆分的三段（命中输入 / 未命中输入 / 输出）——
     * {@code cacheWriteInput} 仅 Anthropic 用到，且缺省时已回退到未命中价，不参与判定。</p>
     */
    private boolean samePrice(TokenPricingConfig.PriceItem a, TokenPricingConfig.PriceItem b) {
        return a.getCacheHitInput() == b.getCacheHitInput()
                && a.getCacheMissInput() == b.getCacheMissInput()
                && a.getOutput() == b.getOutput();
    }

    /**
     * 按 (模型, 时段) 分组的 token 明细 —— 费用结构拆分的数据源。
     *
     * <p>老库未成功迁移 {@code pricing_period} 列时查询会失败：此时降级为「不拆结构」
     * （{@code breakdownAvailable=false}），<b>不影响</b>汇总金额（总额来自
     * {@code SUM(cost_estimate)}，与本次查询无关）。</p>
     */
    private List<TokenUsagePeriodBreakdownVO> loadPeriodBreakdown(String range, Long conversationId) {
        try {
            List<TokenUsagePeriodBreakdownVO> rows = conversationId != null
                    ? usageMapper.summarizeBreakdownByConversation(conversationId)
                    : usageMapper.summarizeBreakdownByRange(resolveFrom(range), LocalDateTime.now().plusDays(1));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.debug("费用结构分组查询失败（降级为不拆分）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 填充峰谷上下文（是否启用 + 当前时段），供前端提示「现在按高峰价计费」。
     */
    private void applyPeakContext(TokenUsageSummaryVO vo, List<String> providers) {
        PeakPricingSpec spec = resolvePeakSpec(providers);
        boolean enabled = spec != null && peakPeriodPolicy.isGloballyEnabled();
        vo.setPeakPricingEnabled(enabled);
        if (!enabled) {
            vo.setCurrentPeriod(null);
            return;
        }
        vo.setCurrentPeriod(peakPeriodPolicy.periodOf(spec, peakPeriodPolicy.nowOf(spec)));
    }

    /** 取范围内第一个「启用峰谷」的平台规格（混合平台时以有峰谷者为准，便于统一展示口径） */
    private PeakPricingSpec resolvePeakSpec(List<String> providers) {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        for (String provider : providers) {
            PeakPricingSpec spec = tokenUsageParser.resolvePeakPricing(provider);
            if (spec != null && spec.isActive()) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 峰谷计价上下文（前端展示「当前时段 / 下次切换 / 各模型两档单价」）。
     *
     * <p>只读内存配置与日历，<b>不查库、不联网</b>，可被前端在成本看板 / 聊天页高频调用。</p>
     *
     * @param provider Provider 编码（可为空：为空时只返回全局信息与模型单价表，{@code period=null}）
     */
    public TokenPricingContextVO pricingContext(String provider) {
        TokenPricingContextVO vo = new TokenPricingContextVO();
        PeakPricingSpec spec = tokenUsageParser.resolvePeakPricing(provider);
        if (spec == null || !spec.isActive()) {
            /*
              成本看板是「全局页面」，前端调用时不带 provider（它也没有单一平台上下文）。
              若这里因 spec=null 直接判 enabled=false，界面的时段提示会永远不出现 —— 功能形同虚设。
              因此退化为「任一启用峰谷的平台档案」：时段窗口 / 节假日地区本就是平台事实，
              全局展示不绑定具体平台，语义仍然正确。
             */
            spec = anyPeakSpec();
        }
        boolean enabled = spec != null && spec.isActive() && peakPeriodPolicy.isGloballyEnabled();
        vo.setEnabled(enabled);
        vo.setZone(peakPeriodPolicy.zoneOf(spec).getId());
        vo.setPeakMultiplier(peakPeriodPolicy.peakMultiplier());

        ZonedDateTime now = peakPeriodPolicy.nowOf(spec);
        /*
          必须用 OffsetDateTime 而不是 ZonedDateTime.toString()：
          后者的输出是 `2026-11-18T22:09:04+08:00[Asia/Shanghai]`，末尾的 [zone] 不是 ISO-8601 的一部分，
          Safari / Firefox 的 new Date() 会直接得到 Invalid Date → 前端「下次切换」文案失效。
         */
        vo.setNow(now.toOffsetDateTime().toString());
        if (enabled) {
            vo.setPeriod(peakPeriodPolicy.periodOf(spec, now));
            vo.setWindows(peakPeriodPolicy.windowsOf(spec));
            ZonedDateTime next = peakPeriodPolicy.nextSwitchAt(spec, now);
            vo.setNextSwitchAt(next == null ? null : next.toOffsetDateTime().toString());
        }

        String effective = enabled ? vo.getPeriod() : null;
        Map<String, TokenPricingConfig.PriceItem> configured = pricingConfig.getModels();
        if (configured != null) {
            for (Map.Entry<String, TokenPricingConfig.PriceItem> entry : configured.entrySet()) {
                TokenPricingConfig.PriceItem base = entry.getValue();
                if (base == null) {
                    continue;
                }
                TokenPricingContextVO.ModelPrice modelPrice = new TokenPricingContextVO.ModelPrice();
                modelPrice.setModel(entry.getKey());
                modelPrice.setOffPeak(toPriceView(base));
                modelPrice.setPeak(toPriceView(base.effective(true, peakPeriodPolicy.peakMultiplier())));
                modelPrice.setEffective(effective);
                vo.getModels().add(modelPrice);
            }
        }
        return vo;
    }

    /**
     * 取第一个启用峰谷的平台档案（provider 未知时的全局视角）。
     *
     * <p>注意：这里只是「时段规则」的兜底，<b>不涉及任何用户配置</b> ——
     * 平台档案是代码内的静态事实（{@code ProviderRegistry}），所以不会把 A 平台的时段
     * 套到 B 平台的金额上（金额仍按各记录的 provider 独立解析）。</p>
     */
    private PeakPricingSpec anyPeakSpec() {
        for (ProviderProfile profile : ProviderRegistry.orderedByDisplay()) {
            PeakPricingSpec spec = profile.getPeakPricing();
            if (spec != null && spec.isActive()) {
                return spec;
            }
        }
        return null;
    }

    /** 单价项 → 前端视图 */
    private static TokenPricingContextVO.Price toPriceView(TokenPricingConfig.PriceItem item) {
        TokenPricingContextVO.Price price = new TokenPricingContextVO.Price();
        price.setCacheHitInput(item.getCacheHitInput());
        price.setCacheMissInput(item.getCacheMissInput());
        price.setOutput(item.getOutput());
        return price;
    }

    /** 是否高峰时段标识 */
    private static boolean isPeakPeriod(String period) {
        return period != null && PeakPeriodPolicy.PERIOD_PEAK.equalsIgnoreCase(period.trim());
    }

    /**
     * 参照单价对：空闲价 + 高峰价。
     * <p>平台未启用峰谷时两者是同一个对象（{@code forPeriod} 恒返回空闲价），行为与改造前一致。</p>
     */
    private static final class PricePair {
        private final TokenPricingConfig.PriceItem offPeak;
        private final TokenPricingConfig.PriceItem peak;

        private PricePair(TokenPricingConfig.PriceItem offPeak, TokenPricingConfig.PriceItem peak) {
            this.offPeak = offPeak;
            this.peak = peak == null ? offPeak : peak;
        }

        private TokenPricingConfig.PriceItem forPeriod(String period) {
            return isPeakPeriod(period) ? peak : offPeak;
        }
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
