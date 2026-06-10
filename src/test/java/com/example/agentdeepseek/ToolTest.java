package com.example.agentdeepseek;

import com.example.agentdeepseek.tool.impl.CommandTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具集成测试 — 测试 command 工具（合并 run_command / run_server / service_control）
 * 无需 Spring 上下文，工具类在 "auto" 模式下可直接实例化测试。
 */
public class ToolTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final CommandTool command = new CommandTool(mapper);

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  zb-agent 工具集成测试");
        System.out.println("  测试工具: command (exec / start / list / logs / stop)");
        System.out.println("═══════════════════════════════════════════\n");

        // ========== action=exec 测试 ==========
        test("T1.1 command exec: 基本命令执行", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "exec");
            params.put("command", isWindows() ? "cmd.exe /c echo HelloWorld" : "echo HelloWorld");
            String result = command.execute(params);
            assertContains(result, "HelloWorld", "应包含命令输出");
            assertContains(result, "退出码：0", "应包含退出码 0");
        });

        test("T1.2 command exec: 指定工作目录", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "exec");
            params.put("command", isWindows() ? "cmd.exe /c echo WorkingDirTest" : "echo WorkingDirTest");
            params.put("cwd", ".");
            String result = command.execute(params);
            assertContains(result, "WorkingDirTest", "指定工作目录应正常执行");
        });

        test("T1.3 command exec: 自定义超时", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "exec");
            params.put("command", isWindows() ? "cmd.exe /c echo FastCommand" : "echo FastCommand");
            params.put("timeout", 5);
            String result = command.execute(params);
            assertContains(result, "FastCommand", "5秒超时应足够");
        });

        test("T1.4 command exec: 命令未找到", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "exec");
            params.put("command", "nonexistent_cmd_xyz");
            String result = command.execute(params);
            assertContains(result, "未找到", "应提示命令未找到");
        });

        test("T1.5 command exec: 缺少 command 参数", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "exec");
            String result = command.execute(params);
            assertContains(result, "参数缺失", "应返回参数缺失提示");
        });

        // ========== action=start 测试 ==========
        test("T2.1 command start: 启动后台进程（持续10秒保证后续测试可读取）", () -> {
            String procCmd = isWindows()
                    ? "cmd.exe /c echo ServerStartTest && echo PORT:8899 && timeout /t 10"
                    : "sh -c 'echo ServerStartTest && echo PORT:8899 && sleep 10'";
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "start");
            params.put("command", procCmd);
            String result = command.execute(params);
            assertContains(result, "后台服务已启动", "应显示启动成功");
            assertContains(result, "服务 ID：", "应显示服务ID");
            assertContains(result, "8899", "应检测到端口 8899");
        });

        test("T2.2 command start: 缺少 command 参数", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "start");
            String result = command.execute(params);
            assertContains(result, "参数缺失", "缺少命令应返回错误");
        });

        // ========== action=list 测试 ==========
        test("T3.1 command list: 列出所有服务", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "list");
            String result = command.execute(params);
            assertContains(result, "后台服务列表", "应显示服务列表");
        });

        // ========== action=logs 测试 ==========
        test("T3.2 command logs: 查看服务日志", () -> {
            // 等待输出缓冲就绪
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "logs");
            params.put("service_id", 1);
            String result = command.execute(params);
            assertContains(result, "服务 #1 输出", "应显示服务 #1 的输出");
            assertContains(result, "ServerStartTest", "输出应包含服务启动文本");
        });

        test("T3.3 command logs: 带 tail 参数", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "logs");
            params.put("service_id", 1);
            params.put("tail", 5);
            String result = command.execute(params);
            assertContains(result, "服务 #1 输出", "应显示服务 #1 的输出");
        });

        test("T3.4 command logs: 缺少 service_id", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "logs");
            String result = command.execute(params);
            assertContains(result, "参数缺失", "缺少 service_id 应报错");
        });

        // ========== action=stop 测试 ==========
        test("T3.5 command stop: 停止服务", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "stop");
            params.put("service_id", 1);
            params.put("force", true);
            String result = command.execute(params);
            assertContains(result, "已停止", "应显示已停止");
        });

        test("T3.6 command stop: 停止后再 list 确认", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "list");
            String result = command.execute(params);
            // 被停止的服务应已清理或标记为已结束
            if (!result.contains("（无后台服务）")) {
                assertContains(result, "已结束", "被停止的服务应标记为已结束或已清理");
            }
        });

        test("T3.7 command stop: 无效服务ID", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "stop");
            params.put("service_id", 999);
            String result = command.execute(params);
            assertContains(result, "服务不存在", "无效 service_id 应报错");
        });

        test("T3.8 command: 未知 action", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "unknown_action");
            String result = command.execute(params);
            assertContains(result, "错误", "未知 action 应报错");
        });

        test("T3.9 command: 缺少 action 参数", () -> {
            ObjectNode params = mapper.createObjectNode();
            String result = command.execute(params);
            assertContains(result, "错误", "缺少 action 应报错");
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

    static void test(String name, Runnable fn) {
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

    static void assertContains(String result, String expected, String msg) {
        if (result == null || !result.contains(expected)) {
            throw new AssertionError(msg + " — 期望包含「" + expected + "」，实际结果:\n" + result);
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
