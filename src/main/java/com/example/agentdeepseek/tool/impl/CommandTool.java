package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.CommandUtils;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 命令执行工具 — 合并 run_command / run_server / service_control
 * 通过 action 参数区分操作：
 *   exec  — 阻塞执行一次性命令，返回完整输出
 *   start — 后台启动持续运行的服务，返回服务ID
 *   list  — 列出所有后台服务
 *   logs  — 查看服务日志
 *   stop  — 停止服务
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.EXECUTE, affectsData = true, highRisk = true, description = "命令执行与服务管理")
public class CommandTool implements Tool {

    // ============ exec 常量 ============
    private static final int MAX_OUTPUT_SIZE = 1 * 1024 * 1024;
    private static final int DEFAULT_TIMEOUT = 120;
    private static final int MAX_TIMEOUT = 600;
    private static final int ABSOLUTE_MAX_TIMEOUT = 900;

    // ============ start 常量 ============
    private static final int MAX_CONCURRENT = 5;
    private static final int MAX_OUTPUT_LINES = 10000;
    private static final int MAX_OUTPUT_BYTES = 5 * 1024 * 1024;
    private static final ConcurrentHashMap<Integer, ServiceInfo> SERVICES = new ConcurrentHashMap<>();
    private static final AtomicInteger SERVICE_ID_SEQ = new AtomicInteger(1);

    // ============ 长耗时命令后台化（exec 新执行模型） ============
    /**
     * 一次性长任务池上限（与服务池分开计数）。
     * <p>为什么要分开：一个 20 分钟的 mvn 不该占掉「长期服务」的槽位，
     * 否则用户想再起一个 npm run dev 会被拒。</p>
     */
    private static final int MAX_TASKS = 10;

    /** 已结束任务在池中的保留时长：结束后仍可用 logs/wait 回看结果，超时才真正移除 */
    private static final long TASK_RETAIN_MS = 30 * 60 * 1000L;

    /** wait 动作默认 / 最大等待秒数 */
    private static final int DEFAULT_WAIT_SECONDS = 300;
    private static final int MAX_WAIT_SECONDS = 600;

    /** 转后台提示里展示的输出行数 */
    private static final int BACKGROUND_TAIL_LINES = 15;
    /** logs 默认展示的尾部行数 */
    private static final int LOGS_DEFAULT_TAIL = 50;

