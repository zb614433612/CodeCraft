package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.CommandUtils;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 启动服务工具 — run_server
 * <p>
 * 在后台启动并管理长期运行的服务（如 npm run dev、mvn spring-boot:run、node server.js），
 * 返回服务 ID 供后续管理。
 * 支持就绪检测（HTTP/关键字/延时）和端口自动发现。
 * 后续使用 service_control 工具管理（查看日志/停止/状态）。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.EXECUTE, affectsData = true, description = "启动后台服务")
public class RunServerTool implements Tool {

    /** 最大并发后台服务数 */
    private static final int MAX_CONCURRENT = 5;

    /** 单个服务输出缓冲区最大行数 */
    private static final int MAX_OUTPUT_LINES = 10000;

    /** 单个服务输出缓冲区最大字节数 */
    private static final int MAX_OUTPUT_BYTES = 5 * 1024 * 1024;

    /** 后台服务存储（包级可见，供 ServiceControlTool 访问） */
    static final ConcurrentHashMap<Integer, ServiceInfo> SERVICES = new ConcurrentHashMap<>();
    private static final AtomicInteger SERVICE_ID_SEQ = new AtomicInteger(1);

    /** 端口号提取正则 */
    private static final Pattern PORT_PATTERN = Pattern.compile(
            "(?:https?://|bind\\s+|port\\s+)(?:localhost|0\\.0\\.0\\.0|127\\.0\\.0\\.1)[:/]?(\\d{2,5})",
            Pattern.CASE_INSENSITIVE);

    /** 需要 Shell 包装的命令特征 */
    private static final Pattern SHELL_NEEDED_PATTERN = Pattern.compile(
            "[|&;<>$`'\"()]|\\b(cd|export|set\\s+\\w+=|source)\\b");

    private final ObjectMapper objectMapper;

