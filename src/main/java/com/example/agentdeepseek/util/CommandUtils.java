package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 命令行工具公用方法
 * <p>
 * 核心改进：通过系统 Shell 执行命令，支持管道、重定向、环境变量等 Shell 特性。
 * 提供预检、编码检测等辅助功能。
 */
@Slf4j
public class CommandUtils {

    /**
     * 构建 Shell 包装命令，自动发现最佳可用 Shell
     * <p>
     * Windows: pwsh → powershell → Git Bash → cmd
     * Unix:    $SHELL → zsh → bash → sh
     *
     * @param command 原始命令字符串（如 "mvn compile | grep error"）
     * @return 适合 ProcessBuilder 的命令列表
     */
    public static List<String> buildShellCommand(String command) {
        return buildShellCommand(command, null);
    }

    /**
     * 构建 Shell 包装命令（含工作目录切换），自动发现最佳可用 Shell
     *
     * @param command          原始命令字符串
     * @param workingDirectory 工作目录（可为 null）
     * @return 适合 ProcessBuilder 的命令列表
     */
    public static List<String> buildShellCommand(String command, String workingDirectory) {
        if (isWindows()) {
            // 按优先级检测 Windows Shell
            String pwsh = findShellInPath("pwsh.exe");
            if (pwsh != null) {
                return psWrap(pwsh, command, workingDirectory);
            }
            String powershell = findShellInPath("powershell.exe");
            if (powershell != null) {
                return psWrap(powershell, command, workingDirectory);
            }
            String bash = findGitBashPath();
            if (bash != null) {
                return bashWrap(bash, command, workingDirectory);
            }
            return cmdWrap(command, workingDirectory);
        } else {
            // Unix: $SHELL → zsh → bash → sh
            String shellEnv = System.getenv("SHELL");
            if (shellEnv != null && !shellEnv.isEmpty()) {
                Path p = Paths.get(shellEnv);
                if (Files.exists(p) && Files.isExecutable(p)) {
                    String name = p.getFileName().toString();
                    if ("zsh".equals(name) || "bash".equals(name)) {
                        return bashWrap(shellEnv, command, workingDirectory);
                    }
                    return shWrap(shellEnv, command, workingDirectory);
                }
            }
            for (String candidate : List.of("/bin/zsh", "/bin/bash")) {
                Path p = Paths.get(candidate);
                if (Files.exists(p) && Files.isExecutable(p)) {
                    return bashWrap(candidate, command, workingDirectory);
                }
            }
            return shWrap("/bin/sh", command, workingDirectory);
        }
    }

    // ===== Shell 包装方法 =====