    /**
     * 编译 / 依赖下载 / 构建类命令识别。
     * <p>命中后前台等待窗口缩短（默认 10s）：这类命令本来就要几十秒到几分钟，
     * 没必要让用户干等满 20 秒才转后台。</p>
     */
    private static final Pattern SLOW_COMMAND_PATTERN = Pattern.compile(
            "^\\s*(mvn|mvnw|gradle|gradlew|npm|pnpm|yarn|pip|pip3|poetry|pipenv|cargo|go|dotnet|docker|"
                    + "make|cmake|msbuild|composer|bundle|nuget|vcpkg|conda|brew)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 失败输出特征行（失败时优先回传这些行，而不是让模型在几万行里翻） */
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile(
            "(?i)(\\berror\\b|\\bfail(ed|ure)?\\b|\\bexception\\b|caused by|\\bcannot\\b|could not|"
                    + "not found|no such file|BUILD FAILURE|npm ERR|permission denied|connection refused|"
                    + "\\bat [\\w.$]+\\([\\w.]+\\.java:\\d+\\)|\\[ERROR\\])");

    /** 任务类型：长期运行的服务（start） */
    private static final String KIND_SERVICE = "service";
    /** 任务类型：一次性长任务（exec 转后台） */
    private static final String KIND_TASK = "task";

    /**
     * 前台最大等待窗口（秒）。超时后命令**不会被杀**，而是转后台任务继续跑。
     * <p>设为 0 或负数 = 关闭新执行模型，完全回退到旧的「同步阻塞直到超时被杀」行为（应急开关）。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${command.exec.background-after-seconds:20}")
    private int backgroundAfterSeconds = 20;

    /** 编译 / 依赖类命令的前台等待窗口（秒），比普通命令更短 */
    @org.springframework.beans.factory.annotation.Value("${command.exec.background-after-build-seconds:10}")
    private int backgroundAfterBuildSeconds = 10;
    private static final Pattern PORT_PATTERN = Pattern.compile(
            "(?:https?://|bind\\s+|port\\s+)(?:localhost|0\\.0\\.0\\.0|127\\.0\\.0\\.1)[:/]?(\\d{2,5})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL_NEEDED_PATTERN = Pattern.compile("[|&;<>$`'\"()]|\\b(cd|export|set\\s+\\w+=|source)\\b");

    private final ObjectMapper objectMapper;

    public CommandTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== Tool 接口 ====================

    @Override
    public String getName() { return "command"; }

    @Override
    public String getDescription() {
        return "【适用场景】命令执行与服务管理一站式工具，通过 action 参数选择操作模式。\n"
                + "⚠️ 调用示例：{\"action\":\"exec\",\"command\":\"mvn clean compile\"} | "
                + "{\"action\":\"wait\",\"service_id\":7} | {\"action\":\"start\",\"command\":\"npm run dev\"}\n"
                + "【action 说明】\n"
                + "  exec  — 执行命令。**长耗时命令不会失败、也不会被杀**：前台最多等 "
                + getBackgroundWindowHint() + " 秒，超时自动转为后台任务继续跑，并返回「任务 ID + 当前输出尾部」。\n"
                + "  wait  — 阻塞等待某个任务结束（默认 300s / 最大 600s）：结束后返回完整输出与退出码，未结束则返回尾部并可再次 wait。\n"
                + "  start — 后台启动**持续运行**的服务（npm run dev、mvn spring-boot:run 等），返回服务 ID。\n"
                + "  list  — 列出所有任务 / 服务及其状态（运行中 / 已结束）。\n"
                + "  logs  — 查看任务或服务的输出（默认尾部 50 行，可用 tail 调整，tail=0 取全量）。\n"
                + "  stop  — 停止任务或服务（默认强制终止整棵进程树）。\n"
                + "【长耗时命令的正确用法】编译 / 装依赖 / 下载 / 构建 这类命令：\n"
                + "  ① 正常调 exec 即可；超过等待窗口会自动转后台，返回里带「任务 ID」，这不是失败；\n"
                + "  ② 收到「已转后台」后**不要重复执行同一命令**（系统会拦截并提示已在运行，白等一轮）；\n"
                + "  ③ 想等结果：command(action=wait, service_id=<任务ID>, timeout=300)；\n"
                + "  ④ 想先干别的：直接继续其它工具调用，稍后用 command(action=logs, service_id=<任务ID>) 看进度。\n"
                + "【典型工作流】\n"
                + "  一次性命令：exec → （若转后台）wait / logs → stop（需要中止时）\n"
                + "  长期服务：start → list 确认运行 → logs 查看日志 → stop 停止服务\n"
                + "【注意事项】action 必填；同一工作目录 + 同一命令重复调用会被去重拦截；"
                + "长任务最多同时 " + MAX_TASKS + " 个、长期服务最多 " + MAX_CONCURRENT + " 个；"
                + "任务结束后输出保留 30 分钟供 logs 回看。";
    }

    /** 描述里的前台等待窗口提示（编译类命令更短，分档说明） */
    private String getBackgroundWindowHint() {
        if (backgroundAfterSeconds <= 0) {
            return "（当前已关闭后台化，按超时终止）";
        }
        return String.valueOf(backgroundAfterSeconds);
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        // === action ===
        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】操作类型。exec=执行命令（耗时超过阈值自动转后台，进程不会中断）；"
                + "wait=阻塞等待任务结束；start=后台启动长期服务；list=列出任务/服务；logs=查看输出；stop=停止任务/服务。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("exec").add("wait").add("start").add("list").add("logs").add("stop");

        // === exec/start 共用 ===
        ObjectNode cmd = props.putObject("command");
        cmd.put("type", "string");
        cmd.put("description", "【exec/start 时必填】要执行的命令字符串。支持管道|、重定向>、环境变量$VAR、命令链&& ||。"
                + "示例：\"mvn clean compile -DskipTests\"、\"npm run dev\"、\"node server.js\"");

        ObjectNode cwd = props.putObject("cwd");
        cwd.put("type", "string");
        cwd.put("description", "【exec/start 可选】工作目录，默认项目根。示例：\"frontend\"（前端子目录）。");

        // === exec/wait 专属 ===
        ObjectNode execTimeout = props.putObject("timeout");
        execTimeout.put("type", "integer");
        execTimeout.put("description", "【wait 可选】等待秒数，默认 300，最大 600。"
                + "注意：exec 已改为「超过等待窗口自动转后台、不杀进程」，此参数仅用于 wait。");

        // === start 专属 ===
        ObjectNode waitFor = props.putObject("wait_for");
        waitFor.put("type", "string");
        waitFor.put("description", "【start 可选】就绪检测方式。数字：等待固定秒数（如\"5\"）；URL：轮询HTTP 200（如\"http://localhost:8080\"）；关键字：等待输出中出现指定文本（如\"Started\"）。不设则立即返回。");

        // === logs/wait/stop 共用 ===
        ObjectNode serviceId = props.putObject("service_id");
        serviceId.put("type", "integer");
        serviceId.put("description", "【logs/wait/stop 时必填】任务或服务 ID：exec 转后台时返回的「任务 ID」，或 start 返回的服务 ID。");

        // === logs 专属 ===
        ObjectNode tail = props.putObject("tail");
        tail.put("type", "integer");
        tail.put("description", "【logs 可选】只显示最后N行日志。示例：20（最后20行）。");

        // === stop 专属 ===
        ObjectNode force = props.putObject("force");
        force.put("type", "boolean");
        force.put("description", "【stop 可选，默认true】停止方式。true=强制终止（默认）；false=优雅终止（SIGTERM，等5秒）。");

        ArrayNode required = root.putArray("required");
        required.add("action");
        return root;
    }

    // ==================== 执行入口 ====================

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText("");
        if (action.isEmpty()) {
            return "【参数缺失】'action' 参数缺失或为空。command 的 action 必须设为 'exec' / 'start' / 'list' / 'logs' / 'stop' 之一。\n"
                    + "正确示例：{ \"action\": \"exec\", \"command\": \"npm run build\" }\n"
                    + "错误示例：{ \"command\": \"npm run build\" } ← 缺少 action！";
        }
        return switch (action) {
            case "exec"  -> doExec(arguments);
            case "wait"  -> doWait(arguments);
            case "start" -> doStart(arguments);
            case "list"  -> doList();
            case "logs"  -> doLogs(arguments);
            case "stop"  -> doStop(arguments);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 exec / wait / start / list / logs / stop 六种取值，请改为其中之一。";
        };
    }

    // ================================================================
    //                      action=exec（原 run_command）
    // ================================================================

    private String doExec(JsonNode args) {
        String commandStr = args.path("command").asText();
        String cwdStr = args.path("cwd").asText();
        int timeout = args.path("timeout").asInt(DEFAULT_TIMEOUT);
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;
        if (timeout > MAX_TIMEOUT) timeout = MAX_TIMEOUT;

        Path workDir = resolveWorkDir(cwdStr);
        if (workDir == null) {
            return "【参数错误】工作目录 \"" + cwdStr + "\" 不在项目范围内。\n"
                    + "【建议】cwd 必须是项目根目录或其子目录。使用相对路径如 \"frontend\" 或留空使用项目根目录。";
        }

        if (commandStr.isEmpty()) {
            return "【参数缺失】action=exec 需要 command 参数。\n"
                    + "示例：command=\"mvn clean compile -DskipTests\"";
        }

        // 预检
        String checkResult = CommandUtils.checkExecutableExists(commandStr);
        if (checkResult != null) return checkResult;

        // Windows 编码适配
        String effectiveCommand = commandStr;
        if (CommandUtils.isWindows() && !CommandUtils.isUtf8CodePage()) {
            effectiveCommand = "chcp 65001 > nul && " + commandStr;
        }

        List<String> cmdLine = CommandUtils.buildShellCommand(effectiveCommand);

        // ① 去重：相同工作目录 + 相同命令已在运行 → 不重复启动。
        //    否则模型不知道后台在跑，很可能再发一次，导致两个 mvn 抢 .m2 锁 / 爆内存 / 产物互相踩。
        ServiceInfo running = findRunningTask(workDir, commandStr);
        if (running != null) {
            return formatAlreadyRunning(running);
        }

        // ② 应急开关：background-after-seconds <= 0 时完整回退到旧的「同步阻塞直到超时被杀」行为
        if (backgroundAfterSeconds <= 0) {
            return runProcess(cmdLine, workDir, timeout, commandStr);
        }

        // ③ 新执行模型：启动 → 注册长任务 → 有限前台等待 → 超时转后台（进程不会被杀）
        return execWithBackground(cmdLine, workDir, commandStr);
    }

    private String runProcess(List<String> cmdLine, Path workDir, int timeout, String displayName) {
        StringBuilder sb = new StringBuilder();
        sb.append("执行命令：").append(displayName).append("\n");
        sb.append("工作目录：").append(workDir.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");

        Charset charset = CommandUtils.getProcessOutputCharset();
        ProcessBuilder pb = new ProcessBuilder(cmdLine);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();
        int effectiveTimeout = timeout;

        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread readerThread = startOutputReader(process, output, charset);

            boolean completed = false;
            long outputLenAtCheck = 0;
            int idleExtensions = 0;

            while (!completed) {
                completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
                if (completed) break;
                long currentLen = output.length();
                if (currentLen > outputLenAtCheck) {
                    outputLenAtCheck = currentLen;
                    idleExtensions = 0;
                    int extended = Math.min(timeout, ABSOLUTE_MAX_TIMEOUT - (int)(System.currentTimeMillis() - startTime) / 1000);
                    if (extended > 10) { effectiveTimeout = Math.min(extended, 60); continue; }
                } else { idleExtensions++; }
                if (idleExtensions >= 2) {
                    process.destroyForcibly();
                    long elapsed = System.currentTimeMillis() - startTime;
                    sb.append("！命令超时（").append(elapsed / 1000).append(" 秒），已强制终止\n");
                    break;
                }
            }

            readerThread.join(2000);
            long elapsed = System.currentTimeMillis() - startTime;

            if (completed) {
                try { sb.append("退出码：").append(process.exitValue()).append("\n"); }
                catch (IllegalThreadStateException e) { sb.append("退出码：未知\n"); }
            }
            sb.append("耗时：").append(elapsed / 1000.0).append(" 秒\n");

            if (output.length() > 0) sb.append("\n").append(output);
            else sb.append("\n（无输出）");
            return sb.toString();

        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Cannot run program") || msg.contains("error=2"))) {
                return displayName + "\n工作目录：" + workDir.toAbsolutePath() + "\n"
                        + "────────────────────────────────────────\n【命令未找到】" + msg + "\n"
                        + "【建议】1. 确认命令已安装  2. 检查命令名拼写  3. 尝试使用完整路径";
            }
            log.error("执行命令失败", e);
            return "【执行异常】" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "【执行中断】命令执行被中断。";
        }
    }

    // ================================================================
    //        长耗时命令：后台化执行（exec 新执行模型）
    // ================================================================

    /** 是否为编译 / 依赖 / 构建类慢命令（命中后前台等待窗口更短） */
    private boolean isSlowCommand(String command) {
        return command != null && SLOW_COMMAND_PATTERN.matcher(command).find();
    }

    /**
     * 查找「同工作目录 + 同命令」且仍在运行的任务
     * <p>命中的意义：模型不知道后台在跑时会重复下发同一命令，导致两个 mvn 抢 .m2 锁、
     * 两个 npm 同时写 node_modules、内存暴涨。这里直接拦掉。</p>
     */
    private ServiceInfo findRunningTask(Path workDir, String command) {
        if (command == null || command.isBlank() || workDir == null) return null;
        String normalizedCmd = command.trim();
        String normalizedDir = workDir.toAbsolutePath().normalize().toString();
        for (ServiceInfo info : SERVICES.values()) {
            if (!info.isAlive()) continue;
            if (info.command == null) continue;
            if (!normalizedCmd.equals(info.command.trim())) continue;
            if (info.workDir == null) continue;
            if (normalizedDir.equals(info.workDir.toAbsolutePath().normalize().toString())) return info;
        }
        return null;
    }

    /** 重复执行拦截提示 */
    private String formatAlreadyRunning(ServiceInfo info) {
        long elapsed = (System.currentTimeMillis() - info.startTime) / 1000;
        return "【已在运行】相同命令正在执行中，本次未重复启动\n"
                + "任务 ID：" + info.id + "\n"
                + "命令：" + info.command + "\n"
                + "已运行：" + elapsed + "s\n\n"
                + "【下一步】任选其一：\n"
                + " · 看进度：command(action=logs, service_id=" + info.id + ", tail=50)\n"
                + " · 等它跑完：command(action=wait, service_id=" + info.id + ")\n"
                + " · 想停掉：command(action=stop, service_id=" + info.id + ")\n\n"
                + "⚠️ 不要重复执行同一命令（会造成锁竞争 / 内存暴涨 / 产物互相覆盖）。";
    }

    /**
     * 新执行模型核心：启动进程 → 注册长任务 → 有限前台等待
     * <p>与旧 {@link #runProcess} 的本质区别：超时**不杀进程**，而是转后台继续跑，
     * 立刻把控制权还给模型（模型可以继续读文件/分析代码，稍后再 logs 或 wait）。</p>
     */
    private String execWithBackground(List<String> cmdLine, Path workDir, String displayName) {
        cleanupExpiredTasks();

        long runningTasks = SERVICES.values().stream().filter(i -> i.isTask() && i.isAlive()).count();
        if (runningTasks >= MAX_TASKS) {
            return "【并发限制】后台长任务已达上限（" + MAX_TASKS + " 个）。\n"
                    + "先用 command(action=list) 查看现状，stop 掉不需要的任务后重试。";
        }

        Charset charset = CommandUtils.getProcessOutputCharset();
        ProcessBuilder pb = new ProcessBuilder(cmdLine);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            int sid = SERVICE_ID_SEQ.getAndIncrement();
            ServiceInfo info = new ServiceInfo(sid, displayName, workDir, process,
                    System.currentTimeMillis(), true, KIND_TASK);
            SERVICES.put(sid, info);
            // 复用后台服务的输出读取线程：持续读进环形缓冲，供 logs / wait / 失败抽取使用
            startServiceOutputReader(sid, process);

            int waitSeconds = isSlowCommand(displayName) ? backgroundAfterBuildSeconds : backgroundAfterSeconds;
            if (waitSeconds <= 0) waitSeconds = backgroundAfterSeconds;

            boolean done = waitForTask(info, waitSeconds);
            if (done) return formatCompletedResult(info);
            return formatBackgroundedResult(info, waitSeconds);

        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Cannot run program") || msg.contains("error=2"))) {
                return displayName + "\n工作目录：" + workDir.toAbsolutePath() + "\n"
                        + "────────────────────────────────────────\n【命令未找到】" + msg + "\n"
                        + "【建议】1. 确认命令已安装  2. 检查命令名拼写  3. 尝试使用完整路径";
            }
            log.error("执行命令失败", e);
            return "【执行异常】" + e.getMessage();
        }
    }

    /**
     * 有限等待任务结束
     *
     * @return true = 已结束；false = 等待窗口内仍在运行
     */
    private boolean waitForTask(ServiceInfo info, int seconds) {
        long deadline = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!info.isAlive()) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !info.isAlive();
            }
        }
        return !info.isAlive();
    }

    /** 任务已完成：返回完整输出（失败时优先关键错误行） */
    private String formatCompletedResult(ServiceInfo info) {
        long elapsed = info.finishTime > 0
                ? (info.finishTime - info.startTime) / 1000
                : (System.currentTimeMillis() - info.startTime) / 1000;
        int code = info.exitValue();

        StringBuilder sb = new StringBuilder();
        sb.append("执行命令：").append(info.command).append("\n");
        sb.append("工作目录：").append(info.workDir.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");
        sb.append("退出码：").append(code).append("\n");
        sb.append("耗时：").append(elapsed).append(" 秒\n\n");

        List<String> lines = info.getOutputLines();
        if (lines.isEmpty()) {
            sb.append("（无输出）");
            return sb.toString();
        }

        if (code != 0) {
            sb.append(formatFailureOutput(lines, info.id));
        } else {
            String body = String.join("\n", lines);
            if (body.length() > MAX_OUTPUT_SIZE) {
                sb.append(body, 0, MAX_OUTPUT_SIZE)
                        .append("\n...（输出超过 1MB 已截断，可用 command(action=logs, service_id=")
                        .append(info.id).append(", tail=N) 按需查看）");
            } else {
                sb.append(body);
            }
        }
        return sb.toString();
    }

    /**
     * 失败输出治理：先给命中的错误行，再给尾部
     * <p>为什么不能一把梭给全量：编译失败时输出常有几万行，关键 Caused by / [ERROR] 往往夹在中间，
     * 而 1MB 截断恰好会把它们切掉。这里主动抽取，让模型第一眼就看到原因。</p>
     */
    private String formatFailureOutput(List<String> lines, int taskId) {
        List<String> errorLines = new ArrayList<>();
        for (String line : lines) {
            if (ERROR_LINE_PATTERN.matcher(line).find()) {
                errorLines.add(line);
                if (errorLines.size() >= 60) break;
            }
        }

        StringBuilder sb = new StringBuilder();
        int tailN = Math.min(BACKGROUND_TAIL_LINES * 2, lines.size());
        List<String> tail = lines.subList(lines.size() - tailN, lines.size());

        if (!errorLines.isEmpty()) {
            sb.append("【关键错误行】（共 ").append(lines.size()).append(" 行输出，以下为命中的错误/异常行）\n");
            sb.append("────────────────────────────────────────\n");
            sb.append(String.join("\n", errorLines)).append("\n");
            sb.append("────────────────────────────────────────\n\n");
        } else {
            sb.append("（未匹配到典型错误关键字，请参考下方输出尾部）\n\n");
        }

        sb.append("【输出尾部】（最后 ").append(tailN).append(" 行）\n");
        sb.append("────────────────────────────────────────\n");
        sb.append(String.join("\n", tail)).append("\n");
        sb.append("────────────────────────────────────────\n");
        sb.append("（完整输出共 ").append(lines.size()).append(" 行，可用 command(action=logs, service_id=")
                .append(taskId).append(", tail=N) 查看）");
        return sb.toString();
    }

    /** 转后台提示（关键：明确告诉模型「不是失败」「不要重复执行」） */
    private String formatBackgroundedResult(ServiceInfo info, int waitedSeconds) {
        long elapsed = (System.currentTimeMillis() - info.startTime) / 1000;
        List<String> tail = info.getOutputTail(BACKGROUND_TAIL_LINES);
        int total = info.getOutputLineCount();

        StringBuilder sb = new StringBuilder();
        sb.append("命令耗时超过 ").append(waitedSeconds).append(" 秒，已转后台继续执行（进程不会中断）\n\n");
        sb.append("任务 ID：").append(info.id).append("\n");
        sb.append("命令：").append(info.command).append("\n");
        sb.append("工作目录：").append(info.workDir.toAbsolutePath()).append("\n");
        sb.append("已运行：").append(elapsed).append("s　状态：运行中\n\n");

        if (!tail.isEmpty()) {
            sb.append("当前输出（最后 ").append(tail.size()).append(" 行）：\n");
            sb.append("────────────────────────────────────────\n");
            sb.append(String.join("\n", tail)).append("\n");
            sb.append("────────────────────────────────────────\n");
            sb.append("（共 ").append(total).append(" 行输出，此处只显示尾部）\n\n");
        } else {
            sb.append("（暂无输出）\n\n");
        }

        sb.append("【下一步】任选其一：\n");
        sb.append(" · 继续做别的事，稍后再看：command(action=logs, service_id=").append(info.id).append(", tail=50)\n");
        sb.append(" · 阻塞等它跑完：command(action=wait, service_id=").append(info.id).append(")\n");
        sb.append(" · 想停掉：command(action=stop, service_id=").append(info.id).append(")\n\n");
        sb.append("⚠️ 不要重复执行同一命令（会触发去重拦截，白等一轮）。");
        return sb.toString();
    }

    /** 清理超过保留期的已结束任务（markFinished 里的定时线程是主路径，这里是兜底） */
    private void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        SERVICES.entrySet().removeIf(e -> {
            ServiceInfo info = e.getValue();
            return info.finished && info.finishTime > 0 && (now - info.finishTime) > TASK_RETAIN_MS;
        });
    }

    private Thread startOutputReader(Process process, StringBuilder output, Charset charset) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                char[] buf = new char[8192];
                int total = 0, n;
                while ((n = br.read(buf)) != -1) {
                    if (total + n > MAX_OUTPUT_SIZE) { n = MAX_OUTPUT_SIZE - total; output.append(buf, 0, n); output.append("\n...（输出截断，超出1MB限制）"); break; }
                    output.append(buf, 0, n); total += n;
                }
            } catch (IOException e) { log.debug("命令输出流读取结束"); }
        }, "cmd-output-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    // ================================================================
    //                      action=start（原 run_server）
    // ================================================================

    private String doStart(JsonNode args) {
        String commandStr = args.path("command").asText();
        String cwdStr = args.path("cwd").asText();
        String waitFor = args.path("wait_for").asText();

        if (commandStr.isEmpty()) {
            return "【参数缺失】action=start 需要 command 参数。示例：command=\"npm run dev\"";
        }

        cleanupFinishedServices();
        long runningCount = getRunningCount();
        if (runningCount >= MAX_CONCURRENT) {
            return "【并发限制】后台服务已达上限（" + MAX_CONCURRENT + " 个）。\n"
                    + "建议：先用 command action=stop service_id=<ID> 停止不需要的服务，或用 command action=list 查看当前服务。\n"
                    + doListRaw();
        }

        Path workDir = resolveWorkDir(cwdStr);
        if (workDir == null) return "【参数错误】工作目录不在项目范围内。";

        List<String> cmdLine;
        boolean useShell = needsShell(commandStr);
        if (useShell) {
            cmdLine = CommandUtils.buildShellCommand(commandStr);
        } else {
            cmdLine = CommandUtils.tokenize(commandStr);
            if (cmdLine.isEmpty()) return "【参数错误】命令解析后为空。";
            cmdLine = CommandUtils.resolveWindowsExecutable(cmdLine);
        }

        ProcessBuilder pb = new ProcessBuilder(cmdLine);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            int sid = SERVICE_ID_SEQ.getAndIncrement();
            ServiceInfo info = new ServiceInfo(sid, commandStr, workDir, process, System.currentTimeMillis(), useShell);
            SERVICES.put(sid, info);

            startServiceOutputReader(sid, process);
            String readyResult = waitForReady(sid, waitFor);

            log.info("启动后台服务 #{}: {} (Shell={})", sid, commandStr, useShell);

            StringBuilder sb = new StringBuilder();
            sb.append("后台服务已启动\n");
            sb.append("服务 ID：").append(sid).append("\n");
            sb.append("命令：").append(commandStr).append("\n");
            sb.append("工作目录：").append(workDir.toAbsolutePath()).append("\n");
            sb.append("当前后台服务数：").append(SERVICES.size()).append("（运行中: ").append(getRunningCount()).append("）\n");
            if (readyResult != null) sb.append("\n").append(readyResult);
            String ports = detectPorts(sid);
            if (ports != null) sb.append("\n").append(ports);
            sb.append("\n使用 command action=logs/stop 管理此服务（service_id=").append(sid).append("）\n");
            sb.append(doListRaw());
            return sb.toString();

        } catch (IOException e) {
            log.error("启动后台服务失败", e);
            return "【启动失败】" + e.getMessage() + "\n"
                    + "建议：1. 确认命令可执行  2. 检查端口占用  3. 检查工作目录";
        }
    }

    // ================================================================
    //                      action=list（原 service_control list）
    // ================================================================

    private String doList() { return doListRaw(); }

    private String doListRaw() {
        cleanupFinishedServices();
        if (SERVICES.isEmpty()) return "  （无后台服务）";
        StringBuilder sb = new StringBuilder("后台服务列表：\n");
        for (ServiceInfo info : SERVICES.values().stream()
                .sorted(Comparator.comparingInt(s -> s.id)).toList()) {
            boolean alive = info.isAlive();
            long elapsed = (System.currentTimeMillis() - info.startTime) / 1000;
            int lines = info.getOutputLineCount();
            // 区分「长期服务」与「一次性长任务」：前者常驻，后者跑完就该收尾
            String kindLabel = info.isTask() ? "任务" : "服务";
            sb.append(String.format("  [%s] #%d %s [%s] %ds (%d行输出)",
                    kindLabel, info.id, info.command, alive ? "运行中" : "已结束", elapsed, lines));
            if (!alive) sb.append(" exit=").append(info.exitValue());
            if (info.detectedPorts != null && !info.detectedPorts.isEmpty()) sb.append(" ports=").append(String.join(",", info.detectedPorts));
            sb.append("\n");
            List<String> recent = info.getOutputTail(3);
            if (!recent.isEmpty()) {
                for (String line : recent) {
                    sb.append("    | ").append(line.length() > 100 ? line.substring(0, 100) + "..." : line).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ================================================================
    //                      action=logs（原 service_control logs）
    // ================================================================

    private String doLogs(JsonNode args) {
        if (!args.has("service_id")) {
            return "【参数缺失】action=logs 需要 service_id 参数。\n先用 command action=list 查看所有服务的ID。";
        }
        int sid = args.get("service_id").asInt();
        if (sid <= 0) return "【参数错误】无效的服务ID：" + sid;

        // 默认只回传尾部 50 行：长编译 / 下载动辄几万行，全量回传会直接撑爆上下文
        // （显式传 tail=0 仍可取全量，供确有需要的场景）
        int tailN = args.has("tail") ? args.get("tail").asInt(0) : LOGS_DEFAULT_TAIL;
        if (tailN < 0) return "【参数错误】tail 不能为负数。";

        ServiceInfo info = SERVICES.get(sid);
        if (info == null) return "【服务不存在】找不到服务 #" + sid + "。先用 command action=list 查看当前服务。";

        List<String> allLines = info.getOutputLines();
        if (allLines.isEmpty()) {
            return info.isAlive() ? "（服务运行中，暂无输出）" : "（服务已结束，无输出）";
        }
        StringBuilder sb = new StringBuilder();
        int total = allLines.size();
        sb.append("服务 #").append(sid).append(" 输出（共 ").append(total).append(" 行）");
        if (tailN > 0 && tailN < total) sb.append("，显示最后 ").append(tailN).append(" 行");
        sb.append("：\n────────────────────────────────────────\n");
        int start = tailN > 0 ? Math.max(0, total - tailN) : 0;
        for (int i = start; i < total; i++) sb.append(allLines.get(i)).append("\n");
        if (info.isAlive()) sb.append("（服务仍在运行）");
        else sb.append("（服务已结束，退出码: ").append(info.exitValue()).append("）");
        return sb.toString();
    }

    // ================================================================
    //                      action=wait（阻塞等待任务结束）
    // ================================================================

    /**
     * 阻塞等待任务结束（最多 timeout 秒）
     * <p>与 exec 的分工：exec 只在前台等一小会儿就转后台返回，把控制权还给模型；
     * wait 是模型**主动**要求继续等 —— 长构建（10 分钟）可以分多次 wait，
     * 中间穿插读文件 / 分析代码这类别的活。</p>
     */
    private String doWait(JsonNode args) {
        int sid = args.path("service_id").asInt(0);
        if (sid <= 0) {
            return "【参数缺失】action=wait 需要 service_id 参数。\n"
                    + "先用 command(action=list) 查看当前任务 / 服务的 ID。";
        }
        ServiceInfo info = SERVICES.get(sid);
        if (info == null) {
            return "【任务不存在】找不到任务 #" + sid + "（任务结束后会保留 30 分钟，超时才会被清理）。\n"
                    + "用 command(action=list) 查看现有任务。";
        }

        // 已经结束：直接返回完整结果
        if (!info.isAlive()) {
            return formatCompletedResult(info);
        }

        int waitSeconds = args.path("timeout").asInt(DEFAULT_WAIT_SECONDS);
        if (waitSeconds <= 0) waitSeconds = DEFAULT_WAIT_SECONDS;
        if (waitSeconds > MAX_WAIT_SECONDS) waitSeconds = MAX_WAIT_SECONDS;

        boolean done = waitForTask(info, waitSeconds);
        if (done) {
            return formatCompletedResult(info);
        }

        // 仍未结束：给尾部 + 允许继续等
        long elapsed = (System.currentTimeMillis() - info.startTime) / 1000;
        List<String> tail = info.getOutputTail(BACKGROUND_TAIL_LINES);
        StringBuilder sb = new StringBuilder();
        sb.append("任务 #").append(sid).append(" 仍在运行（已运行 ").append(elapsed)
                .append("s，本次等待 ").append(waitSeconds).append("s 未结束）\n\n");
        if (!tail.isEmpty()) {
            sb.append("当前输出（最后 ").append(tail.size()).append(" 行）：\n");
            sb.append("────────────────────────────────────────\n");
            sb.append(String.join("\n", tail)).append("\n");
            sb.append("────────────────────────────────────────\n\n");
        }
        sb.append("【下一步】可再次 command(action=wait, service_id=").append(sid)
                .append(", timeout=").append(waitSeconds).append(") 继续等，"
                        + "或用 command(action=logs, service_id=").append(sid).append(") 看进度。");
        return sb.toString();
    }

    // ================================================================
    //                      action=stop（原 service_control stop）
    // ================================================================

    private String doStop(JsonNode args) {
        if (!args.has("service_id")) {
            return "【参数缺失】action=stop 需要 service_id 参数。\n先用 command action=list 查看所有服务的ID。";
        }
        int sid = args.get("service_id").asInt();
        boolean forceFlag = args.path("force").asBoolean(true);

        ServiceInfo info = SERVICES.get(sid);
        if (info == null) return "【服务不存在】找不到服务 #" + sid + "。";

        if (info.isAlive()) {
            if (!forceFlag) {
                info.process.destroy();
                try { if (info.process.waitFor(5, TimeUnit.SECONDS)) { SERVICES.remove(sid); return "服务 #" + sid + " 已优雅停止。"; } }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            try { info.process.descendants().forEach(ProcessHandle::destroyForcibly); } catch (Exception ignored) {}
            info.process.destroyForcibly();
        }
        SERVICES.remove(sid);
        return "服务 #" + sid + " 已停止" + (forceFlag ? "（强制终止）" : "（优雅终止）") + "\n" + doListRaw();
    }

    // ================================================================
    //                      服务管理内部方法
    // ================================================================

    private void cleanupFinishedServices() {
        /*
          注意语义变化：不能再「把已结束的直接删掉」。
          长任务（编译 / 下载）跑完后，模型往往还要 logs 回看失败原因、或 wait 拿退出码；
          直接删会导致「刚跑完就查不到」。现在只清理超过 TASK_RETAIN_MS（30 分钟）的。
        */
        cleanupExpiredTasks();
    }

    /** 运行中的「长期服务」数量（不含一次性长任务，两者池子分开计数） */
    private long getRunningCount() {
        return SERVICES.values().stream().filter(i -> !i.isTask() && i.isAlive()).count();
    }

    private void startServiceOutputReader(int sid, Process process) {
        Charset charset = CommandUtils.getProcessOutputCharset();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = br.readLine()) != null) {
                    ServiceInfo info = SERVICES.get(sid);
                    if (info != null) info.addOutputLine(line);
                }
            } catch (IOException e) { log.debug("服务 #{} 输出流读取结束", sid); }
            finally {
                ServiceInfo info = SERVICES.get(sid);
                if (info != null) info.markFinished();
            }
        }, "svc-output-" + sid);
        reader.setDaemon(true);
        reader.start();
    }

    private String waitForReady(int sid, String waitFor) {
        if (waitFor == null || waitFor.isBlank()) return null;
        ServiceInfo info = SERVICES.get(sid);
        if (info == null) return null;

        try { int s = Integer.parseInt(waitFor.trim()); Thread.sleep(s * 1000L); return "就绪等待：" + s + " 秒（固定延时）"; }
        catch (NumberFormatException ignored) {}
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return "就绪检测：等待被中断。"; }

        if (waitFor.startsWith("http://") || waitFor.startsWith("https://")) {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline && info.isAlive()) {
                try { HttpURLConnection conn = (HttpURLConnection) URI.create(waitFor).toURL().openConnection();
                    conn.setConnectTimeout(2000); conn.setReadTimeout(2000); conn.setRequestMethod("GET");
                    if (conn.getResponseCode() == 200) return "就绪检测：HTTP 200（" + waitFor + "）"; }
                catch (Exception ignored) {}
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            return info.isAlive() ? "就绪检测：等待超时（" + waitFor + "）" : "就绪检测：服务已结束";
        }

        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && info.isAlive()) {
            if (info.getOutputLines().stream().anyMatch(l -> l.contains(waitFor))) return "就绪检测：输出中出现关键词「" + waitFor + "」";
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return info.isAlive() ? "就绪检测：等待关键词「" + waitFor + "」超时" : "就绪检测：服务已结束";
    }

    private String detectPorts(int sid) {
        ServiceInfo info = SERVICES.get(sid);
        if (info == null) return null;
        Set<String> ports = new LinkedHashSet<>();
        for (String line : info.getOutputLines()) {
            Matcher m = PORT_PATTERN.matcher(line);
            if (m.find()) ports.add(m.group(1));
        }
        return ports.isEmpty() ? null : "检测到端口：" + ports.stream().map(p -> "localhost:" + p).collect(Collectors.joining(", "));
    }

    private boolean needsShell(String cmd) { return SHELL_NEEDED_PATTERN.matcher(cmd).find(); }

    private Path resolveWorkDir(String cwdStr) {
        Path root = Paths.get(ProjectRootContext.get()).normalize();
        if (cwdStr == null || cwdStr.isEmpty()) return root;
        Path given = Paths.get(cwdStr);
        Path dir = given.isAbsolute() ? given.normalize() : root.resolve(cwdStr).normalize();
        return (dir.equals(root) || dir.startsWith(root)) ? dir : null;
    }

    // ================================================================
    //                      服务信息类
    // ================================================================

    static class ServiceInfo {
        final int id; final String command; final Path workDir; final Process process;
        final long startTime; final boolean useShell;
        /** 任务类型：{@link #KIND_SERVICE}（长期服务）或 {@link #KIND_TASK}（一次性长任务） */
        final String kind;
        volatile boolean finished; volatile Integer exitCode; volatile Set<String> detectedPorts;
        /** 结束时间戳（毫秒）；未结束时为 0。用于「结束后保留 TTL 内仍可回看」的判断 */
        volatile long finishTime;
        private final List<String> outputLines = new LinkedList<>();
        private int totalBytes;

        ServiceInfo(int id, String command, Path workDir, Process process, long startTime, boolean useShell) {
            this(id, command, workDir, process, startTime, useShell, KIND_SERVICE);
        }

        ServiceInfo(int id, String command, Path workDir, Process process, long startTime,
                    boolean useShell, String kind) {
            this.id = id; this.command = command; this.workDir = workDir;
            this.process = process; this.startTime = startTime; this.useShell = useShell;
            this.kind = kind == null ? KIND_SERVICE : kind;
        }

        /** 是否是一次性长任务 */
        boolean isTask() { return KIND_TASK.equals(kind); }

        synchronized void addOutputLine(String line) {
            int lb = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (totalBytes + lb > MAX_OUTPUT_BYTES) return;
            outputLines.add(line); totalBytes += lb;
            if (outputLines.size() > MAX_OUTPUT_LINES) { String removed = outputLines.remove(0); totalBytes -= removed.getBytes(StandardCharsets.UTF_8).length + 1; if (totalBytes < 0) totalBytes = 0; }
            Matcher m = PORT_PATTERN.matcher(line);
            if (m.find()) { if (detectedPorts == null) detectedPorts = new LinkedHashSet<>(); detectedPorts.add(m.group(1)); }
        }

        synchronized List<String> getOutputLines() { return new ArrayList<>(outputLines); }
        synchronized List<String> getOutputTail(int n) {
            if (outputLines.isEmpty()) return List.of();
            return new ArrayList<>(outputLines.subList(Math.max(0, outputLines.size() - n), outputLines.size()));
        }
        synchronized int getOutputLineCount() { return outputLines.size(); }
        boolean isAlive() { return !finished && process.isAlive(); }
        void markFinished() {
            finished = true;
            finishTime = System.currentTimeMillis();
            try { exitCode = process.exitValue(); } catch (IllegalThreadStateException e) { exitCode = -1; }
            /*
              延迟清理：结束后保留 TASK_RETAIN_MS（30 分钟）再移除。
              为什么从 5 分钟延长到 30 分钟：长任务（编译 / 下载依赖）跑完后，模型往往还要
              logs 回看失败原因；5 分钟内如果模型先去做了别的事，回来就查不到了。
            */
            Thread cleanupThread = new Thread(() -> {
                try { Thread.sleep(TASK_RETAIN_MS); } catch (InterruptedException e) { return; }
                SERVICES.remove(id);
                log.debug("自动清理已结束的任务 #{}（{}）", id, kind);
            }, "svc-cleanup-" + id);
            cleanupThread.setDaemon(true);
            cleanupThread.start();
        }
        int exitValue() { if (exitCode != null) return exitCode; try { return process.exitValue(); } catch (IllegalThreadStateException e) { return -1; } }
    }
}
