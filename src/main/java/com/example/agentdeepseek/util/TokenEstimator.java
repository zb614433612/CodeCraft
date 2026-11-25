package com.example.agentdeepseek.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 精确 Token 估算工具类
 * 基于 JTokkit（Java 版 tiktoken），支持 DeepSeek / OpenAI 系列模型的 token 计数。
 * 用于上下文压缩前的精确用量判断，替代原有的字符数/3 粗略估算。
 */
@Slf4j
public class TokenEstimator {

    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();

    /*
     ═══════════════════════════════════════════════════════════════════════════
     在线校准（2026-11-17 修复「估算虚高 3.4 倍 → 误触发压缩/裁剪」）

     【为什么必须有校准】
     jtokkit 的 CL100K_BASE 是 OpenAI 的分词器。实测同一份请求：
       · 本类估算：438225 tokens
       · API 实际：130290 tokens（llm_token_usage.prompt_tokens）
     相差 3.36 倍。中英文混合（大量中文 + 代码）场景下 CL100K 会把一个汉字拆成
     1~2 个 token，而 DeepSeek 的 tokenizer 对中文效率高得多（约 0.6 token/汉字）。

     【后果有多严重】
     守卫阈值是按「真实 token」设计的（max-context-tokens=300000 → 85% = 255000）。
     拿虚高 3.4 倍的估算去比 → 实际上下文到 7.5 万 token 就误判超限 → 每轮都压缩
     → 每轮都改写历史 → 前缀缓存命中率 0% → 全量 prefill（125K 上下文 ≈ 30 秒）。

     【校准怎么做】
     每个请求的响应都带 usage.prompt_tokens（真实值），据此反推比率：
       observed = actual / finalEstimate
       ratio_new = ratio_old × ((1-α) + α × observed)
     其中 finalEstimate = raw × ratio_old，故上式等价于让 ratio 逼近 actual/raw。
     用滑动平均（α=0.3）抗单次噪声；异常观测（&lt;0.10 或 &gt;2.0）直接丢弃。

     【口径说明】
     · estimate(String) 返回 **raw 值**（不乘比率）——它只用于同口径的相对比较
       （如压缩前后 savings），两侧同为 raw，比值关系不受比率影响。
     · estimateMessages(...) 返回 **校准后的值**——它用于与真实 maxTokens 比较。
     两者不可混用，否则会重新引入口径错位。
     ═══════════════════════════════════════════════════════════════════════════
    */

    /** providerCode（小写） → 校准比率；键 {@link #GLOBAL_RATIO_KEY} 为跨 provider 兜底 */
    private static final java.util.Map<String, Double> CALIBRATION =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 全局比率的键（空串，避免与真实 providerCode 冲突） */
    private static final String GLOBAL_RATIO_KEY = "";

    /** 尚未观测到任何真实 usage 时的比率：1.0 表示「不修正」，即保持历史行为 */
    private static final double DEFAULT_RATIO = 1.0;

    /** 滑动平均的新观测权重（0.3 表示单次观测只挪动 30%，抗噪声） */
    private static final double CALIBRATION_ALPHA = 0.3;

    /**
     * 比率合理区间：单次超出仍视为解析异常（usage 帧错乱 / tools 口径未对齐），丢弃该次观测。
     *
     * <p><b>边界值调整（2026-11-25）</b>：原区间 [0.10, 2.00] 已被真实数据从两端撞穿：</p>
     * <ul>
     *   <li>中文密集会话（工具结果是中文格式化文本）正确比率低至 <b>0.073</b>，
     *       被 0.10 下限钳死 → 估算只有真实值的 45%，且永远无法继续下调；</li>
     *   <li>英文 / 代码密集会话正确比率可达 <b>2.0 以上</b>（实测 raw=540,496 → 真实 1,080,992）。</li>
     * </ul>
     * <p>同一个槽位表示不了 0.073~2.0 这种跨 27 倍的形态差异，钳制只会把误差永久固化。</p>
     */
    private static final double MIN_RATIO = 0.03;
    private static final double MAX_RATIO = 3.00;