    private static List<String> psWrap(String shell, String cmd, String dir) {
        if (dir != null && !dir.isEmpty()) {
            String d = dir.replace('\\', '/');
            return List.of(shell, "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-Command", "Set-Location -LiteralPath '" + d + "'; " + cmd);
        }
        return List.of(shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", cmd);
    }

    private static List<String> bashWrap(String shell, String cmd, String dir) {
        if (dir != null && !dir.isEmpty()) {
            return List.of(shell, "-l", "-c", "cd \"" + dir + "\" && " + cmd);
        }
        return List.of(shell, "-l", "-c", cmd);
    }

    private static List<String> shWrap(String shell, String cmd, String dir) {
        if (dir != null && !dir.isEmpty()) {
            return List.of(shell, "-c", "cd \"" + dir + "\" && " + cmd);
        }
        return List.of(shell, "-c", cmd);
    }

    private static List<String> cmdWrap(String cmd, String dir) {
        if (dir != null && !dir.isEmpty()) {
            return List.of("cmd.exe", "/c", "cd /d \"" + dir + "\" && " + cmd);
        }
        return List.of("cmd.exe", "/c", cmd);
    }

    // ===== Shell 发现辅助 =====

    private static String findShellInPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(isWindows() ? ";" : ":")) {
            if (dir.isEmpty()) continue;
            Path full = Paths.get(dir, name);
            if (Files.exists(full) && Files.isExecutable(full)) {
                return full.toString();
            }
        }
        return null;
    }

    private static String findGitBashPath() {
        for (String c : List.of(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe")) {
            if (Files.exists(Paths.get(c))) return c;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path p = Paths.get(localAppData, "Programs", "Git", "bin", "bash.exe");
            if (Files.exists(p)) return p.toString();
        }
        return findShellInPath("bash.exe");
    }

    /**
     * 检查命令是否可以执行的最外层（用于快速失败反馈）
     * <p>
     * 提取命令的第一个 token 作为可执行文件名，在 PATH 中搜索。
     * 仅在命令看起来不包含特殊字符（无管道、重定向等）时有效。
     * 有 Shell 包装时此检查作为辅助提示，不阻塞执行（Shell 自己也会报错）。
     *
     * @param command 命令字符串
     * @return 如果可执行文件未找到，返回友好的错误描述；找到则返回 null
     */
    public static String checkExecutableExists(String command) {
        if (command == null || command.isBlank()) return null;

        // 提取第一个 token（可执行文件名）
        String trimmed = command.trim();
        String firstToken = tokenize(trimmed).stream().findFirst().orElse(null);
        if (firstToken == null) return null;

        // 路径形式（包含 / 或 \）→ 检查文件是否存在
        if (firstToken.contains("/") || firstToken.contains("\\")) {
            Path execPath = Paths.get(firstToken);
            if (!Files.exists(execPath)) {
                return "【文件不存在】指定的可执行文件未找到：" + execPath.toAbsolutePath() + "\n"
                        + "【建议】1. 确认文件路径拼写正确（区分大小写）。\n"
                        + "  2. 确认文件确实存在于该位置：ls -la " + execPath.toAbsolutePath() + "\n"
                        + "  3. 如果在子目录中，使用正确的相对路径，如 \"./subdir/tool\"。";
            }
            return null;
        }

        // 内置命令或别名 → 跳过检查（由 Shell 处理）
        if (isBuiltinCommand(firstToken)) return null;

        // 在 PATH 中搜索
        String fullPath = findExecutableInPath(firstToken);
        if (fullPath == null) {
            return "【命令未找到】\"" + firstToken + "\" 不在系统 PATH 中，可能未安装或未配置环境变量。\n"
                    + "【建议】1. 确认已安装该工具：运行 \"" + firstToken + " --version\" 在终端中验证。\n"
                    + "  2. 如果使用项目本地脚本（如 mvnw），使用相对路径：\"./mvnw\" 代替 \"mvnw\"。\n"
                    + "  3. 如果刚安装，可能需要重启终端或刷新 PATH 环境变量。";
        }
        return null;
    }

    /**
     * 在 PATH 中搜索可执行文件
     *
     * @param name 可执行文件名（如 "mvn", "node", "npm"）
     * @return 完整路径，未找到返回 null
     */
    public static String findExecutableInPath(String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.contains("/") || name.contains("\\")) return null;

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return null;

        String[] pathDirs = pathEnv.split(isWindows() ? ";" : ":");
        List<String> extensions = isWindows() ? getWindowsExecutableExtensions() : List.of("");

        for (String dir : pathDirs) {
            if (dir.isEmpty()) continue;
            Path dirPath = Paths.get(dir);
            if (!Files.isDirectory(dirPath)) continue;
            for (String ext : extensions) {
                Path fullPath = dirPath.resolve(name + ext);
                if (Files.exists(fullPath) && Files.isExecutable(fullPath)) {
                    return fullPath.toString();
                }
            }
        }
        return null;
    }

    /** chcp 查询结果缓存（1分钟内不重复查询） */
    private static volatile Charset detectedOutputCharset = null;
    private static volatile long lastChcpCheckTime = 0;
    private static final long CHCP_CACHE_TTL_MS = 60_000;

    /**
     * 获取适合当前系统的进程输出编码
     * <p>
     * Windows 上通过 chcp.com 查询当前活动代码页，准确判断 CMD/PowerShell 输出编码。
     * 缓存检测结果 1 分钟，避免频繁调用 chcp。
     * Linux/Mac 上直接返回 UTF-8。
     * <p>
     * 相比 {@code Charset.defaultCharset()} 的优势：
     * 当 JVM 设置了 -Dfile.encoding=UTF-8 时，defaultCharset 返回 UTF-8，
     * 但 Windows cmd 输出仍是系统代码页编码，此前方案会导致乱码。
     */
    public static Charset getProcessOutputCharset() {
        if (isWindows()) {
            // 缓存命中且在有效期内
            if (detectedOutputCharset != null
                    && System.currentTimeMillis() - lastChcpCheckTime < CHCP_CACHE_TTL_MS) {
                return detectedOutputCharset;
            }
            // 通过 chcp.com 查询当前活动代码页
            return detectWindowsCodePage();
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 通过 chcp.com 查询 Windows 当前活动代码页
     */
    private static synchronized Charset detectWindowsCodePage() {
        // 双重检查锁定
        if (detectedOutputCharset != null
                && System.currentTimeMillis() - lastChcpCheckTime < CHCP_CACHE_TTL_MS) {
            return detectedOutputCharset;
        }
        try {
            Process process = Runtime.getRuntime().exec("chcp.com");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null) {
                    // chcp 输出格式: "Active code page: 936"
                    Matcher matcher = Pattern.compile("(\\d+)").matcher(line);
                    if (matcher.find()) {
                        int codePage = Integer.parseInt(matcher.group());
                        detectedOutputCharset = codePageToCharset(codePage);
                        lastChcpCheckTime = System.currentTimeMillis();
                        log.debug("Windows 代码页检测: {} -> {}", codePage, detectedOutputCharset);
                        return detectedOutputCharset;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("chcp 查询失败，回退 defaultCharset: {}", e.getMessage());
        }
        // 查询失败时回退到 Charset.defaultCharset()
        detectedOutputCharset = Charset.defaultCharset();
        lastChcpCheckTime = System.currentTimeMillis();
        return detectedOutputCharset;
    }

    /**
     * 将 Windows 代码页号映射为 Java Charset
     */
    private static Charset codePageToCharset(int codePage) {
        switch (codePage) {
            case 65001: return StandardCharsets.UTF_8;
            case 936:   return Charset.forName("GBK");
            case 950:   return Charset.forName("Big5");
            case 932:   return Charset.forName("Shift_JIS");
            case 949:   return Charset.forName("EUC-KR");
            case 1250:  return Charset.forName("windows-1250");
            case 1251:  return Charset.forName("windows-1251");
            case 1252:  return Charset.forName("windows-1252");
            case 1253:  return Charset.forName("windows-1253");
            case 1254:  return Charset.forName("windows-1254");
            case 1255:  return Charset.forName("windows-1255");
            case 1256:  return Charset.forName("windows-1256");
            case 1257:  return Charset.forName("windows-1257");
            case 1258:  return Charset.forName("windows-1258");
            default:
                log.debug("未知代码页 {}，使用 defaultCharset", codePage);
                return Charset.defaultCharset();
        }
    }

    /**
     * 判断 Windows 当前代码页是否为 UTF-8（65001）
     */
    public static boolean isUtf8CodePage() {
        if (!isWindows()) return true;
        return getProcessOutputCharset().equals(StandardCharsets.UTF_8);
    }

    /**
     * Windows 兼容：解析可执行文件的完整路径
     * ProcessBuilder 在 Windows 上不会自动查找 PATHEXT（.cmd、.bat 等），
     * 导致直接使用 "mvn"、"npm"、"node" 等命令时找不到文件。
     * 此方法在 PATH 中搜索带扩展名的可执行文件并返回完整路径。
     * <p>
     * 仅用于结构化模式（executable + args），Shell 模式不需要此处理。
     *
     * @param cmdAndArgs 命令及参数列表，第一个元素为可执行文件名
     * @return 解析后的命令列表（第一个元素替换为完整路径），非 Windows 或无变化时返回原列表
     */
    public static List<String> resolveWindowsExecutable(List<String> cmdAndArgs) {
        if (cmdAndArgs == null || cmdAndArgs.isEmpty()) return cmdAndArgs;
        if (!isWindows()) return cmdAndArgs;

        String exec = cmdAndArgs.get(0);
        if (exec.contains(".") || exec.contains(File.separator)) return cmdAndArgs;

        List<String> extensions = getWindowsExecutableExtensions();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return cmdAndArgs;

        String[] pathDirs = pathEnv.split(";");
        for (String dir : pathDirs) {
            if (dir.isEmpty()) continue;
            Path dirPath = Paths.get(dir);
            if (!Files.isDirectory(dirPath)) continue;
            for (String ext : extensions) {
                Path fullPath = dirPath.resolve(exec + ext);
                if (Files.exists(fullPath)) {
                    List<String> result = new ArrayList<>(cmdAndArgs);
                    result.set(0, fullPath.toString());
                    log.debug("Windows 可执行文件解析: {} -> {}", exec, fullPath);
                    return result;
                }
            }
        }
        log.warn("Windows 可执行文件未找到: {} (已搜索 PATH)", exec);
        return cmdAndArgs;
    }

    /**
     * 判断当前是否为 Windows 系统
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 将命令字符串解析为可执行文件 + 参数列表
     * 支持单引号、双引号包裹的参数，以及反斜杠转义
     *
     * @param command 原始命令字符串
     * @return 解析后的 token 列表
     */
    public static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && !inSingleQuote) {
                if (i + 1 < command.length()) {
                    i++;
                    current.append(command.charAt(i));
                } else {
                    current.append('\\');
                }
                continue;
            }

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
     * 检查是否为 Shell 内置命令（不需要在 PATH 中存在）
     */
    private static boolean isBuiltinCommand(String cmd) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (isWindows()) {
            return List.of("cd", "dir", "echo", "set", "cls", "type", "copy", "del",
                            "mkdir", "rmdir", "move", "ren", "call", "exit", "path",
                            "pause", "prompt", "pushd", "popd", "title", "color")
                    .contains(lower);
        } else {
            return List.of("cd", "echo", "exit", "export", "pwd", "set", "unset",
                            "alias", "type", "source", "bg", "fg", "jobs", "kill",
                            "test", "[", "true", "false")
                    .contains(lower);
        }
    }

    /**
     * 获取 Windows 可执行文件扩展名列表（从 PATHEXT 环境变量读取）
     */
    private static List<String> getWindowsExecutableExtensions() {
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isEmpty()) {
            return List.of(".exe", ".cmd", ".bat", ".com");
        }
        return Arrays.stream(pathext.split(";"))
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }
}
