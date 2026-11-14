package com.example.agentdeepseek.util;

import com.example.agentdeepseek.config.ToolResultBudgetConfig;

/**
 * 工具结果体积预算（{@link ToolResultBudget}）单元测试（main 方法式，无需 JUnit 容器）
 *
 * <h3>为什么这些断言值得锁死</h3>
 * <p>
 * 这套规则是「前缀缓存能否持续命中」的地基：只要截断结果在两次调用间出现任何差异，
 * 该条消息之后的所有历史都会变成 cache miss（DeepSeek 命中/未命中输入单价相差约 50 倍）。
 * 因此本测试的重点不是「截得对不对」，而是<b>幂等性</b>与<b>预算不被突破</b>。
 * </p>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn test-compile -DskipTests
 * mvn exec:java "-Dexec.mainClass=com.example.agentdeepseek.util.ToolResultBudgetTest" "-Dexec.classpathScope=test"
 * </pre>
 */
public class ToolResultBudgetTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== ToolResultBudgetTest 开始 ===");

        // A 组：基础截断行为
        testNoTruncateWhenUnderBudget();
        testTruncateWhenOverBudget();
        testResultNeverExceedsBudget();
        testHeadAndTailPreserved();
        testNullAndEmpty();

        // B 组：幂等（最关键）
        testIdempotentOnSecondPass();
        testSkipWhenMarkerPresent();
        testIsTruncatedHelper();

        // C 组：按工具分级预算
        testPerToolBudget();
        testUnknownToolUsesDefault();
        testNonPositiveBudgetMeansUnlimited();
        testBudgetLookupCaseInsensitive();

        // D 组：续读指引
        testGuideForFileExplorer();
        testGuideForCommand();
        testGuideForSql();
        testGuideDisabled();

        // E 组：配置边界
        testDisabledSwitch();
        testHeadRatioClamped();
        testTinyBudget();

        System.out.println("=== 结束：通过 " + passed + "，失败 " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 构造辅助 ====================

    /** 默认配置（与 application.yml 的正式值保持一致） */
    private static ToolResultBudgetConfig cfg() {
        ToolResultBudgetConfig c = new ToolResultBudgetConfig();
        c.setEnabled(true);
        c.setDefaultChars(12000);
        c.setHeadRatio(0.7);
        c.setIncludeGuide(true);
        c.getTools().put("file_explorer", 16000);
        c.getTools().put("command", 10000);
        c.getTools().put("web_fetch", 16000);
        c.getTools().put("web_search", 10000);
        c.getTools().put("execute_sql", 10000);
        return c;
    }

    /** 生成带可识别头尾标记的长文本 */
    private static String longText(int len) {
        String head = "HEAD_START";
        String tail = "TAIL_END";
        StringBuilder sb = new StringBuilder(len);
        sb.append(head);
        while (sb.length() < len - tail.length()) {
            sb.append('x');
        }
        sb.append(tail);
        return sb.toString();
    }

    // ==================== A 组 ====================

    private static void testNoTruncateWhenUnderBudget() {
        String content = "short content";
        String out = ToolResultBudget.apply(cfg(), "file_explorer", content);
        assertEq("A1 未超预算原样返回", content, out);
    }

    private static void testTruncateWhenOverBudget() {
        String content = longText(50000);
        String out = ToolResultBudget.apply(cfg(), "command", content);
        assertTrue("A2 超预算被截断", out.length() < content.length());
        assertTrue("A2 含截断标记", out.contains(ToolResultBudget.MARK));
    }

    private static void testResultNeverExceedsBudget() {
        ToolResultBudgetConfig c = cfg();
        for (String tool : new String[]{"file_explorer", "command", "web_fetch", "execute_sql", "unknown_tool"}) {
            int budget = c.resolveBudget(tool);
            String out = ToolResultBudget.apply(c, tool, longText(80000));
            assertTrue("A3 " + tool + " 结果长度 " + out.length() + " 未突破预算 " + budget,
                    out.length() <= budget);
        }
    }

    private static void testHeadAndTailPreserved() {
        String content = longText(60000);
        String out = ToolResultBudget.apply(cfg(), "command", content);
        assertTrue("A4 保留头部", out.startsWith("HEAD_START"));
        assertTrue("A4 保留尾部", out.endsWith("TAIL_END"));
    }

    private static void testNullAndEmpty() {
        assertEq("A5 null 返回 null", null, ToolResultBudget.apply(cfg(), "command", null));
        assertEq("A5 空串返回空串", "", ToolResultBudget.apply(cfg(), "command", ""));
    }

    // ==================== B 组（幂等） ====================

    private static void testIdempotentOnSecondPass() {
        String content = longText(50000);
        String once = ToolResultBudget.apply(cfg(), "command", content);
        String twice = ToolResultBudget.apply(cfg(), "command", once);
        String thrice = ToolResultBudget.apply(cfg(), "command", twice);
        assertEq("B1 二次截断结果不变", once, twice);
        assertEq("B1 三次截断结果不变", once, thrice);
    }

    private static void testSkipWhenMarkerPresent() {
        String fake = "abc" + ToolResultBudget.MARK + "def";
        String out = ToolResultBudget.apply(cfg(), "command", fake);
        assertEq("B2 已带标记的内容原样返回", fake, out);
    }

    private static void testIsTruncatedHelper() {
        String content = longText(50000);
        String out = ToolResultBudget.apply(cfg(), "command", content);
        assertTrue("B3 截断后 isTruncated=true", ToolResultBudget.isTruncated(out));
        assertTrue("B3 原文 isTruncated=false", !ToolResultBudget.isTruncated(content));
        assertTrue("B3 null isTruncated=false", !ToolResultBudget.isTruncated(null));
    }

    // ==================== C 组 ====================

    private static void testPerToolBudget() {
        String content = longText(30000);
        String fileOut = ToolResultBudget.apply(cfg(), "file_explorer", content);
        String cmdOut = ToolResultBudget.apply(cfg(), "command", content);
        // 同样 30000 字符原文：file_explorer 预算 16000 > command 预算 10000
        assertTrue("C1 file_explorer 输出更长（预算更宽）", fileOut.length() > cmdOut.length());
        assertTrue("C1 file_explorer 不超过 16000", fileOut.length() <= 16000);
        assertTrue("C1 command 不超过 10000", cmdOut.length() <= 10000);
    }

    private static void testUnknownToolUsesDefault() {
        String out = ToolResultBudget.apply(cfg(), "some_new_tool", longText(30000));
        assertTrue("C2 未配置工具用默认预算 12000", out.length() <= 12000);
    }

    private static void testNonPositiveBudgetMeansUnlimited() {
        ToolResultBudgetConfig c = cfg();
        c.getTools().put("keep_all", 0);
        String content = longText(50000);
        assertEq("C3 预算=0 表示不限制", content, ToolResultBudget.apply(c, "keep_all", content));

        c.getTools().put("keep_all_neg", -1);
        assertEq("C3 预算<0 表示不限制", content, ToolResultBudget.apply(c, "keep_all_neg", content));
    }

    private static void testBudgetLookupCaseInsensitive() {
        String out = ToolResultBudget.apply(cfg(), "FILE_EXPLORER", longText(30000));
        assertTrue("C4 工具名大小写不敏感命中 16000 预算", out.length() <= 16000 && out.length() > 10000);
    }

    // ==================== D 组 ====================

    private static void testGuideForFileExplorer() {
        String out = ToolResultBudget.apply(cfg(), "file_explorer", longText(30000));
        assertTrue("D1 file_explorer 指引含分段读取参数",
                out.contains("start_line") && out.contains("end_line"));
    }

    private static void testGuideForCommand() {
        String out = ToolResultBudget.apply(cfg(), "command", longText(30000));
        assertTrue("D2 command 指引含 logs 回看", out.contains("action=\"logs\""));
    }

    private static void testGuideForSql() {
        String out = ToolResultBudget.apply(cfg(), "execute_sql", longText(30000));
        assertTrue("D3 execute_sql 指引含 LIMIT 建议",
                out.contains("LIMIT") || out.contains("WHERE"));
    }

    private static void testGuideDisabled() {
        ToolResultBudgetConfig c = cfg();
        c.setIncludeGuide(false);
        String out = ToolResultBudget.apply(c, "command", longText(30000));
        assertTrue("D4 关闭指引后不含续读文案", !out.contains("续读指引"));
    }

    // ==================== E 组 ====================

    private static void testDisabledSwitch() {
        ToolResultBudgetConfig c = cfg();
        c.setEnabled(false);
        String content = longText(50000);
        assertEq("E1 总开关关闭原样返回", content, ToolResultBudget.apply(c, "command", content));
    }

    private static void testHeadRatioClamped() {
        ToolResultBudgetConfig c = cfg();
        c.setHeadRatio(0.01);
        assertTrue("E2 headRatio 下限夹紧到 0.2", Math.abs(c.resolvedHeadRatio() - 0.2) < 1e-9);
        c.setHeadRatio(5.0);
        assertTrue("E2 headRatio 上限夹紧到 0.9", Math.abs(c.resolvedHeadRatio() - 0.9) < 1e-9);
    }

    private static void testTinyBudget() {
        ToolResultBudgetConfig c = cfg();
        c.getTools().put("tiny", 300);
        String out = ToolResultBudget.apply(c, "tiny", longText(5000));
        assertTrue("E3 极小预算不抛异常且结果非空", out != null && !out.isEmpty());
        assertTrue("E3 极小预算仍带标记（保证幂等）", out.contains(ToolResultBudget.MARK));
    }

    // ==================== 断言 ====================

    private static void assertEq(String name, Object expected, Object actual) {
        boolean ok = (expected == null && actual == null)
                || (expected != null && expected.equals(actual));
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            String e = String.valueOf(expected);
            String a = String.valueOf(actual);
            System.out.println("  ❌ " + name + "\n     期望: " + abbreviate(e) + "\n     实际: " + abbreviate(a));
        }
    }

    private static void assertTrue(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name);
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…(" + s.length() + ")" : s;
    }
}
