package com.example.agentdeepseek.service.lesson;

import com.example.agentdeepseek.model.entity.Lesson;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 对话级复盘服务（C2）
 * <p>
 * 背景：自动捕获（LessonRecorder）只在「工具抛异常」时触发；而工具把错误包装成友好提示
 * （如【命令未找到】/ 退出码：1 / BUILD FAILURE）返回给 LLM 时不抛异常 → 完全漏网。
 * <p>
 * 方案：工具循环自然结束（LLM 输出最终回复）后，扫描本轮所有工具结果，
 * 命中「失败信号」（含友好提示）→ 异步调一次 LLM 复盘该轮，
 * 提炼「根因/解法/适用条件/关键词」→ 写入经验库。
 * 写入走 recordLessonWithResult：与自动捕获草稿同指纹时自动合并补全（F2），新坑直接沉淀完整经验。
 * <p>
 * 关键约束（与 LessonRecorder 一致）：
 * 1. 绝不阻塞主流程 —— 独立线程池异步执行
 * 2. ThreadLocal 值（projectKey）必须在调用线程提取，由调用方显式传入
 * 3. 有界队列 + 丢弃策略 —— 高并发时丢任务不丢主流程
 * 4. 幂等：conversationId + turnId 去重，同一轮只复盘一次
 */
@Slf4j
@Component
public class LessonReviewService {

    private final LessonService lessonService;
    private final FailureNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final LLMClientManager llmClientManager;
    /** P0：LLM 语义归一化兜底（C2 提炼的类别/短码反哺缓存，其他通道受益） */
    private final LessonNormalizerService normalizerService;

    /** 单线程 + 有界队列（200），队列满时静默丢弃，绝不阻塞主流程 */
    private final ExecutorService executor;

    /** 幂等表：key=conversationId:turnId → 时间戳（毫秒） */
    private final ConcurrentHashMap<String, Long> reviewDedup = new ConcurrentHashMap<>();
    /** 幂等表容量上限，超过时清理过期条目（防内存膨胀） */
    private static final int REVIEW_DEDUP_MAX = 2000;
    /** 幂等条目有效期（2 小时，超时后可再次复盘） */
    private static final long REVIEW_DEDUP_TTL_MS = 2 * 60 * 60 * 1000L;

    /** 复盘 LLM 调用超时（小任务，60s 足够） */
    private static final Duration REVIEW_LLM_TIMEOUT = Duration.ofSeconds(60);

    // ============================================================
    // 失败信号（高置信，防误报）
    // ============================================================

    /** 框架级错误前缀（与 ToolCallResult.isError() 判定一致） */
    private static final List<String> FRAMEWORK_ERROR_PREFIXES = List.of(
            "工具调用失败:", "工具参数解析失败:", "错误：未知工具", "工具执行异常:");

    /** 工具友好提示失败信号（工具不抛异常但确实是失败——CommandTool/FileTool 等包装格式） */
    private static final List<Pattern> FRIENDLY_FAILURE_PATTERNS = List.of(
            Pattern.compile("【命令未找到】"),
            Pattern.compile("【执行异常】"),
            Pattern.compile("【启动失败】"),
            Pattern.compile("【参数错误】"),
            Pattern.compile("【参数缺失】"),
            Pattern.compile("【缺少参数】"),
            Pattern.compile("【权限不足】"),
            Pattern.compile("【文件不存在】"),
            Pattern.compile("退出码[：:]\\s*[1-9]\\d*"),
            Pattern.compile("BUILD FAILURE"),
            Pattern.compile("NoClassDefFoundError|ClassNotFoundException"),
            Pattern.compile("ECONNREFUSED|Connection refused|connect timed out"),
            Pattern.compile("command not found|不是内部或外部命令"),
            Pattern.compile("Cannot run program"));

    /**
     * P0 启发式失败信号词（正则库未命中时的低置信兜底触发）。
     * 是否值得沉淀由 LLM worthRecord 判定过滤，避免误报污染经验库。
     */
    private static final List<Pattern> HEURISTIC_FAILURE_PATTERNS = List.of(
            Pattern.compile("失败|错误|异常|无法|不能|拒绝|超时|未找到|不存在|缺失|缺少"),
            Pattern.compile("error|fail|exception|timeout|denied|refused|not found", Pattern.CASE_INSENSITIVE));