    public RunServerTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "run_server";
    }

    @Override
    public String getDescription() {
        return "【适用场景】启动需要持续运行的服务（如 npm run dev、mvn spring-boot:run、node server.js），服务在后台运行不阻塞后续操作。\n"
                + "【使用方式】提供 command 字符串，返回服务 ID 供后续管理。\n"
                + "  command：后台服务启动命令，支持管道|、重定向>、环境变量$VAR 等 Shell 特性。\n"
                + "    示例：\"npm run dev\"、\"mvn spring-boot:run\"、\"node server.js\"、\"cd frontend && npm run dev\"\n"
                + "  wait_for：可选的就绪检测方式，支持三种模式：\n"
                + "    数字（秒）：\"5\" — 等待固定秒数后返回\n"
                + "    URL：\"http://localhost:8080\" — 轮询 HTTP 200 直到就绪（最长 60 秒）\n"
                + "    关键字：\"Started\" — 等待输出中出现指定文本（最长 60 秒）\n"
                + "【与 run_command 的区别】run_command 等待命令完成并返回全部输出；run_server 在后台启动服务后立即返回，"
                + "后续用 service_control 查看日志/停止服务。\n"
                + "【并发限制】最多同时运行 " + MAX_CONCURRENT + " 个后台服务。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "【必填】后台服务启动命令字符串。"
                + "支持管道|、重定向>、环境变量$VAR 等 Shell 特性。"
                + "示例：\"npm run dev\"、\"mvn spring-boot:run\"、\"node server.js\"、\"cd frontend && npm run dev\"");
        properties.set("command", command);

        ObjectNode cwd = objectMapper.createObjectNode();
        cwd.put("type", "string");
        cwd.put("description", "【可选，默认项目根目录】服务进程的工作目录。"
                + "可使用相对路径（相对于项目根目录）或绝对路径。示例：\"frontend\"（前端子目录）、\"backend\"（后端子目录）");
        properties.set("cwd", cwd);

        ObjectNode waitFor = objectMapper.createObjectNode();
        waitFor.put("type", "string");
        waitFor.put("description", "【可选】服务就绪检测方式，三种模式任选其一。不设置则立即返回，不等待就绪。\n"
                + "  1. 数字模式：等待固定秒数。示例：\"5\"（等待 5 秒后返回）\n"
                + "  2. URL 模式：轮询 HTTP 200 直到就绪（最长 60 秒）。示例：\"http://localhost:8080\"\n"
                + "  3. 关键字模式：等待输出中出现指定文本（最长 60 秒）。示例：\"Started\"（Spring Boot 启动完成标志）、\"ready\"（Vite 就绪标志）");
        properties.set("wait_for", waitFor);

        parameters.set("properties", properties);
        parameters.putArray("required").add("command");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {

        String commandStr = arguments.path("command").asText();
        String cwdStr = arguments.path("cwd").asText();
        String waitFor = arguments.path("wait_for").asText();

        if (commandStr.isEmpty()) {
            return "【参数错误】缺少必填参数 command。\n"
                    + "【建议】请提供后台服务启动命令，如 command=\"npm run dev\" 或 command=\"mvn spring-boot:run\"。";
        }

        // 清理已结束的服务
        cleanupFinishedServices();

        // 检查并发限制
        long runningCount = getRunningCount();
        if (runningCount >= MAX_CONCURRENT) {
            return "【并发限制】后台服务已达上限（" + MAX_CONCURRENT + " 个）。\n"
                    + "【建议】1. 先停止不需要的服务：service_control action=stop service_id=<ID>\n"
                    + "  2. 查看所有服务：service_control action=list\n"
                    + "  3. 等待已结束的服务自动清理后重试。\n"
                    + "当前服务列表：\n" + buildServiceList();
        }

        // 解析工作目录
        Path effectiveRoot = Paths.get(ProjectRootContext.get()).normalize();
        Path workDir;
        if (cwdStr == null || cwdStr.isEmpty()) {
            workDir = effectiveRoot;
        } else {
            Path given = Paths.get(cwdStr);
            workDir = given.isAbsolute() ? given.normalize() : effectiveRoot.resolve(cwdStr).normalize();
        }
        if (!workDir.equals(effectiveRoot) && !workDir.startsWith(effectiveRoot)) {
            return "【参数错误】工作目录不在项目范围内。\n"
                    + "【建议】cwd 必须是项目根目录或其子目录。"
                    + "请使用如 \"frontend\"、\"backend\" 等相对路径，或留空使用项目根目录。";
        }

        // 启动服务
        return startService(commandStr, workDir, waitFor);
    }

    // ==================== 后台服务启动 ====================

    /**
     * 启动后台服务，根据命令内容自动选择 Shell 或直接执行
     */
    private String startService(String commandStr, Path workDir, String waitFor) {
        List<String> cmdLine;
        boolean useShell = needsShell(commandStr);

        if (useShell) {
            cmdLine = CommandUtils.buildShellCommand(commandStr);
            log.debug("后台 Shell 执行: {}", String.join(" ", cmdLine));
        } else {
            cmdLine = CommandUtils.tokenize(commandStr);
            if (cmdLine.isEmpty()) {
                return "【参数错误】命令解析后为空。\n"
                        + "【建议】请检查 command 参数是否正确，确保命令字符串非空且格式正确。如 command=\"npm run dev\"";
            }
            cmdLine = CommandUtils.resolveWindowsExecutable(cmdLine);
            log.debug("后台直接执行: {}", String.join(" ", cmdLine));
        }

        ProcessBuilder pb = new ProcessBuilder(cmdLine);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            int serviceId = SERVICE_ID_SEQ.getAndIncrement();

            ServiceInfo info = new ServiceInfo(serviceId, commandStr, workDir, process, System.currentTimeMillis(), useShell);
            SERVICES.put(serviceId, info);

            // 启动输出读取线程
            startOutputReader(serviceId, process);

            // 就绪检测
            String readyResult = waitForReady(serviceId, waitFor);

            log.info("启动后台服务 #{}: {} (系统PID={}, Shell={})", serviceId, commandStr, process.pid(), useShell);

            StringBuilder sb = new StringBuilder();
            sb.append("后台服务已启动\n");
            sb.append("服务 ID：").append(serviceId).append("\n");
            sb.append("命令：").append(commandStr).append("\n");
            sb.append("工作目录：").append(workDir.toAbsolutePath()).append("\n");
            if (useShell) {
                sb.append("执行方式：Shell 模式（支持管道、重定向）\n");
            }
            sb.append("当前后台服务数：").append(SERVICES.size())
                    .append("（运行中: ").append(getRunningCount() + "）\n");

            if (readyResult != null) {
                sb.append("\n").append(readyResult);
            }

            // 端口检测
            String ports = detectPorts(serviceId);
            if (ports != null) {
                sb.append("\n").append(ports);
            }

            sb.append("\n────────────────────────────────────────\n");
            sb.append("使用 service_control 管理：\n");
            sb.append("  - service_control action=list                   查看所有服务\n");
            sb.append("  - service_control action=logs service_id=").append(serviceId).append("   查看本服务日志\n");
            sb.append("  - service_control action=stop service_id=").append(serviceId).append("    停止本服务\n");
            sb.append("────────────────────────────────────────\n");
            sb.append("当前所有后台服务：\n").append(buildServiceList());

            return sb.toString();

        } catch (IOException e) {
            log.error("启动后台服务失败", e);
            return "【启动失败】后台服务启动失败：" + e.getMessage() + "\n"
                    + "【建议】1. 确认命令可执行：在终端直接运行该命令检查是否能正常启动。\n"
                    + "  2. 检查端口是否被占用：netstat -an | findstr <端口号>（Windows）或 lsof -i :<端口号>（Linux/Mac）。\n"
                    + "  3. 检查工作目录是否正确：使用 cwd 参数指定正确的子目录。";
        }
    }

    /**
     * 判断命令是否需要 Shell 包装
     */
    private boolean needsShell(String command) {
        return SHELL_NEEDED_PATTERN.matcher(command).find();
    }

    /**
     * 启动后台线程读取服务输出到缓冲区
     */
    private void startOutputReader(int serviceId, Process process) {
        Charset charset = CommandUtils.getProcessOutputCharset();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = br.readLine()) != null) {
                    ServiceInfo info = SERVICES.get(serviceId);
                    if (info != null) {
                        info.addOutputLine(line);
                    }
                }
            } catch (IOException e) {
                log.debug("服务 #{} 输出流读取结束", serviceId);
            } finally {
                ServiceInfo info = SERVICES.get(serviceId);
                if (info != null) {
                    info.markFinished();
                }
            }
        }, "service-output-" + serviceId);
        reader.setDaemon(true);
        reader.start();
    }

    // ==================== 就绪检测 ====================

    /**
     * 等待后台服务就绪
     */
    private String waitForReady(int serviceId, String waitFor) {
        if (waitFor == null || waitFor.isBlank()) return null;

        ServiceInfo info = SERVICES.get(serviceId);
        if (info == null) return null;

        // 模式 1：数字 — 固定等待秒数
        try {
            int seconds = Integer.parseInt(waitFor.trim());
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "就绪等待：" + seconds + " 秒（固定延时）";
        } catch (NumberFormatException ignored) {
        }

        // 模式 2：URL — HTTP 健康检查
        if (waitFor.startsWith("http://") || waitFor.startsWith("https://")) {
            return waitForHttpUrl(serviceId, waitFor);
        }

        // 模式 3：关键字 — 等待输出中出现指定文本
        return waitForKeyword(serviceId, waitFor);
    }

    private String waitForHttpUrl(int serviceId, String url) {
        ServiceInfo info = SERVICES.get(serviceId);
        if (info == null) return null;

        long deadline = System.currentTimeMillis() + 60_000;

        while (System.currentTimeMillis() < deadline && info.isAlive()) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    return "就绪检测：HTTP " + code + "（" + url + "）— 服务已就绪";
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (info.isAlive()) {
            return "就绪检测：等待超时（" + url + "），服务仍在运行";
        }
        return "就绪检测：服务已结束";
    }

    private String waitForKeyword(int serviceId, String keyword) {
        ServiceInfo info = SERVICES.get(serviceId);
        if (info == null) return null;

        long deadline = System.currentTimeMillis() + 60_000;

        while (System.currentTimeMillis() < deadline && info.isAlive()) {
            List<String> output = info.getOutputLines();
            if (output.stream().anyMatch(line -> line.contains(keyword))) {
                return "就绪检测：输出中出现关键词「" + keyword + "」";
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (info.isAlive()) {
            return "就绪检测：等待关键词「" + keyword + "」超时，服务仍在运行";
        }
        return "就绪检测：服务已结束";
    }

    // ==================== 端口检测 ====================

    /**
     * 从服务输出中检测端口号
     */
    private String detectPorts(int serviceId) {
        ServiceInfo info = SERVICES.get(serviceId);
        if (info == null) return null;

        List<String> output = info.getOutputLines();
        Set<String> ports = new LinkedHashSet<>();

        for (String line : output) {
            Matcher m = PORT_PATTERN.matcher(line);
            if (m.find()) {
                ports.add(m.group(1));
            }
        }

        if (!ports.isEmpty()) {
            return "检测到端口：" + String.join(", ", ports.stream()
                    .map(p -> "localhost:" + p)
                    .collect(Collectors.toList()));
        }
        return null;
    }

    // ==================== 服务树清理 ====================

    /**
     * 终止服务及其所有子进程
     */
    public static boolean killServiceTree(int serviceId, boolean force) {
        ServiceInfo info = SERVICES.get(serviceId);
        if (info == null) return false;

        if (info.isAlive()) {
            Process process = info.process;

            if (!force) {
                process.destroy();
                try {
                    if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        SERVICES.remove(serviceId);
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (Exception e) {
                log.warn("终止子进程失败: {}", e.getMessage());
            }
            process.destroyForcibly();
        }

        SERVICES.remove(serviceId);
        return true;
    }

    // ==================== 工具方法 ====================

    private void cleanupFinishedServices() {
        SERVICES.entrySet().removeIf(entry -> {
            if (!entry.getValue().isAlive()) {
                log.debug("清理已结束的服务 #{}: {}", entry.getValue().id, entry.getValue().command);
                return true;
            }
            return false;
        });
    }

    private long getRunningCount() {
        return SERVICES.values().stream().filter(ServiceInfo::isAlive).count();
    }

    String buildServiceList() {
        if (SERVICES.isEmpty()) {
            return "  （无后台服务）";
        }
        StringBuilder sb = new StringBuilder();
        for (ServiceInfo info : SERVICES.values().stream()
                .sorted(Comparator.comparingInt(p -> p.id)).toList()) {
            boolean alive = info.isAlive();
            long elapsed = (System.currentTimeMillis() - info.startTime) / 1000;
            sb.append(String.format("  #%d %s [%s] %ds",
                    info.id,
                    info.command,
                    alive ? "运行中" : "已结束",
                    elapsed));
            if (!alive) {
                sb.append(" exit=").append(info.exitValue());
            }
            if (info.detectedPorts != null && !info.detectedPorts.isEmpty()) {
                sb.append(" ports=").append(String.join(",", info.detectedPorts));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 静态方法（供 ServiceControlTool 调用） ====================

    public static String getServiceList() {
        if (SERVICES.isEmpty()) return "暂无后台服务";
        StringBuilder sb = new StringBuilder("后台服务列表：\n");
        for (ServiceInfo info : SERVICES.values().stream()
                .sorted(Comparator.comparingInt(p -> p.id)).toList()) {
            boolean alive = info.isAlive();
            long elapsed = (System.currentTimeMillis() - info.startTime) / 1000;
            int outputLines = info.getOutputLines().size();
            sb.append(String.format("  #%d %s [%s] %ds (%d行输出)",
                    info.id, info.command, alive ? "运行中" : "已结束", elapsed, outputLines));
            if (!alive) {
                sb.append(" exit=").append(info.exitValue());
            }
            // 端口信息
            if (info.detectedPorts != null && !info.detectedPorts.isEmpty()) {
                sb.append(" ports=").append(String.join(",", info.detectedPorts));
            }
            // 显示最近 3 行输出摘要
            List<String> recent = info.getOutputTail(3);
            if (!recent.isEmpty()) {
                sb.append("\n    最新输出：\n");
                for (String line : recent) {
                    String truncated = line.length() > 100 ? line.substring(0, 100) + "..." : line;
                    sb.append("    | ").append(truncated).append("\n");
                }
            }
        }
        return sb.toString();
    }

    public static boolean stopService(int serviceId) {
        return killServiceTree(serviceId, true);
    }

    public static String readServiceOutput(int serviceId, int tailLines) {
        ServiceInfo info = SERVICES.get(serviceId);
        if (info == null) return null;

        List<String> allLines = info.getOutputLines();
        if (allLines.isEmpty()) {
            if (info.isAlive()) {
                return "（服务运行中，暂无输出）";
            } else {
                return "（服务已结束，无输出）";
            }
        }

        StringBuilder sb = new StringBuilder();
        int totalLines = allLines.size();
        sb.append("服务 #").append(serviceId).append(" 输出（共 ").append(totalLines).append(" 行）");
        if (tailLines > 0 && tailLines < totalLines) {
            sb.append("，显示最后 ").append(tailLines).append(" 行");
        }
        sb.append("：\n");
        sb.append("────────────────────────────────────────\n");

        int start = tailLines > 0 ? Math.max(0, totalLines - tailLines) : 0;
        for (int i = start; i < totalLines; i++) {
            sb.append(allLines.get(i)).append("\n");
        }
        if (info.isAlive()) {
            sb.append("（服务仍在运行，使用 service_control action=logs service_id=")
                    .append(serviceId).append(" 可继续读取）");
        } else {
            sb.append("（服务已结束，退出码: ").append(info.exitValue()).append("）");
        }
        return sb.toString();
    }

    public static ServiceInfo getService(int serviceId) {
        return SERVICES.get(serviceId);
    }

    // ==================== 服务信息类 ====================

    static class ServiceInfo {
        final int id;
        final String command;
        final Path workDir;
        final Process process;
        final long startTime;
        final boolean useShell;
        volatile boolean finished;
        volatile Integer exitCode;

        /** 检测到的端口列表 */
        volatile Set<String> detectedPorts;

        /** 输出行缓冲区 */
        private final List<String> outputLines = new LinkedList<>();
        private int totalBytes;

        ServiceInfo(int id, String command, Path workDir, Process process, long startTime, boolean useShell) {
            this.id = id;
            this.command = command;
            this.workDir = workDir;
            this.process = process;
            this.startTime = startTime;
            this.useShell = useShell;
        }

        synchronized void addOutputLine(String line) {
            int lineBytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (totalBytes + lineBytes > MAX_OUTPUT_BYTES) return;

            outputLines.add(line);
            totalBytes += lineBytes;

            if (outputLines.size() > MAX_OUTPUT_LINES) {
                String removed = outputLines.remove(0);
                totalBytes -= removed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                if (totalBytes < 0) totalBytes = 0;
            }

            // 端口检测
            Matcher m = PORT_PATTERN.matcher(line);
            if (m.find()) {
                if (detectedPorts == null) {
                    detectedPorts = new LinkedHashSet<>();
                }
                detectedPorts.add(m.group(1));
            }
        }

        synchronized List<String> getOutputLines() {
            return new ArrayList<>(outputLines);
        }

        synchronized List<String> getOutputTail(int n) {
            if (outputLines.isEmpty()) return List.of();
            int start = Math.max(0, outputLines.size() - n);
            return new ArrayList<>(outputLines.subList(start, outputLines.size()));
        }

        synchronized int getOutputLineCount() {
            return outputLines.size();
        }

        boolean isAlive() {
            if (finished) return false;
            return process.isAlive();
        }

        void markFinished() {
            finished = true;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException e) {
                exitCode = -1;
            }
        }

        int exitValue() {
            if (exitCode != null) return exitCode;
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException e) {
                return -1;
            }
        }

        long pid() {
            return process.pid();
        }
    }
}
