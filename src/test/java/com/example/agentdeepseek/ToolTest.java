package com.example.agentdeepseek;

import com.example.agentdeepseek.tool.impl.RunCommandTool;
import com.example.agentdeepseek.tool.impl.RunServerTool;
import com.example.agentdeepseek.tool.impl.ServiceControlTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具集成测试 — 直接测试三个新工具（run_command / run_server / service_control）
 * <p>
 * 无需 Spring 上下文，工具类在 "auto" 模式下可直接实例化测试。
 */
public class ToolTest {

    static final ObjectMapper mapper = new ObjectMapper();
    static final RunCommandTool runCmd = new RunCommandTool(mapper);
    static final RunServerTool runSrv = new RunServerTool(mapper);
    static final ServiceControlTool svcCtrl = new ServiceControlTool(mapper);

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  zb-agent 工具集成测试");
        System.out.println("  测试工具: run_command / run_server / service_control");
        System.out.println("═══════════════════════════════════════════\n");

        // ========== run_command 测试 ==========
        test("T1.1 run_command: command 字符串模式", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("command", "echo HelloWorld");
            String result = runCmd.execute(params);
            assertContains(result, "HelloWorld", "应包含命令输出");
            assertContains(result, "退出码：0", "应包含退出码 0");
        });

        test("T1.2 run_command: executable+args 模式", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("executable", "cmd.exe");
            try {
                params.set("args", mapper.readTree("[\"/c\", \"echo\", \"StructArgTest\"]"));
            } catch (Exception e) {
                throw new RuntimeException("JSON解析失败", e);
            }
            String result = runCmd.execute(params);
            assertContains(result, "StructArgTest", "结构参数模式应正常执行");
        });

        test("T1.3 run_command: 工作目录指定", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("command", "echo WorkingDirTest");
            params.put("cwd", ".");
            String result = runCmd.execute(params);
            assertContains(result, "WorkingDirTest", "指定工作目录应正常执行");
        });

        test("T1.4 run_command: 命令超时 - 快速命令不超时", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("command", "echo FastCommand");
            params.put("timeout", 5);
            String result = runCmd.execute(params);
            assertContains(result, "FastCommand", "5秒超时应足够");
        });

        test("T1.5 run_command: 命令未找到应返回友好提示", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("command", "nonexistent_cmd_xyz");
            String result = runCmd.execute(params);
            assertContains(result, "未找到", "应提示命令未找到");
        });

        test("T1.6 run_command: 无参数应返回错误", () -> {
            ObjectNode params = mapper.createObjectNode();
            String result = runCmd.execute(params);
            assertContains(result, "错误", "空参数应返回错误提示");
        });

        // ========== run_server 测试 ==========
        test("T2.1 run_server: 启动后台进程（持续10秒以保证后续测试可读取）", () -> {
            String procCmd = isWindows()
                    ? "cmd.exe /c echo ServerStartTest && echo PORT:8899 && timeout /t 10"
                    : "sh -c 'echo ServerStartTest && echo PORT:8899 && sleep 10'";
            ObjectNode params = mapper.createObjectNode();
            params.put("command", procCmd);
            String result = runSrv.execute(params);
            assertContains(result, "后台服务已启动", "应显示启动成功");
            assertContains(result, "服务 ID：1", "第一个服务ID应为1");
            assertContains(result, "8899", "应检测到端口 8899");
        });

        test("T2.2 run_server: 缺少 command 参数应报错", () -> {
            ObjectNode params = mapper.createObjectNode();
            String result = runSrv.execute(params);
            assertContains(result, "错误", "缺少命令应返回错误");
        });

        // ========== service_control 测试 ==========
        test("T3.1 service_control: action=list 列出服务", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "list");
            String result = svcCtrl.execute(params);
            assertContains(result, "后台服务列表", "应显示服务列表");
            assertContains(result, "#1", "应列出服务 #1");
        });

        test("T3.2 service_control: action=logs 查看日志（等待输出就绪）", () -> {
            // 等待输出缓冲就绪
            for (int i = 0; i < 10; i++) {
                String output = RunServerTool.readServiceOutput(1, 0);
                if (output != null && !output.contains("暂无输出")) break;
                try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
            }
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "logs");
            params.put("service_id", 1);
            String result = svcCtrl.execute(params);
            assertContains(result, "服务 #1 输出", "应显示服务 #1 的输出");
            assertContains(result, "ServerStartTest", "输出应包含服务启动时的命令输出");
        });

        test("T3.3 service_control: action=logs tail=5", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "logs");
            params.put("service_id", 1);
            params.put("tail", 5);
            String result = svcCtrl.execute(params);
            assertContains(result, "服务 #1 输出", "应显示服务 #1 的输出");
        });

        test("T3.4 service_control: action=logs 缺少 service_id", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "logs");
            String result = svcCtrl.execute(params);
            assertContains(result, "错误", "缺少 service_id 应报错");
        });

        test("T3.5 service_control: action=stop 停止服务", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "stop");
            params.put("service_id", 1);
            params.put("force", true);
            String result = svcCtrl.execute(params);
            assertContains(result, "已停止", "应显示已停止");
            assertContains(result, "服务 #1", "应引用服务 #1");
        });

        test("T3.6 service_control: action=stop 后再次 list 确认移除", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "list");
            String result = svcCtrl.execute(params);
            // 此时 #1 应该已被清理，列表可能为空
            if (!result.contains("暂无后台服务")) {
                assertContains(result, "已结束", "被停止的服务应标记为已结束或已清理");
            }
        });

        test("T3.7 service_control: action=stop 无效服务ID", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "stop");
            params.put("service_id", 999);
            String result = svcCtrl.execute(params);
            assertContains(result, "错误", "无效 service_id 应报错");
        });

        test("T3.8 service_control: 未知 action 应报错", () -> {
            ObjectNode params = mapper.createObjectNode();
            params.put("action", "unknown_action");
            String result = svcCtrl.execute(params);
            assertContains(result, "错误", "未知 action 应报错");
        });

        test("T3.9 service_control: 缺少 action 参数", () -> {
            ObjectNode params = mapper.createObjectNode();
            String result = svcCtrl.execute(params);
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
