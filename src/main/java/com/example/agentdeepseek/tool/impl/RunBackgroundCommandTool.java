package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.CommandUtils;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台命令执行工具
 * PID 管理，适合启动长时间运行的命令（如 dev server）
 */
@Slf4j
@Component
public class RunBackgroundCommandTool implements Tool {

    /** 最大并发后台进程数 */
    private static final int MAX_CONCURRENT = 3;

    /** 单个进程输出缓冲区最大行数 */
    private static final int MAX_OUTPUT_LINES = 5000;

    /** 单个进程输出缓冲区最大字节数（5MB） */
    private static final int MAX_OUTPUT_BYTES = 5 * 1024 * 1024;

    /** 后台进程存储 */
    private static final ConcurrentHashMap<Integer, ProcessInfo> PROCESSES = new ConcurrentHashMap<>();
    private static final AtomicInteger PROCESS_ID_SEQ = new AtomicInteger(1);

    private final ObjectMapper objectMapper;

    public RunBackgroundCommandTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "run_background_command";
    }

    @Override
    public String getDescription() {
        return "启动后台命令（如 npm run dev、mvn spring-boot:run、node server.js），返回进程 ID 用于后续管理。"
                + "适用于启动长时间运行的服务（开发服务器、构建监听等），不阻塞后续操作。"
                + "最大并发 " + MAX_CONCURRENT + " 个。"
                + "注意：区别于 run_command（等待完成返回输出），本工具启动进程后立即返回，进程在后台持续运行";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "后台命令，如 \"npm run dev\"、\"mvn spring-boot:run\"、\"node server.js\"。"
                + "需要实时输出请使用 run_command 工具");
        properties.set("command", command);

        ObjectNode cwd = objectMapper.createObjectNode();
        cwd.put("type", "string");
        cwd.put("description", "工作目录（可选），默认为项目根目录");
        properties.set("cwd", cwd);

        parameters.set("properties", properties);
        parameters.putArray("required").add("command");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response;
        }

        String commandStr = arguments.path("command").asText();
        String cwdStr = arguments.path("cwd").asText();

        if (commandStr.isEmpty()) {
            return "错误：缺少必要参数 command";
        }

        // 清理已结束的进程
        cleanupFinishedProcesses();

        // 检查并发限制
        long runningCount = getRunningCount();
        if (runningCount >= MAX_CONCURRENT) {
            return "错误：后台进程已达上限（" + MAX_CONCURRENT + " 个），请等待已有进程结束或手动停止。"
                    + "\n当前进程列表：\n" + buildProcessList();
        }

        // 解析命令
        List<String> tokens = CommandUtils.tokenize(commandStr);
        if (tokens.isEmpty()) {
            return "错误：命令为空";
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
            return "错误：工作目录不在项目范围内";
        }

        // 启动进程
        return startBackgroundProcess(tokens, workDir, commandStr);
    }

    /**
     * 启动后台进程
     */
    private String startBackgroundProcess(List<String> cmdAndArgs, Path workDir, String originalCommand) {
        // Windows 兼容：如果命令没有扩展名，尝试追加 PATHEXT 中的扩展名
        cmdAndArgs = CommandUtils.resolveWindowsExecutable(cmdAndArgs);

        ProcessBuilder pb = new ProcessBuilder(cmdAndArgs);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            int pid = PROCESS_ID_SEQ.getAndIncrement();

            ProcessInfo info = new ProcessInfo(pid, originalCommand, workDir, process, System.currentTimeMillis());
            PROCESSES.put(pid, info);

            // 启动输出读取线程
            startOutputReader(pid, process);

            log.info("启动后台进程 #{}: {} (PID={})", pid, originalCommand, process.pid());

            return "后台进程已启动\n"
                    + "进程 ID：" + pid + "\n"
                    + "命令：" + originalCommand + "\n"
                    + "工作目录：" + workDir.toAbsolutePath() + "\n"
                    + "系统 PID：" + process.pid() + "\n"
                    + "当前后台进程数：" + PROCESSES.size() + "（运行中: " + getRunningCount() + "）\n"
                    + "────────────────────────────────────────\n"
                    + "提示：进程会持续运行直到完成或被终止。\n"
                    + "提示：使用 read_process_output 工具查看进程输出。\n"
                    + "当前所有后台进程：\n" + buildProcessList();

        } catch (IOException e) {
            log.error("启动后台进程失败", e);
            return "错误：启动后台进程失败 - " + e.getMessage();
        }
    }

    /**
     * 启动后台线程读取进程输出到缓冲区
     */
    private void startOutputReader(int pid, Process process) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    ProcessInfo info = PROCESSES.get(pid);
                    if (info != null) {
                        info.addOutputLine(line);
                    }
                }
            } catch (IOException e) {
                log.debug("进程 #{} 输出流读取结束", pid);
            }
        }, "process-output-" + pid);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 清理已结束的进程
     * 使用 entrySet().removeIf() 保证线程安全（ConcurrentHashMap 的 entrySet 视图的 removeIf 是线程安全的）
     */
    private void cleanupFinishedProcesses() {
        PROCESSES.entrySet().removeIf(entry -> {
            if (!entry.getValue().isAlive()) {
                log.debug("清理已结束的进程 #{}: {}", entry.getValue().id, entry.getValue().command);
                return true;
            }
            return false;
        });
    }

    /**
     * 统计正在运行的进程数
     */
    private long getRunningCount() {
        return PROCESSES.values().stream().filter(p -> p.isAlive()).count();
    }

    /**
     * 列出所有后台进程
     */
    private String buildProcessList() {
        if (PROCESSES.isEmpty()) {
            return "  （无后台进程）";
        }
        StringBuilder sb = new StringBuilder();
        for (ProcessInfo info : PROCESSES.values().stream()
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
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 后台进程信息
     */
    static class ProcessInfo {
        final int id;
        final String command;
        final Path workDir;
        final Process process;
        final long startTime;

        /** 线程安全的输出行缓冲区，使用 LinkedList 避免 CopyOnWriteArrayList.remove(0) 的 O(n) 问题 */
        private final List<String> outputLines = new LinkedList<>();
        /** 已缓存的总字节数 */
        private int totalBytes;

        ProcessInfo(int id, String command, Path workDir, Process process, long startTime) {
            this.id = id;
            this.command = command;
            this.workDir = workDir;
            this.process = process;
            this.startTime = startTime;
        }

        synchronized void addOutputLine(String line) {
            // 先计算此行占用的字节数（按 UTF-8 估算）
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1; // +1 for newline

            // 如果总字节数已达上限，丢弃此行
            if (totalBytes + lineBytes > MAX_OUTPUT_BYTES) {
                return;
            }

            outputLines.add(line);
            totalBytes += lineBytes;

            // 超出最大行数时，从头部移除（LinkedList.remove(0) 是 O(1) 操作）
            if (outputLines.size() > MAX_OUTPUT_LINES) {
                String removed = outputLines.remove(0);
                // 同步减少字节计数
                totalBytes -= removed.getBytes(StandardCharsets.UTF_8).length + 1;
                if (totalBytes < 0) totalBytes = 0;
            }
        }

        synchronized List<String> getOutputLines() {
            return new ArrayList<>(outputLines);
        }

        boolean isAlive() {
            return process.isAlive();
        }

        int exitValue() {
            return process.exitValue();
        }

        long pid() {
            return process.pid();
        }
    }

    /**
     * 获取当前进程列表（供其他工具或管理用）
     */
    public static String getProcessList() {
        if (PROCESSES.isEmpty()) return "暂无后台进程";
        StringBuilder sb = new StringBuilder("后台进程列表：\n");
        for (ProcessInfo info : PROCESSES.values()) {
            boolean alive = info.isAlive();
            long elapsed = (System.currentTimeMillis() - info.startTime) / 1000;
            int outputLines = info.outputLines.size();
            sb.append(String.format("  #%d %s [%s] %ds (%d行输出)",
                    info.id, info.command, alive ? "运行中" : "已结束", elapsed, outputLines)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 停止指定进程（供其他工具调用）
     */
    public static boolean stopProcess(int processId) {
        ProcessInfo info = PROCESSES.get(processId);
        if (info == null) return false;
        if (info.isAlive()) {
            info.process.destroyForcibly();
        }
        PROCESSES.remove(processId);
        return true;
    }

    /**
     * 读取指定进程的输出（供其他工具调用）
     */
    public static String readProcessOutput(int processId, int tailLines) {
        ProcessInfo info = PROCESSES.get(processId);
        if (info == null) return null;

        List<String> allLines = info.getOutputLines();
        if (allLines.isEmpty()) {
            if (info.isAlive()) {
                return "（进程运行中，暂无输出）";
            } else {
                return "（进程已结束，无输出）";
            }
        }

        StringBuilder sb = new StringBuilder();
        int totalLines = allLines.size();
        sb.append("进程 #").append(processId).append(" 输出（共 ").append(totalLines).append(" 行）");
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
            sb.append("（进程仍在运行，使用 read_process_output 可继续读取）");
        } else {
            sb.append("（进程已结束，退出码: ").append(info.exitValue()).append("）");
        }
        return sb.toString();
    }

    /**
     * 获取指定进程信息
     */
    public static ProcessInfo getProcess(int processId) {
        return PROCESSES.get(processId);
    }
}
