package com.example.agentdeepseek.service.lesson;

import com.example.agentdeepseek.model.entity.Lesson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P0-1 经验检索排序器：相关度优先的加权总分排序。
 *
 * <p><b>解决的历史缺陷</b>：旧链路最终排序键是 {@code LessonService.sortByEnvCompat}
 * （环境兼容的 3 档离散分：异环境 0 / 通用 1 / 同环境 2）。它带来两个后果：
 * <ul>
 *   <li>环境兼容一票压过相关度——「异环境的同一个坑」会被排到「同环境的弱相关条目」之后；</li>
 *   <li>模糊检索内部算好的「命中词数」排序，在 Top-K 截取前被这次重排完全覆盖，等于白算。</li>
 * </ul>
 *
 * <p><b>现在的口径</b>：总分 = 强信号（错误码精确命中、参数一致）
 * + 主信号（相关度 = 字段权重 × IDF × 覆盖率）
 * + 轻量修正（环境兼容、历史有效率、新鲜度、命中量、项目内沉淀）。
 * 环境兼容从「一票否决」降为 0.8 分权重项，异环境只降权不淘汰。</p>
 *
 * <p><b>为什么不引入向量</b>：经验库的设计前提是「参数差一个值就是两条不同的坑」，
 * 语义相似度反而会混淆；相关度靠字段加权 + 候选集内部 IDF 足够，且零额外依赖、零额外 SQL。</p>
 *
 * <p>所有权重均可用 {@code lesson.ranker.*} 覆盖，{@code lesson.ranker.enabled=false}
 * 时调用方回退旧排序（用于 A/B 对比与线上快速回滚）。</p>
 */
@Component
public class LessonRanker {

    /** 字段权重：现象最能区分「是不是同一个坑」；标签是人工/LLM 提炼过的高信噪比信号 */
    static final double W_SYMPTOM = 3.0;
    static final double W_KEYWORDS = 2.5;
    static final double W_GOAL = 2.0;
    static final double W_ROOT_CAUSE = 1.5;
    static final double W_SOLUTION = 1.0;

    /** 字段权重数组（下标与 {@link #bestField} 返回值一致） */
    private static final double[] FIELD_WEIGHTS = {W_SYMPTOM, W_KEYWORDS, W_GOAL, W_ROOT_CAUSE, W_SOLUTION};
    /** 字段中文名（写进「为何召回」，让人和模型都能看懂命中的是哪个字段） */
    private static final String[] FIELD_LABELS = {"现象", "标签", "目标", "根因", "解法"};
    private static final int FIELD_SYMPTOM = 0;
    private static final int FIELD_KEYWORDS = 1;
    private static final int FIELD_GOAL = 2;
    private static final int FIELD_ROOT_CAUSE = 3;
    private static final int FIELD_SOLUTION = 4;

    /** 总分项条数上限（避免「为何召回」把结果文本撑爆） */
    private static final int MAX_REASONS = 4;
    /** 单字段参与相关度计算的最大字符数（超长解法只扫前段，防排序被长文本拖慢） */
    private static final int FIELD_SCAN_LIMIT = 4000;

    @Value("${lesson.ranker.enabled:true}")
    private boolean enabled = true;
    /** 错误码精确命中（强信号，必须压过「纯靠相关度」的模糊命中） */
    @Value("${lesson.ranker.w-exact-code:3.0}")
    private double wExactCode = 3.0;
    /** 参数一致（强信号：同名参数值必须一致，这是本系统的核心判据） */
    @Value("${lesson.ranker.w-param:2.0}")
    private double wParam = 2.0;
    /** 相关度（主项） */
    @Value("${lesson.ranker.w-relevance:2.5}")
    private double wRelevance = 2.5;
    /** 环境兼容（旧逻辑的「一票否决」在这里降级为轻量加分） */
    @Value("${lesson.ranker.w-env:0.8}")
    private double wEnv = 0.8;
    /** 历史有效率（反馈闭环的后验质量） */
    @Value("${lesson.ranker.w-effective:0.5}")
    private double wEffective = 0.5;
    /** 新鲜度（旧坑可能已被修掉，权重很低，只用于近距离打破平局） */
    @Value("${lesson.ranker.w-fresh:0.3}")
    private double wFresh = 0.3;
    /** 命中量（对数压缩，防老条目霸榜） */
    @Value("${lesson.ranker.w-hit:0.2}")
    private double wHit = 0.2;
    /** 项目内沉淀加成（全局池条目仍可被召回，只是略靠后） */
    @Value("${lesson.ranker.w-project:0.4}")
    private double wProject = 0.4;
    /** 新鲜度衰减尺度（天）：exp(-天数/尺度)，默认 240 天对应约 166 天半衰期 */
    @Value("${lesson.ranker.fresh-scale-days:240}")
    private double freshScaleDays = 240;

