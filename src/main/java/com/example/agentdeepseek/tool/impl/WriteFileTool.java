package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.tool.postedit.Diagnostic;
import com.example.agentdeepseek.tool.postedit.Formatter;
import com.example.agentdeepseek.util.DiffUtil;
import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 写入文件工具
 * <p>
 * 创建新文件或覆盖写入。从 opencode-dev write 工具中吸取的优点：
 * - 写入后自动格式化代码（可关闭）
 * - 写入后自动诊断代码质量（可关闭）
 * - 写入前后对比生成 diff 摘要
 * - 保留原文件 BOM 头
 * - 返回结构化结果供 LLM 决策
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.WRITE, affectsData = true, isPathSensitive = true, description = "写入/覆盖文件")
public class WriteFileTool implements Tool {

    private static final long MAX_CONTENT_SIZE = 50 * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final Formatter formatter;
    private final Diagnostic diagnostic;

    public WriteFileTool(ObjectMapper objectMapper, ObjectProvider<Formatter> formatterProvider, ObjectProvider<Diagnostic> diagnosticProvider) {
        this.objectMapper = objectMapper;
        this.formatter = formatterProvider.getIfAvailable();
        this.diagnostic = diagnosticProvider.getIfAvailable();
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "【适用场景】创建新文件，或整体替换已有文件的全部内容——比如新建一个 Java 类、覆盖整个配置文件。"
                + "【与edit_file的区别】write_file 是「全量写入/覆盖」（content 是文件完整内容），edit_file 是「局部精准替换」（old_text + new_text 只改匹配片段）。如果你只想改几行代码，请用 edit_file，不要用 write_file 重写整个文件。"
                + "【使用方式】提供 file_path（目标路径）+ content（文件的完整新内容）。文件不存在则新建；已存在且 force=true 则覆盖。写入后自动格式化+诊断检查，帮助发现代码问题。"
                + "【注意事项】1) content 必须是文件的全部内容，不是增量补丁或 diff；2) 安全模式（force=false，默认）下覆盖已有文件会拦截报错——这是防止误覆盖的保护机制，确认覆盖请设 force=true；3) 写非代码文件（.txt/.json/.xml 等）建议设置 format=false, skip_lsp=true；4) 内容上限 50MB，超大文件请拆分为多次写入。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "【必填】要写入的文件路径。示例：src/main/java/com/example/Demo.java。支持项目相对路径（推荐）和绝对路径。目录不存在时自动创建。");
        properties.set("file_path", filePath);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "string");
        content.put("description", "【必填】文件的完整内容（非增量补丁，不是 diff）。新建文件时写完整代码；覆盖已有文件时提供替换后的全部内容。示例：public class MyClass { ... } 整个类的完整源码。");
        properties.set("content", content);

        ObjectNode force = objectMapper.createObjectNode();
        force.put("type", "boolean");
        force.put("description", "【可选，默认 false】是否强制覆盖已存在的文件。设为 true 可跳过「文件已存在」的安全拦截直接覆盖。如果只是修改已有文件的局部内容，建议改用 edit_file 工具而非 force=true。");
        properties.set("force", force);

        ObjectNode format = objectMapper.createObjectNode();
        format.put("type", "boolean");
        format.put("description", "【可选，默认 true】写入后是否自动格式化代码。Java/Python/JS/Go 等代码文件建议保持 true（让工具帮你统一风格）；写 .txt/.json/.xml/.yaml 等非代码文件时可设为 false 避免格式化出错。");
        properties.set("format", format);

        ObjectNode skipLsp = objectMapper.createObjectNode();
        skipLsp.put("type", "boolean");
        skipLsp.put("description", "【可选，默认 false】是否跳过写入后的代码诊断（lint/编译检查）。写 .java/.py/.ts 等代码文件建议保持 false（及时发现编译错误）；写 .txt/.md/.json/.yml 等非代码文件时可设为 true 跳过无意义的诊断。");
        properties.set("skip_lsp", skipLsp);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path").add("content");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {

