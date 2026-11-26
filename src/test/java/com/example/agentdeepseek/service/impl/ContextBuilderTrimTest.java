package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.config.ToolResultBudgetConfig;
import com.example.agentdeepseek.util.TokenEstimator;
import com.example.agentdeepseek.util.ToolResultBudget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ContextBuilder} 上下文裁剪自检（main 方法，非 JUnit）。
 *
 * <h3>为什么需要（2026-11-16）</h3>
 * <p>线上日志暴露过一个静默失效：{@code 上下文滑动窗口丢弃：估算 429388 tokens (上限 300000)}
 * 紧接着 {@code 上下文丢弃完成：移除了 0 条消息}——裁剪函数被调用了，但一条也没删掉。
 * 根因是 {@code findProtectStart} 在「user 轮次不足 protectRounds」时返回 1，
 * 使可裁剪区 {@code [idx=1, protectStart=1)} 为空、循环条件恒不成立。</p>
 *
 * <p>这类 bug 的特点是「不报错、不抛异常、日志还显示执行成功」，只能靠断言消息条数
 * 变化来验证。因此本自检直接断言「删除条数 &gt; 0」，而不是只看有没有异常。</p>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -q -f "C:\work\IdeaProjects\CodeCraft\pom.xml" test-compile -DskipFrontend
 * mvn -q -f "C:\work\IdeaProjects\CodeCraft\pom.xml" \
 *   org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *   -Dexec.mainClass=com.example.agentdeepseek.service.impl.ContextBuilderTrimTest \
 *   -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class ContextBuilderTrimTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== ContextBuilder 上下文裁剪自检开始 ===");

        // 除配置外其余依赖在 trimToTokenBudget 路径上都不使用，传 null 即可
        DeepSeekConfig config = new DeepSeekConfig();
        config.getCompaction().setProtectRounds(2);
        ContextBuilder builder = new ContextBuilder(null, null, null, config, null, null, null, null, null);

        testMultiUserRoundsAreTrimmed(builder);
        testTwoUserRoundsStillTrimmed(builder);
        testSingleUserToolLoopLimit(builder);
        testUnderLimitIsNoOp(builder);
        testToolLoopTrimReducesTokens();

        System.out.println("=== 结果: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 用例 1：常规多轮历史超限 → 必须真正删掉早期轮次。
     *
     * <p>这是修复前的「必然失败」用例：3 轮 user 时 protectRounds=2 会把保护带定在
     * 倒数第 2 个 user，早期轮次本应可删，但旧实现因边界计算错误删了 0 条。</p>
     */
    private static void testMultiUserRoundsAreTrimmed(ContextBuilder builder) {
        List<Map<String, Object>> messages = buildRounds(6, 20_000);
        int before = messages.size();
        int maxTokens = 200_000;

        builder.trimToTokenBudget(messages, maxTokens);
        int removed = before - messages.size();

        boolean ok = removed > 0;
        report("多轮历史超限必须真正裁剪（删除了 " + removed + " 条）", ok);
    }

    /**
     * 用例 2：2 轮 user + 大量工具消息 → 仍应裁剪。
     *
     * <p>2 轮刚好等于 protectRounds=2，findProtectStart 会落到「轮次不足保护带」分支，
     * 这是降级链必须接管的边界情况。</p>
     */
    private static void testTwoUserRoundsStillTrimmed(ContextBuilder builder) {
        List<Map<String, Object>> messages = buildRounds(2, 60_000);
        int before = messages.size();
        int maxTokens = 100_000;

        builder.trimToTokenBudget(messages, maxTokens);
        int removed = before - messages.size();

        boolean ok = removed > 0;
        report("2 轮（=protectRounds）边界必须降级裁剪（删除了 " + removed + " 条）", ok);
    }

    /**
     * 用例 3：单 user 工具循环（线上真实形态）→ 记录行为，不强行要求删除。
     *
     * <p>只有 1 条 user 时，除 system 外全部历史都属于「当前这一轮的产物」，
     * 滑动窗口没有可丢的「更早轮次」——此时正确策略是交给
     * {@link ContextBuilder#trimToolLoopToBudget} 做「旧 tool 消息摘要化 + 超大消息截断」，
     * 而不是在这里硬删（硬删会破坏 assistant.tool_calls 与 tool 的配对）。</p>
     *
     * <p>因此本用例只断言「不抛异常且消息数不增加」，把「能否降下来」交给工具循环裁剪。</p>
     */
    private static void testSingleUserToolLoopLimit(ContextBuilder builder) {
        List<Map<String, Object>> messages = buildSingleUserToolLoop(30, 20_000);
        int before = messages.size();

        try {
            builder.trimToTokenBudget(messages, 100_000);
            int after = messages.size();
            // 关键约束：绝不能把最后一条 user 提问删掉（否则 API 直接 400）
            boolean userSurvived = messages.stream()
                    .anyMatch(m -> "user".equals(m.get("role")));
            boolean ok = after <= before && userSurvived;
            report("单 user 工具循环：不抛异常且保留 user 提问（" + before + " → " + after
                    + "，user 保留=" + userSurvived + "）", ok);
        } catch (Exception e) {
            report("单 user 工具循环：不抛异常（实际抛出 " + e.getClass().getSimpleName() + "）", false);
        }
    }

    /** 用例 4：未超限时必须原样返回（防「没超也乱删」的过度裁剪） */
    private static void testUnderLimitIsNoOp(ContextBuilder builder) {
        List<Map<String, Object>> messages = buildRounds(3, 1_000);
        int before = messages.size();

        builder.trimToTokenBudget(messages, 5_000_000);
        int after = messages.size();

        boolean ok = after == before;
        report("未超限时不裁剪（" + before + " → " + after + "）", ok);
    }

    /**
     * 用例 5：单 user 长任务 → {@link ContextBuilder#trimToolLoopToBudget} 必须把 token 降下来。
     *
     * <p><b>为什么必须有这个用例</b>：滑动窗口在「只有 1 条 user」时按设计退让（不删当前轮次），
     * 于是它必然降不下 token。此时真正的防线是工具循环裁剪（旧 tool 消息摘要化 +
     * 超大消息截断）。若这道防线也失效，请求会一直维持 40 万+ tokens 发给 API——
     * 那正是线上日志里「摘要化 N 条, 截断 0 条, 剩余估算 44 万」的形态。</p>
     */
    private static void testToolLoopTrimReducesTokens() {
        // trimToolLoopToBudget 依赖 ToolResultBudget（截断规则），需真实实例
        ToolResultBudget budget = new ToolResultBudget(new ToolResultBudgetConfig());
        DeepSeekConfig config = new DeepSeekConfig();
        ContextBuilder builder = new ContextBuilder(null, null, null, config, budget, null, null, null, null);

        List<Map<String, Object>> messages = buildSingleUserToolLoop(30, 20_000);
        int before = TokenEstimator.estimateMessages(messages);
        int maxTokens = 100_000;

        builder.trimToolLoopToBudget(messages, maxTokens);
        int after = TokenEstimator.estimateMessages(messages);

        /*
         * 断言「显著下降」而非「达标」：摘要化只能作用于旧 tool 消息，
         * 系统提示词与当前轮次仍占固定开销，未必一次就能压到 10 万以下。
         * 但必须远低于原始值，否则说明这道防线也失效了。
         */
        boolean ok = after < before * 0.6;
        report("工具循环裁剪必须显著降低 token（" + before + " → " + after + "，期望低于 "
                + (int) (before * 0.6) + "）", ok);
    }

    /**
     * 构造 n 轮对话：每轮 user + assistant(tool_calls) + 两条 tool。
     *
     * @param contentChars 单条消息的 content 字符数（用于把 token 估算推过阈值）
     */
    private static List<Map<String, Object>> buildRounds(int n, int contentChars) {
        List<Map<String, Object>> messages = new ArrayList<>();
        // index 0 固定是 system prompt（裁剪起点 idx=1 的前提）
        messages.add(msg("system", "you are a helpful assistant " + "S".repeat(contentChars)));

        for (int round = 0; round < n; round++) {
            messages.add(msg("user", "用户提问 " + round + " " + "U".repeat(contentChars)));

            Map<String, Object> assistant = msg("assistant", "处理中 " + "A".repeat(contentChars));
            // 带 tool_calls 的 assistant：与后续 tool 消息构成配对
            assistant.put("tool_calls", List.of(Map.of(
                    "id", "call_" + round,
                    "type", "function",
                    "function", Map.of("name", "file_explorer", "arguments", "{}"))));
            messages.add(assistant);

            messages.add(toolMsg("call_" + round, "工具结果 " + "T".repeat(contentChars)));
            messages.add(toolMsg("call_" + round + "_2", "工具结果2 " + "R".repeat(contentChars)));
        }
        return messages;
    }

    /** 构造「单 user + 多轮工具调用」形态（线上长任务的真实消息形状） */
    private static List<Map<String, Object>> buildSingleUserToolLoop(int iterations, int contentChars) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", "you are a helpful assistant " + "S".repeat(contentChars)));
        messages.add(msg("user", "请完成这个长任务 " + "U".repeat(contentChars)));

        for (int i = 0; i < iterations; i++) {
            Map<String, Object> assistant = msg("assistant", "第 " + i + " 步 " + "A".repeat(contentChars));
            assistant.put("tool_calls", List.of(Map.of(
                    "id", "call_" + i,
                    "type", "function",
                    "function", Map.of("name", "command", "arguments", "{}"))));
            messages.add(assistant);
            messages.add(toolMsg("call_" + i, "工具结果 " + i + " " + "T".repeat(contentChars)));
        }
        return messages;
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> toolMsg(String toolCallId, String content) {
        Map<String, Object> m = msg("tool", content);
        m.put("tool_call_id", toolCallId);
        return m;
    }

    private static void report(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
