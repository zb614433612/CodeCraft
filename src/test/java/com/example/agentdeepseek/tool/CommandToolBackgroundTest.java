package com.example.agentdeepseek.tool;

import com.example.agentdeepseek.tool.impl.CommandTool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;

/**
 * 长耗时命令后台化（exec 新执行模型）的纯逻辑测试（main 方法，不依赖 JUnit / Spring）。
 *
 * <p>覆盖四件事：</p>
 * <ol>
 *   <li>短命令：仍然直接返回完整输出（行为零变化）</li>
 *   <li>长命令：超过前台等待窗口 → 转后台返回任务 ID，且<b>进程不被杀</b></li>
 *   <li>去重：同一 cwd + 同一命令再次下发 → 拦截并提示已在运行</li>
 *   <li>wait：阻塞等待拿完整输出与退出码；list 能列出任务</li>
 * </ol>
 *
 * <p>运行方式：{@code mvn -q exec:java -Dexec.mainClass=com.example.agentdeepseek.tool.CommandToolBackgroundTest -Dexec.classpathScope=test}</p>
 */
public class CommandToolBackgroundTest {

    private static int passed = 0;
    private static int failed = 0;

    /** 前台等待窗口压到 2 秒：默认 20 秒会让测试跑太久 */
    private static final int TEST_WINDOW_SECONDS = 2;
    /** 长命令要跑 8 秒，确保一定超过 2 秒窗口 */
    private static final int LONG_COMMAND_SECONDS = 8;

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CommandTool tool = new CommandTool(mapper);
        setField(tool, "backgroundAfterSeconds", TEST_WINDOW_SECONDS);
        setField(tool, "backgroundAfterBuildSeconds", TEST_WINDOW_SECONDS);

        ProjectRootContext.set(System.getProperty("user.dir"));

        // ==================== 1. 短命令：行为零变化 ====================
        String shortResult = tool.execute(execArgs(mapper, "echo codecraft-short"));
        check("短命令：返回完整输出", shortResult.contains("codecraft-short"));
        check("短命令：带退出码 0", shortResult.contains("退出码：0"));
        check("短命令：不出现「已转后台」", !shortResult.contains("已转后台"));

        // ==================== 2. 长命令：转后台 ====================
        String longCmd = buildLongCommand();
        String longResult = tool.execute(execArgs(mapper, longCmd));
        check("长命令：提示已转后台", longResult.contains("已转后台"));
        check("长命令：带任务 ID", extractTaskId(longResult) > 0);
        check("长命令：不报超时失败", !longResult.contains("命令超时"));
        int taskId = extractTaskId(longResult);

        // ==================== 3. 去重：重复下发被拦截 ====================
        String dupResult = tool.execute(execArgs(mapper, longCmd));
        check("去重：拦截重复执行", dupResult.contains("已在运行"));
        check("去重：提示不要重复执行", dupResult.contains("不要重复执行"));

        // ==================== 4. wait：等到结束拿结果 ====================
        ObjectNode waitArgs = mapper.createObjectNode();
        waitArgs.put("action", "wait");
        waitArgs.put("service_id", taskId);
        waitArgs.put("timeout", 40);
        String waitResult = tool.execute(waitArgs);
        check("wait：拿到退出码 0", waitResult.contains("退出码：0"));
        check("wait：拿到命令最终输出", waitResult.contains("done-long"));
        check("wait：不再提示仍在运行", !waitResult.contains("仍在运行"));

        // ==================== 5. list：能列出任务 ====================
        ObjectNode listArgs = mapper.createObjectNode();
        listArgs.put("action", "list");
        String listResult = tool.execute(listArgs);
        check("list：列出任务标记", listResult.contains("[任务]"));
        check("list：任务已在列表中", listResult.contains("#" + taskId));

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 构造一条会跑 {@link #LONG_COMMAND_SECONDS} 秒的命令（跨平台） */
    private static String buildLongCommand() {
        if (isWindows()) {
            return "powershell -NoProfile -Command \"Start-Sleep -Seconds " + LONG_COMMAND_SECONDS
                    + "; Write-Output done-long\"";
        }
        return "sleep " + LONG_COMMAND_SECONDS + " && echo done-long";
    }

    private static ObjectNode execArgs(ObjectMapper mapper, String command) {
        ObjectNode args = mapper.createObjectNode();
        args.put("action", "exec");
        args.put("command", command);
        return args;
    }

    /** 从「任务 ID：7」里抠出 7 */
    private static int extractTaskId(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("任务 ID：(\\d+)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 反射设置 @Value 字段（非 Spring 环境下只能用反射覆盖默认值） */
    private static void setField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
