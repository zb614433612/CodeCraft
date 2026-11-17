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
        // A 系列断言依赖「默认比率 = 1.0」（未校准状态），先确保干净
        TokenEstimator.resetCalibration();

        testBigToolCallsArguments();
        testEmptyToolCallsKeepsOverhead();
        testNullToolCallsSafe();
        testSmallArgumentsReasonable();
        testMessageWithToolCallsDominatesPlainText();

        System.out.println("--- 在线校准（2026-11-17 修复「估算虚高 3.4 倍」）---");
        testCalibrationLowersRatio();
        testCalibrationConvergesToObserved();
        testCalibrationRejectsOutlier();
        testCalibrationPerProviderIsolated();
        testCalibrationResetRestoresDefault();
        testCalibrationFixesOverestimationScenario();
        testFallbackCalibrationFromChars();
        testTotalRawIncludesTools();

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

    // ==================== 在线校准用例（2026-11-17 修复「估算虚高 3.4 倍」） ====================

    /** 构造一段中英混合文本（贴近真实会话：中文说明 + 代码） */
    private static String mixedText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("第 ").append(i).append(" 步：调用 file_writer 写入配置，检查返回结果是否成功。\n");
            sb.append("public void method").append(i).append("() { return status; }\n");
        }
        return sb.toString();
    }

    /** 把同一段中英混合文本包成一条 API 消息 */
    private static List<Map<String, Object>> mixedMessages() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", mixedText());
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(msg);
        return msgs;
    }

    /**
     * B1：单次校准后比率下降、估算随之变小。
     *
     * <p>期望值可算：初始 1.0，观测 0.3 → 新比率 = 1.0 × (0.7 + 0.3 × 0.3) = 0.79。</p>
     */
    private static void testCalibrationLowersRatio() {
        TokenEstimator.resetCalibration();
        List<Map<String, Object>> msgs = mixedMessages();
        int before = TokenEstimator.estimateMessages(msgs, "deepseek");
        double ratioBefore = TokenEstimator.ratioOf("deepseek");

        // 观测：真实值只有估算的 30%（模拟 CL100K 对中文的严重高估）
        TokenEstimator.calibrate("deepseek", before, Math.max(1, before * 3 / 10));

        double ratioAfter = TokenEstimator.ratioOf("deepseek");
        assertTrue("B1 校准后比率下降（" + String.format("%.3f", ratioBefore)
                        + " → " + String.format("%.3f", ratioAfter) + "）",
                ratioAfter < ratioBefore);
        assertTrue("B1 单次观测只挪动 30%（应落在 [0.75, 0.85]，实际 "
                        + String.format("%.3f", ratioAfter) + "）",
                ratioAfter >= 0.75 && ratioAfter <= 0.85);

        int after = TokenEstimator.estimateMessages(msgs, "deepseek");
        assertTrue("B1 校准后估算随之变小（" + before + " → " + after + "）", after < before);
    }

    /** B2：连续观测 0.3，滑动平均应收敛到 0.3 附近 */
    private static void testCalibrationConvergesToObserved() {
        TokenEstimator.resetCalibration();
        List<Map<String, Object>> msgs = mixedMessages();
        /*
          ★ 关键：真实值必须<b>固定</b>。

          生产链路里 actual 来自 API 返回的 usage.prompt_tokens —— 它与「我们自己的估算」
          无关，是同一份上下文在服务端的真实 token 数，不会因为估算变小而跟着变小。

          若测试按「当前估算 × 0.3」缩放真实值，则 observed 恒等于 0.3，
          比率会被按 ratio × (0.7 + 0.3×0.3) = ratio × 0.79 一路连乘到 0（实测 8 次后 0.151）。
          这不是校准算法的缺陷，而是测试把「被测量」和「测量结果」混为一谈了。
        */
        int raw = TokenEstimator.estimateMessagesRaw(msgs);
        int trueTokens = Math.max(1, raw * 3 / 10);

        for (int i = 0; i < 8; i++) {
            int cur = TokenEstimator.estimateMessages(msgs, "converge");
            TokenEstimator.calibrate("converge", cur, trueTokens);
        }
        double ratio = TokenEstimator.ratioOf("converge");
        assertTrue("B2 8 次观测后收敛到 0.30±0.05（实际 " + String.format("%.3f", ratio) + "）",
                Math.abs(ratio - 0.30) < 0.05);
    }

    /** B3：异常观测（超出 [0.10, 2.00]）必须被丢弃，不能污染比率 */
    private static void testCalibrationRejectsOutlier() {
        TokenEstimator.resetCalibration();
        TokenEstimator.setRatio("outlier", 0.60);
        double before = TokenEstimator.ratioOf("outlier");

        // observed = 1/10000 → 远低于下限
        TokenEstimator.calibrate("outlier", 10000, 1);
        assertTrue("B3 过小观测被丢弃（比率保持 " + String.format("%.3f", before) + "）",
                Math.abs(TokenEstimator.ratioOf("outlier") - before) < 1e-9);

        // observed = 10000/1 → 远超上限
        TokenEstimator.calibrate("outlier", 1, 10000);
        assertTrue("B3 过大观测被丢弃（比率保持 " + String.format("%.3f", before) + "）",
                Math.abs(TokenEstimator.ratioOf("outlier") - before) < 1e-9);
    }

    /** B4：不同 provider 的校准互不影响 */
    private static void testCalibrationPerProviderIsolated() {
        TokenEstimator.resetCalibration();
        TokenEstimator.calibrate("provA", 1000, 300);   // 观测 0.3 → A 下降
        TokenEstimator.calibrate("provB", 1000, 1000);  // 观测 1.0 → B 不变

        double a = TokenEstimator.ratioOf("provA");
        double b = TokenEstimator.ratioOf("provB");
        assertTrue("B4 provA 独立下降（" + String.format("%.3f", a) + " < 0.9）", a < 0.9);
        assertTrue("B4 provB 保持 1.0（实际 " + String.format("%.3f", b) + "）",
                Math.abs(b - 1.0) < 0.01);
    }

    /** B5：reset 后回到默认 1.0（保证自检之间互不污染） */
    private static void testCalibrationResetRestoresDefault() {
        TokenEstimator.resetCalibration();
        double r = TokenEstimator.ratioOf("whatever");
        assertTrue("B5 reset 后任意 provider 比率为 1.0（实际 " + String.format("%.3f", r) + "）",
                Math.abs(r - 1.0) < 1e-9);
    }

    /**
     * B6：端到端复现「虚高 3.4 倍 → 误判超限」并被校准消除。
     *
     * <p>对应线上实测：估算 438225 vs 真实 130290（比值约 3.36）。
     * 校准若干次后，{@code estimateMessages} 的结果必须逼近真实值，
     * 这样「估算 vs maxTokens」的守卫判断才能重新有效 —— 否则实际只有 7.5 万 token
     * 就被判成「已超 25.5 万」，每轮都压缩/裁剪，前缀缓存全量失效。</p>
     */
    private static void testCalibrationFixesOverestimationScenario() {
        TokenEstimator.resetCalibration();
        List<Map<String, Object>> msgs = mixedMessages();
        int raw = TokenEstimator.estimateMessagesRaw(msgs);
        // 真实 token ≈ raw / 3.4
        int trueTokens = Math.max(1, Math.round((float) raw / 3.4f));

        for (int i = 0; i < 12; i++) {
            int cur = TokenEstimator.estimateMessages(msgs, "deepseek");
            /*
              ★ 真实值固定（与 B2 同一理由）：trueTokens 是「服务端对同一份上下文给出的
              prompt_tokens」，它是常量。若按 cur 缩放（cur * trueTokens / raw），
              observed 会恒等于 0.294，比率被一路连乘到 MIN_RATIO 下限 0.10，
              表现为「校准把估算压过头」——那是测试构造错误，不是算法错误。
            */
            TokenEstimator.calibrate("deepseek", cur, trueTokens);
        }

        int finalEstimate = TokenEstimator.estimateMessages(msgs, "deepseek");
        double errPct = Math.abs((double) finalEstimate - trueTokens) * 100.0 / trueTokens;
        assertTrue("B6 校准后估算逼近真实（估算 " + finalEstimate + " vs 真实 " + trueTokens
                        + "，偏差 " + String.format("%.1f", errPct) + "%）",
                errPct < 5.0);
        assertTrue("B6 修复前的虚高已消除（估算 " + finalEstimate + " < raw " + raw + "）",
                finalEstimate < raw);
    }

    /**
     * B7：usage 帧丢失时的兜底校准（2026-11-17 新增）。
     *
     * <p>线上实测根因：{@code takeWhile} 在「工具调用参数收齐」时立即取消上游订阅，
     * 而 usage 帧排在流末尾（{@code data: [DONE]} 之前）→ {@code snapshot==null}
     * → {@code persistTokenUsageSafely} 静默早退 → 校准<b>一个样本都收不到</b>
     * → 比率恒为 1.0 → 估算虚高 4 倍 → 守卫每轮误判超限 → 前缀每轮重建 → 首 token 18 秒。</p>
     *
     * <p>兜底通道改由「请求体字符数 × {@link TokenEstimator#CHARS_TO_TOKEN_RATIO}」反推真实
     * token，保证校准始终有观测值。本用例验证换算准确性 + 校准闭环收敛。</p>
     */
    private static void testFallbackCalibrationFromChars() {
        // ① 换算准确性：与线上实测配对（请求体 163962 chars ↔ 真实 69684 tokens）
        int observed = TokenEstimator.estimateFromRequestChars(163_962);
        assertTrue("B7 字符数反推命中实测值 69684（实际 " + observed + "）",
                Math.abs(observed - 69_684) <= 2_000);
        assertTrue("B7 chars<=0 视为不可用（返回 0）",
                TokenEstimator.estimateFromRequestChars(0) == 0
                        && TokenEstimator.estimateFromRequestChars(-5) == 0);

        // ② 闭环：把兜底观测喂进 calibrate，估算应收敛到观测值附近
        TokenEstimator.resetCalibration();
        List<Map<String, Object>> msgs = mixedMessages();
        int raw = TokenEstimator.estimateMessagesRaw(msgs);
        // 构造观测落在 [0.10, 2.00] 倍 raw 内，避免被 clamp 干扰判断
        int fallbackObserved = TokenEstimator.estimateFromRequestChars(Math.max(1, raw * 3));
        for (int i = 0; i < 12; i++) {
            int cur = TokenEstimator.estimateMessages(msgs, "fallback");
            TokenEstimator.calibrate("fallback", cur, fallbackObserved);
        }
        int calibrated = TokenEstimator.estimateMessages(msgs, "fallback");
        double errPct = Math.abs((double) calibrated - fallbackObserved) * 100.0 / fallbackObserved;
        assertTrue("B7 兜底观测收敛后估算≈观测（" + calibrated + " vs " + fallbackObserved
                        + "，偏差 " + String.format("%.1f", errPct) + "%）",
                errPct < 5.0);
    }

    /**
     * B8：口径统一 —— 估算必须把 tools 定义算进去（与服务端 prompt_tokens 同口径）。
     *
     * <p>线上实测的两处失效都源于口径错位：</p>
     * <ul>
     *   <li>拿「不含 tools 的估算」追「含 tools 的真实」→ 校准比率被顶到 MAX_RATIO=2.0 仍不够；</li>
     *   <li>拿「含 tools 的字符数兜底观测」比「不含 tools 的估算」→ observed≈3.2 &gt; 2.0
     *       被判异常丢弃（日志里一条校准都没有，正是此因）。</li>
     * </ul>
     */
    private static void testTotalRawIncludesTools() {
        List<Map<String, Object>> msgs = mixedMessages();
        String toolsJson = fakeToolsJson();

        int messagesRaw = TokenEstimator.estimateMessagesRaw(msgs);
        int toolsRaw = TokenEstimator.estimateToolsRaw(toolsJson);
        int totalRaw = TokenEstimator.estimateTotalRaw(msgs, toolsJson);

        assertTrue("B8 tools 定义被计入总量（messages " + messagesRaw + " + tools " + toolsRaw
                        + " = " + totalRaw + "）",
                toolsRaw > 0 && totalRaw == messagesRaw + toolsRaw);
        assertTrue("B8 tools 为空时总量退化为 messages（" + TokenEstimator.estimateTotalRaw(msgs, null) + "）",
                TokenEstimator.estimateTotalRaw(msgs, null) == messagesRaw);

        // 未校准时 raw × 1.0 = raw
        TokenEstimator.resetCalibration();
        assertTrue("B8 未校准时 applyRatio 等于 raw（" + TokenEstimator.applyRatio(totalRaw, "ratioCheck")
                        + " vs " + totalRaw + "）",
                TokenEstimator.applyRatio(totalRaw, "ratioCheck") == totalRaw);

        // 实测收敛值 0.243（真实 191965 / raw ≈ 79 万）落在合法区间内，不会被当异常丢弃
        TokenEstimator.setRatio("scopeCheck", 0.243);
        int calibrated = TokenEstimator.applyRatio(totalRaw, "scopeCheck");
        assertTrue("B8 比率 0.243 生效（" + totalRaw + " → " + calibrated + "）",
                Math.abs(calibrated - totalRaw * 0.243) <= Math.max(2, totalRaw * 0.01));
        assertTrue("B8 比率 0.243 未被 clamp（实际 " + String.format("%.3f", TokenEstimator.ratioOf("scopeCheck")) + "）",
                Math.abs(TokenEstimator.ratioOf("scopeCheck") - 0.243) < 1e-9);
    }

    /** 构造一份贴近真实的工具定义 JSON（约 20 个工具、含中文描述，与线上 schema 同量级） */
    private static String fakeToolsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"function\",\"function\":{\"name\":\"tool_").append(i)
                    .append("\",\"description\":\"这是一个用于读写文件、执行命令或检索代码的工具，")
                    .append("包含完整的中文说明与参数约束。\",")
                    .append("\"parameters\":{\"type\":\"object\",\"properties\":{")
                    .append("\"path\":{\"type\":\"string\",\"description\":\"目标文件路径\"},")
                    .append("\"content\":{\"type\":\"string\",\"description\":\"文件完整内容\"}")
                    .append("}}}}");
        }
        sb.append(']');
        return sb.toString();
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
