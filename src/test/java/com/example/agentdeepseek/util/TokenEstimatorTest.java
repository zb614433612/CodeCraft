package com.example.agentdeepseek.util;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TokenEstimator} 对 tool_calls 体积估算的回归自检。
 *
 * <h3>为什么需要这个自检</h3>
 * <p>
 * 此前实现把整个 {@code tool_calls} 字段当成固定 15 token。但真实体积大头是
 * {@code function.arguments} —— {@code file_writer} 写一个完整文件时，全部文件内容
 * 都在这个字符串里，可达数万字符（约上万 token）。
 * </p>
 * <p>
 * 低估的直接后果：{@code estimateMessages} 是所有裁剪/压缩决策的依据
 * （{@code trimToTokenBudget} / {@code trimToolLoopToBudget} / {@code CompactionService.decide}），
 * 低估 → 判定「未超限」→ 不裁剪 → 请求真的超限 → API 返回 400，整轮任务失败。
 * 这类缺陷不报错、不抛异常，只能靠数据断言发现。
 * </p>
 *
 * <h3>回归目标（本次修复的核心契约）</h3>
 * <ol>
 *   <li>大 arguments 的 tool_calls 必须被真实计入（不能退化成 15 token 常量）；</li>
 *   <li>空 tool_calls 仍保留最小结构开销，不返回 0；</li>
 *   <li>null tool_calls 不抛异常；</li>
 *   <li>小 arguments 的估算落在合理区间，不因改造而飙升。</li>
 * </ol>
 *
 * <p>运行方式（与项目内其他自检一致，main 方法而非 JUnit）：</p>
 * <pre>
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *   -Dexec.mainClass=com.example.agentdeepseek.util.TokenEstimatorTest \
 *   -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class TokenEstimatorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== TokenEstimatorTest 开始 ===");

        testBigToolCallsArguments();
        testEmptyToolCallsKeepsOverhead();
        testNullToolCallsSafe();
        testSmallArgumentsReasonable();
        testMessageWithToolCallsDominatesPlainText();

        System.out.println("=== 结束：通过 " + passed + "，失败 " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 大 arguments：模拟 file_writer 写入较大文件的完整内容 */
    private static String bigArguments() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("line ").append(i).append(": some source code content here\n");
        }
        return sb.toString();
    }

    /**
     * A1：大 arguments 必须被真实计入。
     * <p>旧实现（固定 15 token）下这条断言必然失败 —— 正是本修复的回归锁。</p>
     */
    private static void testBigToolCallsArguments() {
        String args = bigArguments();
        ArrayNode toolCalls = JsonNodeFactory.instance.arrayNode();
        ObjectNode call = toolCalls.addObject();
        call.put("id", "call_abc123");
        call.put("type", "function");
        ObjectNode fn = call.putObject("function");
        fn.put("name", "file_writer");
        fn.put("arguments", args);

        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");
        msg.put("tool_calls", toolCalls);

        int estimated = TokenEstimator.estimateMessages(List.of(msg));

        assertTrue("A1 大 arguments 必须被真实计入（估算 " + estimated + " > 1000）", estimated > 1000);
        assertTrue("A1 估算与 arguments 长度同量级（估算 " + estimated
                + "，原文 " + args.length() + "）", estimated >= args.length() / 10);
    }

    /** A2：空 tool_calls 仍计入最小结构开销（与旧行为兼容） */
    private static void testEmptyToolCallsKeepsOverhead() {
        ArrayNode empty = JsonNodeFactory.instance.arrayNode();

        Map<String, Object> withEmpty = new HashMap<>();
        withEmpty.put("role", "assistant");
        withEmpty.put("content", "");
        withEmpty.put("tool_calls", empty);

        Map<String, Object> baseline = new HashMap<>();
        baseline.put("role", "assistant");
        baseline.put("content", "");

        int withEmptyTokens = TokenEstimator.estimateMessages(List.of(withEmpty));
        int baselineTokens = TokenEstimator.estimateMessages(List.of(baseline));

        assertTrue("A2 空 tool_calls 仍保留最小开销（" + withEmptyTokens + " > " + baselineTokens + "）",
                withEmptyTokens > baselineTokens);
    }

    /** A3：null tool_calls 不抛异常 */
    private static void testNullToolCallsSafe() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "hello");
        msg.put("tool_calls", null);

        int estimated = TokenEstimator.estimateMessages(List.of(msg));
        assertTrue("A3 null tool_calls 不抛异常且返回值 >= 1（实际 " + estimated + "）", estimated >= 1);
    }

    /** A4：小 arguments 估算合理，不因改造而飙升 */
    private static void testSmallArgumentsReasonable() {
        ArrayNode toolCalls = JsonNodeFactory.instance.arrayNode();
        ObjectNode call = toolCalls.addObject();
        call.put("id", "call_1");
        call.put("type", "function");
        ObjectNode fn = call.putObject("function");
        fn.put("name", "file_explorer");
        fn.put("arguments", "{\"action\":\"read\",\"file_path\":\"src/main/java/App.java\"}");

        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");
        msg.put("tool_calls", toolCalls);

        int estimated = TokenEstimator.estimateMessages(List.of(msg));
        assertTrue("A4 小 arguments 估算落在合理区间 [16,500]（实际 " + estimated + "）",
                estimated > 15 && estimated < 500);
    }

    /** A5：含大 tool_calls 的消息必须显著高于等长纯文本消息 */
    private static void testMessageWithToolCallsDominatesPlainText() {
        String args = bigArguments();

        ArrayNode toolCalls = JsonNodeFactory.instance.arrayNode();
        ObjectNode call = toolCalls.addObject();
        call.put("id", "call_big");
        call.put("type", "function");
        ObjectNode fn = call.putObject("function");
        fn.put("name", "file_writer");
        fn.put("arguments", args);

        Map<String, Object> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", "");
        assistantMsg.put("tool_calls", toolCalls);

        Map<String, Object> plainMsg = new HashMap<>();
        plainMsg.put("role", "user");
        plainMsg.put("content", args);

        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(assistantMsg);

        int toolCallsTokens = TokenEstimator.estimateMessages(msgs);
        int plainTokens = TokenEstimator.estimateMessages(List.of(plainMsg));

        // tool_calls 里的 arguments 与纯文本内容同样长，估算量应处于同一量级（不出现数量级偏差）
        assertTrue("A5 大 tool_calls 与等价纯文本同量级（" + toolCallsTokens + " vs " + plainTokens + "）",
                Math.abs(toolCallsTokens - plainTokens) < plainTokens / 2 + 50);
    }

    // ==================== 断言 ====================

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
