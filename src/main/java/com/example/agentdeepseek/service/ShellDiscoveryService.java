package com.example.agentdeepseek.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Shell 自动发现服务 —— 替代固定 cmd /c / sh -c
 * <p>
 * 检测当前系统上可用的最佳 Shell，并为给定命令生成合适的参数。
 * <p>
 * 发现优先级：
 *   Windows: pwsh (PS 7) → powershell (PS 5.1) → bash (Git) → cmd
 *   Unix:    $SHELL → zsh → bash → sh
 * <p>
 * <b>兼容性说明：</b>
 * <ul>
 *   <li>PowerShell 7 (pwsh.exe) 完整支持 && / || 命令链</li>
 *   <li>PowerShell 5.1 (powershell.exe) 不支持 && / ||，检测到命令链时自动降级为 cmd</li>
 *   <li>Bash/Zsh 不使用 -l (login shell)，避免加载用户配置文件带来的性能开销</li>
 * </ul>
 */
@Slf4j
@Service
public class ShellDiscoveryService {

    private ShellInfo detectedShell;

    @PostConstruct
    public void init() {
        detectedShell = discover();
        log.info("Shell 自动发现: {} ({})", detectedShell.name, detectedShell.path);
    }

    /**
     * 返回当前检测到的 Shell 信息
     */
    public ShellInfo getShell() {
        return detectedShell;
    }

    /**
     * 为给定的命令生成 Shell 包装参数列表（含工作目录切换）
     *
     * @param command          要执行的命令字符串
     * @param workingDirectory 工作目录（可为 null）
     * @return 适合 ProcessBuilder 的命令列表
     */
    public List<String> wrap(String command, String workingDirectory) {
        return detectedShell.buildCommand(command, workingDirectory);
    }

    // ===== 发现逻辑 =====

    private ShellInfo discover() {
        if (isWindows()) {
            // 1) pwsh.exe — PowerShell 7+（完整支持 && ||）
            String pwshPath = findInPath("pwsh.exe");
            if (pwshPath != null) {
                return new ShellInfo("pwsh", pwshPath, ShellType.PWSH);
            }
            // 2) powershell.exe — Windows PowerShell 5.1（不支持 && ||）
            String psPath = findInPath("powershell.exe");
            if (psPath != null) {
                return new ShellInfo("powershell", psPath, ShellType.POWERSHELL_LEGACY);
            }
            // 3) Git Bash
            String gitBash = findGitBash();
            if (gitBash != null) {
                return new ShellInfo("bash", gitBash, ShellType.BASH);
            }
            // 4) 兜底 cmd
            return new ShellInfo("cmd", "cmd.exe", ShellType.CMD);
        } else {
            // Unix：优先 $SHELL 环境变量
            String shellEnv = System.getenv("SHELL");
            if (shellEnv != null && !shellEnv.isEmpty()) {
                Path p = Paths.get(shellEnv);
                if (Files.exists(p) && Files.isExecutable(p)) {
                    String name = p.getFileName().toString();
                    return new ShellInfo(name, shellEnv, classifyUnix(name));
                }
            }
            // 按优先级检测常见 Shell
            for (String candidate : List.of("/bin/zsh", "/bin/bash", "/bin/sh")) {
                Path p = Paths.get(candidate);
                if (Files.exists(p) && Files.isExecutable(p)) {
                    String name = p.getFileName().toString();
                    return new ShellInfo(name, candidate, classifyUnix(name));
                }
            }
            // 绝对兜底
            return new ShellInfo("sh", "/bin/sh", ShellType.SH);
        }
    }

