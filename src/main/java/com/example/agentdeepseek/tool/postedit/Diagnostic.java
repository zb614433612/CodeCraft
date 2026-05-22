package com.example.agentdeepseek.tool.postedit;

import com.example.agentdeepseek.config.EditFileConfig;
import com.example.agentdeepseek.util.ProjectRootContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * 编辑后自动诊断模块
 * <p>
 * 根据文件类型自动选择合适的诊断工具：
 * - .java → javac 单文件语法检查（fast）/ mvn compile（full）
 * - .ts/.tsx → tsc --noEmit
 * - .js/.jsx → node --check
 * - .py → python py_compile
 * - .json → python -m json.tool / node 检查
 * <p>
 * 所有诊断操作均设超时，失败不阻塞主流程。
 */
@Slf4j
@Component
public class Diagnostic {

    private final EditFileConfig config;

    public Diagnostic(EditFileConfig config) {
        this.config = config;
    }

    /**
     * 诊断结果
     */
    public record DiagnosticResult(boolean executed, boolean hasError,
                                    List<DiagnosticItem> errors, List<DiagnosticItem> warnings,
                                    String summary) {

        public String toDisplay() {
            if (!executed) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("诊断结果：");
            if (!hasError && errors.isEmpty() && warnings.isEmpty()) {
                sb.append("✅ 通过，无错误");
            } else {
                sb.append("\n");
                if (!errors.isEmpty()) {
                    sb.append("  ❌ ").append(errors.size()).append(" 个错误：\n");
                    for (DiagnosticItem item : errors) {
                        sb.append("    ").append(item).append("\n");
                    }
                }
                if (!warnings.isEmpty()) {
                    sb.append("  ⚠️ ").append(warnings.size()).append(" 个警告：\n");
                    for (DiagnosticItem item : warnings) {
                        sb.append("    ").append(item).append("\n");
                    }
                }
            }
            return sb.toString().trim();
        }
    }

    public record DiagnosticItem(int line, String level, String message) {
        @Override
        public String toString() {
            return "第 " + line + " 行：" + message;
        }
    }

