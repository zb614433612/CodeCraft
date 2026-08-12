package com.example.agentdeepseek.service.lesson;

import com.example.agentdeepseek.mapper.LessonMapper;
import com.example.agentdeepseek.mapper.LessonNormCacheMapper;
import com.example.agentdeepseek.mapper.LessonRuleMapper;
import com.example.agentdeepseek.model.entity.LessonNormCache;
import com.example.agentdeepseek.model.entity.LessonRule;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P0 归一化管线 · LLM 语义归一化兜底服务
 * <p>
 * 职责（设计文档 docs/LESSON_GROWTH_OPTIMIZATION.md §4.3）：
 * 1. 缓存读写（lesson_norm_cache，key=md5(toolName|errorText前512)，TTL 7 天，容量上限裁剪）
 * 2. LLM 语义归一化（规则通道落空时异步调用：产出稳定类别 + 短码 + 根因，一次调用两用）
 * 3. 回写增强（按 lessonId 调 updateNormalized，只补空不覆盖，防竞态覆盖 C2 已补全内容）
 * 4. 规则自学习沉淀（lesson_rule：报错文本片段 → 类别+短码，同 pattern ≥2 次转正）
 * <p>
 * 关键约束（与 LessonRecorder / LessonReviewService 一致）：
 * - 绝不阻塞主流程 —— 独立线程池异步执行
 * - ThreadLocal 值（projectKey）在调用线程提取，由调用方显式传入
 * - 有界队列 + 丢弃策略 —— 高并发时丢任务不丢主流程
 * - 幂等：缓存 key 天然幂等（同一错误只调一次 LLM）
 * <p>
 * 指标（供看板/日志，T6）：
 * llmCallCount / cacheHitCount / ruleHitCount / backfillCount
 */
@Slf4j
@Component
public class LessonNormalizerService {

    /**
     * 短码规范（C2 复盘与归一化兜底共用，保证跨通道短码一致、指纹一致——D4）。
     * 两处 prompt（NORMALIZE_SYSTEM_PROMPT 与 LessonReviewService 复盘 prompt）均拼接本常量。
     */
    public static final String SHORT_CODE_RULE = "errorCategory 只能是枚举值之一：COMPILE/DEPENDENCY/NETWORK/AUTH/MCP_HANDSHAKE/SQL/PARAM/ENV/OTHER；"
            + "errorCode 必须是稳定短码：格式「工具域_现象」（如 MAVEN_DEP_RESOLVE、NPM_ENOENT、CMD_NOT_FOUND）"
            + "或标准异常类名（如 NoClassDefFoundError）；禁止长句子、禁止包含路径/版本号/端口/文件名等易变信息";

    /** 归一化 LLM 系统提示（与 C2 复盘共用短码规范，保证跨通道指纹一致——D4） */
    public static final String NORMALIZE_SYSTEM_PROMPT = "你是失败归一化引擎。给定一次工具调用失败信息，输出稳定的结构化结果。\n"
            + "硬性规范：\n"
            + "1. " + SHORT_CODE_RULE + "\n"
            + "2. toolName 使用标准工具名（command/file_writer/execute_sql/...）\n"
            + "3. worthRecord：该失败是否值得沉淀（有明确报错、解法可复用→true；纯一次性误操作→false）\n"
            + "4. 只输出一个 JSON 对象，不要任何其他文字。\n"
            + "格式：{\"worthRecord\": true, \"errorCategory\":\"...\", \"errorCode\":\"...\", \"toolName\":\"...\", \"rootCause\":\"...\"}";

    private final LessonNormCacheMapper cacheMapper;
    private final LessonRuleMapper ruleMapper;
    private final LessonMapper lessonMapper;
    private final LLMClientManager llmClientManager;
    private final ObjectMapper objectMapper;
    /** H1：失败归一化器（回写时重算 error_signature，与 C2 通道指纹一致） */
    private final FailureNormalizer normalizer;

    // ============================================================
    // 配置（application.yml lesson.normalize.*）
    // ============================================================
    @Value("${lesson.normalize.enabled:true}")
    private boolean enabled;
    @Value("${lesson.normalize.llm-fallback:true}")
    private boolean llmFallback;
    @Value("${lesson.normalize.cache-ttl-days:7}")
    private long cacheTtlDays;
    @Value("${lesson.normalize.cache-max:20000}")
    private long cacheMax;
    @Value("${lesson.normalize.llm-timeout-ms:30000}")
    private long llmTimeoutMs;
    /** P2：规则表容量上限（超限时淘汰「候选+从未被提取命中」的最旧规则；替代时间淘汰） */
    @Value("${lesson.rule.max-size:10000}")
    private long ruleMaxSize;

