package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.util.TokenEstimator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩「估算口径」单元测试（main 方法式，无需 JUnit 容器）
 *
 * <p>
 * 背景：v3.1.x 实测发现缓存命中率虽高（97.87%），但 6 轮对话产生了 15 次压缩、
 * 其中 9 次造成前缀缓存全量失效，约 130 万 miss token（≈¥1.3）纯属浪费。
 * 根因是异步预压缩的估算口径与实际请求不一致：
 * </p>
 * <ul>
 *   <li>旧 {@code estimateDbMessages} 直接累加 DB 全量原文，<b>忽略已落库的压缩记录</b>，
 *       导致 ratio 永久 &gt; 0.60，每次请求返回后都触发一次压缩；</li>
 *   <li>旧 {@code buildApiMessages} 把 TOOL 消息的 {@code reasoning}（实际存的是工具结果）
 *       当思考链按 strip 丢弃，严重低估（工具结果才是上下文大头）；</li>
 *   <li>传给 {@code compact()} 的列表未应用压缩记录，与内部重新查库的列表索引不对齐。</li>
 * </ul>
 *
 * <p>本测试锁定修复后的口径，防止回归。运行方式：</p>
 * <pre>
 * mvn test-compile -DskipTests
 * mvn exec:java "-Dexec.mainClass=com.example.agentdeepseek.service.impl.CompactionEstimateTest" "-Dexec.classpathScope=test"
 * </pre>
 */
