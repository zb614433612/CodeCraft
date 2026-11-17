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

        /*
          ★ 2026-11-17 修复「修改文件工具卡死」的第二个根因（第一个是 Diagnostic 的 readLine 顺序）。

          旧实现只要有 pom.xml 就无条件执行 `mvn spotless:apply`，失败后再执行 `mvn javaformat:format`。
          但本项目 pom.xml 并未声明这两个插件（日志原话：未找到可用的 Java 格式化工具，
          项目需配置 Maven spotless 或 javaformat 插件）—— 命令必然失败，
          而失败前 Maven 仍要启动 JVM、解析 pom、并尝试去中央仓库解析插件坐标；
          本地仓库无缓存或网络抖动时，这一步可吃掉十几秒到数分钟，再叠加第二方案重复一次，
          用户侧表现就是 file_writer 一直转圈只能手动停止。

          实测对照：同一次编辑，Maven 缓存命中的那轮 222ms 就打出「编辑后处理完成」，
          未命中的那轮 AUDIT 之后再无任何日志。

          修法：先用纯文本检查 pom.xml 里是否真的声明了 spotless / javaformat，
          没有就完全跳过 Maven 方案（不启动 Maven 就不会卡），有声明才执行并保留超时兜底。
          注意这只是「避免空跑」，不是放弃格式化能力 —— 真正配了插件的项目行为不变。
        */
        Path pomPath = projectDir.resolve("pom.xml");
        if (Files.exists(pomPath)) {
            String pomText = null;
            try {
                pomText = Files.readString(pomPath, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception readErr) {
                log.debug("读取 pom.xml 失败，按未声明插件处理: {}", readErr.getMessage());
            }
            boolean declaresFormatter = pomText != null
                    && (pomText.contains("spotless") || pomText.contains("javaformat"));
            if (!declaresFormatter) {
                log.debug("pom.xml 未声明 spotless/javaformat 插件，跳过 Maven 格式化（避免空跑 Maven 导致工具卡顿）: {}",
                        projectDir);
                return new FormatResult(false, "", false,
                        "未找到可用的 Java 格式化工具（项目需配置 Maven spotless 或 javaformat 插件）");
            }

            // 方案1：Maven spotless:apply（最推荐，支持多种 Java 风格配置）
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
            String[] cmd = {"npx", "--no-install", "prettier", "--write", fileArg};
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