        String filePathStr = arguments.path("file_path").asText();
        String content = arguments.path("content").asText();
        boolean force = arguments.path("force").asBoolean(false);
        boolean autoFormat = arguments.path("format").asBoolean(true);
        boolean skipLsp = arguments.path("skip_lsp").asBoolean(false);

        // ---- 参数校验 ----
        if (filePathStr.isEmpty()) {
            return "【参数缺失】缺少必填参数 file_path。请提供要写入的文件路径。示例：file_path=src/main/java/com/example/Demo.java";
        }
        if (!arguments.has("content") || arguments.path("content").isNull()) {
            return "【参数缺失】缺少必填参数 content。请提供要写入的完整文件内容（write_file 需要文件的全部内容，不是增量片段或 diff）。示例：content=\"package com.example;\\n\\npublic class MyClass {\\n    ...\\n}\"";
        }

        if (content.length() > MAX_CONTENT_SIZE) {
            return "【内容超限】content 大小 " + content.length() + " 字符，超过上限 " + MAX_CONTENT_SIZE + " 字符。"
                    + "【修正建议】1) 将内容拆分为多个小文件分别写入；2) 如果文件内容可由脚本生成，使用 execute_command 工具通过 shell 命令（如 cat/echo）创建文件；3) 检查是否误将整个项目目录的内容放入了一个文件。";
        }

        // ---- 路径解析 ----
        Path filePath = resolvePath(filePathStr);

        boolean exists = Files.exists(filePath);

        // ---- 安全模式检查 ----
        if (exists) {
            if (!Files.isWritable(filePath)) {
                return "【权限不足】文件不可写 - " + filePath.toAbsolutePath()
                        + "\n【修正建议】1) 检查文件是否被其他进程（编辑器、编译器等）占用；2) 使用 execute_command 工具运行 chmod 修改文件权限后重试；3) 确认当前用户对该文件有写入权限。";
            }
            if (!force) {
                long fileSize = 0;
                long lineCount = 0;
                try {
                    fileSize = Files.size(filePath);
                    lineCount = Files.readAllLines(filePath, StandardCharsets.UTF_8).size();
                } catch (IOException ignored) {
                }
                return "【安全拦截】文件已存在且 force 未设为 true（保护机制防止误覆盖）。\n"
                        + "文件路径：" + filePath.toAbsolutePath() + "\n"
                        + "文件信息：" + fileSize + " 字节，" + lineCount + " 行\n"
                        + "【修正建议】\n"
                        + "  方案一（确认覆盖）：设置 force=true 强制覆盖写入\n"
                        + "  方案二（确认内容）：先用 read_file 读取当前内容，确认后重新调用本工具\n"
                        + "  方案三（推荐）：如果只是改几行代码，改用 edit_file 工具进行精确的 Search & Replace 局部修改（更安全且只改目标片段）";
            }
            log.debug("强制覆盖文件: {}", filePath.toAbsolutePath());
        }

        // 目录可写性检查
        Path parent = filePath.getParent();
        if (parent != null && Files.exists(parent) && !Files.isWritable(parent)) {
            return "【权限不足】父目录不可写 - " + parent.toAbsolutePath()
                    + "\n【修正建议】1) 使用 execute_command 工具运行 chmod 修改目录权限；2) 检查父目录是否被其他进程锁定；3) 确认当前用户对该目录有写入权限。";
        }

        // ---- 读取原内容（用于生成 diff）----
        String oldContent = "";
        if (exists) {
            try {
                oldContent = FileEncodingDetector.readString(filePath);
            } catch (IOException e) {
                log.warn("读取原文件内容失败（diff 将不可用）: {}", e.getMessage());
            }
        }
        String normalizedOld = normalizeLineEndings(oldContent);
        String normalizedNew = normalizeLineEndings(content);

