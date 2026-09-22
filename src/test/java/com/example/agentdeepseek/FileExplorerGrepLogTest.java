package com.example.agentdeepseek;

import com.example.agentdeepseek.tool.impl.FileExplorerTool;
import com.example.agentdeepseek.util.ProjectRootContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * file_explorer grep 文本白名单 — 回归测试
 * <p>
 * 背景（2026-09-22 排查）：
 * 旧版 searchGrep 对「有扩展名但不在 TEXT_EXTENSIONS 白名单」的文件静默跳过，
 * 且显式传入 include 也无法绕过 —— .log 等文件永远搜不到（与文件大小无关，
 * 372KB 的小日志同样搜不到）。
 * 修复目标：①白名单补充 .log/.csv/.mjs 等常见文本类型；②显式 include 时绕过白名单（逃生通道）。
 * <p>
 * 运行方式（纯逻辑，无需 Spring 上下文）：
 * mvn test-compile 后：java -cp target/classes;target/test-classes;<cp> com.example.agentdeepseek.FileExplorerGrepLogTest
 */
public class FileExplorerGrepLogTest {

    static final Pattern COUNT = Pattern.compile("匹配结果：共 (\\d+) 个");
    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        ProjectRootContext.set(System.getProperty("user.dir"));
        FileExplorerTool tool = new FileExplorerTool();

        System.out.println("═══════════════════════════════════════════");
        System.out.println("  file_explorer grep 白名单 — 回归测试");
        System.out.println("═══════════════════════════════════════════\n");

        // T1 核心：修复前 0 命中（.log 被静默跳过），修复后应命中
        test("T1（核心）搜索 .log 内容：'2026-09-16' → logs/ 应命中", () -> {
            String out = tool.execute(args("grep", "pattern", "2026-09-16", "path", "logs"));
            int n = extractCount(out);
            assertTrue(n > 0, "修复后应能搜到 .log 内容（修复前为 0）", out);
        });

        // T2 小体积日志（372KB）：证明与文件大小无关
        test("T2 小日志 app.2026-09-09.0.log 搜 '2026-09-09'（include 指定）→ 应命中", () -> {
            String out = tool.execute(args("grep", "pattern", "2026-09-09", "path", "logs", "include", "app.2026-09-09.0.log"));
            int n = extractCount(out);
            assertTrue(n > 0, "372KB 小日志也应可搜索", out);
        });

        // T3 另一个轮转日志
        test("T3 app.2026-09-15.0.log 搜 '2026-09-15' → 应命中", () -> {
            String out = tool.execute(args("grep", "pattern", "2026-09-15", "path", "logs"));
            int n = extractCount(out);
            assertTrue(n > 0, "应能搜到第二个轮转日志", out);
        });

        // T4 逃生通道：白名单外类型（.xyz）+ 显式 include 应可搜索
        test("T4 逃生通道：include='*.xyz' 显式指定白名单外类型 → 应命中", () -> {
            Path tmp = Files.createTempDirectory("grep-escape-test");
            try {
                Files.writeString(tmp.resolve("sample.xyz"), "ESCAPE_TOKEN_12345\nsecond line\n");
                String out = tool.execute(args("grep", "pattern", "ESCAPE_TOKEN",
                        "path", tmp.toString().replace('\\', '/'), "include", "*.xyz"));
                int n = extractCount(out);
                assertTrue(n > 0, "显式 include 应绕过默认白名单", out);
            } finally {
                deleteRecursively(tmp);
            }
        });

        // T5 边界：二进制扩展名仍被拦截（即使显式 include）——安全边界未被破坏
        test("T5 边界：include='*.png' 二进制类型仍被跳过（安全边界）", () -> {
            Path tmp = Files.createTempDirectory("grep-binary-test");
            try {
                Files.writeString(tmp.resolve("fake.png"), "BINARY_TOKEN_999\n");
                String out = tool.execute(args("grep", "pattern", "BINARY_TOKEN",
                        "path", tmp.toString().replace('\\', '/'), "include", "*.png"));
                int n = extractCount(out);
                assertTrue(n == 0, "二进制扩展名应保持跳过", out);
            } finally {
                deleteRecursively(tmp);
            }
        });

        // T6 回归：白名单内文件（.md）正常可搜
        test("T6 回归：.md 白名单内正常搜索（'重复检测' in .planning）", () -> {
            String out = tool.execute(args("grep", "pattern", "重复检测", "path", ".planning/2026-09-21-subagent-early-stop"));
            int n = extractCount(out);
            assertTrue(n > 0, "白名单内类型不受本次修改影响", out);
        });

        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  测试完成: " + (passed + failed) + " 项");
        if (failed > 0) {
            System.out.println("  ✅ 通过: " + passed + " 项");
            System.out.println("  ❌ 失败: " + failed + " 项");
            System.exit(1);
        } else {
            System.out.println("  ✅ 全部 " + passed + " 项通过!");
        }
        System.out.println("═══════════════════════════════════════════");
    }

    // ==================== 辅助方法 ====================

    /** 构造 action + 若干 k/v 参数的 JsonNode */
    static com.fasterxml.jackson.databind.JsonNode args(String action, String... kv) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put("action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            node.put(kv[i], kv[i + 1]);
        }
        return node;
    }

    /** 从输出中提取「匹配结果：共 N 个」的 N；无法解析返回 -1 */
    static int extractCount(String out) {
        Matcher m = COUNT.matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static void test(String name, CheckedRunnable fn) {
        System.out.print("  [测试] " + name + " ... ");
        try {
            fn.run();
            passed++;
            System.out.println("✅ 通过");
        } catch (AssertionError e) {
            failed++;
            System.out.println("❌ 失败: " + e.getMessage());
        } catch (Exception e) {
            failed++;
            System.out.println("💥 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    static void assertTrue(boolean condition, String msg, String context) {
        if (!condition) {
            throw new AssertionError(msg + "\n      输出摘要: " + summarize(context));
        }
    }

    static String summarize(String out) {
        if (out == null) return "(null)";
        String s = out.replace("\n", " ⏎ ");
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    interface CheckedRunnable {
        void run() throws Exception;
    }
}
