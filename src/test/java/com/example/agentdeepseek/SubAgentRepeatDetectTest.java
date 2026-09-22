package com.example.agentdeepseek;

import com.example.agentdeepseek.service.impl.ToolLoopManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子Agent重复调用检测 — 回归测试
 * <p>
 * 背景（2026-09-22 F3 诊断）：AgentForkManager 原判据仅比对「第一个工具调用的工具名」，
 * 连续读取不同文件等正常批处理（每轮首个工具都是 file_explorer）会被误判为死循环并静默终止。
 * 修复后 AgentForkManager 委托 ToolLoopManager 的「相同工具 + 相同关键参数」判据；
 * 本测试直接验证该判据在典型场景下的行为（纯逻辑，无需 Spring 上下文）。
 * </p>
 */
public class SubAgentRepeatDetectTest {

    static final ObjectMapper mapper = new ObjectMapper();
    /** 构造纯逻辑实例（重复检测相关方法不依赖构造参数） */
    static final ToolLoopManager manager = new ToolLoopManager(null, null, null);

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  子Agent重复调用检测 — 回归测试");
        System.out.println("  被测：ToolLoopManager.hasRepeatedCalls（修复后 fork 循环委托的同一实现）");
        System.out.println("═══════════════════════════════════════════\n");

        // ========== 核心回归：修复前被误杀的场景 ==========

        test("T1（核心回归）连续 4 轮读【不同文件】→ 不应判定为重复", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/A1.java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/A2.java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/A3.java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/A4.java\"}"));
            assertFalse(manager.hasRepeatedCalls(messages, 4),
                    "连续读 4 个不同文件属正常批处理（修复前会被误判击毙）");
        });

        test("T2 连续 4 轮【同工具+同参数】（真死循环）→ 判定为重复", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/Same.java\"}"));
            }
            assertTrue(manager.hasRepeatedCalls(messages, 4),
                    "同文件同参数连续 4 轮是死循环特征，应保留检测能力");
        });

        test("T3 连续 4 轮 grep【不同关键词】（同目录）→ 不应判定为重复", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"keyword1\",\"path\":\"src/main\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"keyword2\",\"path\":\"src/main\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"keyword3\",\"path\":\"src/main\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"keyword4\",\"path\":\"src/main\"}"));
            assertFalse(manager.hasRepeatedCalls(messages, 4),
                    "连续搜索不同关键词属正常研究行为（sub-fe-git 案发序列同型）");
        });

        test("T4 连续 4 轮 grep【相同关键词】（同目录）→ 判定为重复", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"same_kw\",\"path\":\"src/main\"}"));
            }
            assertTrue(manager.hasRepeatedCalls(messages, 4),
                    "相同关键词连续搜索是死循环特征，应保留检测能力");
        });

        // ========== 阈值与模式边界 ==========

        test("T5 仅 3 轮相同（阈值 4）→ 不应触发", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/Same.java\"}"));
            }
            assertFalse(manager.hasRepeatedCalls(messages, 4), "3 轮 < 阈值 4，不应触发");
        });

        test("T6 交错调用（A/B/A/B）→ 不应判定为重复", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/A.java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/B.java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/A.java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/B.java\"}"));
            assertFalse(manager.hasRepeatedCalls(messages, 4), "非连续相同不应触发");
        });

        test("T7 多工具轮：首工具相同、第二工具不同 → 不应判定为重复", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(assistantMsg(
                    "file_explorer|{\"action\":\"read\",\"file_path\":\"src/A.java\"}",
                    "file_writer|{\"file_path\":\"src/B1.java\"}"));
            messages.add(assistantMsg(
                    "file_explorer|{\"action\":\"read\",\"file_path\":\"src/A.java\"}",
                    "file_writer|{\"file_path\":\"src/B2.java\"}"));
            messages.add(assistantMsg(
                    "file_explorer|{\"action\":\"read\",\"file_path\":\"src/A.java\"}",
                    "file_writer|{\"file_path\":\"src/B3.java\"}"));
            messages.add(assistantMsg(
                    "file_explorer|{\"action\":\"read\",\"file_path\":\"src/A.java\"}",
                    "file_writer|{\"file_path\":\"src/B4.java\"}"));
            assertFalse(manager.hasRepeatedCalls(messages, 4),
                    "修复前只看首工具名会误判；修复后全量签名包含第二工具差异，不应触发");
        });

        test("T8 案发序列模拟（write→grep 连续段）→ 不应判定为重复", () -> {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(assistantMsg("file_writer|{\"file_path\":\"frontend/src/api/git.ts\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"GitQueryTool\",\"path\":\"src/main/java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"提交历史\",\"path\":\"src/main/java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"show\",\"path\":\"src/main/java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"read\",\"file_path\":\"src/main/java/GitQueryTool.java\"}"));
            messages.add(assistantMsg("file_explorer|{\"action\":\"grep\",\"pattern\":\"firstLines\",\"path\":\"frontend/src\"}"));
            assertFalse(manager.hasRepeatedCalls(messages, 4),
                    "sub-fe-git 死前调用序列（修复前被击毙的现场）不应触发重复判定");
        });

        // ========== 总结 ==========
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

    /**
     * 构造一条含 tool_calls 的 assistant 消息
     *
     * @param toolCallSpecs 每个元素格式 "工具名|参数JSON"
     */
    static Map<String, Object> assistantMsg(String... toolCallSpecs) throws Exception {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");
        ArrayNode calls = mapper.createArrayNode();
        int i = 0;
        for (String spec : toolCallSpecs) {
            int sep = spec.indexOf('|');
            String name = sep >= 0 ? spec.substring(0, sep) : spec;
            String arguments = sep >= 0 ? spec.substring(sep + 1) : "{}";
            ObjectNode call = mapper.createObjectNode();
            call.put("id", "call_" + (i++));
            ObjectNode fn = call.putObject("function");
            fn.put("name", name);
            fn.put("arguments", arguments);
            calls.add(call);
        }
        msg.put("tool_calls", calls);
        return msg;
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
            e.printStackTrace();
        }
    }

    static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError(msg + " — 期望 true，实际 false");
        }
    }

    static void assertFalse(boolean condition, String msg) {
        if (condition) {
            throw new AssertionError(msg + " — 期望 false，实际 true（被误判为重复调用）");
        }
    }

    interface CheckedRunnable {
        void run() throws Exception;
    }
}