        // ---- 写入文件 ----
        try {
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.debug("已创建目录: {}", parent);
            }

            // 检测原文件是否有 BOM，保留一致
            // 同时检测 content 参数自身是否已含 BOM，防止双 BOM
            boolean hasBom = false;
            boolean contentHasBom = content.length() > 0 && content.charAt(0) == '\uFEFF';
            if (exists && !contentHasBom) {
                byte[] header = new byte[3];
                try (InputStream is = Files.newInputStream(filePath)) {
                    int read = is.read(header);
                    hasBom = read >= 3
                            && (header[0] & 0xFF) == 0xEF
                            && (header[1] & 0xFF) == 0xBB
                            && (header[2] & 0xFF) == 0xBF;
                } catch (IOException e) {
                    log.debug("检测 BOM 失败: {}", e.getMessage());
                }
            }

            if (hasBom) {
                byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
                byte[] fullBytes = new byte[bom.length + contentBytes.length];
                System.arraycopy(bom, 0, fullBytes, 0, bom.length);
                System.arraycopy(contentBytes, 0, fullBytes, bom.length, contentBytes.length);
                Files.write(filePath, fullBytes,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.writeString(filePath, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            long fileSize = Files.size(filePath);
            String absPath = filePath.toAbsolutePath().toString();
            log.info("写入文件成功: {} ({} bytes)", absPath, fileSize);

            // ---- 生成 diff ----
            DiffUtil.DiffResult diffResult = DiffUtil.diff(normalizedOld, normalizedNew);

            // ---- 后处理：格式化 ----
            String formatMessage = "";
            if (autoFormat && formatter != null) {
                Formatter.FormatResult formatResult = formatter.format(filePath);
                formatMessage = formatResult.toDisplay();
                log.debug("写入后格式化: {}", formatResult.success() ? "OK" : formatResult.message());
            }

            // ---- 后处理：诊断检查 ----
            String diagnosticMessage = "";
            if (!skipLsp && diagnostic != null) {
                Diagnostic.DiagnosticResult diagResult = diagnostic.diagnose(filePath);
                diagnosticMessage = diagResult.toDisplay();
                log.debug("写入后诊断: executed={}, hasError={}",
                        diagResult.executed(), diagResult.hasError());
            }

            // ---- 组装返回结果 ----
            StringBuilder sb = new StringBuilder();
            sb.append("写入成功：").append(absPath).append("\n");
            sb.append("大小：").append(formatSize(fileSize)).append("\n");
            sb.append("内容长度：").append(content.length()).append(" 字符\n");

            if (exists && diffResult.hasChanges()) {
                sb.append(diffResult.toSummary()).append("\n");
                if (!diffResult.diffPreview().isEmpty()) {
                    sb.append("\n变更预览：\n").append(diffResult.diffPreview()).append("\n");
                }
            } else if (!exists) {
                sb.append("操作：新建文件\n");
            }

            if (!formatMessage.isEmpty()) {
                sb.append("\n").append(formatMessage);
            }

            if (!diagnosticMessage.isEmpty()) {
                sb.append("\n").append(diagnosticMessage);
            }

            // 结构化 JSON 片段（供 LLM 解析）
            sb.append("\n").append(DiffUtil.toJsonSnippet(diffResult, absPath, fileSize));

            return sb.toString();

        } catch (IOException e) {
            log.error("写入文件失败: {}", filePath, e);
            return "【写入失败】写入文件时发生 I/O 错误：" + e.getMessage()
                    + "\n【修正建议】1) 检查磁盘剩余空间是否充足；2) 确认目标路径的父目录存在且有写入权限；3) 确认文件路径中不包含非法字符（如 : * ? \" < > |）；4) 如持续失败，可尝试用 execute_command 工具通过 shell 命令（cat/echo 重定向）创建文件。";
        }
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    private String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
