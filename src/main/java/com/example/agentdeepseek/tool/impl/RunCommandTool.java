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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 命令执行工具
 * <p>
 * 两种模式：
 * 1. command 字符串模式（推荐）：通过系统 Shell 执行，支持管道、重定向、环境变量
 * 2. executable + args 结构化模式：直接 ProcessBuilder 执行，适合简单命令
 * <p>
 * 主要改进：
 * - Shell 包装消除管道/重定向/环境变量不支持的问题
 * - 执行前预检可执行文件是否存在
 * - 超时自动延长（输出持续产生时不会超时）
 * - 友好错误提示帮助 AI 自纠正
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.EXECUTE, affectsData = true, highRisk = true, description = "执行系统命令")
public class RunCommandTool implements Tool {

    /** 最大输出字节数 */
    private static final int MAX_OUTPUT_SIZE = 1 * 1024 * 1024;

    /** 默认超时秒数（给编译/安装等操作充足时间） */
    private static final int DEFAULT_TIMEOUT = 120;

    /** 最大超时秒数 */
    private static final int MAX_TIMEOUT = 600;

    /** 输出持续时自动延长的最大累计超时 */
    private static final int ABSOLUTE_MAX_TIMEOUT = 900;

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
        return "【适用场景】执行一次性命令（编译、安装依赖、运行测试、Git 操作等），需要获取完整输出后再继续。\n"
                + "【使用方式】提供 command 字符串（推荐）或 executable+args 结构化参数：\n"
                + "  command 模式（推荐）：通过系统 Shell 执行，支持管道|、重定向> >>、环境变量$VAR、命令链&& ||。\n"
                + "    示例：\"mvn clean compile -DskipTests\"、\"cd frontend && npm run build\"、\"git status | grep modified\"\n"
                + "  executable+args 模式：直接启动进程，适合简单命令（无需 Shell 特性时使用）。\n"
                + "    示例：executable=\"mvn\" args=[\"--version\"]\n"
                + "  timeout：默认 120 秒，最大 600 秒。输出持续产生时会自动延长，编译/安装等长耗时操作通常无需手动调大。\n"
                + "【与 run_server 的区别】run_command 等待命令结束并返回完整输出；run_server 在后台启动服务后立即返回，"
                + "适合 npm run dev、mvn spring-boot:run 等持续运行的服务，后续用 service_control 管理。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "【必填（与 executable+args 二选一）】通过系统 Shell 执行的命令字符串。"
                + "支持管道|、重定向> >>、环境变量$VAR、命令链&& ||。"
                + "示例：\"mvn clean compile -DskipTests\"、\"cd frontend && npm run build\"、\"git log --oneline -5\"");
        properties.set("command", command);

        ObjectNode executable = objectMapper.createObjectNode();
        executable.put("type", "string");
        executable.put("description", "【必填（与 command 二选一）】直接启动的可执行文件，如 mvn、node、python、git。"
                + "适合不需要管道的简单命令。示例：executable=\"mvn\" args=[\"--version\"]");
        properties.set("executable", executable);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("type", "array");
        args.put("description", "【可选（配合 executable 使用）】命令参数列表，每个元素为一个字符串参数。"
                + "示例：[\"clean\", \"compile\", \"-DskipTests\"]");
        ObjectNode itemsSchema = objectMapper.createObjectNode();
        itemsSchema.put("type", "string");
        args.set("items", itemsSchema);
        properties.set("args", args);

        ObjectNode cwd = objectMapper.createObjectNode();
        cwd.put("type", "string");
        cwd.put("description", "【可选，默认项目根目录】命令执行的工作目录。"
                + "可使用相对路径（相对于项目根目录）或绝对路径。示例：\"frontend\"、\"/abs/path/to/module\"");
        properties.set("cwd", cwd);

        ObjectNode timeout = objectMapper.createObjectNode();
        timeout.put("type", "integer");
        timeout.put("description", "【可选，默认 120 秒】命令超时秒数，最大 600。"
                + "输出持续产生时自动延长超时，通常无需手动设置。示例：编译大型项目时可设为 300");
        properties.set("timeout", timeout);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {

        String commandStr = arguments.path("command").asText();
        String executable = arguments.path("executable").asText();
        JsonNode argsNode = arguments.path("args");
        String cwdStr = arguments.path("cwd").asText();

        int timeout = arguments.path("timeout").asInt(DEFAULT_TIMEOUT);
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;
        if (timeout > MAX_TIMEOUT) timeout = MAX_TIMEOUT;

        // 解析工作目录
        Path workDir = resolveWorkDir(cwdStr);
        if (workDir == null) {
            return "【参数错误】工作目录 \"" + cwdStr + "\" 不在项目范围内。\n"
                    + "【建议】cwd 必须是项目根目录或其子目录。"
                    + "使用相对路径如 \"frontend\" 或留空使用项目根目录。";
        }

        // 命令模式一：Shell 字符串（推荐）
        if (!commandStr.isEmpty()) {
            // 预检：检查可执行文件是否存在（仅作提示，不阻塞执行）
            String checkResult = CommandUtils.checkExecutableExists(commandStr);
            if (checkResult != null) {
                return checkResult;
            }
            return runViaShell(commandStr, workDir, timeout);
        }

        // 命令模式二：结构化 executable + args
        if (!executable.isEmpty()) {
            List<String> cmdAndArgs = new ArrayList<>();
            cmdAndArgs.add(executable);
            if (argsNode != null && argsNode.isArray()) {
                for (JsonNode arg : argsNode) {
                    cmdAndArgs.add(arg.asText());
                }
            }
            if (cmdAndArgs.size() == 1) {
                return "【参数错误】executable 模式下 args 参数为空。\n"
                        + "【建议】请提供 args 参数列表，如 args=[\"--version\"]。"
                        + "或改用 command 模式：command=\"mvn --version\"";
            }
            return runDirect(cmdAndArgs, workDir, timeout);
        }

        return "【参数错误】未提供 command 或 executable 参数。\n"
                + "【建议】推荐使用 command 模式，如 command=\"mvn clean compile\"。"
                + "也可使用 executable+args 模式，如 executable=\"mvn\" args=[\"clean\", \"compile\"]。";
    }

    // ==================== Shell 模式（推荐）====================

    /**
     * 通过系统 Shell 执行命令，支持管道、重定向、环境变量等 Shell 特性
     * <p>
     * 编码兼容性处理：
     * Windows 中文系统默认代码页为 GBK（CP936），但 Java 项目文件通常以 UTF-8 存储。
     * 当执行 .bat/.ps1 等脚本文件时，如果系统代码页不是 UTF-8，
     * 自动在命令前注入 {@code chcp 65001 > nul &&} 切换为 UTF-8 代码页，
     * 确保脚本文件（UTF-8 编码）能被 cmd.exe 正确解析，输出也不会乱码。
     */
    private String runViaShell(String commandStr, Path workDir, int timeout) {
        // 预检
        String checkResult = CommandUtils.checkExecutableExists(commandStr);
        if (checkResult != null) {
            return checkResult;
        }

        // Windows 编码适配：若当前代码页不是 UTF-8，注入 chcp 65001 切换代码页
        // 确保 UTF-8 编码的脚本文件（.bat/.ps1）能被 cmd.exe 正确解析
        String effectiveCommand = commandStr;
        if (CommandUtils.isWindows() && !CommandUtils.isUtf8CodePage()) {
            effectiveCommand = "chcp 65001 > nul && " + commandStr;
            log.debug("注入 chcp 65001 适配编码环境: {}", commandStr);
        }

        List<String> cmdLine = CommandUtils.buildShellCommand(effectiveCommand);
        log.debug("Shell 执行: {}", String.join(" ", cmdLine));

        // displayName 保持原始命令，方便用户查看
        return runProcess(cmdLine, workDir, timeout, commandStr);
    }

    // ==================== 直接模式（结构化参数）====================

    /**
     * 直接通过 ProcessBuilder 执行（不经过 Shell）
     */
    private String runDirect(List<String> cmdAndArgs, Path workDir, int timeout) {
        // Windows 兼容
        cmdAndArgs = CommandUtils.resolveWindowsExecutable(cmdAndArgs);
        log.debug("直接执行: {}", String.join(" ", cmdAndArgs));

        return runProcess(cmdAndArgs, workDir, timeout, cmdAndArgs.get(0));
    }

    // ==================== 进程执行核心 ====================

    /**
     * 执行进程，捕获输出，超时控制
     */
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

            // 异步读取输出
            StringBuilder output = new StringBuilder();
            Thread readerThread = startOutputReader(process, output, charset);

            // 等待完成或超时（带输出延长机制）
            boolean completed = false;
            long outputLenAtCheck = 0;
            int idleExtensions = 0;

            while (!completed) {
                completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
                if (completed) break;

                // 检查输出是否还在增长
                long currentLen = output.length();
                if (currentLen > outputLenAtCheck) {
                    // 输出仍在产生，自动延长超时
                    outputLenAtCheck = currentLen;
                    idleExtensions = 0;
                    int extendedTimeout = Math.min(timeout, ABSOLUTE_MAX_TIMEOUT - (int) (System.currentTimeMillis() - startTime) / 1000);
                    if (extendedTimeout > 10) {
                        effectiveTimeout = Math.min(extendedTimeout, 60);
                        log.debug("命令输出持续增长，自动延长超时: {}s", effectiveTimeout);
                        continue;
                    }
                } else {
                    idleExtensions++;
                }

                // 无输出变化且已超时
                if (idleExtensions >= 2) {
                    process.destroyForcibly();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        log.warn("强制终止进程后仍未退出: {}", displayName);
                    }
                    long elapsed = System.currentTimeMillis() - startTime;
                    sb.append("！命令超时（").append(elapsed / 1000).append(" 秒），输出无变化超过 ").append(timeout).append(" 秒，已强制终止\n");
                    break;
                }
            }

            // 等待读取线程完成
            readerThread.join(2000);
            long elapsed = System.currentTimeMillis() - startTime;

            if (completed) {
                try {
                    int exitCode = process.exitValue();
                    sb.append("退出码：").append(exitCode).append("\n");
                } catch (IllegalThreadStateException e) {
                    sb.append("退出码：未知（进程可能被强制终止）\n");
                }
            }
            sb.append("耗时：").append(elapsed / 1000.0).append(" 秒\n");

            if (output.length() > 0) {
                sb.append("\n").append(output);
            } else {
                sb.append("\n（无输出）");
            }

            return sb.toString();

        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Cannot run program") || msg.contains("error=2"))) {
                // 命令未找到的友好提示
                return displayName + "\n工作目录：" + workDir.toAbsolutePath() + "\n"
                        + "────────────────────────────────────────\n"
                        + "【命令未找到】命令无法执行。\n"
                        + "详情：" + msg + "\n"
                        + "【建议】1. 确认命令已安装：在终端运行 \"<命令> --version\" 检查。\n"
                        + "  2. 使用项目本地脚本：如 \"./mvnw\"（注意加 ./ 前缀）。\n"
                        + "  3. 检查命令名拼写：区分大小写，如 Mvn 和 mvn 不同。";
            }
            log.error("执行命令失败", e);
            return "【执行异常】命令执行失败：" + e.getMessage() + "\n"
                    + "【建议】请检查命令语法、文件权限和工作目录是否正确。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "【执行中断】命令执行被中断。\n"
                    + "【建议】如果因超时中断，可增大 timeout 参数重试；如果是并发执行冲突，请等待当前命令完成后再执行。";
        }
    }

    /**
     * 启动后台线程读取进程输出
     */
    private Thread startOutputReader(Process process, StringBuilder output, Charset charset) {
        Thread reader = new Thread(() -> {
            try (BufferedReader reader_ = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                char[] buf = new char[8192];
                int totalRead = 0;
                int n;
                while ((n = reader_.read(buf)) != -1) {
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
                log.debug("命令输出流读取结束: {}", e.getMessage());
            }
        }, "cmd-output-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    /**
     * 解析并校验工作目录
     */
    private Path resolveWorkDir(String cwdStr) {
        Path effectiveRoot = Paths.get(ProjectRootContext.get()).normalize();
        if (cwdStr == null || cwdStr.isEmpty()) {
            return effectiveRoot;
        }
        Path given = Paths.get(cwdStr);
        Path dir = given.isAbsolute() ? given.normalize() : effectiveRoot.resolve(cwdStr).normalize();

        if (dir.equals(effectiveRoot) || dir.startsWith(effectiveRoot)) {
            return dir;
        }
        return null;
    }
}
