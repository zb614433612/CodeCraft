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
 * 发现优先级：
 *   Windows: pwsh → powershell → bash (Git) → cmd
 *   Unix:    $SHELL → zsh → bash → sh
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
            // 按优先级检测
            for (String candidate : List.of("pwsh.exe", "powershell.exe")) {
                String path = findInPath(candidate);
                if (path != null) {
                    return new ShellInfo("pwsh", path, ShellType.PWSH);
                }
            }
            // Git Bash（通常安装在 %ProgramFiles%/Git/bin/bash.exe）
            String gitBash = findGitBash();
            if (gitBash != null) {
                return new ShellInfo("bash", gitBash, ShellType.BASH);
            }
            // 兜底 cmd
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

    private static String findInPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] dirs = pathEnv.split(isWindows() ? ";" : ":");
        for (String dir : dirs) {
            Path full = Paths.get(dir, executable);
            if (Files.exists(full) && Files.isExecutable(full)) {
                return full.toString();
            }
        }
        return null;
    }

    private static String findGitBash() {
        // 常见 Git for Windows 安装位置
        String[] candidates = {
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                System.getenv("LOCALAPPDATA") + "\\Programs\\Git\\bin\\bash.exe",
        };
        for (String c : candidates) {
            if (c != null && Files.exists(Paths.get(c))) return c;
        }
        // 最后尝试 PATH 中找 bash.exe
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

    // ===== 内部类型 =====

    public enum ShellType {
        /** PowerShell (Windows) */
        PWSH,
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
         * PowerShell 需要 cd + 命令；bash/zsh 使用 login shell 加载用户环境；cmd 用 /c。
         */
        List<String> buildCommand(String cmd, String workDir) {
            String dir = (workDir != null && !workDir.isEmpty()) ? workDir.replace('\\', '/') : "";

            return switch (type) {
                case PWSH -> {
                    // pwsh -NoLogo -NoProfile -NonInteractive -Command "..."
                    if (!dir.isEmpty()) {
                        yield List.of(path, "-NoLogo", "-NoProfile", "-NonInteractive",
                                "-Command", "Set-Location -LiteralPath '" + dir + "'; " + cmd);
                    }
                    yield List.of(path, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", cmd);
                }
                case BASH, ZSH -> {
                    // bash -l -c "cd DIR && cmd"  —— login shell 加载 .bashrc/.zshrc
                    if (!dir.isEmpty()) {
                        yield List.of(path, "-l", "-c", "cd \"" + dir + "\" && " + cmd);
                    }
                    yield List.of(path, "-l", "-c", cmd);
                }
                case CMD -> {
                    if (!dir.isEmpty()) {
                        // cmd /c "cd /d DIR && cmd"
                        yield List.of(path, "/c", "cd /d \"" + dir + "\" && " + cmd);
                    }
                    yield List.of(path, "/c", cmd);
                }
                case SH -> {
                    if (!dir.isEmpty()) {
                        yield List.of(path, "-c", "cd \"" + dir + "\" && " + cmd);
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