    private final ObjectMapper objectMapper;

    public LessonRanker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 排序入参：检索侧的查询画像（只在检索时存在，不落库） */
    public static class RankContext {
        /** 错误码候选集：归一化码 + 原始码，任一 equalsIgnoreCase 命中即算精确命中 */
        public final Set<String> codeCandidates;
        /** 扩展后的查询词（小写；为空则只靠强信号与修正项排序） */
        public final List<String> queryWords;
        /** 查询侧参数（为空表示调用方未提供参数维度，不做参数打分） */
        public final Map<String, String> queryParams;
        /** 当前操作系统（windows/linux/mac/unknown） */
        public final String currentOs;
        /** 当前项目标识（用于「本项目沉淀」加成） */
        public final String projectKey;

        public RankContext(Set<String> codeCandidates, List<String> queryWords,
                           Map<String, String> queryParams, String currentOs, String projectKey) {
            this.codeCandidates = codeCandidates == null ? Set.of() : codeCandidates;
            this.queryWords = queryWords == null ? List.of() : queryWords;
            this.queryParams = queryParams == null ? Map.of() : queryParams;
            this.currentOs = currentOs;
            this.projectKey = projectKey;
        }
    }

    /** 排序结果：原始实体 + 总分 + 相关度 + 命中原因（供结果文本展示，避免模型误用不适用的经验） */
    public static class RankedLesson {
        public final Lesson lesson;
        public final double score;
        public final double relevance;
        public final List<String> reasons;

        public RankedLesson(Lesson lesson, double score, double relevance, List<String> reasons) {
            this.lesson = lesson;
            this.score = score;
            this.relevance = relevance;
            this.reasons = reasons == null ? List.of() : reasons;
        }
    }