    // ===== 辅助 =====

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 在 PATH 中搜索可执行文件。
     * Windows 上 {@link Files#isExecutable(Path)} 行为不可靠（几乎总是返回 true），
     * 因此 Windows 下仅检查文件是否存在。
     */
    private static String findInPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] dirs = pathEnv.split(isWindows() ? ";" : ":");
        for (String dir : dirs) {
            Path full = Paths.get(dir, executable);
            if (isWindows()) {
                if (Files.exists(full)) return full.toString();
            } else {
                if (Files.exists(full) && Files.isExecutable(full)) return full.toString();
            }
        }
        return null;
    }

    private static String findGitBash() {
        String[] candidates = {
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\Git\\bin\\bash.exe",
        };
        for (String c : candidates) {
            if (c != null && Files.exists(Paths.get(c))) return c;
        }
        return findInPath("bash.exe");
    }

    private static ShellType classifyUnix(String name) {
        if (name == null) return ShellType.SH;
        return switch (name) {
            case "zsh" -> ShellType.ZSH;
            case "bash" -> ShellType.BASH;
            default -> ShellType.SH;
        };
    }

    /**
     * 检测命令是否包含 Shell 命令链操作符（&& 或 ||）
     */
    private static boolean containsShellChain(String cmd) {
        return cmd.contains("&&") || cmd.contains("||");
    }

    /**
     * 转义字符串使其安全嵌入 Bash/Zsh 双引号字符串。
     * 在双引号内需要转义的字符：\、"、$、`
     */
    static String escapeBashDq(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
    }

    // ===== 内部类型 =====

    public enum ShellType {
        /** PowerShell 7+ (pwsh.exe) — 完整支持 && || */
        PWSH,
        /** Windows PowerShell 5.1 (powershell.exe) — 不支持 && || */
        POWERSHELL_LEGACY,
        /** Git Bash / Linux bash */
        BASH,
        /** Zsh (macOS/Linux) */
        ZSH,
        /** Windows 命令提示符 */
        CMD,
        /** 通用 POSIX sh */
        SH
    }

    public static class ShellInfo {
        public final String name;
        public final String path;
        public final ShellType type;

        ShellInfo(String name, String path, ShellType type) {
            this.name = name;
            this.path = path;
            this.type = type;
        }

        /**
         * 按 Shell 类型生成命令列表。
         * <ul>
         *   <li>PWSH / POWERSHELL_LEGACY: -NoLogo -NoProfile -NonInteractive -Command "..."</li>
         *   <li>POWERSHELL_LEGACY + &&/||: 自动降级为 cmd /c</li>
         *   <li>BASH / ZSH: -c "cd DIR && cmd"</li>
         *   <li>CMD: /c "cd /d DIR && cmd"（UNC 路径自动用 pushd）</li>
         * </ul>
         * 所有路径参数均做安全转义，防止注入。
         */
        List<String> buildCommand(String cmd, String workDir) {
            String dir = (workDir != null && !workDir.isEmpty()) ? workDir : "";

            return switch (type) {
                case PWSH -> {
                    if (!dir.isEmpty()) {
                        // 转义单引号（PowerShell 中 ' → ''）
                        String safeDir = dir.replace("'", "''");
                        yield List.of(path, "-NoLogo", "-NoProfile", "-NonInteractive",
                                "-Command", "Set-Location -LiteralPath '" + safeDir + "'; " + cmd);
                    }
                    yield List.of(path, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", cmd);
                }
                case POWERSHELL_LEGACY -> {
                    // PowerShell 5.1 不支持 && / ||，检测到命令链则降级为 cmd
                    if (containsShellChain(cmd)) {
                        log.debug("PowerShell 5.1 不支持 &&/||，降级为 cmd 执行: {}", cmd);
                        if (!dir.isEmpty()) {
                            String safeDir = dir.replace("\"", "\"\"");
                            if (safeDir.startsWith("\\\\")) {
                                yield List.of("cmd.exe", "/c", "pushd \"" + safeDir + "\" && " + cmd);
                            }
                            yield List.of("cmd.exe", "/c", "cd /d \"" + safeDir + "\" && " + cmd);
                        }
                        yield List.of("cmd.exe", "/c", cmd);
                    }
                    // 无命令链，正常用 PowerShell 执行
                    if (!dir.isEmpty()) {
                        String safeDir = dir.replace("'", "''");
                        yield List.of(path, "-NoLogo", "-NoProfile", "-NonInteractive",
                                "-Command", "Set-Location -LiteralPath '" + safeDir + "'; " + cmd);
                    }
                    yield List.of(path, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", cmd);
                }
                case BASH, ZSH -> {
                    // 不用 -l (login shell)，避免加载 .bashrc/.profile 带来的 1~3s 启动延迟
                    if (!dir.isEmpty()) {
                        String safeDir = escapeBashDq(dir);
                        yield List.of(path, "-c", "cd \"" + safeDir + "\" && " + cmd);
                    }
                    yield List.of(path, "-c", cmd);
                }
                case CMD -> {
                    if (!dir.isEmpty()) {
                        String safeDir = dir.replace("\"", "\"\"");
                        // UNC 路径（\\server\share）不支持 cd /d，使用 pushd
                        if (safeDir.startsWith("\\\\")) {
                            yield List.of(path, "/c", "pushd \"" + safeDir + "\" && " + cmd);
                        }
                        yield List.of(path, "/c", "cd /d \"" + safeDir + "\" && " + cmd);
                    }
                    yield List.of(path, "/c", cmd);
                }
                case SH -> {
                    if (!dir.isEmpty()) {
                        String safeDir = escapeBashDq(dir);
                        yield List.of(path, "-c", "cd \"" + safeDir + "\" && " + cmd);
                    }
                    yield List.of(path, "-c", cmd);
                }
            };
        }

        @Override
        public String toString() {
            return path + " (" + type + ")";
        }
    }
}
