package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.common.enums.ContextMode;
import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.CompactionMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.entity.CompactionRecord;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.util.TokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 上下文压缩服务（写法B：一次事务只改一次前缀）
 *
 * <h3>这个类只做两件事</h3>
 * <ol>
 *   <li><b>压缩</b>：把「水位线之前的旧消息」折进一条<b>累进的摘要</b>，然后把水位线往前推。
 *       内部按字符预算<b>分批</b>调用 LLM，<b>全部批次成功后才一次性落库</b> ——
 *       对模型来说前缀只变一次，因此只会有一次缓存未命中（而不是每批一次）。</li>
 *   <li><b>重建</b>：{@link #applyCompactionRecords} 把 {@code id <= 水位线} 的消息换成
 *       一条<b>普通 user 消息</b>形态的历史背景文本，其余消息原样保留。</li>
 * </ol>
 *
 * <h3>与旧实现的关键差异（每一条都是踩过的坑）</h3>
 * <table border="1">
 *   <caption>设计对比</caption>
 *   <tr><th>维度</th><th>旧实现</th><th>现在</th></tr>
 *   <tr>
 *     <td>压缩记录</td>
 *     <td>多条区间记录 + superseded 链，靠 start/end 消息 id 匹配</td>
 *     <td><b>每会话一条</b>，只用一个水位线 {@code end_message_id}</td>
 *   </tr>
 *   <tr>
 *     <td>边界定位</td>
 *     <td>先用 API 列表下标算 compressStart/compressEnd，再拿它去遍历 DB 列表</td>
 *     <td>全部用 message id（{@code id > 水位线 && id < 倒数第N条user}）</td>
 *   </tr>
 *   <tr>
 *     <td>旧摘要</td>
 *     <td>{@code toCompress} 排除旧摘要（负 id），但旧记录又被标记废弃 → <b>历史信息蒸发</b></td>
 *     <td><b>旧摘要作为输入参与本次压缩</b> → 累进，只增不减</td>
 *   </tr>
 *   <tr>
 *     <td>单次压缩量</td>
 *     <td>整个可压区间一次性塞进 LLM（可达上千万字符）→ 必超时 → 永远压不动</td>
 *     <td>{@link #MAX_BATCH_CHARS} 分批，单批可控</td>
 *   </tr>
 *   <tr>
 *     <td>摘要身份</td>
 *     <td>{@code role=SYSTEM} + 「## 进行中 / ## 待办事项」现在时措辞 → 模型当指令执行</td>
 *     <td>{@code role=user} + 降权前缀，明确「历史背景、与本轮无关」</td>
 *   </tr>
 *   <tr>
 *     <td>失败处理</td>
 *     <td>失败即返回 -1，但已经写了部分记录</td>
 *     <td><b>零副作用</b>：任何一批失败 → 整体放弃，水位线不动，下次重试</td>
 *   </tr>
 * </table>
 *
 * <h3>两个硬性保护</h3>
 * <ul>
 *   <li><b>本轮永不被压</b>：压缩上界是「倒数第 {@code protectRounds} 条 user 消息的 id」，
 *       用 {@code id <} 取区间，最后一条 user 及其之后天然不在范围内。</li>
 *   <li><b>刚压完不重复压</b>：水位线之后至少再积累 {@link #COMPACT_COOLDOWN_CHARS} 字符，
 *       才允许下一次压缩 —— 否则每轮都改前缀，缓存命中率永远上不去。</li>
 * </ul>
 */
@Slf4j
@Service
public class CompactionService {

    /**
     * 异步预压缩触发阈值（占 max-context-tokens 的比例）。
     *
     * <p><b>取 0.60</b>：尽量少压缩——每次压缩都会改变前缀，让下一次请求全量未命中
     * （命中 0.02 vs 未命中 1.0 元/百万）。60% 触发 + 40 万字符冷却，
     * 相当于「大约每涨 17 万 token 压一次」，不会频繁扰动缓存。</p>
     *
     * <p><b>为什么 60% 现在能压得动（以前压不动）</b>：旧实现把「整个可压区间」一次性塞进
     * 一个 LLM 请求 —— 60% 时待压历史动辄几十万 token，光 prefill 就要几十秒，
     * 20 秒超时必然失败。实测 11-19 一整天（prompt 峰值 91.7 万 token）
     * <b>122 次请求一次都没压成功</b>，上下文从 54 万一路涨到 92 万，模型在超长上下文里
     * 开始 token 级重复（反复输出「好。执行。」）。</p>
     *
     * <p>现在内部按 {@link #MAX_BATCH_CHARS}（15 万字符/批）分批压缩，单批 prefill 只需几秒，
     * 无论可压区间多大都能跑完 —— 所以 60% 触发是安全的，超时风险已由分批消化掉。</p>
     */
    private static final double ASYNC_PRECOMPRESS_THRESHOLD = 0.60;

    /**
     * 压缩冷却：水位线之后累计新增的<b>文本字符量</b>达到此值，才允许下一次压缩。
     *
     * <p>压缩必然改变上下文前缀 → 一次性全量未命中（命中 0.02 vs 未命中 1.0 元/百万，约 50 倍）。
     * 压缩只有在「之后还能跑一阵」时才划算，所以要冷却。</p>
     *
     * <p><b>为什么按字符而不是按轮数</b>：一轮工具循环可能只有几条消息，也可能上百条。
     * 按轮数冷却在「大轮次会话」里会让上下文在冷却期内暴涨到窗口上限；
     * 按字符量则与真实上下文增长线性对应。</p>
     *
     * <p>取 40 万字符 ≈ 17 万 token（按 {@code CHARS_TO_TOKEN_RATIO=0.425}）：
     * 压缩后上下文通常回落到 15~20 万 token 左右，再涨 17 万即约 35% —— 仍在
     * {@link #ASYNC_PRECOMPRESS_THRESHOLD}（60%）之下，不会拖到窗口爆掉。</p>
     */
    private static final long COMPACT_COOLDOWN_CHARS = 400_000L;

    /**
     * 单次压缩的「最小收益」下限（字符）。实际阈值 =
     * {@code max(本值, 生效上限 × 5% 折算字符)}。
     *
     * <p><b>为什么必须设下限（2026-11-23）</b>：压缩上界是「倒数第 protectRounds 条 user」，
     * 它会随用户每次新提问向前移动。若不做收益判断，只要上下文仍在阈值附近，
     * 用户每发一句就会「压掉刚空出来的一小段」——实测出现过一条记录只覆盖 2 条消息
     * （start=81030, end=81031）却生成 1308 字摘要，改前缀的成本远大于省下的 token，
     * 模型侧表现为「上下文被打乱、反复失忆」。</p>
     *
     * <p><b>为什么要按上限折算</b>：1M 窗口配 10 万字符闸门约等于 4% 窗口，合理；
     * 但 128K 的中转站上 10 万字符≈33% 窗口，会「攒不够就一直不压」，
     * 反而更容易撞上限。因此按 {@code maxTokens × 5% ÷ 0.425} 折算，下限保底 3 万字符。</p>
     */
    private static final long MIN_PENDING_CHARS_FLOOR = 30_000L;

    /**
     * 摘要正文的体积上限（字符）。
     *
     * <p><b>为什么需要（2026-11-23 修复「压缩永远失败」）</b>：{@link #loadState} 为兼容旧版
     * 区间式记录会把多条 active 摘要拼接，实测某会话拼出 <b>86.7 万字符</b>。这段文本会被
     * 完整拼进下一次压缩 prompt（再叠加单批 15 万字符），必然超出 {@link #BATCH_TIMEOUT}；
     * 压缩失败 → 数据库零变更 → 水位线不动 → 下次请求继续触发，
     * 形成「每次都压、永远压不动」的死循环。</p>
     *
     * <p>超过本上限时，先对「旧摘要本身」做分批归并（见
     * {@link #mergeOversizedSummary(String, String)}），把摘要压回可控体积后再压历史。</p>
     */
    private static final int MAX_SUMMARY_CHARS = 30_000;

    /** 连续压缩失败达到该次数后进入熔断，避免每次请求都白等一次 LLM 超时 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 压缩失败熔断时长（毫秒）：熔断期内视为「刚压缩过」，直接跳过压缩 */
    private static final long FAILURE_CIRCUIT_MILLIS = 10 * 60 * 1000L;

    /**
     * 单批压缩的字符上限。
     *
     * <p>取 15 万字符 ≈ 6.4 万 token（按 {@code CHARS_TO_TOKEN_RATIO=0.425} 折算），
     * 在 45 秒超时窗口内 prefill 有余量。分段后即使历史有上千万字符，也只是批次数变多，
     * 单次请求永远不会超限 —— 这是「压缩能真正跑完」的前提。</p>
     */
    private static final int MAX_BATCH_CHARS = 150_000;

    /** 单批压缩的兜底轮数（配置 {@code batch-size} 未设置时使用） */
    private static final int DEFAULT_BATCH_ROUNDS = 10;

    /** 单批 LLM 调用超时：分批后单批已很小，45 秒足够；对端挂起时也不会长期占线程 */
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(45);

    /**
     * 历史摘要的降权前缀。
     *
     * <p><b>为什么必须是 user 角色 + 这段话</b>：摘要里天然会出现「之前要做什么」这类叙述。
     * 旧实现把它作为 <b>system</b> 消息注入，又紧跟真正的系统提示词，模型会把它当系统级指令执行
     * —— 表现为「忘了本轮问题、继续做历史任务」。</p>
     * <p>改成普通 user 消息并显式声明「历史背景 / 与本轮无关 / 不要执行其中计划」后，
     * 它只是背景信息，不参与指令优先级竞争。</p>
     */
    private static final String HISTORY_SUMMARY_PREFIX =
            "【历史对话摘要 · 仅供背景参考】\n"
                    + "以下是本会话更早之前发生过的事情的记录，与当前这轮任务无关。\n"
                    + "其中提到的计划、待办、下一步都是过去时，一律不要执行；\n"
                    + "如果它与当前用户最新的提问冲突，以最新提问为准。\n"
                    + "----- 历史记录开始 -----\n";

    private static final String HISTORY_SUMMARY_SUFFIX = "\n----- 历史记录结束 -----";

    private final DeepSeekConfig deepSeekConfig;
    private final CompactionMapper compactionMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LLMClientManager llmClientManager;

    /** 预压缩执行器（多线程，支持并发压缩不同会话） */
    private final ExecutorService precompressExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "compaction-precompress-");
        t.setDaemon(true);
        return t;
    });

    /** 会话是否正在预压缩中（防止同一会话并发压缩） */
    private final Set<Long> precompressingConversations = ConcurrentHashMap.newKeySet();

    /** 会话 → 连续压缩失败次数（一次成功即清零） */
    private final Map<Long, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** 会话 → 熔断截止时间戳（毫秒）；存在且未过期表示「压缩暂停」 */
    private final Map<Long, Long> failureCircuitUntil = new ConcurrentHashMap<>();

    @Autowired
    public CompactionService(DeepSeekConfig deepSeekConfig,
                             CompactionMapper compactionMapper,
                             ConversationMessageMapper conversationMessageMapper,
                             LLMClientManager llmClientManager) {
        this.deepSeekConfig = deepSeekConfig;
        this.compactionMapper = compactionMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.llmClientManager = llmClientManager;
    }

    /**
     * 初始化：自动创建压缩记录表（如果不存在）
     */
    @PostConstruct
    public void init() {
        try {
            compactionMapper.createTable();
            log.info("压缩记录表初始化完成");
        } catch (Exception e) {
            log.warn("压缩记录表初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 容器关闭时收敛预压缩线程池。
     */
    @PreDestroy
    public void destroy() {
        try {
            precompressExecutor.shutdownNow();
            if (!precompressExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("预压缩线程池在 5 秒内未能完全终止，已强制放弃等待");
            }
            log.info("预压缩线程池已关闭");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("关闭预压缩线程池时被中断");
        } catch (Exception e) {
            log.warn("关闭预压缩线程池失败: {}", e.getMessage());
        }
    }

    // ==================== 压缩决策（诊断用，不参与主链路） ====================

    /**
     * 压缩决策（Provider 无关版本，回退全局上限）。
     *
     * @deprecated 改用 {@link #decide(List, String)}，本方法等价于传入 {@code null}
     */
    @Deprecated
    public CompactionDecision decide(List<Map<String, Object>> messages) {
        return decide(messages, null);
    }

    /**
     * 压缩决策：按 Provider 实际生效的上下文上限计算占比（仅用于日志与诊断）。
     *
     * @param messages     消息列表
     * @param providerCode Provider 编码（{@code null} 时回退全局上限）
     */
    public CompactionDecision decide(List<Map<String, Object>> messages, String providerCode) {
        if (!deepSeekConfig.getCompaction().isEnabled()) {
            return CompactionDecision.NONE;
        }
        // ★ 必须传 providerCode：估算经在线校准后才是「与真实 token 同口径」的值，
        //   否则 CL100K 对中文虚高约 3.4 倍，会把「实际只有 7.5 万 token」判成「已超 25.5 万」。
        int estimatedTokens = TokenEstimator.estimateMessages(messages, providerCode);
        int maxTokens = deepSeekConfig.resolveMaxContextTokens(providerCode);
        DeepSeekConfig.CompactionConfig cfg = deepSeekConfig.getCompaction();
        double ratio = (double) estimatedTokens / maxTokens;

        if (ratio >= cfg.getDropThreshold()) {
            log.warn("上下文压缩决策：DROP（红色）, tokens={}, 上限={}, 比例={}", estimatedTokens, maxTokens, ratio);
            return CompactionDecision.DROP;
        }
        if (ratio >= cfg.getCompactThreshold()) {
            log.info("上下文压缩决策：COMPACT（橙色）, tokens={}, 上限={}, 比例={}", estimatedTokens, maxTokens, ratio);
            return CompactionDecision.COMPACT;
        }
        if (ratio >= cfg.getWarningThreshold()) {
            log.debug("上下文压缩决策：WARN（黄色）, tokens={}, 上限={}, 比例={}", estimatedTokens, maxTokens, ratio);
            return CompactionDecision.WARN;
        }
        return CompactionDecision.NONE;
    }

    // ==================== 对外接口：压缩 ====================

    /**
     * 执行压缩（兼容旧签名）。
     *
     * @param conversationId 会话 ID
     * @param messages       本次请求的 API 格式消息列表（成功后会<b>原地</b>替换为压缩后的形态）
     * @return 压缩后的 token 估算；未压缩返回 -1
     */
    public int compact(Long conversationId, List<Map<String, Object>> messages) {
        return compact(conversationId, messages, null);
    }

    /**
     * 执行压缩（写法B）。
     *
     * <p>流程：内部按批压缩 → 全部成功才落库 → 再把内存里的 {@code messages} 同步成压缩后的形态。
     * 内存同步是必需的：否则本次请求仍带着全量历史（继续超限），而且与下次重建的前缀不一致，
     * 会导致连续两次未命中。</p>
     *
     * @param conversationId 会话 ID
     * @param messages       本次请求的 API 格式消息列表（会被原地更新）
     * @param providerCode   LLM Provider Code（为空则不做 LLM 压缩）
     * @return 压缩后的 token 估算；未压缩返回 -1
     */
    public int compact(Long conversationId, List<Map<String, Object>> messages, String providerCode) {
        DeepSeekConfig.CompactionConfig cfg = deepSeekConfig.getCompaction();
        if (!cfg.isEnabled() || conversationId == null) {
            return -1;
        }

        /*
          ★ 同一会话互斥（2026-11-23）：同步压缩与异步预压缩共用这把「会话级锁」。

          并发压缩的后果：两条请求从同一水位线出发、各自调用 LLM 压同一段历史，
          后写入的记录把先写入的 supersede 掉；数据虽不坏（摘要都是基于同一份旧摘要累进），
          但会白花一次压缩成本，且两个请求内存里替换出的前缀形态不一致。
         */
        if (!precompressingConversations.add(conversationId)) {
            log.debug("会话已有压缩在进行中，跳过本次同步压缩：conversationId={}", conversationId);
            return -1;
        }
        try {
            CompactionRecord record = doCompact(conversationId, providerCode, true);
            if (record == null) {
                return -1;
            }

            // 内存侧同步：把「倒数第 protectRounds 条 user 之前」的非 system 消息换成摘要
            if (messages != null && !messages.isEmpty() && record.getSummary() != null) {
                replaceInMemory(messages, cfg.getProtectRounds(), record.getSummary());
            }
            return TokenEstimator.estimateMessages(messages, providerCode);
        } finally {
            precompressingConversations.remove(conversationId);
        }
    }

    /**
     * 异步预压缩：在请求返回后，后台提前压缩最早的历史。
     *
     * <p>压缩成功后，下一次 {@code buildMessagesFromHistory} 会自动使用新水位线，
     * 因此本次请求不需要等待它完成。</p>
     *
     * @param conversationId 会话 ID
     */
    public void asyncPrecompress(Long conversationId) {
        asyncPrecompress(conversationId, null);
    }

    /**
     * 异步预压缩（带 Provider）。
     *
     * @param conversationId 会话 ID
     * @param providerCode   Provider 编码
     */
    public void asyncPrecompress(Long conversationId, String providerCode) {
        asyncPrecompress(conversationId, providerCode, 0, ContextMode.DEFAULT);
    }

    /**
     * 异步预压缩（带 Provider + tools 口径）。
     *
     * <p><b>为什么需要 toolsRawTokens（2026-11-23）</b>：服务端 {@code prompt_tokens} 包含 tools 定义，
     * 本项目约 20~31 个工具的 schema 恒占约 6 万 token。此前异步预压缩只用
     * {@code estimateMessages()}（不含 tools）去比 60% 阈值，真实占用会被系统性低估 6 万 token，
     * 导致「本应提前压掉」的会话拖到请求前才同步压缩（用户侧多等几十秒）。
     * 现在由调用方传入 tools 的 raw 估算，与请求前守卫、工具循环守卫使用同一套口径。</p>
     *
     * @param conversationId 会话 ID
     * @param providerCode   Provider 编码
     * @param toolsRawTokens 本次请求 tools 定义的 raw token 估算（无工具传 0）
     */
    public void asyncPrecompress(Long conversationId, String providerCode, int toolsRawTokens) {
        asyncPrecompress(conversationId, providerCode, toolsRawTokens, ContextMode.DEFAULT);
    }

    /**
     * 异步预压缩（带 Provider + tools + 上下文模式）。
     *
     * <p><b>为什么还要传 mode（2026-11-23 修复口径漂移）</b>：旧实现固定用全局
     * {@code isStripHistoryReasoning()} 估算，而实际请求是按「请求参数 &gt; sys_config &gt; 全局」
     * 解析出的会话级 {@link ContextMode} 构建的 —— 两者不一致时估算会跑偏：</p>
     * <ul>
     *   <li>会话是 balanced/compact 但全局是 full → 把整段历史 reasoning 算进去 → <b>严重高估</b>，
     *       60% 阈值提前触发，压缩频率与改前缀次数一起上升；</li>
     *   <li>会话是 compact 时工具结果已被摘要化，旧实现仍按原文估算 → 同样高估；</li>
     *   <li>反向组合（全局 balanced、请求级 full）→ 低估，压缩拖到接近上限才做。</li>
     * </ul>
     *
     * @param conversationId 会话 ID
     * @param providerCode   Provider 编码
     * @param toolsRawTokens 本次请求 tools 定义的 raw token 估算（无工具传 0）
     * @param mode           本次请求实际生效的上下文模式（{@code null} 视为默认 balanced）
     */
    public void asyncPrecompress(Long conversationId, String providerCode, int toolsRawTokens, ContextMode mode) {
        DeepSeekConfig.CompactionConfig cfg = deepSeekConfig.getCompaction();
        if (!cfg.isEnabled() || !cfg.isAsyncPrecompress() || conversationId == null) {
            return;
        }
        if (!precompressingConversations.add(conversationId)) {
            // 该会话已在压缩中
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 增量读取：只取「SYSTEM + 水位线之后」的消息（与请求路径同一口径），
                //    避免后台在每次请求结束后都全量加载几万条已被摘要覆盖的历史。
                CompactState state = loadState(conversationId);
                List<ConversationMessage> dbMessages;
                if (state.watermark > 0) {
                    dbMessages = conversationMessageMapper.selectForContext(conversationId, state.watermark);
                } else {
                    dbMessages = conversationMessageMapper.selectByConversationId(conversationId);
                }
                if (dbMessages == null || dbMessages.size() < 6) {
                    return;
                }
                dbMessages.sort(Comparator.comparing(ConversationMessage::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
                // 摘要必须在排序之后再插入：它没有 createdAt（nullsLast 会被排到末尾），
                // 先插会把位置搞错，影响「真实请求的组装顺序」这一评估前提。
                if (state.watermark > 0) {
                    insertSummaryBefore(conversationId, dbMessages, state.summary, state.watermark);
                }

                List<Map<String, Object>> apiMessages = toApiMessages(dbMessages, mode);
                // 与真实 prompt_tokens 同口径：messages raw + tools raw，再乘 provider 校准比率。
                int rawTokens = TokenEstimator.estimateMessagesRaw(apiMessages) + Math.max(0, toolsRawTokens);
                // 双口径取 max（2026-11-25）：messages 部分用字符口径（不依赖校准比率）兜底，
                // tools 沿用调用方传入的 raw token（此处没有 tools 原始对象，无法走字符口径）。
                // 否则校准比率失真时（实测低估 10.6 倍）异步预压缩也会跟着判「没超」，永不触发。
                int estimatedTokens = Math.max(
                        TokenEstimator.applyRatio(rawTokens, providerCode),
                        TokenEstimator.estimateTotalByChars(apiMessages, null) + Math.max(0, toolsRawTokens));
                int maxTokens = deepSeekConfig.resolveMaxContextTokens(providerCode);
                double ratio = (double) estimatedTokens / maxTokens;

                if (ratio < ASYNC_PRECOMPRESS_THRESHOLD) {
                    return;
                }
                if (isRecentlyCompacted(conversationId)) {
                    log.debug("异步预压缩跳过：冷却期内（水位线之后新增不足 {} 字符），conversationId={}",
                            COMPACT_COOLDOWN_CHARS, conversationId);
                    return;
                }

                log.info("异步预压缩触发：conversationId={}, provider={}, 估算 tokens={}（已应用摘要，messagesRaw={} + toolsRaw={}）, 生效上限={}, 比例={}",
                        conversationId, providerCode, estimatedTokens,
                        TokenEstimator.estimateMessagesRaw(apiMessages), Math.max(0, toolsRawTokens),
                        maxTokens, String.format("%.4f", ratio));

                // 异步路径受冷却约束：它是「提前小步压」，不值得为它频繁改前缀
                doCompact(conversationId, providerCode, false);
            } catch (Exception e) {
                log.warn("异步预压缩失败: conversationId={}, error={}", conversationId, e.getMessage());
            } finally {
                precompressingConversations.remove(conversationId);
            }
        }, precompressExecutor);
    }

    /**
     * 是否处于压缩后的冷却期（水位线之后累计新增不足 {@link #COMPACT_COOLDOWN_CHARS} 字符）。
     *
     * <p>供同步压缩路径与工具循环守卫使用：冷却期内直接跳过，避免「刚压完又压」
     * 导致前缀每轮都变、缓存命中率永远上不去。</p>
     *
     * @param conversationId 会话 ID
     * @return {@code true} 表示刚压缩过，应跳过本次压缩
     */
    public boolean isRecentlyCompacted(Long conversationId) {
        try {
            // 熔断期内视为「刚压缩过」：连续失败后不再每次请求都白等一次 LLM 超时
            // （2026-11-23：旧实现只看字符增量，压缩一直失败 + 水位线不推进时
            //  会导致每次请求都重试压缩，用户侧表现为「反复压缩、卡住不动」。）
            if (isCompactionCircuitOpen(conversationId)) {
                return true;
            }
            CompactState state = loadState(conversationId);
            if (state.watermark <= 0) {
                return false;
            }
            long charsAfter = conversationMessageMapper.sumTextLengthAfterId(
                    conversationId, state.watermark);
            return charsAfter < COMPACT_COOLDOWN_CHARS;
        } catch (Exception e) {
            log.debug("检查压缩冷却失败（忽略该保护）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 读取会话当前的「有效摘要 + 水位线」。
     *
     * <p><b>兼容旧数据</b>：旧实现允许同一会话存在多条 active 记录（区间式），
     * 这里按创建时间把它们<b>拼接</b>成一份、水位线取最大值 —— 升级后旧摘要内容不会丢，
     * 新版产生的单条记录行为不变（拼接只有一条，等价于原样返回）。</p>
     *
     * @param conversationId 会话 ID
     * @return 合并后的状态；无记录时 summary=null、watermark=0
     */
    private CompactState loadState(Long conversationId) {
        List<CompactionRecord> actives = compactionMapper.selectActiveByConversationId(conversationId);
        if (actives == null || actives.isEmpty()) {
            return new CompactState(null, 0L);
        }
        actives.sort(Comparator.comparing(CompactionRecord::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        StringBuilder sb = new StringBuilder();
        long maxWatermark = 0L;
        for (CompactionRecord r : actives) {
            if (r.getSummary() != null && !r.getSummary().isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(r.getSummary());
            }
            if (r.getEndMessageId() != null && r.getEndMessageId() > maxWatermark) {
                maxWatermark = r.getEndMessageId();
            }
        }
        return new CompactState(sb.length() == 0 ? null : sb.toString(), maxWatermark);
    }

    /** 压缩状态：一份累进摘要 + 一个水位线 */
    public static final class CompactState {
        private final String summary;
        private final long watermark;

        private CompactState(String summary, long watermark) {
            this.summary = summary;
            this.watermark = watermark;
        }

        /** 累进摘要正文（没压过时为 null） */
        public String summary() {
            return summary;
        }

        /** 水位线（{@code end_message_id}），0 表示没有任何压缩记录 */
        public long watermark() {
            return watermark;
        }
    }

    /**
     * 读取会话当前的压缩快照（水位线 + 累进摘要），供 {@code ContextBuilder} 增量重建上下文。
     *
     * <p>与 {@link #applyCompactionRecords} 的区别：那个方法是「全量读出后在内存里替换」，
     * 适合后台/兼容路径；本方法只给出水位线与摘要，调用方可以直接用
     * {@code ConversationMessageMapper#selectForContext} 在 SQL 层过滤掉被压缩的历史，
     * 避免每次请求都加载几十 MB 已废弃正文。</p>
     *
     * @param conversationId 会话 ID
     * @return 快照（无记录时 summary=null、watermark=0）
     */
    public CompactState loadSnapshot(Long conversationId) {
        return loadState(conversationId);
    }

    /**
     * 在「按水位线增量查询」得到的消息列表里插入摘要消息。
     *
     * <p>位置：第一条<b>非 SYSTEM</b> 消息之前（这些消息的 id 都大于水位线）；
     * 若列表里只有 SYSTEM，则追加到末尾。之后 {@code ContextBuilder} 会把 SYSTEM
     * 提到列表最前，最终形态与全量路径完全一致：{@code system + 摘要 + 水位线之后的对话}。</p>
     *
     * @param conversationId 会话 ID
     * @param messages       增量查询得到的消息列表（会被原地插入摘要）
     * @param summary        累进摘要正文
     * @param watermark      水位线（仅用于摘要消息的合成 id 与日志）
     */
    public void insertSummaryBefore(Long conversationId, List<ConversationMessage> messages,
                                    String summary, long watermark) {
        if (messages == null || summary == null || summary.isBlank()) {
            return;
        }
        ConversationMessage summaryMsg = buildSummaryMessage(conversationId, summary, watermark);
        int insertAt = messages.size();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getRole() != MessageRole.SYSTEM) {
                insertAt = i;
                break;
            }
        }
        messages.add(insertAt, summaryMsg);
    }

    // ==================== 对外接口：重建 ====================

    /**
     * 在构建历史消息时，把「已被摘要覆盖」的原始消息换成一条历史背景文本。
     *
     * <p><b>规则非常简单</b>：{@code id <= 水位线} 且不是真实 SYSTEM 的消息全部丢弃，
     * 在原位置插入一条摘要（role=user）。没有区间匹配、没有负 id 叠加、没有从后往前遍历，
     * 因此不可能出现旧实现那种「索引错位写错区间」。</p>
     *
     * <p>保留真实 SYSTEM 消息：系统提示词是身份/工具规范/工作目录，一旦被摘要化，
     * 模型行为会整体漂移，而且前缀从第 0 条起就不稳定。</p>
     *
     * @param conversationId 会话 ID
     * @param dbMessages     从 DB 读出的原始消息列表（会被原地修改）
     */
    public void applyCompactionRecords(Long conversationId, List<ConversationMessage> dbMessages) {
        if (dbMessages == null || dbMessages.isEmpty()) {
            return;
        }
        CompactState state = loadState(conversationId);
        if (state.watermark <= 0 || state.summary == null || state.summary.isBlank()) {
            return;
        }
        long watermark = state.watermark;

        ConversationMessage summaryMsg = buildSummaryMessage(conversationId, state.summary, watermark);

        List<ConversationMessage> result = new ArrayList<>(dbMessages.size() + 1);
        boolean placed = false;
        for (ConversationMessage m : dbMessages) {
            Long id = m.getId();
            boolean realSystem = m.getRole() == MessageRole.SYSTEM && id != null && id > 0;
            boolean covered = id != null && id > 0 && id <= watermark;
            if (covered && !realSystem) {
                if (!placed) {
                    result.add(summaryMsg);
                    placed = true;
                }
                continue;
            }
            result.add(m);
        }
        if (!placed) {
            // 覆盖范围内一条消息都没有（例如消息被删除过）→ 摘要仍放最前，保证背景不丢
            result.add(0, summaryMsg);
        }

        dbMessages.clear();
        dbMessages.addAll(result);
    }

    // ==================== 核心实现 ====================

    /**
     * 压缩一次（写法B）：分批生成摘要 → 全部成功才落库。
     *
     * <p><b>为什么必须「全部成功才落库」</b>：每次落库都会改变下一次请求的前缀，
     * 导致一次全量未命中。如果按批落库，一批一次未命中，成本直接翻 N 倍。
     * 现在整个压缩过程对模型不可见，只在最后变一次。</p>
     *
     * @param ignoreCooldown {@code true} = 忽略冷却（同步/紧急路径：已越过阈值，不压就会 400）；
     *                       {@code false} = 遵守冷却（异步预压缩：提前小步压，不值得频繁改前缀）
     * @return 新落库的记录；未压缩或中途失败返回 {@code null}（此时数据库零变更）
     */
    private CompactionRecord doCompact(Long conversationId, String providerCode, boolean ignoreCooldown) {
        DeepSeekConfig.CompactionConfig cfg = deepSeekConfig.getCompaction();

        // 1. 当前状态：水位线 + 已有摘要（旧摘要要参与本次压缩，实现「累进」）
        CompactState state = loadState(conversationId);
        long watermark = state.watermark;
        String prevSummary = state.summary;

        // 2. 冷却：水位线之后至少再积累 COMPACT_COOLDOWN_CHARS 字符，避免「刚压完又压」
        //    ⚠️ 紧急路径（上下文已越过压缩阈值）必须绕过：否则冷却期内涨到上限就是直接 400。
        if (!ignoreCooldown) {
            long charsAfter = conversationMessageMapper.sumTextLengthAfterId(conversationId, watermark);
            if (charsAfter < COMPACT_COOLDOWN_CHARS) {
                log.debug("压缩跳过：冷却期内（水位线之后新增 {} 字符 < {}）, conversationId={}",
                        charsAfter, COMPACT_COOLDOWN_CHARS, conversationId);
                return null;
            }
        }

        // 2.5 旧摘要体积预检与归并（2026-11-23，修复「旧摘要过大 → 压缩永远超时」）
        //
        //     旧版按区间写多条 active 记录，loadState 会全部拼接；实测某会话拼出 86.7 万字符。
        //     若不处理，这段文本会被全量拼进压缩 prompt（再叠加单批 15 万字符），
        //     45 秒内 prefill 必超时 → 压缩永远失败 → 水位线不动 → 每次请求都重试，
        //     形成「反复压缩但零变更」的死循环，同时巨型摘要本身把上下文顶在上限附近。
        //
        //     这里先把它按 MAX_BATCH_CHARS 分块归并成一份精简摘要，再继续正常压缩流程；
        //     归并结果随本次落库一起生效（不单独写库，避免多改一次前缀）。
        boolean summaryMerged = false;
        if (prevSummary != null && prevSummary.length() > MAX_SUMMARY_CHARS) {
            log.warn("旧摘要过大（{} 字符 > {}），先做分批归并：conversationId={}",
                    prevSummary.length(), MAX_SUMMARY_CHARS, conversationId);
            String merged = mergeOversizedSummary(prevSummary, providerCode);
            if (merged == null || merged.isBlank()) {
                log.warn("旧摘要归并失败，本次放弃（数据库零变更）：conversationId={}", conversationId);
                recordCompactionFailure(conversationId);
                return null;
            }
            log.info("旧摘要归并完成：{} 字符 → {} 字符, conversationId={}",
                    prevSummary.length(), merged.length(), conversationId);
            // 硬上限兜底：归并输出仍可能超过阈值（模型长度不可控），必须截断——
            // 否则 loadState 每次都会读到超限摘要，而归并不推进水位线、冷却判不出来，
            // 会退化成「每次请求都归并一次」的死循环。
            prevSummary = enforceSummaryLimit(merged, conversationId);
            summaryMerged = true;
        }

        // 3. 压缩上界：倒数第 protectRounds 条 user 消息的 id
        //    ★ 用 id < 上界 取区间，最后一条 user 及其之后天然不在压缩范围内 → 本轮永不被压
        //
        //    ⚠️ offset 必须是 protectRounds-1（不是 protectRounds）：
        //    selectNthUserMessageIdFromTail 的 OFFSET 0 = 最新一条 user，
        //    而区间用的是「id < 上界」（不含上界本身）。
        //    若直接传 protectRounds，会多保护一轮（protectRounds=8 实际保护 9 轮）。
        //    protectRounds=0 时 offset 取 0 → 上界 = 最后一条 user，区间 = 它之前全部，
        //    即「本轮永不压、历史全可压」，语义正确。
        int boundaryOffset = Math.max(0, cfg.getProtectRounds() - 1);
        Long boundaryExclusive = conversationMessageMapper.selectNthUserMessageIdFromTail(
                conversationId, null, boundaryOffset);
        if (boundaryExclusive == null || boundaryExclusive <= watermark) {
            // 无历史可压：如果刚刚做过「旧摘要归并」，仍要落库把它固化下来（自愈），
            // 否则下次重建又会把巨型摘要拼回上下文，等于白归并一次。
            if (summaryMerged) {
                return persistMergedSummaryOnly(conversationId, prevSummary, watermark);
            }
            log.debug("压缩跳过：可压缩区间为空（保护带覆盖全部历史或历史轮数不足）, boundary={}, 水位线={}",
                    boundaryExclusive, watermark);
            return null;
        }

        // 4. 取待压缩消息（id > 水位线 && id < 上界）
        //    ★ 排除 SYSTEM：系统提示词是身份/工具规范/工作目录，绝不能进摘要。
        //      重建时 applyCompactionRecords 也会原样保留它，两边口径一致。
        long startInclusive = watermark + 1;
        List<ConversationMessage> pending = new ArrayList<>();
        for (ConversationMessage m : conversationMessageMapper.selectRange(
                conversationId, startInclusive, boundaryExclusive)) {
            if (m.getRole() == MessageRole.SYSTEM) {
                continue;
            }
            pending.add(m);
        }
        // 4.5 最小收益判断（2026-11-23）：可压区间太小就不值得改一次前缀。
        //     上界随用户每轮新提问前移，没有这道闸门就会出现「每次只压 2 条消息、
        //     摘要却重写一遍」的抖动（实测 start=81030/end=81031 只覆盖 2 条）。
        //     阈值按「当前 Provider 生效上限」折算（5% token ÷ 0.425 字符/token），
        //     下限 3 万字符 —— 小窗口 Provider 不能套用 1M 窗口的绝对阈值。
        long pendingChars = 0L;
        for (ConversationMessage m : pending) {
            pendingChars += textLen(m.getContent()) + textLen(m.getReasoning());
        }
        int guardMaxTokens = deepSeekConfig.resolveMaxContextTokens(providerCode);
        long minPendingChars = Math.max(MIN_PENDING_CHARS_FLOOR,
                (long) (guardMaxTokens * 0.05 / 0.425));
        if (pending.size() < 2 || pendingChars < minPendingChars) {
            if (summaryMerged) {
                // 历史太少不值得压，但「旧摘要归并」这个修复本身必须落库，
                // 否则巨型摘要会一直留在上下文里。
                return persistMergedSummaryOnly(conversationId, prevSummary, watermark);
            }
            log.debug("压缩跳过：可压区间过小（{} 条 / {} 字符 < {}）, conversationId={}",
                    pending.size(), pendingChars, minPendingChars, conversationId);
            return null;
        }

        // 5. 分批（单批有字符上限，保证单次 LLM 请求不会超时）
        List<List<ConversationMessage>> batches = splitBatches(pending, cfg.getBatchSize());

        // 6. 逐批累进生成摘要 —— 全部成功前不碰数据库
        String rolling = prevSummary;
        long newWatermark = watermark;
        for (int i = 0; i < batches.size(); i++) {
            List<ConversationMessage> batch = batches.get(i);
            String next = summarizeOnce(rolling, batch, providerCode);
            if (next == null || next.isBlank()) {
                log.warn("压缩中止：第 {}/{} 批失败，本次整体放弃（数据库零变更，下次重试）, conversationId={}",
                        i + 1, batches.size(), conversationId);
                recordCompactionFailure(conversationId);
                return null;
            }
            // 每批产出都过一遍硬上限：避免中间过程就把摘要滚大（最终落库的必须是截断后的版本）
            rolling = enforceSummaryLimit(next, conversationId);
            newWatermark = batch.get(batch.size() - 1).getId();
        }

        // 7. 落库（一次性）
        //    savings 用「原始摘要（state.summary）」作基数，而不是归并后的 prevSummary ——
        //    归并本身就是为了削掉旧摘要的膨胀部分，用归并值会掩盖真实收益。
        int beforeChars = textLen(state.summary);
        for (ConversationMessage m : pending) {
            beforeChars += textLen(m.getContent()) + textLen(m.getReasoning());
        }
        int beforeTokens = TokenEstimator.estimate(String.valueOf(beforeChars));
        int afterTokens = TokenEstimator.estimate(rolling);
        int savings = Math.max(0, beforeTokens - afterTokens);

        CompactionRecord record = new CompactionRecord(
                conversationId, rolling, watermark, newWatermark, savings);
        compactionMapper.insert(record);
        compactionMapper.markSupersededExcept(conversationId, record.getId());
        recordCompactionSuccess(conversationId);

        log.info("压缩完成（写法B）：conversationId={}, 批数={}, 消息={} 条, 水位线 {} → {}, 摘要 {} 字符, 节省≈{} tokens",
                conversationId, batches.size(), pending.size(), watermark, newWatermark, rolling.length(), savings);
        return record;
    }

    /**
     * 只落库「归并后的摘要」而不推进水位线。
     *
     * <p>用于两种场景：① 历史已经不够压（boundary &lt;= 水位线）；② 可压区间太小、
     * 尚未达到 {@link #MIN_PENDING_CHARS_TO_COMPACT}。此时压缩本身不划算，
     * 但旧摘要归并是必须固化的修复 —— 否则下次重建又会把巨型摘要拼回上下文。</p>
     *
     * @param conversationId 会话 ID
     * @param mergedSummary  归并后的精简摘要
     * @param watermark      当前水位线（保持不变）
     * @return 新落库的记录
     */
    private CompactionRecord persistMergedSummaryOnly(Long conversationId, String mergedSummary, long watermark) {
        // 再次兜底：本方法不推进水位线，摘要必须严格小于阈值，否则下次请求会再次触发归并。
        String safeSummary = enforceSummaryLimit(mergedSummary, conversationId);
        CompactionRecord record = new CompactionRecord(
                conversationId, safeSummary, watermark, watermark, 0);
        compactionMapper.insert(record);
        compactionMapper.markSupersededExcept(conversationId, record.getId());
        recordCompactionSuccess(conversationId);
        log.info("旧摘要归并已落库（无可压历史，水位线保持 {}）：conversationId={}, 摘要 {} 字符",
                watermark, conversationId, safeSummary.length());
        return record;
    }

    /**
     * 把「过大的旧摘要」按 {@link #MAX_BATCH_CHARS} 分块、逐块累进归并成一份精简摘要。
     *
     * <p>与历史压缩走同一套 {@link #summarizeOnce}，因此单批请求始终可控，
     * 不会出现「一次塞 86.7 万字符 → 必然超时」的问题。</p>
     *
     * @param prevSummary  超大摘要正文
     * @param providerCode Provider 编码
     * @return 归并后的摘要；任一批失败返回 {@code null}（调用方整体放弃）
     */
    private String mergeOversizedSummary(String prevSummary, String providerCode) {
        List<String> chunks = splitTextByChars(prevSummary, MAX_BATCH_CHARS);
        String rolling = null;
        for (int i = 0; i < chunks.size(); i++) {
            List<ConversationMessage> batch = List.of(virtualUserMessage(chunks.get(i)));
            String next = summarizeOnce(rolling, batch, providerCode);
            if (next == null || next.isBlank()) {
                log.warn("旧摘要归并中止：第 {}/{} 批失败，本次整体放弃", i + 1, chunks.size());
                return null;
            }
            rolling = next;
        }
        return rolling;
    }

    /**
     * 摘要硬上限保护：超过 {@link #MAX_SUMMARY_CHARS} 时截断、只保留「较晚发生的事实」。
     *
     * <p><b>为什么必须硬截断（2026-11-23）</b>：累进摘要的输入包含旧摘要，模型输出长度不可控。
     * 若只做「超过就再归并」，而归并本身<b>不推进水位线</b>，按「水位线之后新增字符」计算的冷却
     * 就不会生效 —— 结果是每次请求都归并一次、每次都改前缀（比不压更糟）。
     * 这里用硬上限兜底：保证 {@link #loadState} 读到的摘要一定在阈值内，归并最多发生一次。</p>
     *
     * <p>截断保留尾部：累积摘要按时间顺序书写，越靠后的事实越贴近当前任务；
     * 截断点会向后对齐到最近的换行，避免把一句话劈成两半。</p>
     *
     * @param summary        模型产出的摘要
     * @param conversationId 会话 ID（仅用于日志）
     * @return 不超过 {@link #MAX_SUMMARY_CHARS} 的摘要
     */
    private String enforceSummaryLimit(String summary, Long conversationId) {
        if (summary == null || summary.length() <= MAX_SUMMARY_CHARS) {
            return summary;
        }
        int cut = summary.length() - MAX_SUMMARY_CHARS;
        int firstNewline = summary.indexOf('\n', cut);
        if (firstNewline > 0 && firstNewline < cut + 200) {
            cut = firstNewline + 1;
        }
        String truncated = summary.substring(cut);
        log.warn("摘要超上限（{} 字符 > {}），已截断保留最近部分：conversationId={}",
                summary.length(), MAX_SUMMARY_CHARS, conversationId);
        return truncated;
    }

    /**
     * 按字符上限切分长文本（优先在换行处断开，避免把一句话劈成两半）。
     *
     * @param text     原文本
     * @param maxChars 单块字符上限
     * @return 切分后的文本块（保序）
     */
    private List<String> splitTextByChars(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start + maxChars / 2) {
                    end = lastNewline + 1;
                }
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    /** 构造一条「仅用于归并摘要」的虚拟 user 消息（不落库；id=0 表示非真实消息） */
    private ConversationMessage virtualUserMessage(String content) {
        ConversationMessage m = new ConversationMessage();
        m.setId(0L);
        m.setRole(MessageRole.USER);
        m.setContent(content);
        return m;
    }

    /** 记录一次压缩失败；连续失败达到阈值后熔断一段时间，避免每次请求都白等 LLM 超时 */
    private void recordCompactionFailure(Long conversationId) {
        int failures = consecutiveFailures.merge(conversationId, 1, Integer::sum);
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            failureCircuitUntil.put(conversationId, System.currentTimeMillis() + FAILURE_CIRCUIT_MILLIS);
            consecutiveFailures.remove(conversationId);
            log.error("压缩连续失败 {} 次，熔断 {} 分钟（熔断期内不再尝试压缩）：conversationId={}",
                    failures, FAILURE_CIRCUIT_MILLIS / 60_000, conversationId);
        }
    }

    /** 压缩成功：清除失败计数与熔断 */
    private void recordCompactionSuccess(Long conversationId) {
        consecutiveFailures.remove(conversationId);
        failureCircuitUntil.remove(conversationId);
    }

    /** 是否处于「压缩失败熔断期」 */
    private boolean isCompactionCircuitOpen(Long conversationId) {
        Long until = failureCircuitUntil.get(conversationId);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() < until) {
            return true;
        }
        failureCircuitUntil.remove(conversationId);
        return false;
    }

    /**
     * 按「字符预算 + 轮数」切批。
     *
     * <p>切点优先落在 user 消息处（保持一轮的完整性），单批最多
     * {@link #MAX_BATCH_CHARS} 字符 / {@code batchSize} 轮。</p>
     *
     * @param pending      待压缩消息（id 升序）
     * @param cfgBatchSize 配置的每批轮数（&lt;=0 用默认值）
     * @return 批次列表
     */
    private List<List<ConversationMessage>> splitBatches(List<ConversationMessage> pending, int cfgBatchSize) {
        int roundLimit = cfgBatchSize > 0 ? cfgBatchSize : DEFAULT_BATCH_ROUNDS;
        List<List<ConversationMessage>> batches = new ArrayList<>();
        List<ConversationMessage> cur = new ArrayList<>();
        int curChars = 0;
        int curRounds = 0;

        for (ConversationMessage m : pending) {
            int len = textLen(m.getContent()) + textLen(m.getReasoning());
            boolean isUser = m.getRole() == MessageRole.USER;
            // 超字符预算、或已攒够轮数且遇到新一轮起点 → 切批（保证当前批至少 1 条）
            if (!cur.isEmpty()
                    && (curChars + len > MAX_BATCH_CHARS || (isUser && curRounds >= roundLimit))) {
                batches.add(cur);
                cur = new ArrayList<>();
                curChars = 0;
                curRounds = 0;
            }
            cur.add(m);
            curChars += len;
            if (isUser) {
                curRounds++;
            }
        }
        if (!cur.isEmpty()) {
            batches.add(cur);
        }
        return batches;
    }

    /**
     * 调用 LLM 生成一批的摘要（旧摘要 + 本批消息 → 新摘要，累进）。
     *
     * @param prevSummary  上一版摘要（可为 null）
     * @param batch        本批消息
     * @param providerCode Provider 编码（必须显式传入，避免误扣其他账户余额）
     * @return 新摘要；失败返回 {@code null}
     */
    private String summarizeOnce(String prevSummary, List<ConversationMessage> batch, String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            log.warn("压缩跳过：未提供 providerCode（避免误用默认 Provider 扣错账户）");
            return null;
        }
        LLMClient client = llmClientManager.resolveClientByCode(providerCode);
        if (client == null) {
            log.error("无法获取 LLMClient，providerCode={}, 跳过压缩", providerCode);
            return null;
        }

        String model = deepSeekConfig.getCompaction().getModel();
        if (model == null || model.isBlank()) {
            model = llmClientManager.getDefaultModel(client.getProviderCode());
        }

        String historyText = formatMessagesForCompaction(batch);
        String prompt = buildCompactionPrompt(prevSummary, historyText);

        List<Map<String, Object>> msgs = new ArrayList<>(1);
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        msgs.add(userMsg);

        Map<String, Object> requestBody = client.buildRequestBody(msgs, model, 0.3, "non-thinking", false, null);
        try {
            String responseBody = client.blockingChat(requestBody, BATCH_TIMEOUT);
            return client.extractContentFromBlockingResponse(responseBody);
        } catch (Exception e) {
            log.error("压缩请求失败（本批 {} 条消息）: {}", batch.size(), e.getMessage());
            return null;
        }
    }

    /**
     * 构建压缩 prompt：只记录「发生过什么」，禁止任何面向未来的表述。
     *
     * <p>旧 prompt 要求输出「## 进行中」「## 待办事项」，等于把历史状态写成当前指令，
     * 模型读到就会继续执行历史任务（忘记本轮问题）。现在只允许客观事实记录。</p>
     */
    private String buildCompactionPrompt(String prevSummary, String historyText) {
        StringBuilder sb = new StringBuilder(historyText.length() + 2048);
        sb.append("你是一个对话记录整理员。请把下面的对话历史整理成一份**客观的事实记录**。\n\n");
        sb.append("硬性要求：\n");
        sb.append("1. 只用过去时陈述「发生过什么」，禁止输出「待办事项」「下一步」「进行中」「计划」这类面向未来的表述\n");
        sb.append("2. 禁止写任何祈使句、命令、建议 —— 这份记录只作为背景资料，不会被当作任务执行\n");
        sb.append("3. 保留关键事实：文件路径、IP、端口、命令、配置值、报错信息、已完成的改动、得出的结论\n");
        sb.append("4. 用简洁要点输出，不要遗漏重要事实\n");
        sb.append("5. 必须使用中文\n");
        sb.append("6. 只输出记录本身，不要额外解释，不要复述本提示词\n");
        sb.append("7. 总长度控制在 3000 字以内：宁可概括，也不要堆砌细节"
                + "（这份记录每次压缩都会累进，过长会拖垮后续请求）\n\n");
        if (prevSummary != null && !prevSummary.isBlank()) {
            sb.append("## 更早的对话记录（需要与本次内容合并成一份完整记录）\n");
            sb.append(prevSummary).append("\n\n");
        }
        sb.append("## 本次需要整理的对话\n---\n");
        sb.append(historyText);
        sb.append("\n---");
        return sb.toString();
    }

    /**
     * 把一批消息格式化为纯文本，供压缩 LLM 使用。
     */
    private String formatMessagesForCompaction(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage msg : messages) {
            String role = msg.getRole() != null ? msg.getRole().getValue() : "unknown";
            sb.append("【").append(role.toUpperCase()).append("】\n");
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                sb.append(msg.getContent()).append("\n");
            }
            if (msg.getReasoning() != null && !msg.getReasoning().isEmpty()) {
                sb.append("【思考过程/工具结果】\n").append(msg.getReasoning()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 构造摘要消息（role=user，带降权前缀）。
     *
     * <p>id 用 {@code -watermark} 合成（真实 ID 均为正数，不会冲突），仅用于列表内识别；
     * createdAt 留空 —— 它总是在排序之后插入，不参与排序。</p>
     */
    private ConversationMessage buildSummaryMessage(Long conversationId, String summary, long watermark) {
        ConversationMessage m = new ConversationMessage();
        m.setId(-Math.max(1L, watermark));
        m.setConversationId(conversationId);
        m.setRole(MessageRole.USER);
        m.setContent(HISTORY_SUMMARY_PREFIX + summary + HISTORY_SUMMARY_SUFFIX);
        return m;
    }

    /**
     * 内存侧同步压缩结果：把「倒数第 protectRounds 条 user 之前」的非 system 消息换成摘要。
     *
     * <p>与 DB 侧用<b>同一套规则</b>（倒数第 N 条 user），因此不需要 message id，
     * 也不存在「API 列表下标 vs DB 下标」的对齐问题 —— 两条链路看到的是同一批 user 消息。</p>
     *
     * <p><b>⚠️ 为什么第一步必须先删掉旧摘要</b>：摘要本身也是 {@code role=user}，
     * 如果留在列表里，"倒数第 N 条 user"会把摘要也算进去 → 定位点整体前移，
     * 结果就是压缩完变成两条摘要、且新摘要没覆盖任何真实历史。
     * 先移除再定位，两种形态（首压 / 二次压）都能对齐。</p>
     *
     * @param messages      本次请求的 API 格式消息列表（原地修改）
     * @param protectRounds 保护带轮数
     * @param summary       新摘要正文
     */
    private void replaceInMemory(List<Map<String, Object>> messages, int protectRounds, String summary) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 1. 先清掉上一版摘要（幂等：没有则无操作，避免摘要套娃 / 轮次定位偏移）
        messages.removeIf(m -> "user".equals(m.get("role"))
                && m.get("content") instanceof String text
                && text.startsWith(HISTORY_SUMMARY_PREFIX.substring(0, 12)));

        // 2. 按 user 轮次定位压缩上界
        int cut = findNthUserFromTailIndex(messages, protectRounds);
        if (cut <= 0) {
            return;
        }

        Map<String, Object> summaryMsg = new HashMap<>();
        summaryMsg.put("role", "user");
        summaryMsg.put("content", HISTORY_SUMMARY_PREFIX + summary + HISTORY_SUMMARY_SUFFIX);

        List<Map<String, Object>> kept = new ArrayList<>(messages.size());
        boolean placed = false;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);
            boolean isSystem = "system".equals(m.get("role"));
            if (i < cut && !isSystem) {
                if (!placed) {
                    kept.add(summaryMsg);
                    placed = true;
                }
                continue;
            }
            kept.add(m);
        }
        if (!placed) {
            kept.add(0, summaryMsg);
        }
        messages.clear();
        messages.addAll(kept);
    }

    /**
     * 返回「倒数第 protectRounds 条 user 消息」的下标；不足则返回 -1。
     *
     * <p>与 DB 侧 boundary 口径严格一致：压缩区间是 {@code [0, 返回值)}，
     * 因此倒数第 protectRounds 条及之后（共 protectRounds 轮）全部受保护。
     * protectRounds=0 时返回最后一条 user 的下标 —— 保住本轮提问。</p>
     */
    private int findNthUserFromTailIndex(List<Map<String, Object>> messages, int protectRounds) {
        int target = Math.max(1, protectRounds);
        int found = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                found++;
                if (found == target) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int textLen(String s) {
        return s == null ? 0 : s.length();
    }

    // ==================== 消息格式转换 ====================

    /** 旧签名兼容重载：等价于 balanced（strip=true）/ full（strip=false） */
    static List<Map<String, Object>> toApiMessages(List<ConversationMessage> messages,
                                                   boolean stripHistoryReasoning) {
        return toApiMessages(messages,
                stripHistoryReasoning ? ContextMode.BALANCED : ContextMode.FULL);
    }

    /**
     * 将 ConversationMessage 列表转为「与真实请求口径一致」的 API 格式消息列表。
     *
     * <p><b>为什么要传 mode（2026-11-23 修复口径漂移）</b>：真实请求的消息由
     * {@code ContextBuilder#buildMessagesFromHistory} 按会话生效的 {@link ContextMode} 构建：
     * balanced/compact 会把历史 reasoning 置为空串，compact 还会把「最后一条 user 之前」的
     * 历史工具结果替换成摘要。估算若不同步这两条规则，就会与真实请求体产生数量级偏差 ——
     * 高估会让 60% 阈值提前触发（压缩频率、改前缀次数一起上升），低估则压得太晚。</p>
     *
     * <p>TOOL 消息的工具结果存在 {@code reasoning} 字段（content 通常为 NULL），
     * 且<b>不受 reasoning 剔除策略影响</b>（它存的是工具结果，不是思考链）。</p>
     *
     * @param messages DB 消息（已排序、已应用水位线与摘要）
     * @param mode     本次会话实际生效的上下文模式（{@code null} 视为默认 balanced）
     * @return API 格式消息列表，与入参逐条对应
     */
    static List<Map<String, Object>> toApiMessages(List<ConversationMessage> messages, ContextMode mode) {
        ContextMode effectiveMode = mode != null ? mode : ContextMode.DEFAULT;
        boolean stripReasoning = effectiveMode.shouldStripHistoryReasoning();
        boolean compactToolResults = effectiveMode.shouldCompactToolResults();

        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }

        // compact 模式只摘要「最后一条 user 之前」的工具结果（与 ContextBuilder 同一边界）：
        // 本轮对话（最后一条 user 及其后）在真实请求里仍是工具结果原文，不能按摘要估算。
        int lastUserIdx = -1;
        if (compactToolResults) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).getRole() == MessageRole.USER) {
                    lastUserIdx = i;
                    break;
                }
            }
        }

        for (int idx = 0; idx < messages.size(); idx++) {
            ConversationMessage msg = messages.get(idx);
            Map<String, Object> map = new HashMap<>();
            MessageRole role = msg.getRole();

            // 角色缺失时保底占位，绝不能让条数变少
            if (role == null) {
                map.put("role", "system");
                result.add(map);
                continue;
            }

            map.put("role", role.getValue());

            if (role == MessageRole.TOOL) {
                String content = msg.getReasoning() != null ? msg.getReasoning()
                        : (msg.getContent() != null ? msg.getContent() : "");
                if (compactToolResults && idx < lastUserIdx) {
                    // 与 ContextBuilder#compactHistoryMessages 的产出同量级（约 40 字符）。
                    // 估算不需要真的生成摘要文本，只要方向正确（不计原文长度）。
                    map.put("content", "[工具调用结果已精简]");
                } else {
                    map.put("content", content);
                }
            } else {
                if (msg.getContent() != null) {
                    map.put("content", msg.getContent());
                }
                if (role == MessageRole.ASSISTANT) {
                    if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                        map.put("tool_calls", msg.getToolCalls());
                    }
                    if (msg.getReasoning() != null && !stripReasoning) {
                        map.put("reasoning_content", msg.getReasoning());
                    }
                }
            }
            result.add(map);
        }
        return result;
    }

    // ==================== 内部枚举 ====================

    public enum CompactionDecision {
        /** 无需压缩 */
        NONE,
        /** 黄色预警：标记但暂不压缩 */
        WARN,
        /** 橙色压缩：执行 LLM 压缩 */
        COMPACT,
        /** 红色：压缩后仍超限 */
        DROP
    }
}