    /**
     * 连续「同向」异常观测达到该次数后，判定当前比率已严重失真，
     * 直接用 {@code ratio_old × observed}（= actual / raw）反推重建，不再丢弃。
     *
     * <p><b>为什么必须这样（2026-11-25 会话 612 事故）</b>：比率被污染到下限 0.10 后，
     * 发送前估算 115,847、真实 1,229,069，观测比率恒为 10.6 —— 旧实现每次都判「异常」丢弃，
     * 比率永不修正，守卫因此永不触发压缩，请求一路涨到 1,048,576 上限后返回 400。
     * 「丢弃坏样本」的前提是比率本身还靠谱；比率已经失真时必须反推。</p>
     */
    private static final int ABNORMAL_STREAK_TO_RESET = 2;

    /**
     * 单次「反推重建」允许相对当前比率放大/缩小的最大倍数（2026-11-25 审查新增）。
     *
     * <p><b>为什么需要这道保护</b>：反推公式 {@code corrected = oldRatio × observed} 在
     * usage 帧正常时恒等于 {@code actual/raw}（正确值）；但如果 usage 帧本身错乱
     * （拿到别次请求的 usage、代理改写、跨 provider 混用），observed 可能是个巨大值，
     * 会把比率一步顶到 {@link #MAX_RATIO} 上限 —— 之后估算<b>虚高</b> 3 倍，
     * 守卫反复触发压缩、前缀缓存被反复推翻，比「低估」更难排查。</p>
     *
     * <p>取 20 倍：正常失真的量级在 3~10 倍（实测 612 需要 0.10 → 1.06，即 10.6 倍），
     * 20 倍足够覆盖真实场景，又能挡住明显的帧错乱。</p>
     */
    private static final double RESET_MAX_FACTOR = 20.0;