    /** 成功语境词：启发式判定时排除（如「修复成功」「无错误」，防误报） */
    private static final List<Pattern> SUCCESS_CONTEXT_PATTERNS = List.of(
            Pattern.compile("成功|完成|无错误|无异常|修复成功|no error|no exception|success", Pattern.CASE_INSENSITIVE));

    // ============================================================
    // 构造与线程池
    // ============================================================

    public LessonReviewService(LessonService lessonService, FailureNormalizer normalizer,
                               ObjectMapper objectMapper, LLMClientManager llmClientManager,
                               LessonNormalizerService normalizerService) {
        this.lessonService = lessonService;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.llmClientManager = llmClientManager;
        this.normalizerService = normalizerService;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "lesson-review");
                    t.setDaemon(true);
                    return t;
                },
                (r, e) -> log.warn("对话复盘任务队列已满，丢弃一条（不阻塞主流程）"));
    }

    // ============================================================
    // 对外入口：异步复盘一轮对话
    // ============================================================

    /**
     * 异步复盘一轮对话：扫描本轮工具结果，命中失败信号 → LLM 提炼经验入库。
     * 调用线程执行（上下文值在此提取），实际复盘在独立线程池中进行。
     *
     * @param conversationId 会话 ID（幂等键组成部分）
     * @param turnId         轮次 ID（幂等键组成部分，可为 null）
     * @param projectRoot    项目根目录（ThreadLocal 已清空时显式传入）
     * @param messages       本轮完整消息列表（含 tool_calls / tool 结果 / 最终回复）
     * @param client         LLM 客户端（可为 null，null 时取第一个可用客户端）
     * @param model          模型名（可为 null，null 时取默认模型）
     */
    public void reviewTurnAsync(Long conversationId, String turnId, String projectRoot,
                                List<Map<String, Object>> messages, LLMClient client, String model) {
        try {
            if (messages == null || messages.isEmpty()) {
                return;
            }
            // ★ 短路保护：本轮已有 LLM 主动汇报（lesson record/complete 成功）→ 跳过 C2 复盘。
            //   避免重复 LLM 调用、同坑双记录、hit_count 虚增。
            //   仅当 lesson 调用成功（结果无失败信号）才短路；若 LLM 主动汇报失败（如参数错误），C2 照常兜底。
            if (hasSuccessfulLessonReport(messages)) {
                log.debug("本轮已有 LLM 主动汇报（lesson record/complete 成功），跳过 C2 复盘: conversationId={}", conversationId);
                return;
            }
            // ★ 必须在调用线程提取：projectKey 依赖 ThreadLocal
            String projectKey = normalizer.extractProjectKey(projectRoot);
            // 提取失败工具调用（含失败信号检测）
            List<FailedToolCall> failures = extractFailedToolCalls(messages);
            if (failures.isEmpty()) {
                return; // 本轮无失败信号，零成本跳过
            }
            // 幂等检查：同一轮只复盘一次
            if (!markReviewed(conversationId, turnId)) {
                return;
            }
            // 提取 LLM 最终回复（最后一条 assistant 非工具消息）
            String finalAnswer = extractFinalAnswer(messages);
            // 截断保护：最多复盘前 3 个失败工具
            List<FailedToolCall> capped = failures.size() > 3 ? failures.subList(0, 3) : failures;

            executor.submit(() -> doReview(conversationId, turnId, projectKey, capped, finalAnswer, client, model));
        } catch (Exception e) {
            log.warn("提交对话复盘任务失败（不影响主流程）: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    // ============================================================
    // 内部实现
    // ============================================================

    /** 一次失败工具调用的现场信息 */
    private static class FailedToolCall {
        final String toolName;
        final String argumentsJson;
        final String content;

        FailedToolCall(String toolName, String argumentsJson, String content) {
            this.toolName = toolName;
            this.argumentsJson = argumentsJson;
            this.content = content;
        }
    }

    /** 失败信号检测：框架错误前缀 OR 友好提示失败模式 OR P0 启发式（低置信兜底） */
    private boolean hasFailureSignal(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String text = content.trim();
        for (String prefix : FRAMEWORK_ERROR_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        for (Pattern p : FRIENDLY_FAILURE_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        // P0 启发式兜底：正则未命中时，命中失败词且无成功语境 → 低置信触发（LLM worthRecord 过滤）
        return hasHeuristicFailureSignal(text);
    }

    /**
     * P0 启发式失败信号：命中失败词 且 不含成功语境。
     * 仅用于「低置信补触发」——是否值得沉淀交给 LLM 判定（worthRecord 过滤），
     * 避免新格式报错因正则覆盖不全而完全漏网。
     */
    private boolean hasHeuristicFailureSignal(String text) {
        for (Pattern success : SUCCESS_CONTEXT_PATTERNS) {
            if (success.matcher(text).find()) {
                return false;
            }
        }
        for (Pattern p : HEURISTIC_FAILURE_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测本轮是否已有「LLM 主动汇报」成功：
     * 存在 lesson 工具的 record/complete 调用，且其工具结果未命中失败信号（汇报成功）。
     * 命中 → C2 复盘应短路（LLM 已在主动维护经验库，避免重复调用/双记录/计数虚增）。
     * 若 lesson 调用本身失败（如参数错误，结果命中失败信号）→ 返回 false，C2 照常兜底。
     */
    private boolean hasSuccessfulLessonReport(List<Map<String, Object>> messages) {
        // 1. 构建 tool_call_id → arguments 映射（与 extractFailedToolCalls 同源逻辑）
        Map<String, String> callIdToArgs = new HashMap<>();
        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) {
                continue;
            }
            Object tcObj = msg.get("tool_calls");
            if (!(tcObj instanceof List<?> tcList)) {
                continue;
            }
            for (Object item : tcList) {
                if (item instanceof Map<?, ?> tc) {
                    Object id = tc.get("id");
                    Object fn = tc.get("function");
                    if (fn instanceof Map<?, ?> fnMap) {
                        Object args = fnMap.get("arguments");
                        if (id != null && args != null) {
                            callIdToArgs.put(String.valueOf(id), String.valueOf(args));
                        }
                    }
                }
            }
        }

        // 2. 遍历 tool 结果：lesson 工具 + 无失败信号 → 解析 action 是否为 record/complete
        for (Map<String, Object> msg : messages) {
            if (!"tool".equals(msg.get("role"))) {
                continue;
            }
            Object toolName = msg.get("tool_name");
            if (!"lesson".equals(String.valueOf(toolName))) {
                continue;
            }
            Object content = msg.get("content");
            // 汇报结果本身失败（如参数错误）→ 不短路，C2 兜底
            if (content == null || hasFailureSignal(String.valueOf(content))) {
                continue;
            }
            String args = callIdToArgs.get(String.valueOf(msg.get("tool_call_id")));
            if (args == null || args.isBlank()) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(args);
                String action = node.path("action").asText("");
                if ("record".equals(action) || "complete".equals(action)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 参数解析失败不阻塞判断（此时 extractFailedToolCalls 的失败信号逻辑兜底）
            }
        }
        return false;
    }

    /**
     * 扫描消息列表，提取所有命中失败信号的工具结果。
     * 同时建立 tool_call_id → arguments 映射，供归一化指纹使用。
     */
    private List<FailedToolCall> extractFailedToolCalls(List<Map<String, Object>> messages) {
        Map<String, String> callIdToArgs = new HashMap<>();
        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(msg.get("role"))) {
                continue;
            }
            Object tcObj = msg.get("tool_calls");
            if (!(tcObj instanceof List<?> tcList)) {
                continue;
            }
            for (Object item : tcList) {
                if (item instanceof Map<?, ?> tc) {
                    Object id = tc.get("id");
                    Object fn = tc.get("function");
                    if (fn instanceof Map<?, ?> fnMap) {
                        Object args = fnMap.get("arguments");
                        if (id != null && args != null) {
                            callIdToArgs.put(String.valueOf(id), String.valueOf(args));
                        }
                    }
                }
            }
        }

        List<FailedToolCall> failures = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if (!"tool".equals(msg.get("role"))) {
                continue;
            }
            Object content = msg.get("content");
            Object toolName = msg.get("tool_name");
            if (content == null) {
                continue;
            }
            String text = String.valueOf(content);
            if (!hasFailureSignal(text)) {
                continue;
            }
            String args = callIdToArgs.get(String.valueOf(msg.get("tool_call_id")));
            failures.add(new FailedToolCall(
                    toolName == null ? "unknown" : String.valueOf(toolName),
                    args == null ? null : args,
                    truncate(text, 1200)));
        }
        return failures;
    }

    /** 提取 LLM 最终回复：最后一条带 content 的 assistant 消息（无 tool_calls） */
    private String extractFinalAnswer(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role")) && msg.get("tool_calls") == null) {
                Object content = msg.get("content");
                if (content != null && !String.valueOf(content).isBlank()) {
                    return truncate(String.valueOf(content), 1500);
                }
            }
        }
        return null;
    }

    /** 幂等：conversationId:turnId 去重（超容量清理过期条目） */
    private boolean markReviewed(Long conversationId, String turnId) {
        if (reviewDedup.size() > REVIEW_DEDUP_MAX) {
            long now = System.currentTimeMillis();
            reviewDedup.entrySet().removeIf(e -> now - e.getValue() > REVIEW_DEDUP_TTL_MS);
            if (reviewDedup.size() > REVIEW_DEDUP_MAX) {
                reviewDedup.clear(); // 仍超限则整体清理（防内存膨胀优先）
            }
        }
        // M4 修复：conversationId 为 null 时用唯一后缀——否则不同会话共享 "null:xxx" 幂等键，
        // 先复盘的那轮会误吞后续所有会话的复盘机会（漏沉淀）。null 场景罕见，宁重复不复用。
        String key = (conversationId == null ? "conv-" + System.nanoTime() : conversationId)
                + ":" + (turnId == null ? "no-turn" : turnId);
        Long prev = reviewDedup.putIfAbsent(key, System.currentTimeMillis());
        return prev == null;
    }

    /** 复盘主逻辑（异步线程内执行，全部 try-catch 保护） */
    private void doReview(Long conversationId, String turnId, String projectKey,
                          List<FailedToolCall> failures, String finalAnswer,
                          LLMClient client, String model) {
        try {
            LLMClient activeClient = client != null ? client : llmClientManager.getFirstClient();
            if (activeClient == null) {
                log.warn("对话复盘跳过：无可用 LLM 客户端，conversationId={}", conversationId);
                return;
            }
            String activeModel = model;
            if (activeModel == null || activeModel.isBlank()) {
                activeModel = llmClientManager.getDefaultModel(activeClient.getProviderCode());
            }

            // 1. LLM 提炼（现象由失败工具结果携带，LLM 只负责根因/解法/条件/关键词）
            JsonNode review = reviewWithLLM(activeClient, activeModel, failures, finalAnswer);
            if (review == null || !review.path("record").asBoolean(false)) {
                log.debug("对话复盘判定无需沉淀: conversationId={}, turnId={}", conversationId, turnId);
                return;
            }
            String rootCause = nullableText(review.get("rootCause"));
            String solution = nullableText(review.get("solution"));
            if (solution == null) {
                log.debug("对话复盘无解法，不写入（避免产生无价值草稿）: conversationId={}", conversationId);
                return;
            }
            String applicableCond = nullableText(review.get("applicableCond"));
            String keywords = nullableText(review.get("keywords"));

            // P0 链路 B：LLM 提炼的归一化字段（短码规范保证跨通道指纹一致——D4）。
            // 类别/错误码用 LLM 有效值覆盖规则通道结果（归一化核心价值）；
            // 工具名始终用实际失败工具名（LLM 输出 toolName 可能幻觉，不采纳）。
            String effCategory = validateCategory(review.get("errorCategory"));
            String effCode = nullableText(review.get("errorCode"));

            // P0：逐个失败归一化 + 入库（不再只取第一个失败工具）
            // 同坑由 error_signature 指纹去重（同工具+同类别+同错误码+同参数 → 第二次仅 hit+1），
            // 不同坑分别沉淀；LLM 提炼的根因/解法为整轮级别，各条共享（合理：同一轮的经验）
            for (FailedToolCall fc : failures) {
                FailureNormalizer.NormalizedFailure nf = normalizer.normalize(
                        fc.toolName, fc.argumentsJson, fc.content);
                // LLM 有效字段覆盖规则通道结果（无效则回退规则通道值）
                String category = effCategory != null ? effCategory : nf.errorCategory;
                String code = (effCode != null && !effCode.isBlank()) ? effCode : nf.errorCode;
                String symptom = "工具[" + nf.toolName + "] 执行失败: " + nf.symptom;
                Lesson lesson = lessonService.recordLessonWithResult(
                        projectKey, nf.toolName, category, code,
                        symptom, rootCause, solution, nf.paramsJson, applicableCond, keywords,
                        Lesson.SOURCE_REVIEW, null, null).lesson;
                // P0 链路 B：复盘结果反哺归一化缓存（同一错误后续零 LLM 成本）
                normalizerService.cacheNormalization(nf.toolName, fc.content, category, code, rootCause);
                log.info("对话复盘沉淀经验: id={}, conversationId={}, tool={}, code={}, source={}",
                        lesson.getId(), conversationId, nf.toolName, lesson.getErrorCode(), lesson.getSource());
            }
        } catch (Exception e) {
            log.warn("对话复盘执行失败（不影响主流程）: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    /** 调 LLM 提炼经验 JSON（只输出 JSON） */
    private JsonNode reviewWithLLM(LLMClient client, String model,
                                   List<FailedToolCall> failures, String finalAnswer) {
        // 组装工具调用记录文本
        StringBuilder processSb = new StringBuilder();
        for (FailedToolCall f : failures) {
            processSb.append("工具[").append(f.toolName).append("]");
            if (f.argumentsJson != null && !f.argumentsJson.isBlank() && !"null".equals(f.argumentsJson)) {
                processSb.append(" 参数").append(truncate(f.argumentsJson, 300));
            }
            processSb.append("：\n").append(truncate(f.content, 800)).append("\n\n");
        }

        List<Map<String, Object>> reviewMessages = new ArrayList<>();
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        // P0：prompt 扩展——输出 errorCategory/errorCode（共用短码规范，与归一化兜底跨通道一致 D4）
        sysMsg.put("content", "你是踩坑经验提炼助手。下面是一轮 AI 助手执行任务时的工具调用记录（可能包含失败）。请判断：\n"
                + "1. 是否出现了值得沉淀的踩坑经验（工具报错、命令失败、重试后成功等）；\n"
                + "2. 若值得沉淀，提炼：rootCause 根因（为什么失败）、solution 解法（如何解决，写成可复用的操作步骤或修改要点）、"
                + "applicableCond 适用条件（什么场景下该解法适用）、keywords 检索标签（3~5 个词，空格分隔）、"
                + "errorCategory + errorCode（按短码规范：" + LessonNormalizerService.SHORT_CODE_RULE + "）。\n"
                + "注意：全程正常成功（无任何失败）不要沉淀；AI 已通过 lesson 工具主动记录过的也不要重复沉淀。\n"
                + "只输出一个 JSON 对象，不要输出任何其他文字。格式："
                + "{\"record\": true或false, \"rootCause\": \"根因或null\", \"solution\": \"解法或null\", "
                + "\"applicableCond\": \"适用条件或null\", \"keywords\": \"标签或null\", "
                + "\"errorCategory\": \"类别或null\", \"errorCode\": \"短码或null\"}");
        reviewMessages.add(sysMsg);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        StringBuilder userContent = new StringBuilder();
        userContent.append("【本轮工具调用记录】\n").append(processSb);
        if (finalAnswer != null) {
            userContent.append("【AI 最终回复】\n").append(finalAnswer);
        }
        userMsg.put("content", userContent.toString());
        reviewMessages.add(userMsg);

        Map<String, Object> requestBody = client.buildRequestBody(
                reviewMessages, model, 0.2, "non-thinking", false, null);
        String rawResponse = client.blockingChat(requestBody, REVIEW_LLM_TIMEOUT);
        String content = client.extractContentFromBlockingResponse(rawResponse);
        if (content == null || content.isBlank()) {
            log.warn("对话复盘 LLM 返回空内容");
            return null;
        }
        return parseJsonStrict(content);
    }

    /** 解析 LLM 输出的 JSON（容忍 ```json 包裹 / 首尾杂文本） */
    private JsonNode parseJsonStrict(String content) {
        String text = content.trim();
        // 去掉 ```json ... ``` 代码块包裹
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastBacktick = text.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                text = text.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        // 提取第一个 { 到最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("对话复盘 LLM 输出非合法 JSON，放弃: {}", truncate(content, 200));
            return null;
        }
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    /**
     * P0：类别枚举校验（与 LessonNormalizerService.normalizeCategory 同口径）。
     * LLM 输出不在枚举内 → null（调用方回退规则通道值，防止污染指纹）。
     */
    private static String validateCategory(JsonNode node) {
        String text = nullableText(node);
        if (text == null) {
            return null;
        }
        String c = text.toUpperCase();
        return switch (c) {
            case "COMPILE", "DEPENDENCY", "NETWORK", "AUTH", "MCP_HANDSHAKE",
                 "SQL", "PARAM", "ENV", "OTHER" -> c;
            default -> null;
        };
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