    /** 单线程 + 有界队列（队列大小构造器注入，M3 修复：配置生效），满时静默丢弃，绝不阻塞主流程 */
    private final ExecutorService executor;

    // ============================================================
    // 指标计数（T6 看板/日志）
    // ============================================================
    private final AtomicLong llmCallCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong ruleHitCount = new AtomicLong();
    private final AtomicLong backfillCount = new AtomicLong();

    public LessonNormalizerService(LessonNormCacheMapper cacheMapper, LessonRuleMapper ruleMapper,
                                   LessonMapper lessonMapper, LLMClientManager llmClientManager,
                                   ObjectMapper objectMapper, FailureNormalizer normalizer,
                                   @org.springframework.beans.factory.annotation.Value("${lesson.normalize.queue-size:500}") int queueSize) {
        this.cacheMapper = cacheMapper;
        this.ruleMapper = ruleMapper;
        this.lessonMapper = lessonMapper;
        this.llmClientManager = llmClientManager;
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(queueSize),
                r -> {
                    Thread t = new Thread(r, "lesson-normalizer");
                    t.setDaemon(true);
                    return t;
                },
                (r, e) -> log.warn("归一化任务队列已满，丢弃一条（不阻塞主流程）"));
    }

    // ============================================================
    // 对外入口：异步 LLM 语义归一化兜底（调用线程，绝不阻塞）
    // ============================================================

    /**
     * 触发 LLM 语义归一化兜底（仅当规则通道落空时由调用方调用）：
     * 缓存命中 → 直接回写草稿（零 LLM 成本）；未命中 → 异步调 LLM → 写缓存/规则表/回写。
     *
     * @param projectKey    项目标识（调用线程提取的 ThreadLocal 值）
     * @param lessonId      已落库草稿的 id（回写目标；幂等：同一 id 只增强一次）
     * @param toolName      工具名
     * @param argumentsJson 工具参数 JSON（供 LLM 参考）
     * @param errorText     报错文本（缓存 key 来源）
     */
    public void normalizeAsync(String projectKey, Long lessonId, String toolName,
                               String argumentsJson, String errorText) {
        try {
            if (!enabled || !llmFallback || lessonId == null
                    || errorText == null || errorText.isBlank()) {
                return;
            }
            String cacheKey = buildCacheKey(toolName, errorText);

            // 1. 缓存命中（未过期）→ 直接回写，零 LLM 成本
            Optional<LessonNormCache> cached = cacheMapper.selectByKey(cacheKey);
            if (cached.isPresent() && !isExpired(cached.get())) {
                cacheHitCount.incrementAndGet();
                // M3 修复：命中即续期（刷新 updated_at 延长 TTL）——高频错误不因
                // 7 天固定过期重复调 LLM；续期失败不影响回写（缓存本身仍有效）
                try {
                    LessonNormCache touch = cached.get();
                    touch.setUpdatedAt(LocalDateTime.now());
                    cacheMapper.update(touch);
                } catch (Exception touchErr) {
                    log.debug("归一化缓存续期失败（不影响回写）: {}", touchErr.getMessage());
                }
                backfill(lessonId, cached.get());
                return;
            }

            // 2. 提交异步 LLM 归一化（调用线程只做提交与缓存检查）
            executor.submit(() -> doNormalize(projectKey, lessonId, toolName, argumentsJson, errorText, cacheKey));
        } catch (Exception e) {
            log.warn("提交归一化任务失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    // ============================================================
    // 内部实现（异步线程内执行，全部 try-catch 保护）
    // ============================================================

    private void doNormalize(String projectKey, Long lessonId, String toolName,
                             String argumentsJson, String errorText, String cacheKey) {
        try {
            LLMClient client = llmClientManager.getFirstClient();
            if (client == null) {
                log.warn("归一化跳过：无可用 LLM 客户端");
                return;
            }
            String model = llmClientManager.getDefaultModel(client.getProviderCode());

            llmCallCount.incrementAndGet();
            JsonNode result = normalizeWithLLM(client, model, toolName, argumentsJson, errorText);
            if (result == null || !result.path("worthRecord").asBoolean(false)) {
                // LLM 判定无需沉淀 → 不写缓存不写规则（下次同类错误可再判定，成本可控）
                return;
            }
            String category = normalizeCategory(result.path("errorCategory").asText());
            String code = result.path("errorCode").asText("").trim();
            // 输出校验：类别/短码非法 → 降级丢弃，不污染缓存与规则（D2）
            if (code.isEmpty() || "UNKNOWN".equalsIgnoreCase(code) || category == null) {
                log.debug("归一化输出校验失败，降级丢弃: code={}, category={}", code, category);
                return;
            }
            String rootCause = nullableText(result.get("rootCause"));

            // 3. 写缓存（upsert）
            upsertCache(cacheKey, category, code, toolName, rootCause);
            // 4. 规则自学习沉淀
            learnRule(errorText, category, code);
            // 5. 回写草稿（H1：重算 error_signature 与 C2 通道指纹一致，防同坑双条；只补空不覆盖）
            backfillWithSignature(lessonId, category, code, rootCause);
            log.info("归一化完成: lessonId={}, tool={}, category={}, code={}, cacheKey={}",
                    lessonId, toolName, category, code, cacheKey);
        } catch (Exception e) {
            log.warn("归一化执行失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    /** 调 LLM 语义归一化（只输出 JSON；与 C2 reviewWithLLM 同模式） */
    private JsonNode normalizeWithLLM(LLMClient client, String model,
                                      String toolName, String argsJson, String errorText) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", NORMALIZE_SYSTEM_PROMPT);
        messages.add(sysMsg);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "工具名：" + toolName
                + "\n参数：" + truncate(argsJson, 300)
                + "\n报错文本：" + truncate(errorText, 800));
        messages.add(userMsg);

        Map<String, Object> requestBody = client.buildRequestBody(
                messages, model, 0.2, "non-thinking", false, null);
        String rawResponse = client.blockingChat(requestBody, Duration.ofMillis(llmTimeoutMs));
        String content = client.extractContentFromBlockingResponse(rawResponse);
        if (content == null || content.isBlank()) {
            log.warn("归一化 LLM 返回空内容");
            return null;
        }
        return parseJsonStrict(content);
    }

    /** 解析 LLM 输出的 JSON（容忍 ```json 包裹 / 首尾杂文本；与 LessonReviewService 同源，P2 可抽公共工具） */
    private JsonNode parseJsonStrict(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastBacktick = text.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                text = text.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("归一化 LLM 输出非合法 JSON，放弃: {}", truncate(content, 200));
            return null;
        }
    }

    // ============================================================
    // 缓存
    // ============================================================

    /** 缓存 key：md5(toolName|errorText前512)，不含参数（类别/错误码与参数解耦） */
    private String buildCacheKey(String toolName, String errorText) {
        String raw = (toolName == null ? "" : toolName) + "|" + truncate(errorText, 512);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isExpired(LessonNormCache cache) {
        return cache.getUpdatedAt() == null
                || cache.getUpdatedAt().plusDays(cacheTtlDays).isBefore(LocalDateTime.now());
    }

    private void upsertCache(String cacheKey, String category, String code,
                             String toolName, String rootCause) {
        LocalDateTime now = LocalDateTime.now();
        Optional<LessonNormCache> exist = cacheMapper.selectByKey(cacheKey);
        if (exist.isPresent()) {
            LessonNormCache update = exist.get();
            update.setErrorCategory(category);
            update.setErrorCode(code);
            update.setToolName(toolName);
            update.setRootCause(rootCause);
            update.setUpdatedAt(now);
            cacheMapper.update(update);
            return;
        }
        // 容量裁剪：超限先清过期，再清最旧
        if (cacheMapper.count() >= cacheMax) {
            cacheMapper.deleteExpired(now.minusDays(cacheTtlDays));
            long over = cacheMapper.count() - cacheMax + 1;
            if (over > 0) {
                cacheMapper.deleteOldest((int) Math.min(over, 1000));
            }
        }
        LessonNormCache cache = new LessonNormCache();
        cache.setCacheKey(cacheKey);
        cache.setErrorCategory(category);
        cache.setErrorCode(code);
        cache.setToolName(toolName);
        cache.setRootCause(rootCause);
        cache.setCreatedAt(now);
        cache.setUpdatedAt(now);
        try {
            cacheMapper.insert(cache);
        } catch (Exception e) {
            // 并发竞态：同 key 已插入 → 更新
            LessonNormCache update = new LessonNormCache();
            update.setCacheKey(cacheKey);
            update.setErrorCategory(category);
            update.setErrorCode(code);
            update.setToolName(toolName);
            update.setRootCause(rootCause);
            update.setUpdatedAt(now);
            cacheMapper.update(update);
        }
    }

    /** 回写草稿（缓存命中路径：无需再调 LLM；H1：重算签名防同坑双条） */
    private void backfill(Long lessonId, LessonNormCache cache) {
        backfillWithSignature(lessonId, cache.getErrorCategory(), cache.getErrorCode(), cache.getRootCause());
    }

    /**
     * H1 修复：回写草稿时重算 error_signature（与新 error_code/category 一致）。
     * 原实现只改 error_code 不改 signature → 内容与指纹不一致，C2 复盘用新 error_code
     * 算指纹时命中不了旧草稿，同一坑出现两条。修复后：
     * - 重算签名（与 C2 复盘等通道同路径）→ 后续通道可命中合并
     * - 同签名已存在（同坑已有完整记录）→ 本条草稿冗余，删除
     * - 并发唯一键冲突 → 删除冗余草稿（保底）
     */
    private void backfillWithSignature(Long lessonId, String category, String code, String rootCause) {
        try {
            com.example.agentdeepseek.model.entity.Lesson existing =
                    lessonMapper.selectById(lessonId).orElse(null);
            if (existing == null || !"UNKNOWN".equals(existing.getErrorCode())) {
                return; // 草稿已不存在或已被其他通道补全（C2 等）
            }
            // 重算签名：用回写后的 category/code（与 C2 复盘 fingerprint 同算法）
            FailureNormalizer.NormalizedFailure nf = new FailureNormalizer.NormalizedFailure();
            nf.toolName = existing.getToolName();
            nf.errorCategory = category;
            nf.errorCode = code;
            nf.paramsJson = existing.getParamsJson() == null ? "[]" : existing.getParamsJson();
            String signature = normalizer.fingerprint(existing.getProjectKey(), nf);

            // 同签名已有「其他」记录（同坑已存在完整经验）→ 本条草稿冗余，删除
            // M5 修复：排除自身 id——并发下另一线程已合法回写本条草稿（signature=S）时，
            // selectBySignature(S) 会命中自身，若直接删除会误删有效回写记录
            java.util.Optional<com.example.agentdeepseek.model.entity.Lesson> sameSig =
                    lessonMapper.selectBySignature(signature);
            if (sameSig.isPresent() && !sameSig.get().getId().equals(lessonId)) {
                lessonMapper.deleteById(lessonId);
                log.info("归一化回写: 同坑经验已存在（签名一致，非自身），删除冗余草稿 id={}", lessonId);
                return;
            }
            int rows = lessonMapper.updateNormalized(lessonId, category, code, rootCause, signature,
                    LocalDateTime.now());
            if (rows > 0) {
                backfillCount.incrementAndGet();
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发竞态：另一线程已插入同签名 → 本条草稿冗余，删除
            try {
                lessonMapper.deleteById(lessonId);
                log.info("归一化回写: 并发同坑冲突，删除冗余草稿 id={}", lessonId);
            } catch (Exception ignore) {
                // 删除失败不影响主流程
            }
        } catch (Exception e) {
            log.debug("归一化回写失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    // ============================================================
    // 规则自学习（P0 简化版：只沉淀不淘汰）
    // ============================================================

    private void learnRule(String errorText, String category, String code) {
        try {
            // P2 规则闭环：容量检查（替代时间淘汰）——超上限时淘汰「候选+从未被提取命中」的最旧规则。
            // 用户长期不碰项目不会导致规则被误杀（无时间维度）；被命中过的规则有复用价值，永不淘汰。
            try {
                long total = ruleMapper.count();
                if (total > ruleMaxSize) {
                    int removed = ruleMapper.deleteUnusedCandidates((int) (total - ruleMaxSize));
                    if (removed > 0) {
                        log.info("规则容量淘汰未使用候选: {} 条（总数 {} 超上限 {}）", removed, total, ruleMaxSize);
                    }
                }
            } catch (Exception cleanupErr) {
                log.debug("规则容量淘汰失败（不影响主流程）: {}", cleanupErr.getMessage());
            }

            String pattern = normalizePattern(errorText);
            if (pattern.length() < 8) {
                return; // 太短无区分度
            }
            LocalDateTime now = LocalDateTime.now();
            Optional<LessonRule> exist = ruleMapper.selectByPattern(pattern);
            if (exist.isPresent()) {
                // 同 pattern 再次被沉淀 → 计数，≥2 次转正；L7：同步更新非空 category/code
                ruleMapper.incrementHit(exist.get().getId(), category, code, now);
            } else {
                LessonRule rule = new LessonRule();
                rule.setTextPattern(pattern);
                rule.setErrorCategory(category);
                rule.setErrorCode(code);
                rule.setHitCount(1);
                rule.setSource(LessonRule.SOURCE_LLM);
                rule.setStatus(LessonRule.STATUS_CANDIDATE);
                rule.setCreatedAt(now);
                rule.setUpdatedAt(now);
                try {
                    ruleMapper.insert(rule);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // L3 修复：并发竞态（同 pattern 已插入，uk_rule_pattern 唯一约束兜底）
                    // → 按已有记录计数转正（与 selectByPattern 预查同路径）；L7：同步更新非空值
                    ruleMapper.selectByPattern(pattern).ifPresent(existing ->
                            ruleMapper.incrementHit(existing.getId(), category, code, now));
                    log.debug("规则并发插入冲突，已降级计数转正: pattern={}", pattern);
                }
            }
        } catch (Exception e) {
            log.debug("规则沉淀失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    /**
     * 规则文本片段：转小写、压缩空白、清洗易变信息（路径/版本号/端口/时间戳）、截断 80。
     * M2 修复：报错开头常含易变 token（如 C:\work\...、2.3.232、:8080），直接沉淀会导致
     * 下次同类错误路径一变 contains 匹配永远落空，规则成为占容量的死数据；
     * 清洗为占位符（&lt;path&gt;/&lt;ver&gt;/&lt;port&gt;/&lt;ts&gt;）后特征保留、易变维度消除。
     */
    private String normalizePattern(String errorText) {
        String s = errorText.trim().toLowerCase().replaceAll("\\s+", " ");
        // 清洗易变信息（顺序敏感：路径→版本→时间戳→端口——时间戳必须在端口前，
        // 否则 "13:00:00" 的 ":00" 会被端口正则先替换成 ":<port>:00"，时间戳正则失配）
        s = s.replaceAll("[a-z]:\\\\[^\\s]*", "<path>")                       // Windows 路径 C:\xxx
             .replaceAll("[a-z]:/[^\\s]*", "<path>")                          // Windows 路径 C:/xxx（正斜杠写法）
             .replaceAll("(?<![a-z0-9])\\d+(\\.\\d+)+", "<ver>")               // 版本号 2.3.232
             .replaceAll("\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}", "<ts>") // 时间戳 2026-08-12T13:00:00（文本已小写化，T/t 都要兼容）
             .replaceAll(":\\d{2,5}\\b", ":<port>");                          // 端口 :8080
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    // ============================================================
    // 对外：C2 复盘结果反哺缓存（链路 B，其他通道受益，避免重复 LLM 调用）
    // ============================================================

    /**
     * C2 复盘提炼出有效 errorCategory/errorCode 时，反哺归一化缓存。
     * 后续同一错误走缓存直接回写（零 LLM 成本）；非法参数静默忽略。
     *
     * @param toolName  工具名
     * @param errorText 报错文本（缓存 key 来源）
     * @param category  校验通过的枚举类别
     * @param code      稳定短码
     * @param rootCause 根因（可空）
     */
    public void cacheNormalization(String toolName, String errorText, String category, String code, String rootCause) {
        try {
            if (!enabled || errorText == null || errorText.isBlank()) {
                return;
            }
            String validCategory = normalizeCategory(category);
            if (validCategory == null || code == null || code.isBlank() || "UNKNOWN".equalsIgnoreCase(code)) {
                return;
            }
            upsertCache(buildCacheKey(toolName, errorText), validCategory, code, toolName, rootCause);
        } catch (Exception e) {
            log.debug("缓存反哺失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    // ============================================================
    // 指标访问（T6 看板）
    // ============================================================

    public long getLlmCallCount() { return llmCallCount.get(); }
    public long getCacheHitCount() { return cacheHitCount.get(); }
    public long getRuleHitCount() { return ruleHitCount.get(); }
    public long getBackfillCount() { return backfillCount.get(); }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 类别校验：不在枚举内返回 null（调用方降级丢弃） */
    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String c = category.trim().toUpperCase();
        return switch (c) {
            case "COMPILE", "DEPENDENCY", "NETWORK", "AUTH", "MCP_HANDSHAKE",
                 "SQL", "PARAM", "ENV", "OTHER" -> c;
            default -> null;
        };
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
