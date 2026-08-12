package com.example.agentdeepseek.service.lesson;

import com.example.agentdeepseek.model.entity.Lesson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 踩坑经验异步捕获器
 * ToolExecutor 失败钩子 → recordAsync()（调用线程提取上下文值）→ 独立线程池异步入库。
 *
 * 关键约束：
 * 1. 绝不阻塞主流程 —— 工具失败后 LLM 还等着结果重试，捕获必须异步
 * 2. ThreadLocal 值（projectKey 等）必须在调用线程提取，异步线程里取不到
 * 3. 有界队列 + 丢弃策略 —— 高并发失败时丢日志不丢主流程，防雪崩
 */
@Slf4j
@Component
public class LessonRecorder {

    private final LessonService lessonService;
    private final FailureNormalizer normalizer;
    /** P0：LLM 语义归一化兜底（规则通道落空时异步增强草稿，不阻塞主流程） */
    private final LessonNormalizerService normalizerService;

    /** 单线程 + 有界队列（500），队列满时静默丢弃（记录日志），绝不阻塞工具返回 */
    private final ExecutorService executor;

    public LessonRecorder(LessonService lessonService, FailureNormalizer normalizer,
                          LessonNormalizerService normalizerService) {
        this.lessonService = lessonService;
        this.normalizer = normalizer;
        this.normalizerService = normalizerService;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "lesson-recorder");
                    t.setDaemon(true);
                    return t;
                },
                (r, e) -> log.warn("踩坑经验捕获队列已满，丢弃一条失败记录（不阻塞主流程）")
        );
    }

    /**
     * 异步记录一次工具失败（调用线程调用，上下文值在此提取）
     *
     * @param toolName      工具名（如 command / file_writer / mcp_server_manager）
     * @param argumentsJson 工具参数 JSON（原样传入，用于提取结构化参数）
     * @param errorMessage  失败信息（异常消息或错误提示）
     */
    public void recordAsync(String toolName, String argumentsJson, String errorMessage) {
        try {
            // ★ 必须在调用线程提取：ProjectRootContext 是 ThreadLocal，异步线程里取不到
            String projectKey = normalizer.extractProjectKey();
            executor.submit(() -> doRecord(projectKey, toolName, argumentsJson, errorMessage));
        } catch (Exception e) {
            log.warn("提交踩坑经验捕获任务失败: tool={}, err={}", toolName, e.getMessage());
        }
    }

    private void doRecord(String projectKey, String toolName, String argumentsJson, String errorMessage) {
        try {
            if (errorMessage == null || errorMessage.isBlank()) {
                return; // 无有效错误信息，不值得记录
            }
            FailureNormalizer.NormalizedFailure nf =
                    normalizer.normalize(toolName, argumentsJson, errorMessage);

            // 现象里带上工具名上下文，方便检索时理解
            String symptom = "工具[" + nf.toolName + "] 执行失败: " + nf.symptom;

            Lesson lesson = lessonService.recordLesson(
                    projectKey, nf.toolName, nf.errorCategory, nf.errorCode,
                    symptom, null, Lesson.PLACEHOLDER_SOLUTION,
                    nf.paramsJson, null, null, Lesson.SOURCE_AUTO);

            log.info("踩坑经验自动捕获完成: id={}, {}", lesson.getId(), nf);

            // P0：规则通道落空（errorCode=UNKNOWN 或类别=OTHER）时，触发 LLM 语义归一化兜底
            // 异步增强草稿（缓存命中零 LLM 成本；未命中则后台调 LLM 后回写），绝不阻塞主流程
            if ("UNKNOWN".equals(lesson.getErrorCode()) || "OTHER".equals(lesson.getErrorCategory())) {
                normalizerService.normalizeAsync(projectKey, lesson.getId(), toolName, argumentsJson, errorMessage);
            }
        } catch (Exception e) {
            log.warn("踩坑经验自动捕获失败（不影响主流程）: tool={}, err={}", toolName, e.getMessage());
        }
    }
}