    /** provider → 连续异常观测的方向与次数（正数=估算偏低，负数=估算偏高） */
    private static final java.util.Map<String, Integer> ABNORMAL_STREAK =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 估算单段文本的 token 数
     * @param text 输入文本
     * @return token 数量
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            Encoding enc = registry.getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
            return enc.countTokens(text);
        } catch (Exception e) {
            // 保底：字符数/2（比 /3 更保守）
            log.debug("JTokkit 估算失败，使用保底算法: {}", e.getMessage());
            return text.length() / 2 + 1;
        }
    }

    /**
     * 估算 API 消息列表的总 token 数（<b>校准后</b>，可直接与 maxTokens 比较）。
     *
     * <p>无 provider 上下文时使用全局校准比率；这是兼容旧调用点的入口，
     * 新代码请优先使用 {@link #estimateMessages(List, String)} 传入真实 provider，
     * 因为不同平台的分词效率不同（实测 DeepSeek 中文场景比 CL100K 低约 3.4 倍）。</p>
     *
     * @param messages API 格式的消息列表
     * @return 校准后的总 token 数
     */
    public static int estimateMessages(List<Map<String, Object>> messages) {
        return estimateMessages(messages, null);
    }

    /**
     * 估算 API 消息列表的总 token 数（按 provider 校准后）。
     *
     * @param messages     API 格式的消息列表
     * @param providerCode Provider 编码（deepseek/huoshan/openai/...）；{@code null} 用全局比率
     * @return 校准后的总 token 数，可直接与「真实 maxTokens」比较
     */
    public static int estimateMessages(List<Map<String, Object>> messages, String providerCode) {
        int raw = estimateMessagesRaw(messages);
        if (raw <= 0) {
            return 0;
        }
        long calibrated = Math.round(raw * ratioOf(providerCode));
        if (calibrated < 1) {
            calibrated = 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, calibrated);
    }

    /**
     * 未校准的原始估算（cl100k 口径）。
     *
     * <p><b>⚠️ 不要拿它与 maxTokens 比较</b>——它是 OpenAI 分词口径，中文场景比
     * DeepSeek 真实 token 高约 3.4 倍，直接比较会导致「实际没超限却判定超限」。
     * 它只用于「同口径的相对比较」（如压缩前后 savings 差值）。</p>
     *
     * @param messages API 格式的消息列表
     * @return 原始（未校准）token 估算
     */
    public static int estimateMessagesRaw(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;
        for (Map<String, Object> msg : messages) {
            // 每条消息约 3~5 token 的基础开销（role 字段等）
            total += 4;

            // content 字段（M3：支持多模态数组——text 块按文本估算，file/image 块按 ~1024 token/块）
            total += estimateContent(msg.get("content"));

            // reasoning_content 字段（DeepSeek 思考链）
            String reasoning = (String) msg.getOrDefault("reasoning_content", "");
            if (reasoning != null && !reasoning.isEmpty()) {
                total += estimate(reasoning);
            }

            // tool_calls 字段（含 function.name 与 function.arguments）
            //
            // ⚠️ 不能只加固定值（2026-11-16 修复）：此前这里写 `total += 15`，把整个
            // tool_calls 当成 15 token 的固定开销。但实测 tool_calls 的 JSON 里
            // `function.arguments` 才是体积大头 —— file_writer 写一个完整文件时，
            // 全部文件内容都在 arguments 字符串里，动辄数万字符（≈上万 token）。
            // 严重低估的直接后果：estimateMessages 是所有裁剪/压缩决策的依据
            // （trimToTokenBudget / trimToolLoopToBudget / CompactionService.decide），
            // 低估 → 判定「没超限」→ 不裁剪 → 请求真的超限 → API 400，整轮失败。
            // 因此这里按实际 JSON 文本精确估算，只保留极小的结构开销。
            if (msg.containsKey("tool_calls")) {
                Object toolCalls = msg.get("tool_calls");
                int toolCallsTokens = estimateToolCalls(toolCalls);
                // 无 tool_calls 内容时（null/空数组）仍保留最小结构开销
                total += toolCallsTokens > 0 ? toolCallsTokens : 15;
            }

            // tool_call_id 字段
            if (msg.containsKey("tool_call_id")) {
                total += 3;
            }
        }
        return Math.max(total, 1);
    }

    /**
     * 估算 content 字段的 token 数（兼容 String 与多模态内容块数组）
     * <p>
     * 数组形态（M3 图像注入后）：text 块按文本精确估算；file / image_url 等图片块按
     * ~1024 token/块 估算（DeepSeek 文档口径；仅预算守卫用途，非计费口径）。
     * </p>
     *
     * @param content 消息 content（String / List&lt;Map&gt; / null）
     * @return token 估算值
     */
    private static int estimateContent(Object content) {
        if (content == null) {
            return 0;
        }
        if (content instanceof String text) {
            return estimate(text);
        }
        if (content instanceof List<?> parts) {
            int sum = 0;
            for (Object item : parts) {
                if (item instanceof Map<?, ?> block) {
                    Object type = block.get("type");
                    if ("text".equals(type)) {
                        Object text = block.get("text");
                        sum += estimate(text == null ? "" : String.valueOf(text));
                    } else {
                        sum += 1024; // 图片类块（file / image_url / image）
                    }
                }
            }
            return sum;
        }
        return estimate(String.valueOf(content));
    }

    /**
     * 估算 {@code tool_calls} 字段的 token 数（兼容 JsonNode / List / String）。
     *
     * <p>历史上有两种形态：{@code ContextBuilder} 组装时用 {@code ObjectNode}
     * （Jackson JsonNode），部分路径也用 {@code List<Map>}。这里统一转成 JSON 文本
     * 再估算，保证不因类型不同而漏算。</p>
     *
     * <p>为什么必须精确估算：{@code function.arguments} 是工具入参的完整字符串，
     * 写文件时可达数万字符。截断/压缩决策依赖这个值，低估会导致超限请求被放行。</p>
     *
     * @param toolCalls tool_calls 字段值（可为 null）
     * @return token 估算值；无法估算时返回 0（调用方会退回最小结构开销）
     */
    private static int estimateToolCalls(Object toolCalls) {
        if (toolCalls == null) {
            return 0;
        }
        // Jackson JsonNode：直接取 JSON 文本长度估算
        if (toolCalls instanceof com.fasterxml.jackson.databind.JsonNode node) {
            if (node.isNull() || node.isMissingNode()) {
                return 0;
            }
            // 空数组/空对象没有实际内容，交给调用方的最小开销兜底
            if ((node.isArray() || node.isObject()) && node.isEmpty()) {
                return 0;
            }
            return estimate(node.toString());
        }
        // List / Map 形态：转字符串后估算
        if (toolCalls instanceof java.util.Collection<?> || toolCalls instanceof java.util.Map<?, ?>) {
            return estimate(String.valueOf(toolCalls));
        }
        if (toolCalls instanceof String text) {
            return estimate(text);
        }
        return estimate(String.valueOf(toolCalls));
    }

    // ==================== 含 tools 的总量口径 ====================

    /**
     * 估算 tools 定义段的 raw token（CL100K 口径）。
     *
     * <p><b>为什么必须单独算它</b>：{@link #estimateMessagesRaw} 只统计 messages，
     * 而服务端返回的 {@code usage.prompt_tokens} <b>包含 tools 定义</b>。
     * 本项目约 20~31 个工具的 schema 恒占 ~14 万字符 ≈ 6 万真实 token，
     * 这是一块「压不掉也删不得」的固定地板。</p>
     *
     * <p>若不把它计入估算，就会出现口径错位：拿「不含 tools 的估算」去比
     * 「含 tools 的真实值」，比率被迫放大到 2.0 上限仍追不上，
     * 或者反过来把兜底观测判成异常值丢弃（实测 observed≈3.2 → 超 MAX_RATIO 被丢弃）。</p>
     *
     * @param tools 请求体里的 {@code tools} 字段（JsonNode / List / Map / String 均可）
     * @return raw token 估算；{@code tools} 为空时返回 0
     */
    public static int estimateToolsRaw(Object tools) {
        if (tools == null) {
            return 0;
        }
        if (tools instanceof com.fasterxml.jackson.databind.JsonNode node) {
            if (node.isNull() || node.isMissingNode()) {
                return 0;
            }
            return estimate(node.toString());
        }
        return estimate(String.valueOf(tools));
    }

    /**
     * 估算请求的总 raw token = messages raw + tools raw（<b>校准前</b>）。
     *
     * <p>这是与服务端 {@code prompt_tokens} 同口径的基数 —— 两者都含 tools。
     * 返回值仍需经 {@link #applyRatio(int, String)} 应用校准比率后才可与 maxTokens 比较。</p>
     *
     * @param messages API 格式的消息列表
     * @param tools    请求体的 tools 字段
     * @return raw 总 token 估算（至少为 1）
     */
    public static int estimateTotalRaw(List<Map<String, Object>> messages, Object tools) {
        long total = (long) estimateMessagesRaw(messages) + estimateToolsRaw(tools);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, total));
    }

    /**
     * 对 raw token 应用校准比率（{@link #ratioOf(String)}）。
     *
     * <p>调用方拿到的是「含 tools 的 raw 总量」，乘比率后即与真实 prompt_tokens 同口径，
     * 可直接与配置的 maxTokens 比较。</p>
     *
     * @param raw          {@link #estimateTotalRaw} 或 {@link #estimateMessagesRaw} 的结果
     * @param providerCode Provider 编码
     * @return 校准后的 token 估算
     */
    public static int applyRatio(int raw, String providerCode) {
        if (raw <= 0) {
            return 0;
        }
        long calibrated = Math.round(raw * ratioOf(providerCode));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, calibrated));
    }

    // ==================== 在线校准 ====================

    /**
     * 用一次真实请求的 usage 校准估算比率（每个请求结束后调用一次）。
     *
     * <p><b>推导</b>：设 raw 为原始估算、ratio_old 为当前比率，
     * 则发送前的 finalEstimate = raw × ratio_old；真实值为 actual。
     * 理想比率应满足 raw × ratio_new ≈ actual，即
     * {@code ratio_new ≈ actual / raw = ratio_old × (actual / finalEstimate)}。
     * 令 {@code observed = actual / finalEstimate}，
     * 则 {@code ratio_new = ratio_old × ((1-α) + α × observed)}（滑动平均，抗单次噪声）。</p>
     *
     * <p>异常观测（observed 超出 [{@value #MIN_RATIO}, {@value #MAX_RATIO}]）直接丢弃：
     * 常见于 usage 帧解析错乱、闸道代理改写 usage、或跨 provider 复用同一比率。</p>
     *
     * @param providerCode Provider 编码（可为 null，计入全局比率）
     * @param finalEstimate 发送前用 {@link #estimateMessages(List, String)} 算出的值
     *                      （必须与实际发出的 messages 对应，否则校准会引入偏差）
     * @param actualPromptTokens API 返回的真实 {@code usage.prompt_tokens}
     */
    public static synchronized void calibrate(String providerCode, int finalEstimate, int actualPromptTokens) {
        if (finalEstimate <= 0 || actualPromptTokens <= 0) {
            return;
        }
        double observed = (double) actualPromptTokens / (double) finalEstimate;
        String key = normalizeProviderKey(providerCode);
        double oldRatio = CALIBRATION.getOrDefault(key, DEFAULT_RATIO);

        // ===== 异常观测：单次当噪声丢弃，连续同向则判定「比率失真」并反推重建 =====
        //
        // 【为什么不能一律丢弃（2026-11-25 会话 612 事故）】
        //   比率被污染到下限 0.10 后，发送前估算 115,847 / 真实 1,229,069，观测比率恒为 10.6。
        //   旧实现每次都判「异常」丢弃 → 比率永不修正 → 守卫永不触发压缩 → 请求越过窗口上限 400。
        //   正确比率 = actual / raw = ratio_old × observed，连续异常时必须按它反推。
        if (observed < MIN_RATIO || observed > MAX_RATIO) {
            int dir = observed > MAX_RATIO ? 1 : -1;
            Integer prev = ABNORMAL_STREAK.get(key);
            int prevCount = prev == null ? 0 : Math.abs(prev);
            int count = (prev != null && Integer.signum(prev) == dir) ? prevCount + 1 : 1;

            if (count >= ABNORMAL_STREAK_TO_RESET) {
                /*
                  反推 = oldRatio × observed（等价于 actual/raw），再夹进「当前比率的
                  ±RESET_MAX_FACTOR 倍」区间：usage 帧错乱时 observed 可能异常巨大，
                  放任它一步顶到 MAX_RATIO（3.0），会让估算从此虚高 3 倍 →
                  守卫反复触发压缩 → 前缀缓存被反复推翻，比「低估」更难排查。
                */
                double rawCorrected = oldRatio * observed;
                double upper = oldRatio * RESET_MAX_FACTOR;
                double lower = oldRatio / RESET_MAX_FACTOR;
                double corrected = clampRatio(Math.min(upper, Math.max(lower, rawCorrected)));
                CALIBRATION.put(key, corrected);
                ABNORMAL_STREAK.remove(key);
                log.warn("[Token校准-重置] provider={} 连续 {} 次异常观测（观测比率={}），"
                                + "判定比率失真并按 actual/raw 反推：{} → {}（发送前估算={}, 真实={}, 反推原值={}）",
                        providerLabel(providerCode), count, String.format("%.3f", observed),
                        String.format("%.3f", oldRatio), String.format("%.3f", corrected),
                        finalEstimate, actualPromptTokens, String.format("%.3f", rawCorrected));
            } else {
                ABNORMAL_STREAK.put(key, dir * count);
                log.warn("Token 校准观测异常，已丢弃该次样本: provider={}, 发送前估算={}, 真实={}, "
                                + "观测比率={}（连续同向异常 {}/{}，达阈值将反推重建比率）",
                        providerCode, finalEstimate, actualPromptTokens,
                        String.format("%.3f", observed), count, ABNORMAL_STREAK_TO_RESET);
            }
            return;
        }

        // ===== 正常观测：滑动平均向 actual/raw 收敛 =====
        ABNORMAL_STREAK.remove(key);
        double newRatio = clampRatio(oldRatio * (1 - CALIBRATION_ALPHA + CALIBRATION_ALPHA * observed));
        CALIBRATION.put(key, newRatio);

        // 【不再同步全局比率（2026-11-25）】
        // 旧实现把每个 provider 的观测同时写进 GLOBAL_RATIO_KEY，导致「huoshan 校准出的 0.10
        // 被 deepseek 继承」——不同平台分词效率差异极大（本项目实测 0.073~2.0），互相污染后
        // 没有样本的 provider 会拿到完全错误的比率（deepseek 正是这样被低估 10.6 倍）。
        // 全局键只保留给「未带 provider 的调用点」兜底；未观测到时用 DEFAULT_RATIO=1.0（不修正，安全）。

        log.info("[Token校准] provider={} 比率 {} → {}（发送前估算={}, 真实={}, 观测={}）",
                providerLabel(providerCode),
                String.format("%.3f", oldRatio), String.format("%.3f", newRatio),
                finalEstimate, actualPromptTokens, String.format("%.3f", observed));
    }

    /** 日志用的 provider 展示名 */
    private static String providerLabel(String providerCode) {
        return providerCode == null || providerCode.isEmpty() ? "<global>" : providerCode;
    }

    /**
     * 读取当前生效的校准比率（供诊断与自检使用）。
     *
     * @param providerCode Provider 编码；{@code null} 读全局比率
     * @return 当前比率（未观测到时返回 {@value #DEFAULT_RATIO}）
     */
    public static double ratioOf(String providerCode) {
        String key = normalizeProviderKey(providerCode);
        Double exact = CALIBRATION.get(key);
        if (exact != null) {
            return exact;
        }
        return CALIBRATION.getOrDefault(GLOBAL_RATIO_KEY, DEFAULT_RATIO);
    }

    /**
     * 直接设置比率（供自检程序与「从配置恢复」场景使用，生产链路只走 {@link #calibrate}）。
     *
     * @param providerCode Provider 编码；{@code null} 表示全局
     * @param ratio        目标比率，会被夹到 [{@value #MIN_RATIO}, {@value #MAX_RATIO}]
     */
    public static synchronized void setRatio(String providerCode, double ratio) {
        CALIBRATION.put(normalizeProviderKey(providerCode), clampRatio(ratio));
    }

    // ==================== 字符数兜底观测 ====================

    /**
     * 请求体字符数 → 真实 prompt_tokens 的经验系数。
     *
     * <p><b>为什么必须有这条兜底通道</b>：{@code handleStreamingPhase} 里用
     * {@code takeWhile(chunk -> shouldContinue.get())} 在「工具调用参数收齐」时立即停止处理后续帧，
     * 而 usage 帧位于流末尾（{@code data: [DONE]} 之前）。于是<b>带 tool_calls 的请求绝大多数
     * 拿不到 usage</b> —— {@link #calibrate} 连一个样本都收不到，比率恒为
     * {@link #DEFAULT_RATIO}=1.0，CL100K 的虚高原样存在，守卫仍然每轮误判超限。</p>
     *
     * <p><b>为什么是 0.425</b>：把 app.log 的 {@code requestBodySize}
     * （{@code requestBody.toString().length()}，即 Map 形态长度）与
     * {@code llm_token_usage.prompt_tokens} 逐轮配对实测（火山 deepseek-v4.1-flash，
     * 同一会话连续 5 轮）：</p>
     * <pre>
     *   请求体 chars   真实 prompt_tokens   比值
     *   163 966        69 690              0.425
     *   173 924        74 309              0.427
     *   179 329        76 381              0.426
     *   183 313        77 911              0.425
     *   184 918        78 510              0.425
     * </pre>
     * <p>波动仅 ±0.5%，样本覆盖「中文 + 代码 + JSON 结构」的典型混合内容。</p>
     *
     * <p><b>与 {@link #estimateMessagesRaw} 的口径差异（关键）</b>：
     * {@code estimateMessagesRaw} 只统计 messages（content / reasoning / tool_calls），
     * <b>不含 tools 定义</b>；而本系数基于完整请求体，<b>含 tools</b>
     * （本项目约 20 个工具的 schema 恒占 ~14 万字符 ≈ 6 万 token，压不掉也删不得）。
     * 两者结合后，校准出的比率恰好让 {@code estimateMessages} 对上<b>含 tools 的总 prompt</b>，
     * 这正是守卫阈值（按真实总 token 设计）需要的口径。</p>
     *
     * <p><b>漂移风险</b>：tools 是常量、messages 是变量，故 messages 大幅增长时该系数会产生
     * 约 10% 的偏移；由 85% 阈值留出的 15% 保护带 + 每轮重新校准吸收，不影响判定。</p>
     */
    public static final double CHARS_TO_TOKEN_RATIO = 0.425;

    /**
     * 由请求体字符数反推真实 prompt_tokens（usage 帧丢失时的兜底观测值）。
     *
     * <p>精度低于 API 返回的 {@code usage.prompt_tokens}，但远好于「不校准」
     * （后者虚高 4 倍导致每轮误压）。观测噪声由 {@link #calibrate} 的滑动平均（α=0.3）吸收。</p>
     *
     * @param requestChars 请求体字符数（含 tools 定义）
     * @return 估算的真实 prompt_tokens；{@code requestChars<=0} 时返回 0，表示本条观测不可用
     */
    public static int estimateFromRequestChars(int requestChars) {
        if (requestChars <= 0) {
            return 0;
        }
        long value = Math.round(requestChars * CHARS_TO_TOKEN_RATIO);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, value));
    }

    /**
     * 每条消息的结构开销字符数（{@code role=} / 花括号 / 逗号 / 等号等），
     * 与 {@code requestBody.toString().length()} 的口径对齐。
     */
    private static final int STRUCT_CHARS_PER_MESSAGE = 40;

    /**
     * 统计 messages 的「内容字符数」（content + reasoning_content + tool_calls + tool_call_id + 结构开销）。
     *
     * <p><b>为什么要单独做字符口径</b>：{@link #estimateMessagesRaw} 走 CL100K 分词再乘校准比率，
     * 比率一旦失真（实测被钳在 0.10 下限导致低估 10.6 倍），守卫就形同虚设 ——
     * 会话 612 发送前估算 115,847、真实 1,229,069，守卫全程认为「才 11 万，远没到 85%」，
     * 最终请求越过模型窗口返回 400。字符口径<b>不依赖比率</b>，乘
     * {@link #CHARS_TO_TOKEN_RATIO}（实测波动 ±0.5%）即可得到与真实 prompt_tokens 同量级的值，
     * 适合作为守卫的下界。</p>
     *
     * @param messages API 格式的消息列表
     * @return 内容字符数；空列表返回 0
     */
    public static long estimateMessagesChars(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (Map<String, Object> msg : messages) {
            total += contentChars(msg.get("content"));
            Object reasoning = msg.get("reasoning_content");
            if (reasoning instanceof String text) {
                total += text.length();
            }
            Object toolCalls = msg.get("tool_calls");
            if (toolCalls != null) {
                total += String.valueOf(toolCalls).length();
            }
            Object toolCallId = msg.get("tool_call_id");
            if (toolCallId instanceof String id) {
                total += id.length();
            }
            total += STRUCT_CHARS_PER_MESSAGE;
        }
        return total;
    }

    /**
     * 统计 tools 定义的字符数（与 {@link #estimateToolsRaw} 的区别：这里返回字符数，不做分词）。
     *
     * @param tools 请求体里的 {@code tools} 字段（JsonNode / List / Map / String 均可）
     * @return 字符数；为空返回 0
     */
    public static long estimateToolsChars(Object tools) {
        if (tools == null) {
            return 0;
        }
        if (tools instanceof com.fasterxml.jackson.databind.JsonNode node) {
            if (node.isNull() || node.isMissingNode()) {
                return 0;
            }
            return node.toString().length();
        }
        return String.valueOf(tools).length();
    }

    /**
     * 字符口径的 token 估算（messages + tools，<b>不依赖校准比率</b>）。
     *
     * <p>用途：与 {@link #applyRatio(int, String)} 的结果取 {@code max} 作为守卫依据 ——
     * 校准比率失真时由本方法兜底，避免「估算 11.6 万 / 真实 122.9 万」这种把守卫废掉的偏差。</p>
     *
     * <p><b>实测校验</b>（2026-11-25 会话 612）：messages+tools 字符数 2,891,926 × 0.425
     * = 1,229,069，与 API 返回的真实 {@code prompt_tokens} <b>完全一致</b>。</p>
     *
     * @param messages API 格式的消息列表
     * @param tools    请求体的 tools 字段
     * @return 估算 token；输入为空返回 0
     */
    public static int estimateTotalByChars(List<Map<String, Object>> messages, Object tools) {
        long chars = estimateMessagesChars(messages) + estimateToolsChars(tools);
        if (chars <= 0) {
            return 0;
        }
        long value = Math.round(chars * CHARS_TO_TOKEN_RATIO);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, value));
    }

    /** content 字段的字符数（兼容 String 与多模态内容块数组） */
    private static long contentChars(Object content) {
        if (content == null) {
            return 0;
        }
        if (content instanceof String text) {
            return text.length();
        }
        if (content instanceof List<?> parts) {
            long sum = 0;
            for (Object item : parts) {
                if (item instanceof Map<?, ?> block) {
                    Object text = block.get("text");
                    sum += text == null ? 0 : String.valueOf(text).length();
                }
            }
            return sum;
        }
        return String.valueOf(content).length();
    }

    /** 清空全部校准状态（仅自检程序使用，生产链路不要调用） */
    public static synchronized void resetCalibration() {
        CALIBRATION.clear();
    }

    private static String normalizeProviderKey(String providerCode) {
        return providerCode == null ? GLOBAL_RATIO_KEY : providerCode.trim().toLowerCase();
    }

    private static double clampRatio(double ratio) {
        if (Double.isNaN(ratio) || ratio <= 0) {
            return DEFAULT_RATIO;
        }
        return Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
    }
}