    /**
     * 对指定文件执行诊断
     */
    public DiagnosticResult diagnose(Path filePath) {
        if (!config.getDiagnostic().isEnabled()) {
            return new DiagnosticResult(false, false, List.of(), List.of(), "诊断未启用");
        }

        String fileName = filePath.getFileName().toString().toLowerCase();
        String level = config.getDiagnostic().getLevel();

        try {
            if (fileName.endsWith(".java")) {
                return diagnoseJavaFile(filePath, level);
            } else if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")) {
                return diagnoseTypeScript(filePath);
            } else if (fileName.endsWith(".js") || fileName.endsWith(".jsx") || fileName.endsWith(".mjs")) {
                return diagnoseJavaScript(filePath);
            } else if (fileName.endsWith(".py")) {
                return diagnosePython(filePath);
            } else if (fileName.endsWith(".json")) {
                return diagnoseJson(filePath);
            } else {
                return new DiagnosticResult(false, false, List.of(), List.of(), "");
            }
        } catch (Exception e) {
            log.warn("诊断异常: file={}, err={}", filePath, e.getMessage());
            return new DiagnosticResult(false, false, List.of(), List.of(),
                    "诊断异常：" + e.getMessage());
        }
    }

    // ==================== Java 诊断 ====================

    private DiagnosticResult diagnoseJavaFile(Path filePath, String level) {
        String projectRoot = ProjectRootContext.get();
        if (projectRoot == null) {
            return new DiagnosticResult(false, false, List.of(), List.of(), "未设置项目根目录");
        }

        // full 模式：使用 mvn compile -o（离线编译）
        if ("full".equals(level)) {
            Path pomPath = Paths.get(projectRoot, "pom.xml");
            if (Files.exists(pomPath)) {
                try {
                    String[] cmd = {"mvn", "compile", "-o", "-q", "-f", projectRoot};
                    String output = execCommandWithOutput(cmd, projectRoot,
                            config.getDiagnostic().getTimeout());
                    List<DiagnosticItem> errors = (output == null || output.isEmpty())
                            ? List.of()
                            : parseMavenErrors(output, filePath);
                    if (errors.isEmpty()) {
                        return new DiagnosticResult(true, false, List.of(), List.of(), "编译通过");
                    }
                    return new DiagnosticResult(true, true, errors, List.of(),
                            "编译发现 " + errors.size() + " 个错误");
                } catch (Exception e) {
                    log.debug("Maven 编译失败，降级到单文件检查: {}", e.getMessage());
                }
            }
        }

        // fast 模式（默认）或 full 降级：单文件 javac 语法检查
        return diagnoseJavaSingleFile(filePath);
    }

    private DiagnosticResult diagnoseJavaSingleFile(Path filePath) {
        try {
            String nullDevice = File.separatorChar == '\\' ? "NUL" : "/dev/null";
            String[] cmd = {"javac", "-proc:none", "-d", nullDevice,
                    "-Xlint:all", filePath.toAbsolutePath().toString()};
            String output = execCommandWithOutput(cmd, null,
                    config.getDiagnostic().getTimeout());

            if (output == null || output.isEmpty()) {
                return new DiagnosticResult(true, false, List.of(), List.of(), "语法检查通过");
            }

            List<DiagnosticItem> errors = new ArrayList<>();
            List<DiagnosticItem> warnings = new ArrayList<>();
            parseJavacOutput(output, filePath, errors, warnings);

            return new DiagnosticResult(true, !errors.isEmpty(), errors, warnings,
                    errors.isEmpty() ? warnings.size() + " 个警告" : errors.size() + " 个错误");
        } catch (Exception e) {
            return new DiagnosticResult(false, false, List.of(), List.of(),
                    "javac 检查异常：" + e.getMessage());
        }
    }

    // ==================== TypeScript / JavaScript / Python / JSON 诊断 ====================

    private DiagnosticResult diagnoseTypeScript(Path filePath) {
        String projectRoot = ProjectRootContext.get();
        if (projectRoot == null) {
            return new DiagnosticResult(false, false, List.of(), List.of(), "未设置项目根目录");
        }
        try {
            String[] cmd = {"npx", "tsc", "--noEmit", "--skipLibCheck",
                    filePath.toAbsolutePath().toString()};
            String output = execCommandWithOutput(cmd, projectRoot,
                    config.getDiagnostic().getTimeout());

            if (output == null || output.isEmpty() || output.contains("0 errors")) {
                return new DiagnosticResult(true, false, List.of(), List.of(), "TypeScript 检查通过");
            }

            List<DiagnosticItem> errors = new ArrayList<>();
            List<DiagnosticItem> warnings = new ArrayList<>();
            parseTscOutput(output, filePath, errors, warnings);

            return new DiagnosticResult(true, !errors.isEmpty(), errors, warnings,
                    errors.isEmpty() ? warnings.size() + " 个警告" : errors.size() + " 个错误");
        } catch (Exception e) {
            return new DiagnosticResult(false, false, List.of(), List.of(),
                    "TypeScript 诊断异常：" + e.getMessage());
        }
    }

    private DiagnosticResult diagnoseJavaScript(Path filePath) {
        try {
            String[] cmd = {"node", "--check", filePath.toAbsolutePath().toString()};
            String output = execCommandWithOutput(cmd, null,
                    config.getDiagnostic().getTimeout());

            if (output == null || output.isEmpty()) {
                return new DiagnosticResult(true, false, List.of(), List.of(), "语法检查通过");
            }

            List<DiagnosticItem> errors = new ArrayList<>();
            // node --check 输出格式: 文件名:行号: 错误描述
            Pattern p = Pattern.compile(".*:(\\d+):(.+)");
            for (String line : output.split("\n")) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    errors.add(new DiagnosticItem(
                            Integer.parseInt(m.group(1)), "error", m.group(2).trim()));
                }
            }
            if (errors.isEmpty()) {
                errors.add(new DiagnosticItem(0, "error", output.trim()));
            }
            return new DiagnosticResult(true, true, errors, List.of(),
                    "语法检查发现 " + errors.size() + " 个错误");
        } catch (Exception e) {
            return new DiagnosticResult(false, false, List.of(), List.of(),
                    "JavaScript 诊断异常：" + e.getMessage());
        }
    }

    private DiagnosticResult diagnosePython(Path filePath) {
        try {
            String[] cmd = {"python", "-m", "py_compile",
                    filePath.toAbsolutePath().toString()};
            String output = execCommandWithOutput(cmd, null,
                    config.getDiagnostic().getTimeout());

            if (output == null || output.isEmpty()) {
                return new DiagnosticResult(true, false, List.of(), List.of(), "语法检查通过");
            }

            List<DiagnosticItem> errors = new ArrayList<>();
            Pattern lineP = Pattern.compile("line (\\d+)");
            for (String line : output.split("\n")) {
                Matcher m = lineP.matcher(line);
                int lineNo = 0;
                if (m.find()) lineNo = Integer.parseInt(m.group(1));
                if (line.contains("Error:") || line.contains("SyntaxError")) {
                    errors.add(new DiagnosticItem(lineNo, "error", line.trim()));
                }
            }
            if (errors.isEmpty()) {
                errors.add(new DiagnosticItem(0, "error", output.trim()));
            }
            return new DiagnosticResult(true, true, errors, List.of(),
                    "语法检查发现 " + errors.size() + " 个错误");
        } catch (Exception e) {
            return new DiagnosticResult(false, false, List.of(), List.of(),
                    "Python 诊断异常：" + e.getMessage());
        }
    }

    private DiagnosticResult diagnoseJson(Path filePath) {
        try {
            // 统一使用 node -e，文件路径作为独立参数传递，避免注入
            // 不再使用 shell 重定向，ProcessBuilder 不经过 shell
            String[] cmd = {
                    "node", "-e",
                    "const fs=require('fs');const p=process.argv[1];"
                    + "try{JSON.parse(fs.readFileSync(p,'utf8'));process.exit(0)}"
                    + "catch(e){console.log(e.message);process.exit(1)}",
                    filePath.toAbsolutePath().toString()
            };
            String output = execCommandWithOutput(cmd, null,
                    config.getDiagnostic().getTimeout());

            if (output == null || output.isEmpty()) {
                return new DiagnosticResult(true, false, List.of(), List.of(), "JSON 格式正确");
            }

            List<DiagnosticItem> errors = new ArrayList<>();
            errors.add(new DiagnosticItem(0, "error", "JSON 格式错误：" + output.trim()));
            return new DiagnosticResult(true, true, errors, List.of(), "JSON 格式错误");
        } catch (Exception e) {
            return new DiagnosticResult(false, false, List.of(), List.of(),
                    "JSON 诊断异常：" + e.getMessage());
        }
    }

    // ==================== 输出解析 ====================

    private void parseJavacOutput(String output, Path filePath,
                                   List<DiagnosticItem> errors,
                                   List<DiagnosticItem> warnings) {
        String absPath = filePath.toAbsolutePath().toString().replace("\\", "/");
        // javac 格式: 路径:行号: 错误/警告: 描述
        Pattern p1 = Pattern.compile(
                Pattern.quote(absPath) + ":(\\d+):\\s*(error|warning):\\s*(.+)",
                Pattern.CASE_INSENSITIVE);
        // javac 格式: 路径:行号:列号: 错误/警告: 描述
        Pattern p2 = Pattern.compile(
                Pattern.quote(absPath) + ":(\\d+):\\d+:\\s*(error|warning):\\s*(.+)",
                Pattern.CASE_INSENSITIVE);

        for (String line : output.split("\n")) {
            Matcher m = p2.matcher(line);
            if (!m.find()) m = p1.matcher(line);
            if (m.find()) {
                int lineNo = Integer.parseInt(m.group(1));
                String level = m.group(2).toLowerCase();
                String msg = m.group(3);
                if ("error".equals(level)) {
                    errors.add(new DiagnosticItem(lineNo, "error", msg));
                } else {
                    warnings.add(new DiagnosticItem(lineNo, "warning", msg));
                }
            }
        }
    }

    private void parseTscOutput(String output, Path filePath,
                                 List<DiagnosticItem> errors,
                                 List<DiagnosticItem> warnings) {
        String absPath = filePath.toAbsolutePath().toString().replace("\\", "/");
        // tsc 格式: 路径(行号,列号): error TSxxxx: 描述
        Pattern p = Pattern.compile(
                Pattern.quote(absPath) + "\\((\\d+).*\\).*:\\s*(error|warning)\\s+(TS\\d+):\\s*(.+)",
                Pattern.CASE_INSENSITIVE);

        for (String line : output.split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                int lineNo = Integer.parseInt(m.group(1));
                String level = m.group(2).toLowerCase();
                String code = m.group(3);
                String msg = m.group(4);
                String fullMsg = code + ": " + msg;
                if ("error".equals(level)) {
                    errors.add(new DiagnosticItem(lineNo, "error", fullMsg));
                } else {
                    warnings.add(new DiagnosticItem(lineNo, "warning", fullMsg));
                }
            }
        }
    }

    private List<DiagnosticItem> parseMavenErrors(String output, Path filePath) {
        List<DiagnosticItem> errors = new ArrayList<>();
        String fileName = filePath.getFileName().toString();
        Set<String> seen = new HashSet<>();

        // 格式1: [ERROR] /path/to/file.java:[行号,列号] 描述
        Pattern p1 = Pattern.compile(
                "\\[ERROR\\]\\s+.*" + Pattern.quote(fileName) + ":\\[(\\d+),\\d+\\]\\s+(.+)");
        // 格式2: [ERROR] /path/to/file.java:行号: 描述
        Pattern p2 = Pattern.compile(
                "\\[ERROR\\]\\s+.*" + Pattern.quote(fileName) + ":(\\d+):\\s+(.+)");

        for (String line : output.split("\n")) {
            Matcher m = p1.matcher(line);
            if (!m.find()) m = p2.matcher(line);
            if (m.find()) {
                int lineNo = Integer.parseInt(m.group(1));
                String msg = m.group(2).trim();
                String key = lineNo + ":" + msg;
                if (seen.add(key)) {
                    errors.add(new DiagnosticItem(lineNo, "error", msg));
                }
            }
        }
        return errors;
    }

    // ==================== 命令执行 ====================

    private String execCommandWithOutput(String[] cmd, String workingDir, int timeoutMs)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workingDir != null) {
            pb.directory(new File(workingDir));
        }
        pb.redirectErrorStream(true);
        Process process = null;
        try {
            process = pb.start();

            // 读取输出
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new Exception("诊断命令执行超时（" + timeoutMs + "ms）");
            }

            return output.toString().trim();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