public class CompactionEstimateTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== CompactionEstimateTest 开始 ===");

        // A 组：toApiMessages 转换口径
        testToolMessageUsesReasoningAsContent();
        testToolMessageNotDroppedByStrip();
        testToolMessageFallbackToContent();
        testAssistantReasoningRespectsStrip();
        testAssistantToolCallsCounted();
        testOneToOneMapping();
        testSystemSummaryMessage();

        // B 组：P0 根因复现（压缩记录是否被计入）
        testCompactionRecordsReduceEstimate();
        testToolResultsAreDominantTokenSource();

        System.out.println("=== 结束：通过 " + passed + "，失败 " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== A 组：转换口径 ====================

    /**
     * TOOL 消息的 API content 必须取自 reasoning（工具结果存在该字段），
     * 与 ContextBuilder.buildMessagesFromHistory 完全一致。
     */
    private static void testToolMessageUsesReasoningAsContent() {
        ConversationMessage tool = msg(1L, MessageRole.TOOL, null, "TOOL_RESULT_BODY_12345", null);
        List<Map<String, Object>> api = CompactionService.toApiMessages(List.of(tool), true);
        check("A1 TOOL 消息条数", 1, api.size());
        check("A1 TOOL role", "tool", api.get(0).get("role"));
        check("A1 TOOL content 取 reasoning", "TOOL_RESULT_BODY_12345", api.get(0).get("content"));
    }

    /**
     * 关键回归点：stripHistoryReasoning=true 时 TOOL 消息<b>不能</b>被丢弃。
     * 旧实现把 TOOL 的 reasoning 当思考链剥掉，导致整个工具结果不计入估算。
     */
    private static void testToolMessageNotDroppedByStrip() {
        String body = repeat("x", 4000);
        ConversationMessage tool = msg(1L, MessageRole.TOOL, null, body, null);

        List<Map<String, Object>> stripped = CompactionService.toApiMessages(List.of(tool), true);
        List<Map<String, Object>> full = CompactionService.toApiMessages(List.of(tool), false);

        check("A2 strip=true 时 TOOL content 非空", body, stripped.get(0).get("content"));
        int strippedTokens = TokenEstimator.estimateMessages(stripped);
        int fullTokens = TokenEstimator.estimateMessages(full);
        check("A2 strip 开关不影响 TOOL 估算", fullTokens, strippedTokens);
        checkTrue("A2 TOOL 估算应接近正文规模（>=500 token）", strippedTokens >= 500);
    }

    /** TOOL 消息 reasoning 为空时回退到 content。 */
    private static void testToolMessageFallbackToContent() {
        ConversationMessage tool = msg(1L, MessageRole.TOOL, "FALLBACK", null, null);
        List<Map<String, Object>> api = CompactionService.toApiMessages(List.of(tool), true);
        check("A3 TOOL reasoning 为空回退 content", "FALLBACK", api.get(0).get("content"));

        ConversationMessage bothNull = msg(2L, MessageRole.TOOL, null, null, null);
        List<Map<String, Object>> api2 = CompactionService.toApiMessages(List.of(bothNull), true);
        check("A3 TOOL 双空给空串（不抛异常）", "", api2.get(0).get("content"));
    }

    /** ASSISTANT 的 reasoning_content 严格跟随 strip 开关。 */
    private static void testAssistantReasoningRespectsStrip() {
        ConversationMessage asst = msg(1L, MessageRole.ASSISTANT, "answer", "thinking...", null);

        Map<String, Object> kept = CompactionService.toApiMessages(List.of(asst), false).get(0);
        check("A4 strip=false 保留 reasoning_content", "thinking...", kept.get("reasoning_content"));

        Map<String, Object> stripped = CompactionService.toApiMessages(List.of(asst), true).get(0);
        checkTrue("A4 strip=true 不下发 reasoning_content", !stripped.containsKey("reasoning_content"));
        check("A4 strip 不影响正文", "answer", stripped.get("content"));
    }

    /** ASSISTANT 的 tool_calls 必须计入（有 token 开销）。 */
    private static void testAssistantToolCallsCounted() {
        String calls = "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"command\",\"arguments\":\"{}\"}}]";
        ConversationMessage withCalls = msg(1L, MessageRole.ASSISTANT, null, null, calls);
        Map<String, Object> map = CompactionService.toApiMessages(List.of(withCalls), true).get(0);
        check("A5 tool_calls 被保留", calls, map.get("tool_calls"));

        ConversationMessage emptyCalls = msg(2L, MessageRole.ASSISTANT, "hi", null, "");
        Map<String, Object> map2 = CompactionService.toApiMessages(List.of(emptyCalls), true).get(0);
        checkTrue("A5 空 tool_calls 不下发", !map2.containsKey("tool_calls"));

        ConversationMessage nullCalls = msg(3L, MessageRole.ASSISTANT, "hi", null, null);
        Map<String, Object> map3 = CompactionService.toApiMessages(List.of(nullCalls), true).get(0);
        checkTrue("A5 null tool_calls 不下发", !map3.containsKey("tool_calls"));
    }

    /**
     * 一一映射：输出条数必须等于输入条数。
     * compact() 依赖该性质保证 compressStart/compressEnd 索引与内部列表对齐。
     */
    private static void testOneToOneMapping() {
        List<ConversationMessage> input = new ArrayList<>();
        input.add(msg(1L, MessageRole.SYSTEM, "sys", null, null));
        input.add(msg(2L, MessageRole.USER, "u1", null, null));
        input.add(msg(3L, MessageRole.ASSISTANT, null, null, "[{\"id\":\"c1\"}]"));
        input.add(msg(4L, MessageRole.TOOL, null, "tool result", null));
        input.add(msg(5L, MessageRole.ASSISTANT, "done", "think", null));

        List<Map<String, Object>> api = CompactionService.toApiMessages(input, true);
        check("A6 条数一一对应", input.size(), api.size());

        // role 缺失（脏数据）时也必须占位，不能少条
        ConversationMessage broken = new ConversationMessage();
        broken.setId(9L);
        broken.setConversationId(1L);
        broken.setRole(null);
        List<Map<String, Object>> api2 = CompactionService.toApiMessages(List.of(broken), true);
        check("A6 role=null 仍占位一条", 1, api2.size());

        List<Map<String, Object>> api3 = CompactionService.toApiMessages(null, true);
        check("A6 null 入参返回空列表", 0, api3.size());
    }

    /** 压缩摘要（SYSTEM 角色）正常转换。 */
    private static void testSystemSummaryMessage() {
        ConversationMessage summary = msg(-7L, MessageRole.SYSTEM, "【上下文摘要】...", null, null);
        Map<String, Object> map = CompactionService.toApiMessages(List.of(summary), true).get(0);
        check("A7 摘要 role", "system", map.get("role"));
        check("A7 摘要 content", "【上下文摘要】...", map.get("content"));
        checkTrue("A7 摘要无 reasoning_content", !map.containsKey("reasoning_content"));
    }

    // ==================== B 组：P0 根因复现 ====================

    /**
     * 核心回归：应用压缩记录后，估算值必须大幅下降。
     * <p>
     * 旧逻辑累加 DB 全量原文，历史被压缩多少次估算值都不会降 →
     * ratio 永久超阈值 → 每次请求后都压缩 → 每次压缩又改变前缀 → 缓存永久失效。
     * </p>
     */
    private static void testCompactionRecordsReduceEstimate() {
        // 构造 30 条 TOOL 消息，每条 6000 字符（≈1500 token），共约 4.5 万 token
        List<ConversationMessage> dbMessages = new ArrayList<>();
        dbMessages.add(msg(1L, MessageRole.USER, "开始任务", null, null));
        long id = 2;
        for (int i = 0; i < 30; i++) {
            dbMessages.add(msg(id++, MessageRole.ASSISTANT, null, null,
                    "[{\"id\":\"c" + i + "\",\"type\":\"function\",\"function\":{\"name\":\"command\",\"arguments\":\"{}\"}}]"));
            dbMessages.add(msg(id++, MessageRole.TOOL, null, repeat("tool-output-", 500), null));
        }
        dbMessages.add(msg(id, MessageRole.USER, "最新问题", null, null));

        // 旧口径：无差别累加全量原文（本地复现旧 estimateDbMessages 行为）
        int legacyTokens = legacyEstimateDbMessages(dbMessages);

        // 新口径：模拟 applyCompactionRecords 之后的效果 ——
        // 中间 60 条（含 30 组 assistant+tool）被压成 1 条摘要，只保留首尾少量消息
        List<ConversationMessage> effective = new ArrayList<>();
        effective.add(dbMessages.get(0));
        effective.add(msg(-1L, MessageRole.SYSTEM, "【上下文摘要】已完成 30 次命令调用，输出均为构建日志。", null, null));
        effective.add(dbMessages.get(dbMessages.size() - 1));

        List<Map<String, Object>> apiMessages = CompactionService.toApiMessages(effective, true);
        int effectiveTokens = TokenEstimator.estimateMessages(apiMessages);

        System.out.println("    [B1] 旧口径(全量原文)=" + legacyTokens + " tokens, 新口径(应用压缩记录)="
                + effectiveTokens + " tokens, 降幅="
                + (legacyTokens > 0 ? (100 - effectiveTokens * 100 / legacyTokens) : 0) + "%");

        checkTrue("B1 旧口径应显著大于新口径（>=10 倍）", legacyTokens >= effectiveTokens * 10);

        // 用真实阈值口径验证。
        // 真实场景：max-context-tokens=1,000,000，旧口径估算 215 万 → ratio 2.15（永久触发）。
        // 测试按比例缩小规模：上限取 40,000，使旧 ratio > 0.60、新 ratio 接近 0。
        int maxTokens = 40_000;
        double legacyRatio = (double) legacyTokens / maxTokens;
        double newRatio = (double) effectiveTokens / maxTokens;
        System.out.println("    [B1] 假设上限 " + maxTokens + "：旧 ratio=" + String.format("%.3f", legacyRatio)
                + "（触发压缩）, 新 ratio=" + String.format("%.3f", newRatio) + "（不触发）");
        checkTrue("B1 旧口径应超 0.60 阈值（复现 P0）", legacyRatio >= 0.60);
        checkTrue("B1 新口径应低于 0.60 阈值（修复后不再反复压缩）", newRatio < 0.60);
    }

    /** 工具结果是上下文的绝对大头：TOOL 消息必须计入估算，否则会严重低估。 */
    private static void testToolResultsAreDominantTokenSource() {
        List<ConversationMessage> list = new ArrayList<>();
        // 1 条 assistant（短）+ 1 条 TOOL（长）
        list.add(msg(1L, MessageRole.ASSISTANT, "ok", null, null));
        list.add(msg(2L, MessageRole.TOOL, null, repeat("A", 8000), null));

        List<Map<String, Object>> api = CompactionService.toApiMessages(list, true);
        Map<String, Object> toolMap = api.get(1);
        Object content = toolMap.get("content");
        checkTrue("B2 TOOL content 存在", content instanceof String && !((String) content).isEmpty());

        int toolTokens = TokenEstimator.estimateMessages(api);
        int assistantOnlyTokens = TokenEstimator.estimateMessages(CompactionService.toApiMessages(List.of(list.get(0)), true));
        System.out.println("    [B2] TOOL 消息估算=" + toolTokens + " tokens, 仅 assistant=" + assistantOnlyTokens + " tokens");

        checkTrue("B2 TOOL 结果应主导估算（>=10 倍 assistant）", toolTokens >= assistantOnlyTokens * 10);
    }

    // ==================== 辅助 ====================

    /** 本地复现旧 estimateDbMessages 的算法（无差别累加 content + reasoning + 10） */
    private static int legacyEstimateDbMessages(List<ConversationMessage> messages) {
        int total = 0;
        for (ConversationMessage msg : messages) {
            total += TokenEstimator.estimate(msg.getContent());
            total += TokenEstimator.estimate(msg.getReasoning());
            total += 10;
        }
        return total;
    }

    private static ConversationMessage msg(Long id, MessageRole role, String content,
                                          String reasoning, String toolCalls) {
        ConversationMessage m = new ConversationMessage();
        m.setId(id);
        m.setConversationId(1L);
        m.setRole(role);
        m.setContent(content);
        m.setReasoning(reasoning);
        m.setToolCalls(toolCalls);
        // 保证排序稳定：id 越大时间越晚
        m.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(id));
        return m;
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    private static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name + "：期望=" + expected + "，实际=" + actual);
        }
    }

    private static void checkTrue(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name);
        }
    }
}
