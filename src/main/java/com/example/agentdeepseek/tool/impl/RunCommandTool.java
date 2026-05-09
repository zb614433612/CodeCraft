package com.example.agentdeepseek.tool.impl;
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
import java.util.concurrent.*;

/**
 * 命令执行工具
 * 支持字符串命令和结构化参数两种模式
 */
@Slf4j
@Component
public class RunCommandTool implements Tool {

    /** 最大输出字节数 */
    private static final int MAX_OUTPUT_SIZE = 1 * 1024 * 1024;

    /** 默认超时秒数 */
    private static final int DEFAULT_TIMEOUT = 60;

    private final ObjectMapper objectMapper;

    public RunCommandTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "run_command";
    }

    @Override
    public String getDescription() {
        return "执行 shell 命令（如 mvn compile、npm build、git status、python script.py 等），返回标准输出和错误输出。"
                + "支持两种模式：① command 字符串模式（如 \"mvn clean compile\"）② executable + args 结构化模式。"
                + "可设置 timeout（默认 60s，最大 300s）。"
                + "注意：对于需要长时间运行的命令（如 dev server），请使用 run_background_command 工具";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "命令字符串（模式一），如 \"mvn clean compile\"。与 executable+args 二选一");
        properties.set("command", command);

        ObjectNode executable = objectMapper.createObjectNode();
        executable.put("type", "string");
        executable.put("description", "可执行文件（模式二），如 mvn、node、python 等");
        properties.set("executable", executable);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("type", "array");
        args.put("description", "命令参数（模式二），与 executable 搭配使用");
        ObjectNode itemsSchema = objectMapper.createObjectNode();
        itemsSchema.put("type", "string");
        args.set("items", itemsSchema);
        properties.set("args", args);

        ObjectNode cwd = objectMapper.createObjectNode();
        cwd.put("type", "string");
        cwd.put("description", "工作目录（可选），默认为项目根目录。必须为项目目录的子目录");
        properties.set("cwd", cwd);

        ObjectNode timeout = objectMapper.createObjectNode();
        timeout.put("type", "integer");
        timeout.put("description", "超时秒数，默认 60，最大 300");
        properties.set("timeout", timeout);

        parameters.set("properties", properties);
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
        String executable = arguments.path("executable").asText();
        JsonNode argsNode = arguments.path("args");
        String cwdStr = arguments.path("cwd").asText();
        int timeout = arguments.path("timeout").asInt(DEFAULT_TIMEOUT);
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;
        if (timeout > 300) timeout = 300;

        // 解析工作目录
        Path workDir = resolveWorkDir(cwdStr);
        if (workDir == null) {
            return "错误：工作目录不在项目范围内 - " + cwdStr;
        }

        // 解析命令
        List<String> cmdAndArgs;
        String execName;

        if (!executable.isEmpty()) {
            // 模式二：结构化参数
            cmdAndArgs = new ArrayList<>();
            cmdAndArgs.add(executable);
            if (argsNode != null && argsNode.isArray()) {
                for (JsonNode arg : argsNode) {
                    cmdAndArgs.add(arg.asText());
                }
            }
        } else if (!commandStr.isEmpty()) {
            // 模式一：字符串命令
            cmdAndArgs = tokenize(commandStr);
            if (cmdAndArgs.isEmpty()) {
                return "错误：命令为空";
            }
        } else {
            return "错误：请提供 command 字符串，或 executable + args 结构化参数";
        }

        // 执行命令
        return runProcess(cmdAndArgs, workDir, timeout);
    }

    /**
     * 执行进程，捕获输出，超时控制
     */
    private String runProcess(List<String> cmdAndArgs, Path workDir, int timeout) {
        StringBuilder sb = new StringBuilder();
        sb.append("执行命令：").append(String.join(" ", cmdAndArgs)).append("\n");
        sb.append("工作目录：").append(workDir.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");

        ProcessBuilder pb = new ProcessBuilder(cmdAndArgs);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();
        try {
            Process process = pb.start();

            // 读取输出（独立线程防阻塞）
            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[8192];
                    int totalRead = 0;
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        if (totalRead + n > MAX_OUTPUT_SIZE) {
                            n = MAX_OUTPUT_SIZE - totalRead;
                            output.append(buf, 0, n);
                            output.append("\n...（输出截断，超出 1MB 限制）");
                            break;
                        }
                        output.append(buf, 0, n);
                        totalRead += n;
                    }
                } catch (IOException e) {
                    // 进程结束后流关闭是正常情况
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // 等待完成或超时
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startTime;

            if (!finished) {
                process.destroyForcibly();
                sb.append("！命令超时（").append(timeout).append(" 秒），已强制终止\n");
            }

            // 等待读取线程完成
            readerThread.join(2000);

            int exitCode = process.exitValue();
            sb.append("退出码：").append(exitCode).append("\n");
            sb.append("耗时：").append(elapsed / 1000.0).append(" 秒\n");

            if (output.length() > 0) {
                sb.append("\n").append(output);
            } else {
                sb.append("\n（无输出）");
            }

            return sb.toString();

        } catch (IOException e) {
            log.error("执行命令失败", e);
            return sb.append("错误：执行命令失败 - ").append(e.getMessage()).toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return sb.append("错误：命令执行被中断").toString();
        }
    }

    /**
     * 将命令字符串解析为可执行文件 + 参数列表
     * 支持单引号和双引号包裹的参数
     */
    private List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * 解析并校验工作目录
     */
    private Path resolveWorkDir(String cwdStr) {
        Path effectiveRoot = Paths.get(ProjectRootContext.get()).normalize();
        Path dir;
        if (cwdStr == null || cwdStr.isEmpty()) {
            dir = effectiveRoot;
        } else {
            Path given = Paths.get(cwdStr);
            if (given.isAbsolute()) {
                dir = given.normalize();
            } else {
                dir = effectiveRoot.resolve(cwdStr).normalize();
            }
        }

        // 校验工作目录必须在项目根目录下
        if (dir.equals(effectiveRoot) || dir.startsWith(effectiveRoot)) {
            return dir;
        }
        return null;
    }
}
