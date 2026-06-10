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
                + "{\"action\":\"start\",\"command\":\"npm run dev\"}\n"
                + "【action 说明】\n"
                + "  exec  — 阻塞执行一次性命令（编译、安装依赖、运行测试等），等待完成返回完整输出\n"
                + "  start — 后台启动持续运行的服务（npm run dev、mvn spring-boot:run 等），返回服务ID\n"
                + "  list  — 列出所有后台服务及其状态\n"
                + "  logs  — 查看指定服务的日志输出\n"
                + "  stop  — 停止指定服务\n"
                + "【典型工作流】start 启动服务 → list 确认运行 → logs 查看日志 → stop 停止服务\n"
                + "【注意事项】action 必填；exec 默认超时 120s（最大 600s），输出持续增长时自动延长；"
                + "start 最多同时运行 " + MAX_CONCURRENT + " 个后台服务";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        // === action ===
        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】操作类型。exec=阻塞执行命令；start=后台启动服务；list=列出服务；logs=查看日志；stop=停止服务。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("exec").add("start").add("list").add("logs").add("stop");

        // === exec/start 共用 ===
        ObjectNode cmd = props.putObject("command");
        cmd.put("type", "string");
        cmd.put("description", "【exec/start 时必填】要执行的命令字符串。支持管道|、重定向>、环境变量$VAR、命令链&& ||。"
                + "示例：\"mvn clean compile -DskipTests\"、\"npm run dev\"、\"node server.js\"");

        ObjectNode cwd = props.putObject("cwd");
        cwd.put("type", "string");
        cwd.put("description", "【exec/start 可选】工作目录，默认项目根。示例：\"frontend\"（前端子目录）。");

        // === exec 专属 ===
        ObjectNode execTimeout = props.putObject("timeout");
        execTimeout.put("type", "integer");
        execTimeout.put("description", "【exec 可选】超时秒数，默认120，最大600。输出持续增长时自动延长。");

        // === start 专属 ===
        ObjectNode waitFor = props.putObject("wait_for");
        waitFor.put("type", "string");
        waitFor.put("description", "【start 可选】就绪检测方式。数字：等待固定秒数（如\"5\"）；URL：轮询HTTP 200（如\"http://localhost:8080\"）；关键字：等待输出中出现指定文本（如\"Started\"）。不设则立即返回。");

        // === logs/stop 共用 ===
        ObjectNode serviceId = props.putObject("service_id");
        serviceId.put("type", "integer");
        serviceId.put("description", "【logs/stop 时必填】start 返回的服务ID。");

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
            case "start" -> doStart(arguments);
            case "list"  -> doList();
            case "logs"  -> doLogs(arguments);
            case "stop"  -> doStop(arguments);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 exec / start / list / logs / stop 五种取值，请改为其中之一。";
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
        return runProcess(cmdLine, workDir, timeout, commandStr);
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
            sb.append(String.format("  #%d %s [%s] %ds (%d行输出)", info.id, info.command, alive ? "运行中" : "已结束", elapsed, lines));
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

        int tailN = args.has("tail") ? args.get("tail").asInt(0) : 0;
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
        SERVICES.entrySet().removeIf(e -> !e.getValue().isAlive());
    }

    private long getRunningCount() {
        return SERVICES.values().stream().filter(ServiceInfo::isAlive).count();
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
        volatile boolean finished; volatile Integer exitCode; volatile Set<String> detectedPorts;
        private final List<String> outputLines = new LinkedList<>();
        private int totalBytes;

        ServiceInfo(int id, String command, Path workDir, Process process, long startTime, boolean useShell) {
            this.id = id; this.command = command; this.workDir = workDir;
            this.process = process; this.startTime = startTime; this.useShell = useShell;
        }

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
        void markFinished() { finished = true; try { exitCode = process.exitValue(); } catch (IllegalThreadStateException e) { exitCode = -1; } }
        int exitValue() { if (exitCode != null) return exitCode; try { return process.exitValue(); } catch (IllegalThreadStateException e) { return -1; } }
    }
}