    /**
     * 排序候选集（不修改入参顺序）。
     *
     * <p>稳定性：同分时按 id 升序，保证同一查询多次执行结果一致（便于 A/B 对比与问题复现）。</p>
     */
    public List<RankedLesson> rank(List<Lesson> candidates, RankContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Lesson, String[]> lowerCache = new IdentityHashMap<>();
        long maxHit = 0;
        for (Lesson lesson : candidates) {
            lowerCache.put(lesson, lowerFields(lesson));
            maxHit = Math.max(maxHit, nz(lesson.getHitCount()));
        }
        // 词统计里顺带缓存「词 → 每条候选命中的字段下标」，打分阶段不必再扫一遍全文
        WordStats stats = WordStats.of(candidates, lowerCache, ctx.queryWords);
        LocalDateTime now = LocalDateTime.now();
        List<RankedLesson> ranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            ranked.add(scoreOf(candidates.get(i), ctx, stats, maxHit, i, now));
        }
        ranked.sort(Comparator.comparingDouble((RankedLesson r) -> -r.score)
                .thenComparingLong(r -> r.lesson.getId() == null ? Long.MAX_VALUE : r.lesson.getId()));
        return ranked;
    }

    // ============================================================
    // 打分
    // ============================================================

    private RankedLesson scoreOf(Lesson lesson, RankContext ctx, WordStats stats, long maxHit,
                                 int index, LocalDateTime now) {
        double score = 0;
        List<String> reasons = new ArrayList<>(MAX_REASONS);

        // ① 环境警告优先入队（异环境是「可能不适用」的强提醒，不能被后续理由挤掉）
        String envLabel = envLabel(lesson, ctx.currentOs);
        score += wEnv * envCompat(lesson, ctx.currentOs);
        if (envLabel != null) {
            reasons.add(envLabel);
        }

        // ② 错误码精确命中（强信号）
        if (lesson.getErrorCode() != null) {
            for (String code : ctx.codeCandidates) {
                if (code != null && code.equalsIgnoreCase(lesson.getErrorCode())) {
                    score += wExactCode;
                    reasons.add("错误码精确命中 " + lesson.getErrorCode());
                    break;
                }
            }
        }

        // ③ 参数一致（强信号；部分一致按比例给分，不淘汰）
        ParamMatch paramMatch = paramMatch(lesson, ctx.queryParams);
        if (paramMatch.weight > 0) {
            score += wParam * paramMatch.weight;
            reasons.add(paramMatch.text);
        }

        // ④ 相关度（主项：字段权重 × IDF × 覆盖率）
        Relevance relevance = relevance(stats, index);
        if (relevance.value > 0) {
            score += wRelevance * relevance.value;
            reasons.add("相关度 " + twoDecimals(relevance.value) + "（命中" + relevance.fieldText() + "）");
        }

        // ⑤ 历史有效率（反馈闭环的后验质量，总次数为 0 时不占位——头部括号已展示统计）
        score += wEffective * effectiveRate(lesson);

        // ⑥ 新鲜度（旧坑可能已被修复，权重很低）
        score += wFresh * freshness(lesson, now);

        // ⑦ 命中量（对数归一，防老条目靠累计值霸榜）
        if (maxHit > 0 && nz(lesson.getHitCount()) > 0) {
            score += wHit * (Math.log1p(nz(lesson.getHitCount())) / Math.log1p(maxHit));
        }

        // ⑧ 项目内沉淀（全局池条目仍可被召回，只是略靠后）
        if (ctx.projectKey != null && ctx.projectKey.equals(lesson.getProjectKey())) {
            score += wProject;
            reasons.add("本项目沉淀");
        }

        if (reasons.size() > MAX_REASONS) {
            reasons = new ArrayList<>(reasons.subList(0, MAX_REASONS));
        }
        return new RankedLesson(lesson, score, relevance.value, reasons);
    }

    /** 相关度：Σ(字段权重 × IDF) / Σ(最高字段权重 × IDF)，只统计「候选集内至少出现一次」的词 */
    private Relevance relevance(WordStats stats, int index) {
        if (stats.idf.isEmpty()) {
            return Relevance.ZERO;
        }
        double numerator = 0;
        double denominator = 0;
        Set<Integer> fields = new LinkedHashSet<>();
        for (Map.Entry<String, Double> entry : stats.idf.entrySet()) {
            double weight = entry.getValue();
            denominator += W_SYMPTOM * weight;
            int[] hits = stats.fieldHits.get(entry.getKey());
            int field = (hits == null || index < 0 || index >= hits.length) ? -1 : hits[index];
            if (field >= 0) {
                numerator += FIELD_WEIGHTS[field] * weight;
                fields.add(field);
            }
        }
        if (denominator <= 0) {
            return Relevance.ZERO;
        }
        return new Relevance(Math.min(1.0, numerator / denominator), fields);
    }

    /** 相关度结果：值 + 命中字段集合（0..4，顺序即 FIELD_LABELS 下标） */
    private static final class Relevance {
        static final Relevance ZERO = new Relevance(0, Set.of());
        final double value;
        final Set<Integer> fields;

        Relevance(double value, Set<Integer> fields) {
            this.value = value;
            this.fields = fields;
        }

        String fieldText() {
            if (fields.isEmpty()) {
                return "无";
            }
            StringBuilder sb = new StringBuilder();
            for (Integer field : fields) {
                if (sb.length() > 0) {
                    sb.append("/");
                }
                sb.append(FIELD_LABELS[field]);
            }
            return sb.toString();
        }
    }

    // ============================================================
    // 各修正项
    // ============================================================

    /** 参数匹配：只有「两边都出现的参数名」参与判定；无共现维度返回 0（未提供维度不参与） */
    private ParamMatch paramMatch(Lesson lesson, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return ParamMatch.NONE;
        }
        Map<String, String> lessonParams = LessonParams.parse(lesson.getParamsJson(), objectMapper);
        if (lessonParams.isEmpty()) {
            return ParamMatch.NONE;
        }
        int common = 0;
        int same = 0;
        StringBuilder sameDesc = new StringBuilder();
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String lessonValue = lessonParams.get(entry.getKey());
            if (lessonValue == null) {
                continue;
            }
            common++;
            if (lessonValue.equalsIgnoreCase(entry.getValue())) {
                same++;
                if (sameDesc.length() > 0) {
                    sameDesc.append("/");
                }
                sameDesc.append(entry.getKey()).append("=").append(lessonValue);
            }
        }
        if (common == 0) {
            return ParamMatch.NONE;
        }
        double weight = (double) same / common;
        String text = same == common
                ? "参数一致(" + sameDesc + ")"
                : "参数部分匹配(" + same + "/" + common + (same > 0 ? "，" + sameDesc : "") + ")";
        return new ParamMatch(weight, text);
    }

    private static final class ParamMatch {
        static final ParamMatch NONE = new ParamMatch(0, null);
        final double weight;
        final String text;

        ParamMatch(double weight, String text) {
            this.weight = weight;
            this.text = text;
        }
    }

    /** 环境兼容分：同环境 1.0 / 通用（无环境限制）0.85 / 异环境 0.2 */
    private double envCompat(Lesson lesson, String currentOs) {
        String env = lesson.getEnvParams();
        if (env == null || env.isBlank()) {
            return 0.85;
        }
        if (currentOs != null && !"unknown".equals(currentOs) && env.contains("\"os\":\"" + currentOs + "\"")) {
            return 1.0;
        }
        return 0.2;
    }

    /** 环境提示：只在「异环境」时返回文案（同环境/通用是常态，不占展示预算） */
    private String envLabel(Lesson lesson, String currentOs) {
        if (envCompat(lesson, currentOs) > 0.5) {
            return null;
        }
        String env = lesson.getEnvParams();
        int idx = env == null ? -1 : env.indexOf("\"os\":\"");
        String os = idx >= 0 ? env.substring(idx + 6, Math.min(env.length(), idx + 6 + 12)) : "";
        int end = os.indexOf('"');
        if (end >= 0) {
            os = os.substring(0, end);
        }
        return "⚠异环境(" + (os.isEmpty() ? "未知" : os) + ")，可能不适用";
    }

    /** 历史有效率：贝叶斯平滑成功率（防除零）打底，状态加成（已验证 > 草稿） */
    private double effectiveRate(Lesson lesson) {
        int success = nz(lesson.getSuccessCount());
        int fail = nz(lesson.getFailCount());
        double bayes = (success + 1.0) / (success + fail + 2.0);
        int status = lesson.getStatus() == null ? Lesson.STATUS_DRAFT : lesson.getStatus();
        double bonus = status == Lesson.STATUS_ACTIVE ? 0.25 : (status == Lesson.STATUS_DRAFT ? 0.1 : 0);
        return Math.min(1.0, bayes * 0.75 + bonus);
    }

    /** 新鲜度：exp(-天数/尺度)；用 updated_at（检索命中会刷新，表达「近期仍被使用/维护」） */
    private double freshness(Lesson lesson, LocalDateTime now) {
        LocalDateTime time = lesson.getUpdatedAt() != null ? lesson.getUpdatedAt() : lesson.getCreatedAt();
        if (time == null || freshScaleDays <= 0) {
            return 0.5;
        }
        long days = Duration.between(time, now).toDays();
        if (days < 0) {
            days = 0;
        }
        return Math.exp(-days / freshScaleDays);
    }

    /** 两位小数（不用 String.format：一次检索要输出多条理由，format 的正则解析是热点） */
    private static String twoDecimals(double value) {
        int scaled = (int) Math.round(value * 100);
        int intPart = scaled / 100;
        int frac = Math.abs(scaled % 100);
        return intPart + "." + (frac < 10 ? "0" : "") + frac;
    }

    // ============================================================
    // 文本工具
    // ============================================================

    /**
     * 返回命中词的「最高权重字段」下标（-1 = 未命中）。
     * 一个词只按最高权重字段计一次分，避免「解法里出现 10 次」压过「现象里出现 1 次」。
     */
    private static int bestField(String[] lower, String word) {
        if (lower == null || word == null || word.isEmpty()) {
            return -1;
        }
        if (lower[FIELD_SYMPTOM].contains(word)) return FIELD_SYMPTOM;
        if (lower[FIELD_KEYWORDS].contains(word)) return FIELD_KEYWORDS;
        if (lower[FIELD_GOAL].contains(word)) return FIELD_GOAL;
        if (lower[FIELD_ROOT_CAUSE].contains(word)) return FIELD_ROOT_CAUSE;
        if (lower[FIELD_SOLUTION].contains(word)) return FIELD_SOLUTION;
        return -1;
    }

    /** 五字段小写缓存：避免「词数 × 条数 × 字段长度」重复 toLowerCase（长解法反复小写化很贵） */
    private static String[] lowerFields(Lesson lesson) {
        return new String[]{
                lowerOf(lesson.getSymptom()),
                lowerOf(lesson.getKeywords()),
                lowerOf(lesson.getGoal()),
                lowerOf(lesson.getRootCause()),
                lowerOf(lesson.getSolution())
        };
    }

    private static String lowerOf(String text) {
        if (text == null) {
            return "";
        }
        // 只取前 FIELD_SCAN_LIMIT 字符参与相关度计算：经验库里偶有「解法正文塞了一大段代码」的条目，
        // 全量小写化 + 逐词 contains 会把排序拖到几十毫秒；而解法开头已足够表达「这条是否相关」。
        String head = text.length() <= FIELD_SCAN_LIMIT ? text : text.substring(0, FIELD_SCAN_LIMIT);
        return head.toLowerCase(Locale.ROOT);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 候选集内部的词统计（零额外 SQL）。
     *
     * <p>IDF 用候选集而不是全库：候选集通常 1~20 条，统计一次就是 O(条数×词数)，
     * 而效果与全库 IDF 在同一量级——高频噪声词（失败/error）在候选集里同样遍地出现，
     * 稀有词（EADDRINUSE）同样稀少，压制效果成立。</p>
     *
     * <p>{@code df == 0} 的词不进 map：它在候选集里无人命中，不构成区分度，
     * 也不该参与相关度分母（否则一个噪声 2-gram 会整体压低所有候选的分数）。</p>
     */
    private static final class WordStats {
        /** 词 → IDF（df==0 的词不进本表，也不参与相关度分母） */
        final Map<String, Double> idf = new LinkedHashMap<>();
        /** 词 → 每条候选命中的字段下标（-1=未命中），下标与候选列表顺序一致 */
        final Map<String, int[]> fieldHits = new LinkedHashMap<>();

        static WordStats of(List<Lesson> candidates, Map<Lesson, String[]> lowerCache, List<String> words) {
            WordStats stats = new WordStats();
            if (words == null || words.isEmpty()) {
                return stats;
            }
            int total = candidates.size();
            Set<String> seen = new LinkedHashSet<>();
            for (String word : words) {
                if (word == null || word.isEmpty()) {
                    continue;
                }
                String normalized = word.toLowerCase(Locale.ROOT);
                if (!seen.add(normalized)) {
                    continue;
                }
                int[] hits = new int[total];
                int df = 0;
                for (int i = 0; i < total; i++) {
                    int field = bestField(lowerCache.get(candidates.get(i)), normalized);
                    hits[i] = field;
                    if (field >= 0) {
                        df++;
                    }
                }
                if (df == 0) {
                    continue;
                }
                stats.fieldHits.put(normalized, hits);
                stats.idf.put(normalized, Math.log((total + 1.0) / (df + 1.0)) + 1.0);
            }
            return stats;
        }
    }
}
