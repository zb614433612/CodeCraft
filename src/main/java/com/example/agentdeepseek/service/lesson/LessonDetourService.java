package com.example.agentdeepseek.service.lesson;

import com.example.agentdeepseek.model.entity.Lesson;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * P1 弯路通道（C3）· 弯路经验提炼服务
 * <p>
 * 设计文档 docs/LESSON_GROWTH_OPTIMIZATION.md §5：
 * 轮末触发（与 C2 并列）→ DetourSignalDetector 扫描信号（A 用户否定 / B LLM 自述换方案 / C 工具序列辅助）
 * → 幂等检查 → 异步 LLM 判定+提炼（一次调用，worthRecord 过滤）→ 入库 DETOUR 经验
 * （goal 维度指纹去重，与 FAILURE 分叉）。
 * <p>
 * 关键约束（与 LessonReviewService 一致）：
 * - 绝不阻塞主流程 —— 独立线程池异步执行
 * - ThreadLocal 值（projectKey）在调用线程提取，显式传入
 * - 有界队列 + 丢弃策略 —— 高并发时丢任务不丢主流程
 * - 幂等：conversationId:turnId 去重，同一轮只提炼一次
 */
@Slf4j
@Component
public class LessonDetourService {

    /** LLM 判定+提炼系统提示（设计 §5.3.2） */
    public static final String DETOUR_SYSTEM_PROMPT = "你是方案复盘助手。给定一轮 AI 助手执行任务的对话记录，判断是否存在\"走了弯路\"："
            + "尝试的方案不可行/无法满足需求，更换方案后才成功。\n"
            + "判断规则：\n"
            + "1. 只有真正更换方案且最终成功才值得记录；一次性正常完成、或正常重构不记录\n"
            + "2. 用户说\"不要删除文件\"这类权限指令不是方案否定，不记录\n"
            + "3. 输出 JSON，只输出 JSON：\n"
            + "{\"worthRecord\": true|false, \"goal\": \"任务目标一句话（检索主维度，不含具体文件名/路径）\", "
            + "\"abandonedApproach\": \"被放弃的方案（可识别描述）\", \"abandonReason\": \"放弃原因（为什么走不通，这是关键知识）\", "
            + "\"adoptedApproach\": \"最终方案\", \"solution\": \"可复用建议：下次遇到同类目标直接怎么做\", "
            + "\"keywords\": \"3~5 个检索标签，空格分隔\"}\n"
            + "4. worthRecord=false 时，其他字段全部为 null";

    private final LessonService lessonService;
    private final DetourSignalDetector signalDetector;
    private final ObjectMapper objectMapper;
    private final LLMClientManager llmClientManager;

    // ============================================================
    // 配置（application.yml lesson.detour.*）
    // ============================================================
    @Value("${lesson.detour.enabled:true}")
    private boolean enabled;
    @Value("${lesson.detour.signal-a:true}")
    private boolean signalAEnabled;
    @Value("${lesson.detour.signal-b:true}")
    private boolean signalBEnabled;
    @Value("${lesson.detour.signal-c:true}")
    private boolean signalCEnabled;
    @Value("${lesson.detour.llm-timeout-ms:60000}")
    private long llmTimeoutMs;

    /** 单线程 + 有界队列（队列大小构造器注入，M3 修复：配置生效），满时静默丢弃，绝不阻塞主流程 */
    private final ExecutorService executor;

    /** 幂等表：key=conversationId:turnId → 时间戳（毫秒） */
    private final ConcurrentHashMap<String, Long> detourDedup = new ConcurrentHashMap<>();
    private static final int DEDUP_MAX = 2000;
    private static final long DEDUP_TTL_MS = 2 * 60 * 60 * 1000L;

