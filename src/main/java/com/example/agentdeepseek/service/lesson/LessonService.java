package com.example.agentdeepseek.service.lesson;

import com.example.agentdeepseek.mapper.LessonMapper;
import com.example.agentdeepseek.mapper.LessonRuleMapper;
import com.example.agentdeepseek.model.entity.Lesson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 踩坑经验服务
 * 核心能力：
 * 1. 写入查重（error_signature 指纹去重，同坑不重复入库）
 * 2. 三级确定性检索漏斗（硬过滤 → 参数等值比对 → LIKE 兜底）
 * 3. 反馈闭环（成功转正 / 连续失败自动隐藏）
 *
 * 设计原则：踩坑经验是「确定性知识」，检索靠结构化字段精确匹配，
 * 不引入向量语义（参数差一个值就是两条不同的坑，向量反而分不清）。
 */
@Slf4j
@Service
public class LessonService {

    /** 检索返回条数上限 */
    private static final int SEARCH_LIMIT = 10;
    /** 最终展示条数 */
    private static final int RESULT_TOP_K = 3;
    /** P0-2：模糊检索候选上限（多词 OR 命中面更大，多取一些供词命中数精排） */
    private static final int FUZZY_SEARCH_LIMIT = 20;
    /** P0-2：模糊检索最大词数（多词 OR 上限，防 SQL 条件膨胀；数据量小无压力） */
    private static final int MAX_FUZZY_WORDS = 10;

    /** P0-2：中文单字虚词集（2-gram 首/尾字命中即丢弃，过滤跨界噪声词） */
    private static final Set<Character> CN_STOP_CHARS = Set.of(
            '我', '你', '他', '她', '它', '们', '的', '了', '着', '是', '在', '和', '与', '及',
            '把', '被', '请', '让', '就', '都', '也', '还', '又', '吗', '呢', '吧', '啊', '呀',
            '么', '这', '那', '哪', '谁', '什', '怎', '帮', '并', '或', '而', '且', '以', '之');

