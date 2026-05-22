package com.example.agentdeepseek.tool.postedit;

import com.example.agentdeepseek.config.EditFileConfig;
import com.example.agentdeepseek.util.ProjectRootContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * 编辑后自动格式化模块
 * <p>
 * 根据项目类型自动选择合适的格式化工具：
 * - Java → Maven Spotless / Maven JavaFormat
 * - 前端文件 → Prettier
 * - Python → autopep8 / Black
 * <p>
 * 所有格式化操作均设超时，失败不阻塞主流程。
 */
@Slf4j
@Component
public class Formatter {

    private final EditFileConfig config;

    public Formatter(EditFileConfig config) {
        this.config = config;
    }

    /**
     * 格式化结果
     */
    public record FormatResult(boolean executed, String tool, boolean success, String message) {
        public String toDisplay() {
            if (!executed) return "";
            if (success) {
                return "✅ 已自动格式化（" + tool + "）";
            } else {
                return "⚠️ 格式化跳过：" + message;
            }
        }
    }

    /**
     * 对指定文件执行格式化
     */
    public FormatResult format(Path filePath) {
        if (!config.getFormatter().isEnabled()) {
            return new FormatResult(false, "", false, "格式化未启用");
        }

        String fileName = filePath.getFileName().toString().toLowerCase();
        String projectRoot = ProjectRootContext.get();
        if (projectRoot == null) {
            return new FormatResult(false, "", false, "未设置项目根目录");
        }

        try {
            if (fileName.endsWith(".java")) {
                return formatJavaFile(filePath, projectRoot);
            } else if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")
                    || fileName.endsWith(".js") || fileName.endsWith(".jsx")
                    || fileName.endsWith(".vue") || fileName.endsWith(".css")
                    || fileName.endsWith(".scss") || fileName.endsWith(".less")
                    || fileName.endsWith(".json") || fileName.endsWith(".html")
                    || fileName.endsWith(".md") || fileName.endsWith(".yml")
                    || fileName.endsWith(".yaml")) {
                return formatWithPrettier(filePath, projectRoot);
            } else if (fileName.endsWith(".py")) {
                return formatWithPython(filePath);
            } else {
                return new FormatResult(false, "", false, "不支持的文件类型：" + fileName);
            }
        } catch (Exception e) {
            log.warn("格式化异常: file={}, err={}", filePath, e.getMessage());
            return new FormatResult(false, "", false, "格式化异常：" + e.getMessage());
        }
    }

    private FormatResult formatJavaFile(Path filePath, String projectRoot) {
        // 校验 projectRoot 是合法目录，防止注入
        Path projectDir = validateDirPath(projectRoot);
        if (projectDir == null) {
            return new FormatResult(false, "", false, "项目根目录无效：" + projectRoot);
        }

        // 方案1：Maven spotless:apply（最推荐，支持多种 Java 风格配置）
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            try {
                String[] cmd = {"mvn", "spotless:apply", "-q",
                        "-f", projectDir.toAbsolutePath().toString()};
                int exitCode = execCommand(cmd, projectDir.toAbsolutePath().toString(),
                        config.getFormatter().getTimeout());
                if (exitCode == 0) {
                    return new FormatResult(true, "Maven Spotless", true, "");
                }
                log.debug("Maven spotless 返回非零退出码: {}", exitCode);
            } catch (Exception e) {
                log.debug("Maven spotless 不可用: {}", e.getMessage());
            }

            // 方案2：Maven javaformat:format（Google Java Style 专用）
            try {
                String[] cmd = {"mvn", "javaformat:format", "-q",
                        "-f", projectDir.toAbsolutePath().toString()};
                int exitCode = execCommand(cmd, projectDir.toAbsolutePath().toString(),
                        config.getFormatter().getTimeout());
                if (exitCode == 0) {
                    return new FormatResult(true, "Maven JavaFormat", true, "");
                }
                log.debug("Maven javaformat 返回非零退出码: {}", exitCode);
            } catch (Exception e) {
                log.debug("Maven javaformat 不可用: {}", e.getMessage());
            }
        }

        return new FormatResult(false, "", false,
                "未找到可用的 Java 格式化工具（项目需配置 Maven spotless 或 javaformat 插件）");
    }

    private FormatResult formatWithPrettier(Path filePath, String projectRoot) {
        // 校验路径合法性
        Path resolvedFile = validateFilePath(filePath);
        if (resolvedFile == null) {
            return new FormatResult(false, "", false, "文件路径无效：" + filePath);
        }
        Path projectDir = validateDirPath(projectRoot);
        if (projectDir == null) {
            return new FormatResult(false, "", false, "项目根目录无效");
        }

        Path packageJsonPath = projectDir.resolve("package.json");
        if (!Files.exists(packageJsonPath)) {
            return new FormatResult(false, "", false, "未找到 package.json，无法使用 Prettier");
        }

        try {
            // 优先使用本地 node_modules 中的 prettier
            String prettierBin = null;
            Path localPrettier = projectDir.resolve("node_modules").resolve(".bin").resolve("prettier");
            Path localPrettierCmd = projectDir.resolve("node_modules").resolve(".bin").resolve("prettier.cmd");
            if (Files.exists(localPrettier)) {
                prettierBin = localPrettier.toAbsolutePath().toString();
            } else if (Files.exists(localPrettierCmd)) {
                prettierBin = localPrettierCmd.toAbsolutePath().toString();
            }

            String fileArg = resolvedFile.toAbsolutePath().toString();

            if (prettierBin != null) {
                // prettierBin 来自本地 node_modules，由系统管理，无注入风险
                String[] cmd = {prettierBin, "--write", fileArg};
                int exitCode = execCommand(cmd, projectDir.toAbsolutePath().toString(),
                        config.getFormatter().getTimeout());
                if (exitCode == 0) {
                    return new FormatResult(true, "Prettier", true, "");
                }
                return new FormatResult(false, "", false, "Prettier 格式化失败（exit=" + exitCode + "）");
            }

            // 回退：使用 npx（fileArg 经过 Path 标准化，无注入风险）
            String[] cmd = {"npx", "--yes", "prettier", "--write", fileArg};
            int exitCode = execCommand(cmd, projectDir.toAbsolutePath().toString(),
                    config.getFormatter().getTimeout());
            if (exitCode == 0) {
                return new FormatResult(true, "Prettier (npx)", true, "");
            }
            return new FormatResult(false, "", false,
                    "Prettier 未安装，请运行 npm install prettier");

        } catch (Exception e) {
            return new FormatResult(false, "", false, "Prettier 格式化异常：" + e.getMessage());
        }
    }

    private FormatResult formatWithPython(Path filePath) {
        // 方案1：autopep8
        try {
            String[] cmd = {"python", "-m", "autopep8", "--in-place",
                    filePath.toAbsolutePath().toString()};
            int exitCode = execCommand(cmd, null, config.getFormatter().getTimeout());
            if (exitCode == 0) {
                return new FormatResult(true, "autopep8", true, "");
            }
        } catch (Exception e) {
            log.debug("autopep8 不可用: {}", e.getMessage());
        }

        // 方案2：Black
        try {
            String[] cmd = {"python", "-m", "black", "-q",
                    filePath.toAbsolutePath().toString()};
            int exitCode = execCommand(cmd, null, config.getFormatter().getTimeout());
            if (exitCode == 0) {
                return new FormatResult(true, "Black", true, "");
            }
        } catch (Exception e) {
            log.debug("Black 不可用: {}", e.getMessage());
        }

        return new FormatResult(false, "", false, "未找到 Python 格式化工具（支持 autopep8/black）");
    }

    // ==================== 命令执行 ====================

    /**
     * 执行系统命令，静默模式（丢弃 stdout/stderr），等待完成
     *
     * @return 进程退出码
     */
    private int execCommand(String[] cmd, String workingDir, int timeoutMs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workingDir != null) {
            pb.directory(new File(workingDir));
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = null;
        try {
            process = pb.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new Exception("格式化命令执行超时（" + timeoutMs + "ms）");
            }
            return process.exitValue();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // ==================== 路径安全校验 ====================

    /**
     * 校验并标准化文件路径，防止路径穿越和注入
     * 确保文件存在且是普通文件
     */
    private Path validateFilePath(Path path) {
        try {
            Path normalized = path.normalize().toAbsolutePath();
            if (!Files.exists(normalized)) return null;
            if (!Files.isRegularFile(normalized)) return null;
            return normalized;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验并标准化目录路径，防止路径穿越和注入
     * 确保目录存在且是目录
     */
    private Path validateDirPath(String dir) {
        try {
            Path normalized = Paths.get(dir).normalize().toAbsolutePath();
            if (!Files.exists(normalized)) return null;
            if (!Files.isDirectory(normalized)) return null;
            return normalized;
        } catch (Exception e) {
            return null;
        }
    }
}