    public LessonDetourService(LessonService lessonService, DetourSignalDetector signalDetector,
                               ObjectMapper objectMapper, LLMClientManager llmClientManager,
                               @org.springframework.beans.factory.annotation.Value("${lesson.detour.queue-size:200}") int queueSize) {
        this.lessonService = lessonService;
        this.signalDetector = signalDetector;
        this.objectMapper = objectMapper;
        this.llmClientManager = llmClientManager;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(queueSize),
                r -> {
                    Thread t = new Thread(r, "lesson-detour");
                    t.setDaemon(true);
                    return t;
                },
                (r, e) -> log.warn("弯路提炼任务队列已满，丢弃一条（不阻塞主流程）"));
    }

    // ============================================================
    // 对外入口：异步复盘弯路信号（调用线程，绝不阻塞）
    // ============================================================

    /**
     * 轮末触发弯路检测：扫描信号 → 触发判定 → 幂等 → 异步 LLM 判定+提炼入库。
     *
     * @param conversationId 会话 ID（幂等键组成部分）
     * @param turnId         轮次 ID（幂等键组成部分，可为 null）
     * @param projectKey     项目标识（调用线程提取的 ThreadLocal 值）
     * @param messages       本轮完整消息列表（user/assistant/tool）
     * @param client         LLM 客户端（可为 null）
     * @param model          模型名（可为 null）
     */
    public void detourReviewAsync(Long conversationId, String turnId, String projectKey,
                                  List<Map<String, Object>> messages, LLMClient client, String model) {
        try {
            if (!enabled || messages == null || messages.isEmpty()) {
                return;
            }
            // 1. 信号扫描（纯字符串匹配，零 LLM 成本）
            DetourSignalDetector.DetourSignal signal = signalDetector.scan(messages);
            // 按开关过滤信号
            if (!signalAEnabled) {
                signal.setSignalA(false);
            }
            if (!signalBEnabled) {
                signal.setSignalBStrong(false);
                signal.setSignalBMedium(false);
            }
            if (!signalCEnabled) {
                signal.setSignalC(false);
            }
            // 2. 触发判定（设计矩阵；无信号零成本跳过）
            if (!signal.shouldTrigger()) {
                return;
            }
            // 3. 幂等检查：同一轮只提炼一次
            if (!markReviewed(conversationId, turnId)) {
                return;
            }
            // 4. 提取 LLM 最终回复（供 LLM 判定「最终成功」）
            String finalAnswer = extractFinalAnswer(messages);
            // 5. 异步提交（上下文值已在调用线程提取）
            executor.submit(() -> doDetour(projectKey, signal, finalAnswer, client, model));
        } catch (Exception e) {
            log.warn("提交弯路检测任务失败（不影响主流程）: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    // ============================================================
    // 对外：【方案取舍】块入库（LLM 主动汇报通道，source=llm；短路 C3 提炼）
    // ============================================================

    /**
     * 解析出的【方案取舍】块直接入库 DETOUR 经验（source=llm，goal 维度指纹去重）。
     * 调用方（DeepSeekServiceImpl）解析成功 → 调本方法并短路 C3 信号提炼，避免重复 LLM 调用。
     * M7 修复：入库改为 detour 线程池异步执行——调用方在 reactor 调度线程（doOnComplete）同步
     * 执行 DB 写与「全程异步，绝不阻塞主流程」承诺不符；提交任务立即返回。
     *
     * @param projectKey 项目标识
     * @param block      取舍块（goal/abandonedApproach/abandonReason/adoptedApproach 已校验非空）
     */
    public void recordDetourBlock(String projectKey, DetourBlockParser.DetourBlock block) {
        try {
            executor.submit(() -> doRecordDetourBlock(projectKey, block));
        } catch (Exception e) {
            log.warn("提交取舍块入库任务失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    /** 异步线程内执行取舍块入库（M7：从 reactor 线程挪到 detour 线程池） */
    private void doRecordDetourBlock(String projectKey, DetourBlockParser.DetourBlock block) {
        try {
            String symptom = "方案绕路: " + block.getAbandonedApproach() + " 不可行（"
                    + (block.getAbandonReason() == null ? "" : block.getAbandonReason()) + "），改用 "
                    + block.getAdoptedApproach();
            String applicableCond = "目标为: " + block.getGoal() + " 时";
            lessonService.recordLessonWithResult(
                    projectKey, "detour", "DETOUR", "DETOUR",
                    symptom, block.getAbandonReason(),
                    block.getSolution() == null ? Lesson.PLACEHOLDER_SOLUTION : block.getSolution(),
                    "[]", applicableCond, null,
                    Lesson.SOURCE_LLM, Lesson.TYPE_DETOUR, block.getGoal());
        } catch (Exception e) {
            log.warn("方案取舍块入库失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    // ============================================================
    // 内部实现（异步线程内执行，全部 try-catch 保护）
    // ============================================================

    private void doDetour(String projectKey, DetourSignalDetector.DetourSignal signal,
                          String finalAnswer, LLMClient client, String model) {
        try {
            LLMClient activeClient = client != null ? client : llmClientManager.getFirstClient();
            if (activeClient == null) {
                log.warn("弯路提炼跳过：无可用 LLM 客户端");
                return;
            }
            String activeModel = model;
            if (activeModel == null || activeModel.isBlank()) {
                activeModel = llmClientManager.getDefaultModel(activeClient.getProviderCode());
            }

            // 1. LLM 判定+提炼（一次调用；worthRecord 过滤误报）
            JsonNode result = detourWithLLM(activeClient, activeModel, signal, finalAnswer);
            if (result == null || !result.path("worthRecord").asBoolean(false)) {
                log.debug("弯路判定无需沉淀（误报过滤）: signals=A{} B{}{} C{}",
                        signal.isSignalA(), signal.isSignalBStrong() ? "强" : "",
                        signal.isSignalBMedium() ? "中" : "", signal.isSignalC());
                return;
            }
            String goal = nullableText(result.get("goal"));
            String abandoned = nullableText(result.get("abandonedApproach"));
            String reason = nullableText(result.get("abandonReason"));
            String adopted = nullableText(result.get("adoptedApproach"));
            String solution = nullableText(result.get("solution"));
            String keywords = nullableText(result.get("keywords"));

            // 2. 输出校验：核心字段非空才入库（宁缺毋滥）
            if (goal == null || abandoned == null || reason == null || adopted == null) {
                log.debug("弯路提炼输出校验失败（核心字段缺失），丢弃");
                return;
            }

            // 3. 入库 DETOUR（goal 维度指纹去重；symptom 组装弯路叙事）
            String symptom = "方案绕路: " + abandoned + " 不可行（" + reason + "），改用 " + adopted;
            String applicableCond = "目标为: " + goal + " 时";
            Lesson lesson = lessonService.recordLessonWithResult(
                    projectKey, "detour", "DETOUR", "DETOUR",
                    symptom, reason, solution == null ? Lesson.PLACEHOLDER_SOLUTION : solution,
                    "[]", applicableCond, keywords,
                    Lesson.SOURCE_REVIEW, Lesson.TYPE_DETOUR, goal).lesson;
            log.info("弯路经验沉淀: id={}, project={}, goal={}, source={}",
                    lesson.getId(), projectKey, goal, lesson.getSource());
        } catch (Exception e) {
            log.warn("弯路提炼执行失败（不影响主流程）: err={}", e.getMessage());
        }
    }

    /** 调 LLM 判定+提炼（只输出 JSON） */
    private JsonNode detourWithLLM(LLMClient client, String model,
                                   DetourSignalDetector.DetourSignal signal, String finalAnswer) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> sysMsg = new HashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", DETOUR_SYSTEM_PROMPT);
        messages.add(sysMsg);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        StringBuilder content = new StringBuilder("【本轮对话摘要】\n");
        // 信号命中情况
        List<String> signalDesc = new ArrayList<>();
        if (signal.isSignalA()) {
            signalDesc.add("用户否定/纠正信号（命中文本: " + signal.getUserText() + "）");
        }
        if (signal.isSignalBStrong()) {
            signalDesc.add("LLM 自述换方案强信号（命中文本: " + signal.getAssistantText() + "）");
        } else if (signal.isSignalBMedium()) {
            signalDesc.add("LLM 自述换方案中信号（命中文本: " + signal.getAssistantText() + "）");
        }
        if (signal.isSignalC()) {
            signalDesc.add("工具序列事实: " + signal.getToolFact());
        }
        content.append("检测到信号: ").append(String.join("；", signalDesc)).append("\n");
        if (finalAnswer != null) {
            content.append("【AI 最终回复】\n").append(truncate(finalAnswer, 1000));
        }
        userMsg.put("content", content.toString());
        messages.add(userMsg);

        Map<String, Object> requestBody = client.buildRequestBody(
                messages, model, 0.2, "non-thinking", false, null);
        String rawResponse = client.blockingChat(requestBody, Duration.ofMillis(llmTimeoutMs));
        String contentText = client.extractContentFromBlockingResponse(rawResponse);
        if (contentText == null || contentText.isBlank()) {
            log.warn("弯路提炼 LLM 返回空内容");
            return null;
        }
        return parseJsonStrict(contentText);
    }

    /** 解析 LLM 输出的 JSON（容忍 ```json 包裹 / 首尾杂文本；与 C2 同源实现） */
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
            log.warn("弯路提炼 LLM 输出非合法 JSON，放弃: {}", truncate(content, 200));
            return null;
        }
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
        if (detourDedup.size() > DEDUP_MAX) {
            long now = System.currentTimeMillis();
            detourDedup.entrySet().removeIf(e -> now - e.getValue() > DEDUP_TTL_MS);
            if (detourDedup.size() > DEDUP_MAX) {
                detourDedup.clear();
            }
        }
        // M4 修复：conversationId 为 null 时用唯一后缀——否则不同会话共享 "null:xxx" 幂等键，
        // 先提炼的那轮会误吞后续所有会话的弯路提炼机会（漏沉淀）。null 场景罕见，宁重复不复用。
        String key = (conversationId == null ? "conv-" + System.nanoTime() : conversationId)
                + ":" + (turnId == null ? "no-turn" : turnId);
        Long prev = detourDedup.putIfAbsent(key, System.currentTimeMillis());
        return prev == null;
    }

    // ============================================================
    // 工具方法
    // ============================================================

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