    /** P0-2：英文停用词集（ASCII 段整词过滤） */
    private static final Set<String> EN_STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "for", "with", "that", "this", "these", "those",
            "is", "are", "was", "were", "be", "been", "to", "of", "in", "on", "at", "by",
            "it", "its", "as", "not", "no", "do", "does", "did", "can", "could", "should",
            "would", "please", "help", "need", "want", "make", "let", "get", "use", "using",
            "from", "into", "than", "then", "when", "where", "which", "what", "how", "why");

    private final LessonMapper lessonMapper;
    private final FailureNormalizer normalizer;
    private final ObjectMapper objectMapper;
    /** P0：LLM 语义归一化兜底（指标：LLM 调用/缓存命中/回写次数） */
    private final LessonNormalizerService normalizerService;
    /** P2：规则自学习表（看板统计候选/转正数 + 惰性淘汰） */
    private final LessonRuleMapper ruleMapper;
    /** P2：规则表容量上限（超限时淘汰「候选+从未被提取命中」的最旧规则；替代时间淘汰） */
    @org.springframework.beans.factory.annotation.Value("${lesson.rule.max-size:10000}")
    private long ruleMaxSize;

    public LessonService(LessonMapper lessonMapper, FailureNormalizer normalizer, ObjectMapper objectMapper,
                         LessonNormalizerService normalizerService, LessonRuleMapper ruleMapper) {
        this.lessonMapper = lessonMapper;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.normalizerService = normalizerService;
        this.ruleMapper = ruleMapper;
    }

    // ============================================================
    // 写入：指纹去重，同坑不重复入库
    // ============================================================

    /**
     * 记录结果：含入库经验、是否新增、是否发生解法合并（供调用方区分三种情况）
     */
    public static class RecordResult {
        public final Lesson lesson;
        /** true=本次新增入库；false=同坑已存在（hit_count+1 后返回已有记录） */
        public final boolean created;
        /** true=旧草稿无解法，已用新记录的解法/根因等补全（F2 解法合并） */
        public final boolean merged;

        public RecordResult(Lesson lesson, boolean created, boolean merged) {
            this.lesson = lesson;
            this.created = created;
            this.merged = merged;
        }
    }

    /**
     * 记录一条踩坑经验。
     * 同指纹（同项目+同工具+同错误码+同参数）已存在时：不新增行，命中次数 +1，返回已有记录。
     * 默认类型 FAILURE（失败经验）。
     *
     * @return 入库（或命中已有）的 lesson，含 id
     */
    public Lesson recordLesson(String projectKey, String toolName, String errorCategory, String errorCode,
                               String symptom, String rootCause, String solution,
                               String paramsJson, String applicableCond, String keywords,
                               String source) {
        return recordLessonWithResult(projectKey, toolName, errorCategory, errorCode,
                symptom, rootCause, solution, paramsJson, applicableCond, keywords, source,
                null, null).lesson;
    }

    /**
     * 记录一条踩坑经验（返回是否新增/是否合并标志，供 LLM 工具区分「新建/合并/仅命中」）。
     * 并发安全：insert 时捕获唯一键冲突（两个线程同时先查后插的竞态），降级为查已有记录。
     * F2 解法合并：去重命中且「旧记录无解法、新记录有解法」时，用新内容补全旧草稿（只补空，不覆盖）。
     * <p>
     * P1 指纹分叉：type=DETOUR（弯路经验）时指纹 = md5(projectKey|detour|normalizedGoal)，
     * 与 FAILURE 的工具维度指纹分叉（goal 维度去重），互不影响。
     *
     * @param type 经验类型：Lesson.TYPE_FAILURE（默认）/ Lesson.TYPE_DETOUR；null/空白按 FAILURE
     * @param goal DETOUR 专用：任务目标（检索维度 + 指纹维度）；FAILURE 忽略
     */
    public RecordResult recordLessonWithResult(String projectKey, String toolName, String errorCategory, String errorCode,
                                               String symptom, String rootCause, String solution,
                                               String paramsJson, String applicableCond, String keywords,
                                               String source, String type, String goal) {
        if (projectKey == null || projectKey.isBlank()) projectKey = normalizer.extractProjectKey();
        if (toolName == null || toolName.isBlank()) toolName = "unknown";
        // P3-5：空解法统一写占位符（与 LessonRecorder 自动捕获通道一致），
        // 保证 hasUsableSolution()/newHasSolution 对「无解法记录」的判定口径统一
        if (solution == null || solution.isBlank()) solution = Lesson.PLACEHOLDER_SOLUTION;
        String category = errorCategory == null || errorCategory.isBlank() ? "OTHER" : errorCategory;
        // P1-B：错误码 canonical 化（同义码归一——如 CMD_ENCODING_MISMATCH → CMD_ENCODING；防同坑多码碎片）
        String code = normalizer.canonicalizeCode(errorCode == null || errorCode.isBlank() ? "UNKNOWN" : errorCode);
        // P1：类型归一化（null/空白 → FAILURE；未知值 → FAILURE 防污染）
        String effType = Lesson.TYPE_DETOUR.equals(type) ? Lesson.TYPE_DETOUR : Lesson.TYPE_FAILURE;

        // L2 修复：params 统一归一化（校验格式 + 按 name 排序 + value 截断），
        // 保证自动捕获（FailureNormalizer）与手动记录（LLM）跨通道指纹一致
        String normalizedParams = normalizeParamsJson(paramsJson);

        // 组装归一化结果用于指纹（FAILURE 走工具维度；DETOUR 走 goal 维度分叉）
        FailureNormalizer.NormalizedFailure nf = new FailureNormalizer.NormalizedFailure();
        nf.toolName = toolName;
        nf.errorCategory = category;
        nf.errorCode = code;
        nf.paramsJson = normalizedParams;
        String signature;
        if (Lesson.TYPE_DETOUR.equals(effType)) {
            signature = detourFingerprint(projectKey, goal);
        } else {
            signature = normalizer.fingerprint(projectKey, nf);
        }

        // 新记录内容（用于可能的合并）
        String newSolution = truncate(solution, 2048);
        String newRootCause = (rootCause == null || rootCause.isBlank()) ? null : truncate(rootCause, 1024);
        String newParams = "[]".equals(normalizedParams) ? null : normalizedParams;
        String newCond = (applicableCond == null || applicableCond.isBlank()) ? null : truncate(applicableCond, 512);
        String newKeywords = (keywords == null || keywords.isBlank()) ? null : truncate(keywords, 512);
        // L2：DETOUR 目标（检索维度，合并时补全空 goal 用）
        String newGoal = (goal == null || goal.isBlank()) ? null : truncate(goal, 256);
        boolean newHasSolution = newSolution != null && !newSolution.isBlank()
                && !Lesson.PLACEHOLDER_SOLUTION.equals(newSolution.trim());

        Optional<Lesson> existing = lessonMapper.selectBySignature(signature);
        if (existing.isPresent()) {
            Lesson hit = existing.get();
            lessonMapper.incrementHitCount(hit.getId(), LocalDateTime.now());
            // F2 解法合并：旧记录无可用解法、新记录有解法 → 用新内容补全旧草稿（只补空，不覆盖）
            // L2：DETOUR goal 补全（旧 goal 空、新 goal 非空）
            boolean merged = mergeIfDraft(hit, newHasSolution, newRootCause, newSolution, newParams,
                    newCond, newKeywords, newGoal, signature);
            if (merged) {
                hit = lessonMapper.selectById(hit.getId()).orElse(hit);
            }
            log.info("踩坑经验去重命中: id={}, signature={}, merged={}", hit.getId(), signature, merged);
            return new RecordResult(hit, false, merged);
        }

        LocalDateTime now = LocalDateTime.now();
        Lesson lesson = new Lesson();
        lesson.setProjectKey(projectKey);
        lesson.setToolName(toolName);
        lesson.setErrorCategory(category);
        lesson.setErrorCode(code);
        lesson.setErrorSignature(signature);
        lesson.setSymptom(symptom == null ? "" : truncate(symptom, 512));
        lesson.setRootCause(newRootCause == null ? "" : newRootCause);
        lesson.setSolution(newSolution);
        lesson.setParamsJson(normalizedParams);
        lesson.setApplicableCond(newCond == null ? "" : newCond);
        lesson.setKeywords(newKeywords == null ? "" : newKeywords);
        lesson.setStatus(Lesson.STATUS_DRAFT); // 自动捕获/新记录一律先入草稿，验证后转正
        lesson.setSource(source == null ? Lesson.SOURCE_AUTO : source);
        lesson.setHitCount(0);
        lesson.setSuccessCount(0);
        lesson.setFailCount(0);
        // P1：类型与目标（DETOUR 弯路经验维度）
        lesson.setType(effType);
        lesson.setGoal(Lesson.TYPE_DETOUR.equals(effType) ? (goal == null ? "" : truncate(goal, 256)) : null);
        // P2：环境参数（记录时自动附加当前 os，供检索异环境降权；env 不参与指纹）
        lesson.setEnvParams(buildEnvParams());
        lesson.setCreatedAt(now);
        lesson.setUpdatedAt(now);

        try {
            lessonMapper.insert(lesson);
            log.info("踩坑经验入库: id={}, project={}, tool={}, category={}, code={}",
                    lesson.getId(), projectKey, toolName, category, code);
            return new RecordResult(lesson, true, false);
        } catch (DuplicateKeyException e) {
            // M2 修复：并发竞态（两线程同时先查后插）→ 唯一键冲突，降级为查已有记录
            log.info("踩坑经验并发去重命中（DuplicateKey）: signature={}", signature);
            Optional<Lesson> concurrent = lessonMapper.selectBySignature(signature);
            if (concurrent.isPresent()) {
                Lesson hit = concurrent.get();
                lessonMapper.incrementHitCount(hit.getId(), LocalDateTime.now());
                boolean merged = mergeIfDraft(hit, newHasSolution, newRootCause, newSolution, newParams,
                        newCond, newKeywords, newGoal, signature);
                if (merged) {
                    hit = lessonMapper.selectById(hit.getId()).orElse(hit);
                }
                return new RecordResult(hit, false, merged);
            }
            throw e; // 理论不可达：冲突必然能查到已有记录
        }
    }

    /**
     * F2 解法合并（P2-3 提取，正常命中与 DuplicateKey 竞态命中共用）：
     * 旧记录无可用解法、新记录有解法 → 用新内容补全旧草稿（只补空，不覆盖）。
     * L2：DETOUR 检索维度精化——旧 goal 为空、新 goal 非空 → 补全（只补空不覆盖）。
     *
     * @return true=已用新内容补全旧草稿
     */
    private boolean mergeIfDraft(Lesson hit, boolean newHasSolution,
                                 String newRootCause, String newSolution, String newParams,
                                 String newCond, String newKeywords, String newGoal, String signature) {
        boolean updated = false;
        // F2：旧无可用解法、新有解法 → 补全
        if (!hit.hasUsableSolution() && newHasSolution) {
            lessonMapper.updateContent(hit.getId(), newRootCause, newSolution, newParams,
                    newCond, newKeywords, newGoal, LocalDateTime.now());
            log.info("踩坑经验解法合并: id={}, signature={}", hit.getId(), signature);
            updated = true;
        } else if (newGoal != null && (hit.getGoal() == null || hit.getGoal().isBlank())) {
            // L2：DETOUR 目标补全（旧 goal 空、新 goal 非空；F2 已合并解法时 goal 随 updateContent 一起补）
            lessonMapper.updateContent(hit.getId(), null, null, null, null, null,
                    newGoal, LocalDateTime.now());
            log.info("DETOUR goal 补全: id={}, goal={}", hit.getId(), newGoal);
            updated = true;
        }
        return updated;
    }

    // ============================================================
    // 检索：三级确定性漏斗
    // ============================================================

    /**
     * 三级漏斗检索（P0/P1 增强版）：
     * ① 硬过滤（project_key 必填 + tool_name/error_code 精确匹配，错误码先归一化+别名对齐）
     * ② 参数等值比对（候选非空时过滤；宽松策略——不符不淘汰）
     * ③ 模糊兜底（多词拆解 OR + 大小写不敏感 + 词命中数精排）
     *
     * @param paramsJson 当前场景参数 [{name,value},...]，可为 null
     * @param type       经验类型过滤：Lesson.TYPE_DETOUR / Lesson.TYPE_FAILURE / null=全部（P1）
     * @return 格式化检索结果文本（供 LLM 直接阅读）
     */
    public String searchLessons(String projectKey, String toolName, String errorCode,
                                String keyword, String paramsJson, String type) {
        return doSearch(projectKey, toolName, errorCode, keyword, paramsJson, type, false).text;
    }

    /**
     * P1-D：LLM 主动 search 专用入口——在结果文本之上附带「命中引用」（有解法的 Top 条目），
     * 供 LessonTool 经 ToolContext 会话通道投递、主循环并入 F3 追踪（检索采用后自动验证）。
     */
    public SearchOutcome searchLessonsForTool(String projectKey, String toolName, String errorCode,
                                              String keyword, String paramsJson, String type) {
        return doSearch(projectKey, toolName, errorCode, keyword, paramsJson, type, true);
    }

    /** P1-D：search 结构化结果（文本 + 命中引用） */
    public static class SearchOutcome {
        public final String text;
        public final List<SearchHitTrack> hits;

        public SearchOutcome(String text, List<SearchHitTrack> hits) {
            this.text = text;
            this.hits = hits;
        }
    }

    private SearchOutcome doSearch(String projectKey, String toolName, String errorCode,
                                   String keyword, String paramsJson, String type, boolean collectHits) {
        if (projectKey == null || projectKey.isBlank()) projectKey = normalizer.extractProjectKey();
        String tool = (toolName == null || toolName.isBlank()) ? null : toolName;
        String rawCode = (errorCode == null || errorCode.isBlank()) ? null : errorCode;
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword;
        // P1：type 归一化（未知值视为不过滤）
        String effType = Lesson.TYPE_DETOUR.equals(type) || Lesson.TYPE_FAILURE.equals(type) ? type : null;

        // P0-1：检索入口 error_code 归一化对齐（LLM 常传原始报错文本/异常类名，与库内归一化短码体系错位）：
        //  ① 先经归一化管线转换（"command not found" → CMD_NOT_FOUND，与入库端同一体系）
        //  ② 转换不出稳定短码时禁用精确码维度，原文交给模糊兜底
        String normalizedCode = rawCode == null ? null : normalizeQueryErrorCode(tool, rawCode);
        boolean codeUsable = normalizedCode != null && !"UNKNOWN".equalsIgnoreCase(normalizedCode);
        String exactCode = codeUsable ? normalizedCode : null;

        // 第一级：硬过滤
        // P2 修复：DETOUR 弯路经验的检索维度是 goal/keywords（无错误码维度），直接走模糊检索
        // P0-3 修复：不再用「整串 LIKE」（自然语言长句与 goal 短句必然失配），改走拆词 OR 匹配
        // M2 修复：DETOUR 检索忽略 tool 维度（DETOUR 记录 tool_name='detour'，
        // 传 tool_name 会被 selectFuzzy 的 AND tool_name 过滤掉导致恒空）
        List<Lesson> candidates;
        if (Lesson.TYPE_DETOUR.equals(effType)) {
            candidates = (kw != null)
                    ? fuzzySearch(projectKey, null, kw, effType, FUZZY_SEARCH_LIMIT)
                    : List.of();
        } else {
            candidates = exactCode != null
                    ? lessonMapper.selectByScope(projectKey, tool, null, exactCode, effType, SEARCH_LIMIT)
                    : List.of();
            // P0-1②：原文再试一次精确（覆盖「LLM 直接传库内短码——字典不识别短码字面」与大小写差异）
            if (candidates.isEmpty() && rawCode != null
                    && (exactCode == null || !rawCode.equals(exactCode))) {
                candidates = lessonMapper.selectByScope(projectKey, tool, null, rawCode, effType, SEARCH_LIMIT);
            }
        }

        // 第二级：参数等值比对（候选非空时执行；DETOUR 无参数维度，跳过）
        if (!candidates.isEmpty() && !Lesson.TYPE_DETOUR.equals(effType)) {
            List<Lesson> filtered = filterByParams(candidates, paramsJson);
            if (!filtered.isEmpty()) {
                candidates = filtered;
            }
            // 若参数过滤后为空：保留原候选展示（宽松策略——参数不符不淘汰，交由 LLM 结合「适用条件」判断）
        }

        // 第三级：模糊兜底（P0：多词 OR；kw 为空时用原始 error_code 文本兜底，不再直接「未找到」）
        if (candidates.isEmpty() && !Lesson.TYPE_DETOUR.equals(effType)) {
            String fuzzyKw = kw != null ? kw : rawCode;
            if (fuzzyKw != null) {
                candidates = fuzzySearch(projectKey, tool, fuzzyKw, effType, FUZZY_SEARCH_LIMIT);
            }
        }

        if (candidates.isEmpty()) {
            return new SearchOutcome("📭 未找到匹配的踩坑经验。可尝试：\n"
                    + "  1. 减少过滤条件（不传 tool_name / error_code，只传 keyword）\n"
                    + "  2. 用更通用的关键词（如错误码、异常类名、报错关键字）\n"
                    + "  3. 这是新坑的话，可用 lesson action=record 记录，后续遇到就能查到", List.of());
        }

        // P2：环境兼容排序（同 os/通用经验优先，异环境经验降权）后截取 Top-K
        sortByEnvCompat(candidates);
        List<Lesson> top = candidates.subList(0, Math.min(candidates.size(), RESULT_TOP_K));
        for (Lesson l : top) {
            lessonMapper.incrementHitCount(l.getId(), LocalDateTime.now());
        }
        String text = buildResultText(top);
        if (!collectHits) {
            return new SearchOutcome(text, List.of());
        }
        // P1-D：收集命中引用（仅「有可用解法」条目——与 F3 SOLUTION 追踪语义一致）
        List<SearchHitTrack> hits = new ArrayList<>();
        for (Lesson l : top) {
            if (l.hasUsableSolution()) {
                hits.add(new SearchHitTrack(l.getId(), crossProjectSignature(l),
                        nz(l.getSuccessCount()), nz(l.getFailCount())));
            }
        }
        return new SearchOutcome(text, hits);
    }

    /**
     * 参数等值比对：对「两边都出现的参数名」，value 必须一致；
     * 只在一侧出现的参数不淘汰（未提供维度不参与判定）。
     */
    private List<Lesson> filterByParams(List<Lesson> lessons, String paramsJson) {
        Map<String, String> queryParams = parseParams(paramsJson);
        if (queryParams.isEmpty()) {
            return lessons; // 调用方没给参数维度，不做二级过滤
        }
        List<Lesson> kept = new ArrayList<>();
        for (Lesson lesson : lessons) {
            Map<String, String> lessonParams = parseParams(lesson.getParamsJson());
            boolean match = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                String lessonValue = lessonParams.get(entry.getKey());
                if (lessonValue != null && !lessonValue.equals(entry.getValue())) {
                    match = false; // 同名参数值不一致 → 淘汰（差一个参数就是两条不同的坑）
                    break;
                }
            }
            if (match) {
                kept.add(lesson);
            }
        }
        return kept;
    }

    private Map<String, String> parseParams(String paramsJson) {
        Map<String, String> result = new HashMap<>();
        if (paramsJson == null || paramsJson.isBlank() || "[]".equals(paramsJson)) {
            return result;
        }
        try {
            JsonNode array = objectMapper.readTree(paramsJson);
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    String name = node.path("name").asText("");
                    String value = node.path("value").asText("");
                    if (!name.isEmpty()) {
                        result.put(name, value);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 params_json 失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * L2 修复：归一化 params JSON —— 校验格式、按 name 排序、value 截断 200。
     * 自动捕获（FailureNormalizer.extractParams 已按 name 排序）与手动记录（LLM 输入顺序不定）
     * 的格式统一，保证同坑跨通道指纹一致、去重有效。
     * 非数组/非法 JSON 一律归为 "[]"（不污染经验库）。
     */
    private String normalizeParamsJson(String paramsJson) {
        Map<String, String> map = parseParams(paramsJson);
        if (map.isEmpty()) {
            return "[]";
        }
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("name", e.getKey());
                        String value = e.getValue();
                        node.put("value", value.length() > 200 ? value.substring(0, 200) : value);
                        arr.add(node);
                    });
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            log.debug("归一化 params_json 失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * L1 修复：转义 LIKE 通配符（% _ \），配合 Mapper 的 ESCAPE '\' 子句，
     * 防止用户输入 "100%" 之类关键词被当通配符全表匹配。
     */
    private String escapeLikeKeyword(String keyword) {
        if (keyword == null) return null;
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    // ============================================================
    // P0 检索增强：入口错误码归一化对齐 + 多词拆解模糊检索
    // ============================================================

    /**
     * P0-1：检索入口错误码归一化——LLM 传入的文本（原始报错/异常类名）经归一化管线
     * （规则表 → 字典 → 正则，与入库端同一体系）转为库内稳定短码。
     * 返回 "UNKNOWN"/null 表示无法转换（调用方禁用精确码维度，改用模糊兜底）。
     */
    private String normalizeQueryErrorCode(String toolName, String rawCode) {
        // P1-B：先走别名映射（LLM 可能直接传别名形态的码，如 HTTP_404_NOT_FOUND → HTTP_404）
        String canonical = normalizer.canonicalizeCode(rawCode);
        if (canonical != null && !canonical.equals(rawCode)) {
            return canonical;
        }
        try {
            FailureNormalizer.NormalizedFailure nf = normalizer.normalize(
                    toolName == null ? "" : toolName, null, rawCode);
            return nf == null ? null : nf.errorCode;
        } catch (Exception e) {
            log.debug("检索入口错误码归一化失败，按原文处理: {}", e.getMessage());
            return null;
        }
    }

    /**
     * P0-2：模糊检索封装——拆词 → 多词 OR 查询 → 按命中词数稳定排序。
     * 全链路大小写不敏感（SQL LOWER + 此处小写比对）；词内通配符已转义。
     */
    private List<Lesson> fuzzySearch(String projectKey, String toolName, String rawText, String type, int limit) {
        List<String> words = splitFuzzyKeywords(rawText);
        if (words.isEmpty()) {
            return List.of();
        }
        List<String> escaped = new ArrayList<>(words.size());
        for (String w : words) {
            escaped.add(escapeLikeKeyword(w));
        }
        List<Lesson> hits = lessonMapper.selectFuzzy(projectKey, toolName, escaped, type, limit);
        if (hits.size() > 1) {
            // 稳定排序：命中词数多者优先（同词数保持 SQL 排序：项目内 → 有效 → 命中数）
            hits.sort(Comparator.comparingInt((Lesson l) -> -countMatchedWords(l, words)));
        }
        return hits;
    }

    /**
     * P0-2：拆词——空白/标点切段；ASCII 段整词保留（滤英文停用词）；中文段切 2-gram
     * （首/尾字为虚词的单字过滤，降低跨界噪声）；去重后取前 MAX_FUZZY_WORDS 个。
     * P2：包级可见静态（供验证类直测）。
     */
    static List<String> splitFuzzyKeywords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] segments = text.split("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+");
        Set<String> words = new LinkedHashSet<>();
        for (String seg : segments) {
            if (seg.isEmpty()) {
                continue;
            }
            boolean ascii = seg.chars().allMatch(c -> c < 128);
            if (ascii) {
                String w = seg.toLowerCase(Locale.ROOT);
                if (w.length() >= 2 && !EN_STOP_WORDS.contains(w)) {
                    words.add(w);
                }
            } else {
                // 中文（含混合）段：2-gram 滑窗（长度为 2 的段自然生成自身）
                for (int i = 0; i + 2 <= seg.length(); i++) {
                    String bg = seg.substring(i, i + 2);
                    if (CN_STOP_CHARS.contains(bg.charAt(0)) || CN_STOP_CHARS.contains(bg.charAt(1))) {
                        continue;
                    }
                    words.add(bg);
                }
            }
            if (words.size() >= MAX_FUZZY_WORDS) {
                break;
            }
        }
        if (words.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(words);
        return result.size() > MAX_FUZZY_WORDS ? new ArrayList<>(result.subList(0, MAX_FUZZY_WORDS)) : result;
    }

    /** P0-2：统计经验文本（五字段）命中的查询词数（小写比对，与 SQL LOWER 语义一致；供相关度排序） */
    private int countMatchedWords(Lesson lesson, List<String> words) {
        String text = ((lesson.getSymptom() == null ? "" : lesson.getSymptom()) + "\n"
                + (lesson.getRootCause() == null ? "" : lesson.getRootCause()) + "\n"
                + (lesson.getSolution() == null ? "" : lesson.getSolution()) + "\n"
                + (lesson.getKeywords() == null ? "" : lesson.getKeywords()) + "\n"
                + (lesson.getGoal() == null ? "" : lesson.getGoal())).toLowerCase(Locale.ROOT);
        int count = 0;
        for (String w : words) {
            if (!w.isEmpty() && text.contains(w.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    // ============================================================
    // P1-C：入库前浅查重（review 通道降压——相似合并优先于新建）
    // ============================================================

    /**
     * P1-C：同坑浅查重——同 project+tool+category+code 已有「项目内」条目时，
     * 把新提炼内容「补空合并」进既有条目（只补空不覆盖），返回合并目标；
     * 无既有条目返回 null（调用方走正常新建/签名去重）。
     * 用于高产的复盘通道：经验库中已有同类坑时不再新建条目，防碎片累积。
     */
    public Lesson mergeIntoExisting(String projectKey, String toolName, String category, String code,
                                    String rootCause, String solution, String paramsJson,
                                    String applicableCond, String keywords) {
        if (projectKey == null || projectKey.isBlank()) projectKey = normalizer.extractProjectKey();
        String pKey = projectKey;
        List<Lesson> existing = lessonMapper.selectByScope(pKey, toolName, category, code,
                        Lesson.TYPE_FAILURE, 5).stream()
                .filter(l -> pKey.equals(l.getProjectKey())) // 只合并项目内条目（不污染全局池）
                .collect(java.util.stream.Collectors.toList());
        if (existing.isEmpty()) {
            return null;
        }
        // 选合并目标：有可用解法优先 → 命中高 → id 小（P2：与 mergeGroup 共用 pickMergeTarget）
        Lesson target = pickMergeTarget(existing);
        // 只补空（不覆盖既有内容）
        String newRc = isBlankText(target.getRootCause()) && !isBlankText(rootCause) ? truncate(rootCause, 1024) : null;
        String newSol = !target.hasUsableSolution() && !isBlankText(solution)
                && !Lesson.PLACEHOLDER_SOLUTION.equals(solution.trim()) ? truncate(solution, 2048) : null;
        String newCond = isBlankText(target.getApplicableCond()) && !isBlankText(applicableCond)
                ? truncate(applicableCond, 512) : null;
        String newKw = isBlankText(target.getKeywords()) && !isBlankText(keywords) ? truncate(keywords, 512) : null;
        String normParams = normalizeParamsJson(paramsJson);
        String newParams = (isBlankText(target.getParamsJson()) || "[]".equals(target.getParamsJson()))
                && !"[]".equals(normParams) ? normParams : null;
        if (newRc != null || newSol != null || newCond != null || newKw != null || newParams != null) {
            lessonMapper.updateContent(target.getId(), newRc, newSol, newParams, newCond, newKw,
                    null, LocalDateTime.now());
            log.info("经验入库浅查重合并（补空）: targetId={}, tool={}, code={}", target.getId(), toolName, code);
            return lessonMapper.selectById(target.getId()).orElse(target);
        }
        log.info("经验入库浅查重命中（既有条目已完整，不新建）: targetId={}, tool={}, code={}",
                target.getId(), toolName, code);
        return target;
    }

    /** 空串/null 判定（浅查重补空用） */
    private boolean isBlankText(String s) {
        return s == null || s.isBlank();
    }

    // ============================================================
    // P1-D：LLM 主动 search 命中追踪（工具线程投递 → 主循环登记 F3）
    // ============================================================

    /** search 命中追踪引用（工具线程产出，主循环消费并登记 F3 追踪表） */
    public static class SearchHitTrack {
        public final Long lessonId;
        public final String errorSignature;
        public final int baselineSuccess;
        public final int baselineFail;

        public SearchHitTrack(Long lessonId, String errorSignature, int baselineSuccess, int baselineFail) {
            this.lessonId = lessonId;
            this.errorSignature = errorSignature;
            this.baselineSuccess = baselineSuccess;
            this.baselineFail = baselineFail;
        }
    }

    /** 待登记队列：conversationId（原始）→ 命中引用列表；工具线程写入，主循环 drain */
    private final Map<Long, List<SearchHitTrack>> pendingSearchTracks = new java.util.concurrent.ConcurrentHashMap<>();

    /** P1-D：工具线程投递 search 命中（LessonTool 经 ToolContext 取 conversationId 调用；best-effort，容量超限整体清理防泄漏） */
    public void registerSearchHits(Long conversationId, List<SearchHitTrack> hits) {
        if (conversationId == null || hits == null || hits.isEmpty()) {
            return;
        }
        if (pendingSearchTracks.size() > 500) {
            pendingSearchTracks.clear();
        }
        pendingSearchTracks.computeIfAbsent(conversationId, k -> new ArrayList<>()).addAll(hits);
    }

    /** P1-D：主循环领取并清空（drain）本会话的 search 命中引用 */
    public List<SearchHitTrack> drainSearchHits(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<SearchHitTrack> hits = pendingSearchTracks.remove(conversationId);
        return hits == null ? List.of() : hits;
    }

    /**
     * P1：DETOUR 弯路经验指纹 = md5(projectKey|detour|normalizedGoal)。
     * goal 维度去重（同一目标的弯路经验只保留一条，方案迭代时 hit+1 合并）。
     * normalizedGoal：去标点/空白、转小写（M6 修复：不做长度截断——
     * 两个不同长目标若共享前 N 字符会被误合并（误合并丢信息比不合并更危险），完整哈希保准）。
     */
    private String detourFingerprint(String projectKey, String goal) {
        String normalizedGoal = normalizeGoal(goal);
        String raw = projectKey + "|detour|" + normalizedGoal;
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 归一化 goal：去空白 + ASCII/全角/中文标点、转小写（M6 修复：不截断，完整哈希防误合并） */
    private String normalizeGoal(String goal) {
        if (goal == null) {
            return "";
        }
        // \p{Punct} 只覆盖 ASCII 标点，需补全角（\uFF00-\uFFEF：！＂等）与 CJK 标点（\u3000-\u303F：。、【】等）
        return goal.trim().toLowerCase()
                .replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", "");
    }

    // ============================================================
    // P2 环境参数（记录时自动附加 os；检索异环境降权不淘汰，避免误杀通用经验）
    // ============================================================

    /** 检测当前操作系统（归一化：windows/linux/mac/unknown） */
    private String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac";
        if (os.contains("linux")) return "linux";
        return "unknown";
    }

    /** 构建环境参数 JSON（当前 os） */
    private String buildEnvParams() {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("os", detectOs());
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"os\":\"unknown\"}";
        }
    }

    /** 环境兼容排序：通用经验（无 env）> 同 os 经验 > 异环境经验（P2，在截取 Top-K 前调用） */
    private void sortByEnvCompat(List<Lesson> lessons) {
        String currentOs = detectOs();
        lessons.sort((a, b) -> envScore(b, currentOs) - envScore(a, currentOs));
    }

    /** 环境兼容分：通用=1 / 同环境=2 / 异环境=0 */
    private int envScore(Lesson lesson, String currentOs) {
        String env = lesson.getEnvParams();
        if (env == null || env.isBlank()) {
            return 1;
        }
        if (env.contains("\"os\":\"" + currentOs + "\"")) {
            return 2;
        }
        return 0;
    }

    private String buildResultText(List<Lesson> lessons) {
        StringBuilder sb = new StringBuilder();
        sb.append("📚 踩坑经验命中 ").append(lessons.size()).append(" 条（来自经验库，供参考）:\n");
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            int total = l.getSuccessCount() + l.getFailCount();
            String verified = l.getStatus() == Lesson.STATUS_ACTIVE
                    ? "✅已验证" : (total > 0 ? "⏳验证中" : "🆕新记录");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("[").append(i + 1).append("] [").append(l.getErrorCategory()).append("] ")
                    .append(l.getErrorCode()).append(" (ID:").append(l.getId()).append(", ")
                    .append(verified).append(", 命中").append(l.getHitCount())
                    .append("次, 有效").append(l.getSuccessCount()).append("/").append(total).append(")\n");
            if (l.getSymptom() != null && !l.getSymptom().isBlank()) {
                sb.append("  现象: ").append(l.getSymptom()).append("\n");
            }
            if (l.getRootCause() != null && !l.getRootCause().isBlank()) {
                sb.append("  根因: ").append(l.getRootCause()).append("\n");
            }
            sb.append("  解法: ").append(l.getSolution()).append("\n");
            if (l.getApplicableCond() != null && !l.getApplicableCond().isBlank()) {
                sb.append("  适用: ").append(l.getApplicableCond()).append("\n");
            }
            if (l.getParamsJson() != null && !l.getParamsJson().isBlank() && !"[]".equals(l.getParamsJson())) {
                sb.append("  关键参数: ").append(l.getParamsJson()).append("\n");
            }
            sb.append("  验证: 应用后请调用 lesson action=feedback lesson_id=").append(l.getId())
                    .append(" effective=true/false 反馈结果\n");
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("提示: 经验仅供参考，请结合当前项目实际情况判断是否适用（注意适用条件与关键参数）。");
        return sb.toString();
    }

    // ============================================================
    // 反馈闭环
    // ============================================================

    /**
     * 反馈经验应用结果：
     * effective=true → success_count+1，草稿且成功≥2 次自动转正
     * effective=false → fail_count+1，连续失败≥5 次自动隐藏
     */
    public String feedbackLesson(Long id, boolean effective) {
        Optional<Lesson> exist = lessonMapper.selectById(id);
        if (exist.isEmpty()) {
            return "错误: 经验 ID=" + id + " 不存在，无法反馈。";
        }
        LocalDateTime now = LocalDateTime.now();
        Lesson lesson = exist.get();
        if (effective) {
            lessonMapper.feedbackSuccess(id, now);
        } else {
            lessonMapper.feedbackFail(id, now);
        }
        Lesson updated = lessonMapper.selectById(id).orElse(lesson);
        String outcome = effective
                ? "✅ 验证有效，成功次数 +1（累计 " + updated.getSuccessCount() + "）"
                : "❌ 验证无效，失败次数 +1（累计 " + updated.getFailCount() + "，连续失败 5 次自动隐藏）";
        String statusText;
        if (updated.getStatus() == Lesson.STATUS_ACTIVE) {
            statusText = "经验已转正为【有效】，后续检索将优先展示";
        } else if (updated.getStatus() == Lesson.STATUS_HIDDEN) {
            statusText = "经验已自动【隐藏】，不再参与检索";
        } else {
            int need = 2 - updated.getSuccessCount();
            statusText = "经验仍为【草稿】，再验证有效 " + Math.max(need, 0) + " 次即可转正";
        }
        return "经验 (ID:" + id + ") 反馈完成: " + outcome + "。" + statusText;
    }

    // ============================================================
    // 补全（LLM 模板提炼）：把自动捕获的草稿完善成「模板+变量」
    // ============================================================

    /**
     * 补全草稿经验内容。
     * 只更新非空字段，status 保持草稿（转正仍由反馈闭环驱动）。
     *
     * @return 补全结果提示文本
     */
    public String completeLesson(Long id, String rootCause, String solution,
                                 String paramsJson, String applicableCond, String keywords) {
        Optional<Lesson> exist = lessonMapper.selectById(id);
        if (exist.isEmpty()) {
            return "错误: 经验 ID=" + id + " 不存在，无法补全。";
        }
        Lesson lesson = exist.get();
        if ((rootCause == null || rootCause.isBlank())
                && (solution == null || solution.isBlank())
                && (paramsJson == null || paramsJson.isBlank())
                && (applicableCond == null || applicableCond.isBlank())
                && (keywords == null || keywords.isBlank())) {
            return "参数不足: complete 至少需要提供 root_cause / solution / params / applicable_cond / keywords 中的一项。";
        }
        // 空字符串视为不更新，null 也视为不更新（由调用方决定传什么）
        String effRootCause = (rootCause == null || rootCause.isBlank()) ? null : truncate(rootCause, 1024);
        String effSolution = (solution == null || solution.isBlank()) ? null : truncate(solution, 2048);
        // L2 修复：补全的 params 同样归一化，保证与自动捕获格式一致
        String effParams = (paramsJson == null || paramsJson.isBlank()) ? null : normalizeParamsJson(paramsJson);
        String effCond = (applicableCond == null || applicableCond.isBlank()) ? null : truncate(applicableCond, 512);
        String effKeywords = (keywords == null || keywords.isBlank()) ? null : truncate(keywords, 512);

        lessonMapper.updateContent(id, effRootCause, effSolution, effParams, effCond, effKeywords,
                null, LocalDateTime.now());
        Lesson updated = lessonMapper.selectById(id).orElse(lesson);

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 经验 (ID:").append(id).append(") 已补全:\n");
        sb.append("  项目: ").append(updated.getProjectKey())
                .append(" | 工具: ").append(updated.getToolName())
                .append(" | [").append(updated.getErrorCategory()).append("] ")
                .append(updated.getErrorCode()).append("\n");
        if (updated.getRootCause() != null && !updated.getRootCause().isBlank()) {
            sb.append("  根因: ").append(updated.getRootCause()).append("\n");
        }
        if (updated.getSolution() != null && !updated.getSolution().isBlank()) {
            sb.append("  解法: ").append(updated.getSolution()).append("\n");
        }
        if (updated.getParamsJson() != null && !updated.getParamsJson().isBlank() && !"[]".equals(updated.getParamsJson())) {
            sb.append("  关键参数: ").append(updated.getParamsJson()).append("\n");
        }
        if (updated.getApplicableCond() != null && !updated.getApplicableCond().isBlank()) {
            sb.append("  适用条件: ").append(updated.getApplicableCond()).append("\n");
        }
        if (updated.getKeywords() != null && !updated.getKeywords().isBlank()) {
            sb.append("  关键词: ").append(updated.getKeywords()).append("\n");
        }
        sb.append("  状态: ").append(updated.getStatus() == Lesson.STATUS_ACTIVE ? "有效"
                : updated.getStatus() == Lesson.STATUS_HIDDEN ? "隐藏" : "草稿")
                .append("（补全不改变状态，转正仍靠 feedback 验证）");
        return sb.toString();
    }

    // ============================================================
    // 被动提示（供工具循环失败时自动注入，零常驻成本）
    // ============================================================

    /** 提示类型：有解法的经验（可指导 LLM 解决问题） */
    public static final String HINT_TYPE_SOLUTION = "SOLUTION";
    /** 提示类型：无解法的草稿（仅告知存在，引导 LLM 补全） */
    public static final String HINT_TYPE_DRAFT = "HINT";

    /**
     * 被动提示结果：含经验 ID、提示类型（去重 key 维度）、错误签名（F3 追踪比对）、
     * 注入时的计数基线（F3 baseline 防双重计数）
     */
    public static class LessonHint {
        public final Long lessonId;
        public final String text;
        public final String type;            // SOLUTION / HINT
        public final String errorSignature;
        public final int baselineSuccess;
        public final int baselineFail;

        public LessonHint(Long lessonId, String text, String type, String errorSignature,
                          int baselineSuccess, int baselineFail) {
            this.lessonId = lessonId;
            this.text = text;
            this.type = type;
            this.errorSignature = errorSignature;
            this.baselineSuccess = baselineSuccess;
            this.baselineFail = baselineFail;
        }
    }

    /**
     * 检索被动提示（F1 修正版双通道）：
     * 从候选中提取两条提示 ——
     *   ① SOLUTION：第一条「有可用解法」的经验（指导 LLM 解决问题）
     *   ② HINT：第一条「无解法草稿」（告知 LLM 该坑被记录过，引导 complete 补全）
     * 返回 List 可为空；两条 hint 的 lessonId 不同（同一经验不可能同时有/无解法）。
     *
     * 检索路径与 searchLessons 一致：scope 硬过滤 → 参数等值比对 → LIKE 兜底
     *
     * @param symptom 失败现象/错误消息原文，用于 LIKE 兜底的关键词来源（可 null）
     */
    public List<LessonHint> retrieveLessonHints(String projectKey, String toolName, String errorCode,
                                                String paramsJson, String symptom) {
        if (projectKey == null || projectKey.isBlank()) projectKey = normalizer.extractProjectKey();
        String tool = (toolName == null || toolName.isBlank()) ? null : toolName;
        String code = (errorCode == null || errorCode.isBlank()) ? null : errorCode;

        // ① 硬过滤（P1：被动注入面向工具失败场景，只查 FAILURE 类型；DETOUR 由任务规划时主动 search 命中）
        List<Lesson> candidates = lessonMapper.selectByScope(projectKey, tool, null, code, Lesson.TYPE_FAILURE, SEARCH_LIMIT);
        // ② 参数等值比对
        if (!candidates.isEmpty()) {
            List<Lesson> filtered = filterByParams(candidates, paramsJson);
            if (!filtered.isEmpty()) {
                candidates = filtered;
            }
        }
        // ③ 模糊兜底（P0-2：失败现象交拆词器处理——多词 OR + 大小写不敏感，
        //    覆盖「错误码归一化不一致」与「整串 LIKE 失配」两类缺口）
        if (candidates.isEmpty() && symptom != null && !symptom.isBlank()) {
            List<Lesson> fuzzy = fuzzySearch(projectKey, tool, symptom, Lesson.TYPE_FAILURE, FUZZY_SEARCH_LIMIT);
            if (!fuzzy.isEmpty()) {
                candidates = fuzzy;
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // P2：环境兼容排序（同 os/通用经验优先，异环境经验降权）——F1 双通道遍历前
        sortByEnvCompat(candidates);

        // F1 双通道：解法提示 + 补全引导（两者互斥取不同记录）
        Lesson solutionHint = null;
        Lesson draftHint = null;
        for (Lesson l : candidates) {
            if (solutionHint == null && l.hasUsableSolution()) {
                solutionHint = l;
            } else if (draftHint == null && !l.hasUsableSolution()) {
                draftHint = l;
            }
            if (solutionHint != null && draftHint != null) {
                break;
            }
        }

        List<LessonHint> hints = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        if (solutionHint != null) {
            lessonMapper.incrementHitCount(solutionHint.getId(), now);
            // P1-1：追踪签名改用「无项目签名」——全局经验（__global__）入库签名含项目前缀，
            // 与当前项目失败重算的含项目签名必然不等，会导致 F3「再次失败判无效」对全局经验永不触发
            hints.add(new LessonHint(solutionHint.getId(), buildHintText(solutionHint),
                    HINT_TYPE_SOLUTION, crossProjectSignature(solutionHint),
                    nz(solutionHint.getSuccessCount()), nz(solutionHint.getFailCount())));
        }
        if (draftHint != null) {
            // P0-4：草稿补全引导不计 hit_count——hit 语义收窄为「有解法的经验被展示/复用」，
            // 避免草稿提示灌水导致命中统计失真（原实现此处同样 incrementHitCount）
            hints.add(new LessonHint(draftHint.getId(), buildDraftHintText(draftHint),
                    HINT_TYPE_DRAFT, crossProjectSignature(draftHint),
                    nz(draftHint.getSuccessCount()), nz(draftHint.getFailCount())));
        }
        return hints;
    }

    /**
     * 从经验实体组装「无项目同坑签名」（P1-1，F3 追踪比对专用）：
     * 与 FailureNormalizer.fingerprintNoProject 算法一致（不含 projectKey），
     * 保证当前项目失败重算的签名与注入经验（无论项目内还是全局池）可命中。
     */
    private String crossProjectSignature(Lesson lesson) {
        try {
            FailureNormalizer.NormalizedFailure nf = new FailureNormalizer.NormalizedFailure();
            nf.toolName = lesson.getToolName();
            nf.errorCategory = lesson.getErrorCategory();
            nf.errorCode = lesson.getErrorCode();
            nf.paramsJson = lesson.getParamsJson();
            return normalizer.fingerprintNoProject(nf);
        } catch (Exception e) {
            log.warn("组装无项目签名失败，退回库内签名: lessonId={}, err={}", lesson.getId(), e.getMessage());
            return lesson.getErrorSignature();
        }
    }

    /**
     * 构建解法提示（≤200字）：状态徽章（F4）+ 现象/解法 + 适用条件
     */
    private String buildHintText(Lesson lesson) {
        StringBuilder sb = new StringBuilder();
        // F4 状态徽章：✅已验证×N / ⏳验证中 / 🆕未验证
        String badge;
        if (lesson.getStatus() != null && lesson.getStatus() == Lesson.STATUS_ACTIVE) {
            badge = "✅已验证×" + nz(lesson.getSuccessCount());
        } else if (nz(lesson.getSuccessCount()) + nz(lesson.getFailCount()) > 0) {
            badge = "⏳验证中(有效" + nz(lesson.getSuccessCount()) + "/失败" + nz(lesson.getFailCount()) + ")";
        } else {
            badge = "🆕未验证";
        }
        sb.append("📚 踩坑经验参考 (ID:").append(lesson.getId())
                .append(", ").append(lesson.getErrorCategory()).append("/").append(lesson.getErrorCode())
                .append(") [").append(badge).append("]: ");
        String solution = lesson.getSolution();
        if (solution != null && !solution.isBlank()) {
            sb.append(solution);
        }
        if (lesson.getApplicableCond() != null && !lesson.getApplicableCond().isBlank()) {
            sb.append(" [适用条件: ").append(lesson.getApplicableCond()).append("]");
        }
        String text = sb.toString();
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    /**
     * 构建补全引导（固定短模板 ≤80字）：告知草稿存在 + 引导 complete
     */
    private String buildDraftHintText(Lesson lesson) {
        return "⚠️ 同坑已有草稿经验 (ID:" + lesson.getId() + ", " + lesson.getErrorCategory() + "/"
                + lesson.getErrorCode() + ") 但解法未沉淀；若你本次成功解决，请调用 lesson action=complete lesson_id="
                + lesson.getId() + " 补全解法，后续可自动复用。";
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ============================================================
    // 列表（巡检/管理）
    // ============================================================

    /**
     * 列出项目最近经验（按有效优先 + 命中次数排序）
     */
    public String listLessons(String projectKey, int limit) {
        if (projectKey == null || projectKey.isBlank()) projectKey = normalizer.extractProjectKey();
        int capped = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 20));
        List<Lesson> lessons = lessonMapper.selectRecent(projectKey, capped);
        if (lessons.isEmpty()) {
            return "📭 当前项目「" + projectKey + "」暂无踩坑经验。\n"
                    + "提示: 工具执行失败会自动捕获草稿经验，也可用 lesson action=record 主动记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📚 当前项目「").append(projectKey).append("」经验库共 ").append(lessons.size()).append(" 条（按有效/命中排序）:\n");
        for (int i = 0; i < lessons.size(); i++) {
            Lesson l = lessons.get(i);
            String verified = l.getStatus() == Lesson.STATUS_ACTIVE
                    ? "✅已验证" : (l.getSuccessCount() + l.getFailCount() > 0 ? "⏳验证中" : "🆕新记录");
            sb.append("[").append(i + 1).append("] ID:").append(l.getId())
                    .append(" [").append(l.getErrorCategory()).append("/").append(l.getErrorCode()).append("] ")
                    .append(verified).append(", 命中").append(l.getHitCount())
                    .append("次, 有效").append(l.getSuccessCount()).append("/")
                    .append(l.getSuccessCount() + l.getFailCount()).append("\n");
            if (l.getSymptom() != null && !l.getSymptom().isBlank()) {
                String symptom = l.getSymptom();
                sb.append("  现象: ").append(symptom.length() > 100 ? symptom.substring(0, 100) + "…" : symptom).append("\n");
            }
            if (l.getSolution() != null && !l.getSolution().isBlank()) {
                String solution = l.getSolution();
                sb.append("  解法: ").append(solution.length() > 120 ? solution.substring(0, 120) + "…" : solution).append("\n");
            }
            sb.append("  工具: ").append(l.getToolName())
                    .append(" | 来源: ").append(l.getSource())
                    .append(" | 更新: ").append(l.getUpdatedAt()).append("\n");
        }
        sb.append("提示: 对某条经验可用 lesson action=search 查看完整内容，或 action=complete 补全草稿。");
        return sb.toString();
    }

    // ============================================================
    // Phase 3：管理 API（分页 / 统计 / 删除）
    // ============================================================

    /**
     * 分页查询（管理页面）：project_key 必填，status/tool_name/error_code/type/zeroHit 可选过滤
     *
     * @return Map 含 items（当前页列表）与 total（总条数）
     */
    public Map<String, Object> pageQuery(String projectKey, Integer status, String toolName,
                                         String errorCode, String type, Boolean zeroHit, int page, int size) {
        // 管理页面：projectKey 留空 = 查询全部项目（管理通道不受项目隔离限制，
        // 避免 LLM 记录经验的项目（根目录名）与页面默认查询不一致导致查不到数据）
        if (projectKey != null && projectKey.isBlank()) projectKey = null;
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 10 : size, 100));
        int offset = (safePage - 1) * safeSize;

        List<Lesson> items = lessonMapper.selectPage(projectKey, status,
                (toolName == null || toolName.isBlank()) ? null : toolName,
                (errorCode == null || errorCode.isBlank()) ? null : errorCode,
                type, zeroHit, offset, safeSize);
        long total = lessonMapper.countByFilter(projectKey, status,
                (toolName == null || toolName.isBlank()) ? null : toolName,
                (errorCode == null || errorCode.isBlank()) ? null : errorCode,
                type, zeroHit);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    /**
     * 经验库统计（成长看板）：总数 / 各状态 / 总命中 / 成功率 / 来源分布 / 近7天新增
     */
    public Map<String, Object> getStats(String projectKey) {
        // 管理页面：projectKey 留空 = 统计全部项目（与 pageQuery 的查全部口径一致）
        if (projectKey != null && projectKey.isBlank()) projectKey = null;
        Map<String, Object> raw = lessonMapper.selectStats(projectKey, LocalDateTime.now().minusDays(7));

        long total = toLong(raw.get("total"));
        long totalSuccess = toLong(raw.get("total_success"));
        long totalFail = toLong(raw.get("total_fail"));
        long verified = totalSuccess + totalFail;

        Map<String, Object> stats = new HashMap<>();
        stats.put("projectKey", projectKey == null ? "all" : projectKey);
        stats.put("total", total);
        stats.put("draftCount", toLong(raw.get("draft_count")));
        stats.put("activeCount", toLong(raw.get("active_count")));
        stats.put("hiddenCount", toLong(raw.get("hidden_count")));
        stats.put("totalHits", toLong(raw.get("total_hits")));
        stats.put("totalSuccess", totalSuccess);
        stats.put("totalFail", totalFail);
        // 验证成功率（贝叶斯平滑，防除零）：已验证 ≥1 次的经验中有效的比例
        stats.put("successRate", verified == 0 ? 0.0 : Math.round(totalSuccess * 1000.0 / verified) / 10.0);
        stats.put("autoCount", toLong(raw.get("auto_count")));
        stats.put("llmCount", toLong(raw.get("llm_count")));
        stats.put("manualCount", toLong(raw.get("manual_count")));
        stats.put("recent7d", toLong(raw.get("recent_count")));
        // P0 归一化管线指标（规则通道增强 + LLM 兜底效果）
        stats.put("ruleMatchCount", normalizer.getRuleMatchCount());
        stats.put("normLlmCallCount", normalizerService.getLlmCallCount());
        stats.put("normCacheHitCount", normalizerService.getCacheHitCount());
        stats.put("normBackfillCount", normalizerService.getBackfillCount());
        // P2 规则自学习闭环（容量检查 + 候选/转正/使用度统计；替代时间淘汰）
        try {
            long ruleTotal = ruleMapper.count();
            if (ruleTotal > ruleMaxSize) {
                int removed = ruleMapper.deleteUnusedCandidates((int) (ruleTotal - ruleMaxSize));
                if (removed > 0) {
                    log.info("看板触发规则容量淘汰未使用候选: {} 条", removed);
                }
            }
            stats.put("ruleTotalCount", ruleMapper.count());
            // L1 修复：候选数用 countCandidates（status=0 且非 manual），与容量淘汰语义一致
            stats.put("ruleCandidateCount", ruleMapper.countCandidates());
            stats.put("ruleActiveCount", ruleMapper.countActive());
            stats.put("ruleMatchHitTotal", ruleMapper.sumMatchHits());
        } catch (Exception e) {
            log.debug("规则统计失败（不影响看板）: {}", e.getMessage());
            stats.put("ruleTotalCount", 0L);
            stats.put("ruleCandidateCount", 0L);
            stats.put("ruleActiveCount", 0L);
            stats.put("ruleMatchHitTotal", 0L);
        }
        return stats;
    }

    /**
     * 删除经验（物理删除，管理操作：确认误录/废弃）
     */
    public boolean deleteLesson(Long id) {
        if (id == null) return false;
        return lessonMapper.deleteById(id) > 0;
    }

    // ============================================================
    // P2：聚类查询 + 归并执行（管理页人工治理入口）
    // ============================================================

    /**
     * P2：聚类查询——返回重复组列表（条数 ≥2，排除 DETOUR），供管理页归并入口。
     */
    public List<Map<String, Object>> listClusters(String projectKey, int limit) {
        if (projectKey != null && projectKey.isBlank()) projectKey = null;
        int capped = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return lessonMapper.selectClusterGroups(projectKey, capped);
    }

    /**
     * P2：归并重复组——同 project+tool+category+code 的多条记录合并为一条：
     * 选主（有解法 → 命中高 → id 小）→ 删除其余（计数汇聚）→ 主记录 canonical 码 + 新指纹重算。
     * 管理操作（人工触发），与 P1 存量迁移同一套规则。
     */
    public String mergeGroup(String projectKey, String toolName, String errorCategory, String errorCode) {
        if (projectKey == null || projectKey.isBlank()
                || toolName == null || toolName.isBlank()
                || errorCategory == null || errorCategory.isBlank()
                || errorCode == null || errorCode.isBlank()) {
            return "参数不足：需要 projectKey / toolName / errorCategory / errorCode";
        }
        List<Lesson> group = lessonMapper.selectByScope(projectKey, toolName, errorCategory, errorCode,
                        null, 100).stream()
                .filter(l -> projectKey.equals(l.getProjectKey()))
                .collect(java.util.stream.Collectors.toList());
        if (group.size() < 2) {
            return "无需合并：组内仅 " + group.size() + " 条";
        }
        Lesson main = pickMergeTarget(group);
        int sumHit = 0, sumSuccess = 0, sumFail = 0;
        for (Lesson l : group) {
            sumHit += nz(l.getHitCount());
            sumSuccess += nz(l.getSuccessCount());
            sumFail += nz(l.getFailCount());
        }
        // 先删非主（避免主记录更新签名时撞唯一索引）
        int removed = 0;
        for (Lesson l : group) {
            if (!l.getId().equals(main.getId())) {
                removed += lessonMapper.deleteById(l.getId());
            }
        }
        // 主记录：canonical 码 + 新指纹（与代码现行规则一致）+ 计数汇聚
        String canonCode = normalizer.canonicalizeCode(errorCode);
        FailureNormalizer.NormalizedFailure nf = new FailureNormalizer.NormalizedFailure();
        nf.toolName = toolName;
        nf.errorCategory = errorCategory;
        nf.errorCode = canonCode;
        nf.paramsJson = main.getParamsJson();
        String newSig = normalizer.fingerprint(projectKey, nf);
        lessonMapper.updateMerged(main.getId(), sumHit, sumSuccess, sumFail, canonCode, newSig, LocalDateTime.now());
        log.info("经验归并完成: project={}, tool={}, code={} → 保留 id={}, 合并 {} 条, hit {}→{}",
                projectKey, toolName, canonCode, main.getId(), removed, nz(main.getHitCount()), sumHit);
        return "已归并 " + group.size() + " 条 → 保留 ID:" + main.getId()
                + "（删除 " + removed + " 条，命中汇聚为 " + sumHit + "）";
    }

    /**
     * P2：归并选主——有可用解法优先 → 命中高 → id 小。
     * 包级可见静态方法：mergeIntoExisting / mergeGroup 共用，且供验证类直测（⑫）。
     */
    static Lesson pickMergeTarget(List<Lesson> group) {
        return group.stream()
                .min(Comparator.comparingInt((Lesson l) -> (l.hasUsableSolution() ? -1_000_000 : 0))
                        .thenComparingInt(l -> -(l.getHitCount() == null ? 0 : l.getHitCount()))
                        .thenComparingLong(Lesson::getId))
                .orElse(group.get(0));
    }

    /** 查询单条经验详情 */
    public Optional<Lesson> getLesson(Long id) {
        return id == null ? Optional.empty() : lessonMapper.selectById(id);
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
