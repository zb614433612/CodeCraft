package com.example.agentdeepseek.tool.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * grep 结果渲染单元测试（main 方法式，无需 JUnit 容器）
 *
 * <h3>背景：两个真实缺陷</h3>
 * <ol>
 *   <li><b>行号 Bug</b>：旧实现给 {@code contextBefore} / {@code contextAfter} 的每一行
 *       都写同一个行号（{@code r.lineNum - size} / {@code r.lineNum + 1}），
 *       输出形如「5 - A / 5 - B / 5 - C」。模型按行号去读文件会读到错的位置，
 *       而输出看起来又像「内容重复」，掩盖了真实原因。</li>
 *   <li><b>窗口重叠</b>：每个命中都带 {@code context_lines} 行上下文，
 *       命中密集时相邻窗口大量重叠，同一行被重复输出 2~5 次。
 *       实测一条 2 万字符的 grep 结果里超过一半是这种重复（纯烧 cache-miss token）。</li>
 * </ol>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn test-compile -DskipTests
 * mvn exec:java "-Dexec.mainClass=com.example.agentdeepseek.tool.impl.GrepMergeTest" "-Dexec.classpathScope=test"
 * </pre>
 */
public class GrepMergeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== GrepMergeTest 开始 ===");

        // A 组：行号正确性（修复旧 Bug）
        testContextLineNumbersIncrement();
        testBeforeTruncatedAtFileStart();
        testAfterTruncatedAtFileEnd();

        // B 组：上下文窗口合并
        testOverlappingWindowsMerged();
        testAdjacentWindowsMerged();
        testGapMarkerInserted();

        // C 组：匹配行标记
        testMatchMarkedWithGreaterThan();
        testMatchNotOverwrittenByContext();

        // D 组：多文件
        testMultipleFilesGrouped();

        // E 组：边界
        testEmptyAndNull();

        System.out.println("=== 结束：通过 " + passed + "，失败 " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 构造辅助 ====================

    private static FileExplorerTool.GrepHit hit(String file, int line, String content,
                                                List<String> before, List<String> after) {
        return new FileExplorerTool.GrepHit(file, line, content, before, after);
    }

    private static List<String> lines(String... values) {
        List<String> list = new ArrayList<>();
        for (String v : values) list.add(v);
        return list;
    }

    /** 提取输出里所有「行号 -/> 内容」行的行号（按出现顺序） */
    private static List<Integer> lineNumbers(String out) {
        List<Integer> nums = new ArrayList<>();
        for (String raw : out.split("\n")) {
            String s = raw.trim();
            if (!s.matches("^\\d+\\s+[>-]\\s.*")) continue;
            int sp = s.indexOf(' ');
            nums.add(Integer.parseInt(s.substring(0, sp)));
        }
        return nums;
    }

    /** 某行号是否被渲染为匹配行（>） */
    private static boolean isMatchLine(String out, int lineNum) {
        return out.contains(String.format("  %5d > ", lineNum));
    }

    /** 某行号是否被渲染为上下文行（-） */
    private static boolean isContextLine(String out, int lineNum) {
        return out.contains(String.format("  %5d - ", lineNum));
    }

    /** 某文本在输出中出现的次数 */
    private static int countOccurrences(String out, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = out.indexOf(target, idx)) >= 0) {
            count++;
            idx += target.length();
        }
        return count;
    }

    // ==================== A 组：行号正确性 ====================

    private static void testContextLineNumbersIncrement() {
        // 匹配行 10，上文 3 行（7,8,9），下文 2 行（11,12）
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 10, "match", lines("b1", "b2", "b3"), lines("a1", "a2"))));
        List<Integer> nums = lineNumbers(out);
        boolean ok = nums.equals(List.of(7, 8, 9, 10, 11, 12));
        assertTrue("A1 行号逐行递增 7..12（旧实现会全写成 7/10/11）: " + nums, ok);
    }

    private static void testBeforeTruncatedAtFileStart() {
        // 匹配行 2，文件开头上文只有 1 行（行 1）
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 2, "match", lines("first"), lines("n3"))));
        List<Integer> nums = lineNumbers(out);
        assertTrue("A2 开头行号为 1,2,3: " + nums, nums.equals(List.of(1, 2, 3)));
    }

    private static void testAfterTruncatedAtFileEnd() {
        // 匹配行 100，下文只有 1 行（101）
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 100, "match", lines("b98", "b99"), lines("tail"))));
        List<Integer> nums = lineNumbers(out);
        assertTrue("A3 末尾行号为 98,99,100,101: " + nums, nums.equals(List.of(98, 99, 100, 101)));
    }

    // ==================== B 组：窗口合并 ====================

    private static void testOverlappingWindowsMerged() {
        // 命中 10（上下文 8~12）、命中 12（上下文 10~14）：10~12 区间重叠
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 10, "m10", lines("l8", "l9"), lines("l11", "l12")),
                hit("A.java", 12, "m12", lines("l10", "l11"), lines("l13", "l14"))));
        List<Integer> nums = lineNumbers(out);
        // 合并后应为 8..14 连续 7 行，无重复
        assertTrue("B1 重叠窗口合并为 8..14: " + nums, nums.equals(List.of(8, 9, 10, 11, 12, 13, 14)));
        assertTrue("B1 行 11 只出现一次", countOccurrences(out, String.format("  %5d ", 11)) == 1);
    }

    private static void testAdjacentWindowsMerged() {
        // 命中 10（下文到 11）、命中 12（上文从 11 开始）：11 是交界行，只应出现一次
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 10, "m10", lines(), lines("l11")),
                hit("A.java", 12, "m12", lines("l11"), lines())));
        List<Integer> nums = lineNumbers(out);
        assertTrue("B2 相邻窗口合并为 10,11,12: " + nums, nums.equals(List.of(10, 11, 12)));
        assertTrue("B2 交界行 11 只出现一次", countOccurrences(out, String.format("  %5d ", 11)) == 1);
    }

    private static void testGapMarkerInserted() {
        // 命中 5（下文到 6）、命中 50（上文从 48 开始）：中间断开应插 ...
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 5, "m5", lines(), lines("l6")),
                hit("A.java", 50, "m50", lines("l48", "l49"), lines())));
        assertTrue("B3 不连续处插入省略标记", out.contains("    ..."));
        List<Integer> nums = lineNumbers(out);
        assertTrue("B3 行号为 5,6,48,49,50: " + nums, nums.equals(List.of(5, 6, 48, 49, 50)));
    }

    // ==================== C 组：匹配行标记 ====================

    private static void testMatchMarkedWithGreaterThan() {
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 10, "matchLine", lines("ctx"), lines("ctx2"))));
        assertTrue("C1 匹配行用 > 标记", isMatchLine(out, 10));
        assertTrue("C1 上文用 - 标记", isContextLine(out, 9));
        assertTrue("C1 下文用 - 标记", isContextLine(out, 11));
    }

    private static void testMatchNotOverwrittenByContext() {
        // 命中 10 的下文包含 12；随后命中 12 自己也是匹配行 → 12 必须渲染为匹配行
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 10, "m10", lines(), lines("l11", "l12")),
                hit("A.java", 12, "m12", lines("l10", "l11"), lines())));
        assertTrue("C2 被先前上下文覆盖的行仍标记为匹配行", isMatchLine(out, 12));
        assertTrue("C2 行 12 不会同时出现上下文形态", !isContextLine(out, 12));
    }

    // ==================== D 组：多文件 ====================

    private static void testMultipleFilesGrouped() {
        String out = FileExplorerTool.renderGrepHits(List.of(
                hit("A.java", 3, "a3", lines("a2"), lines("a4")),
                hit("B.java", 7, "b7", lines("b6"), lines("b8"))));
        assertTrue("D1 A.java 标题存在", out.contains("A.java\n"));
        assertTrue("D1 B.java 标题存在", out.contains("B.java\n"));
        assertTrue("D1 每个文件各自输出匹配计数", out.contains("... 1 处匹配"));
        // 两个文件的行块互不干扰
        List<Integer> nums = lineNumbers(out);
        assertTrue("D1 行号序列 2,3,4,6,7,8: " + nums, nums.equals(List.of(2, 3, 4, 6, 7, 8)));
    }

    // ==================== E 组：边界 ====================

    private static void testEmptyAndNull() {
        assertEq("E1 空列表返回空串", "", FileExplorerTool.renderGrepHits(List.of()));
        assertEq("E1 null 返回空串", "", FileExplorerTool.renderGrepHits(null));
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
            System.out.println("  ❌ " + name + "\n     期望: [" + expected + "]\n     实际: [" + actual + "]");
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
}
