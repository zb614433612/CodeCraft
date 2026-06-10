package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DeleteFileConfig;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.tool.postedit.Diagnostic;
import com.example.agentdeepseek.tool.postedit.Formatter;
import com.example.agentdeepseek.tool.postedit.PostEditPipeline;
import com.example.agentdeepseek.util.DiffUtil;
import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 文件写入工具 — 合并 write_file / edit_file / delete_file
 * 通过 action 参数区分操作，覆盖全量写入、精准编辑、文件删除三大能力。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.WRITE, affectsData = true, isPathSensitive = true, description = "文件写入/编辑/删除")
public class FileWriterTool implements Tool {

    // ============ write 常量 ============
    private static final long MAX_CONTENT_SIZE = 50 * 1024 * 1024;

    // ============ edit 常量 ============
    private static final ConcurrentHashMap<Path, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();
    private static final int MAX_FUZZY_RESULTS = 5;
    private static final int MAX_DIFF_LINES = 50;

    // ============ 注入依赖 ============
    private final ObjectMapper objectMapper;
    private final Formatter formatter;
    private final Diagnostic diagnostic;
    private final PostEditPipeline postEditPipeline;
    private final DeleteFileConfig deleteFileConfig;

    public FileWriterTool(ObjectMapper objectMapper,
                          ObjectProvider<Formatter> formatterProvider,
                          ObjectProvider<Diagnostic> diagnosticProvider,
                          PostEditPipeline postEditPipeline,
                          DeleteFileConfig deleteFileConfig) {
        this.objectMapper = objectMapper;
        this.formatter = formatterProvider.getIfAvailable();
        this.diagnostic = diagnosticProvider.getIfAvailable();
        this.postEditPipeline = postEditPipeline;
        this.deleteFileConfig = deleteFileConfig;
    }

    // ==================== Tool 接口 ====================

    @Override
    public String getName() {
        return "file_writer";
    }

    @Override
    public String getDescription() {
        return "【适用场景】文件修改一站式工具，通过 action 参数选择操作模式。\n"
                + "⚠️ 调用示例：{\"action\":\"write\",\"file_path\":\"...\",\"content\":\"...\"} | "
                + "{\"action\":\"edit\",\"file_path\":\"...\",\"old_text\":\"...\",\"new_text\":\"...\"}\n"
                + "【action 说明】\n"
                + "  write  — 创建新文件或覆盖已有文件（content 是完整内容）\n"
                + "  edit   — 对已有文件局部精准替换（old_text → new_text）\n"
                + "  delete — 删除文件或目录\n"
                + "【使用方式】\n"
                + "  write: 提供 file_path + content，文件不存在则新建，已存在且 force=true 则覆盖\n"
                + "  edit:  提供 file_path + old_text + new_text，自动 Search & Replace\n"
                + "  delete: 传入 path，删除文件或目录（手动模式下需用户授权）\n"
                + "【注意事项】\n"
                + "  1) edit 的 old_text 建议先从 file_explorer(action=read) 复制（至少 3~5 行）\n"
                + "  2) write 覆盖已有文件需 force=true，否则安全拦截\n"
                + "  3) delete 拒绝删除项目根目录和受保护目录\n"
                + "  4) action 必填不可省略！";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        // === action（公共必填） ===
        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】操作类型。write=全量创建/覆盖；edit=精准局部替换；delete=删除文件/目录。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("write");
        actionEnum.add("edit");
        actionEnum.add("delete");

        // === write/edit 共用 ===
        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "【write/edit 时必填】文件路径。示例：'src/main/java/com/example/Demo.java'。");

        // === write 专属 ===
        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "【write 时必填】文件的完整内容（非增量补丁）。新建文件时写完整代码；覆盖已有文件时提供替换后的全部内容。上限 50MB。");

        ObjectNode force = props.putObject("force");
        force.put("type", "boolean");
        force.put("description", "【write 可选，默认 false】是否强制覆盖已存在的文件。保护机制防止误覆盖，确认覆盖请设 true。");

        ObjectNode format = props.putObject("format");
        format.put("type", "boolean");
        format.put("description", "【write 可选，默认 true】写入后是否自动格式化代码。非代码文件（.txt/.json/.xml）建议设 false。");

        ObjectNode skipLsp = props.putObject("skip_lsp");
        skipLsp.put("type", "boolean");
        skipLsp.put("description", "【write 可选，默认 false】是否跳过写入后的代码诊断。非代码文件建议设 true。");

        // === edit 专属 ===
        ObjectNode oldText = props.putObject("old_text");
        oldText.put("type", "string");
        oldText.put("description", "【edit 时必填】文件中要替换的原始文本。建议从 file_explorer(action=read) 返回内容中直接复制，至少 3~5 行。");

        ObjectNode newText = props.putObject("new_text");
        newText.put("type", "string");
        newText.put("description", "【edit 可选，默认为空】替换后的新文本。为空表示删除 old_text 匹配到的内容。");

        // === delete 专属 ===
        ObjectNode delPath = props.putObject("path");
        delPath.put("type", "string");
        delPath.put("description", "【delete 时必填】要删除的文件或目录路径。示例：'src/main/java/com/example/OldFile.java'。");

        ArrayNode required = root.putArray("required");
        required.add("action");
        return root;
    }

    // ==================== 执行入口 ====================

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText("");
        if (action.isEmpty()) {
            return "【参数缺失】'action' 参数缺失或为空。file_writer 的 action 必须设为 'write' / 'edit' / 'delete' 之一。\n"
                    + "正确示例：{ \"action\": \"write\", \"file_path\": \"Demo.java\", \"content\": \"...\" }\n"
                    + "错误示例：{ \"file_path\": \"Demo.java\", \"content\": \"...\" } ← 缺少 action！";
        }
        return switch (action) {
            case "write"  -> doWrite(arguments);
            case "edit"   -> doEdit(arguments);
            case "delete" -> doDelete(arguments);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 write / edit / delete 三种取值，请改为其中之一。";
        };
    }

    // ================================================================
    //                      action=write
    // ================================================================

    private String doWrite(JsonNode args) {
        String filePathStr = args.path("file_path").asText();
        String contentStr = args.path("content").asText();
        boolean forceFlag = args.path("force").asBoolean(false);
        boolean autoFormat = args.path("format").asBoolean(true);
        boolean skipLspFlag = args.path("skip_lsp").asBoolean(false);

        if (filePathStr.isEmpty()) {
            return "【参数缺失】action=write 需要 file_path 参数。示例：file_path='src/main/java/com/example/Demo.java'";
        }
        if (!args.has("content") || args.path("content").isNull()) {
            return "【参数缺失】action=write 需要 content 参数。请提供要写入的完整文件内容。";
        }
        if (contentStr.length() > MAX_CONTENT_SIZE) {
            return "【内容超限】content 大小 " + contentStr.length() + " 字符，超过上限 " + MAX_CONTENT_SIZE + " 字符。建议拆分为多个小文件。";
        }

        Path filePath = resolvePath(filePathStr);
        boolean exists = Files.exists(filePath);

        if (exists) {
            if (!Files.isWritable(filePath)) {
                return "【权限不足】文件不可写 - " + filePath.toAbsolutePath()
                        + "\n建议：检查文件是否被其他进程占用，或修改文件权限后重试。";
            }
            if (!forceFlag) {
                long fileSize = 0, lineCount = 0;
                try { fileSize = Files.size(filePath); lineCount = Files.readAllLines(filePath).size(); } catch (IOException ignored) {}
                return "【安全拦截】文件已存在且 force 未设为 true。\n"
                        + "文件路径：" + filePath.toAbsolutePath() + "\n"
                        + "文件信息：" + fileSize + " 字节，" + lineCount + " 行\n"
                        + "方案一：设置 force=true 强制覆盖\n"
                        + "方案二：如果只改几行代码，改用 action=edit 精准替换（更安全）";
            }
        }

        Path parent = filePath.getParent();
        if (parent != null && Files.exists(parent) && !Files.isWritable(parent)) {
            return "【权限不足】父目录不可写 - " + parent.toAbsolutePath();
        }

        String oldContent = "";
        if (exists) {
            try { oldContent = FileEncodingDetector.readString(filePath); } catch (IOException e) { log.warn("读取原文件失败: {}", e.getMessage()); }
        }
        String normalizedOld = normalizeLineEndings(oldContent);
        String normalizedNew = normalizeLineEndings(contentStr);

        try {
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);

            boolean hasBom = false;
            boolean contentHasBom = !contentStr.isEmpty() && contentStr.charAt(0) == '\uFEFF';
            if (exists && !contentHasBom) {
                byte[] header = new byte[3];
                try (InputStream is = Files.newInputStream(filePath)) {
                    if (is.read(header) >= 3
                            && (header[0] & 0xFF) == 0xEF && (header[1] & 0xFF) == 0xBB && (header[2] & 0xFF) == 0xBF)
                        hasBom = true;
                }
            }
            if (hasBom) {
                byte[] bom = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
                byte[] contentBytes = contentStr.getBytes(StandardCharsets.UTF_8);
                byte[] full = new byte[bom.length + contentBytes.length];
                System.arraycopy(bom, 0, full, 0, bom.length);
                System.arraycopy(contentBytes, 0, full, bom.length, contentBytes.length);
                Files.write(filePath, full, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.writeString(filePath, contentStr, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            long fileSize = Files.size(filePath);
            String absPath = filePath.toAbsolutePath().toString();
            log.info("写入文件成功: {} ({} bytes)", absPath, fileSize);

            DiffUtil.DiffResult diffResult = DiffUtil.diff(normalizedOld, normalizedNew);

            String formatMsg = "";
            if (autoFormat && formatter != null) {
                formatMsg = formatter.format(filePath).toDisplay();
            }
            String diagMsg = "";
            if (!skipLspFlag && diagnostic != null) {
                diagMsg = diagnostic.diagnose(filePath).toDisplay();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 写入成功：").append(absPath).append("\n");
            sb.append("大小：").append(formatSize(fileSize)).append("\n");
            sb.append("内容长度：").append(contentStr.length()).append(" 字符\n");
            if (exists && diffResult.hasChanges()) {
                sb.append(diffResult.toSummary()).append("\n");
                if (!diffResult.diffPreview().isEmpty()) sb.append("\n变更预览：\n").append(diffResult.diffPreview()).append("\n");
            } else if (!exists) {
                sb.append("操作：新建文件\n");
            }
            if (!formatMsg.isEmpty()) sb.append("\n").append(formatMsg);
            if (!diagMsg.isEmpty()) sb.append("\n").append(diagMsg);
            sb.append("\n").append(DiffUtil.toJsonSnippet(diffResult, absPath, fileSize));
            return sb.toString();

        } catch (IOException e) {
            log.error("写入文件失败: {}", filePath, e);
            return "【写入失败】" + e.getMessage() + "\n建议：检查磁盘空间、文件权限、路径合法性。";
        }
    }

    // ================================================================
    //                      action=edit
    // ================================================================

    private String doEdit(JsonNode args) {
        String filePathStr = args.path("file_path").asText();
        String oldTextStr = args.path("old_text").asText();
        String newTextStr = args.path("new_text").asText();

        if (filePathStr.isEmpty()) {
            return "【参数缺失】action=edit 需要 file_path 参数。示例：file_path='src/main/java/com/example/Demo.java'";
        }
        if (oldTextStr.isEmpty()) {
            return "【参数缺失】action=edit 需要 old_text 参数。请先用 file_explorer(action=read) 读取文件，然后从返回内容中直接复制要替换的代码段。";
        }

        Path filePath = resolvePath(filePathStr);
        if (!Files.exists(filePath)) {
            return "【文件不存在】" + filePath.toAbsolutePath() + "\nedit 只能修改已有文件——如需创建新文件，请使用 action=write。";
        }
        if (!Files.isRegularFile(filePath)) {
            return "【路径类型错误】" + filePath.toAbsolutePath() + " 不是普通文件。";
        }
        if (!Files.isWritable(filePath)) {
            return "【权限不足】文件不可写 - " + filePath.toAbsolutePath();
        }

        Path normalizedPath = filePath.normalize();
        ReentrantLock fileLock = FILE_LOCKS.computeIfAbsent(normalizedPath, k -> new ReentrantLock());
        fileLock.lock();
        try {
            return executeReplace(filePath, oldTextStr, newTextStr);
        } finally {
            fileLock.unlock();
        }
    }

    private String executeReplace(Path filePath, String oldText, String newText) {
        try {
            String originalContent = FileEncodingDetector.readString(filePath);
            String normalizedContent = normalizeLineEndings(originalContent);
            String normalizedOld = normalizeLineEndings(oldText);
            String normalizedNew = normalizeLineEndings(newText);

            // 第一步：精确匹配
            int index = normalizedContent.indexOf(normalizedOld);
            if (index >= 0) {
                int secondIndex = normalizedContent.indexOf(normalizedOld, index + 1);
                if (secondIndex >= 0) return formatMultipleMatches(normalizedContent, normalizedOld);
                return doReplace(filePath, originalContent, normalizedContent, normalizedOld, normalizedNew, index);
            }

            // 第二步：宽松匹配
            String relaxedResult = tryRelaxedReplace(filePath, originalContent, normalizedContent, normalizedOld, normalizedNew);
            if (relaxedResult != null) return relaxedResult;

            // 第三步：模糊诊断
            String suggestion = tryFuzzyMatch(normalizedContent, normalizedOld);
            return "【匹配失败】在文件中未找到 old_text 的精确匹配。\n"
                    + "建议：1) 先用 file_explorer(action=read) 读取文件最新内容\n"
                    + "       2) 从返回内容中直接复制要替换的代码段（不要手打）\n"
                    + "       3) 确保 old_text 缩进、空格与文件中完全一致\n"
                    + suggestion;

        } catch (IOException e) {
            log.error("编辑文件失败: {}", filePath, e);
            return "【编辑失败】" + e.getMessage();
        }
    }

    private String doReplace(Path filePath, String originalContent, String normalizedContent,
                             String normalizedOld, String normalizedNew, int matchIndex) throws IOException {
        String beforeMatch = normalizedContent.substring(0, matchIndex);
        int startLine = countLines(beforeMatch) + 1;
        int endLine = startLine + countLines(normalizedOld) - 1;
        if (endLine < startLine) endLine = startLine;

        String resultContent = normalizedContent.substring(0, matchIndex)
                + normalizedNew
                + normalizedContent.substring(matchIndex + normalizedOld.length());

        String writeContent = convertLineEndings(resultContent, originalContent);
        Files.writeString(filePath, writeContent, StandardCharsets.UTF_8);

        PostEditPipeline.PostEditResult postResult = postEditPipeline.execute(filePath);

        int oldLineCount = Math.max(1, countLines(normalizedOld));
        int newLineCount = Math.max(1, countLines(normalizedNew));
        int delta = newLineCount - oldLineCount;
        int totalLines = countLines(resultContent);
        String diff = generateDiff(normalizedOld, normalizedNew, MAX_DIFF_LINES);

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 编辑成功：").append(filePath.toAbsolutePath()).append("\n");
        sb.append("匹配位置：第 ").append(startLine).append("~").append(endLine).append(" 行\n");
        sb.append("行数变化：").append(oldLineCount).append(" 行 → ").append(newLineCount).append(" 行");
        if (delta > 0) sb.append(" (+").append(delta).append(" 行)");
        else if (delta < 0) sb.append(" (").append(delta).append(" 行)");
        sb.append("\n文件现总行数：").append(totalLines).append(" 行\n");
        if (!diff.isEmpty()) sb.append("\n变更预览：\n").append(diff);
        if (postResult.hasInfo()) sb.append("\n").append(postResult.toReport());
        log.info("编辑文件成功: {} (第{}~{}行, {}行→{}行)", filePath.toAbsolutePath(), startLine, endLine, oldLineCount, newLineCount);
        return sb.toString();
    }

    // ---- 多匹配 ----
    private String formatMultipleMatches(String content, String oldText) {
        String[] oldLines = oldText.split("\n", -1);
        List<Integer> positions = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int idx = content.indexOf(oldText, searchFrom);
            if (idx < 0) break;
            positions.add(idx);
            searchFrom = idx + 1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【匹配冲突】old_text 在文件中匹配到 ").append(positions.size()).append(" 处，需要唯一匹配。\n");
        sb.append("建议：增加 old_text 行数（多复制几行上下文）以区分不同位置。\n\n");
        for (int i = 0; i < positions.size(); i++) {
            int pos = positions.get(i);
            int lineNum = 1;
            for (int j = 0; j < pos; j++) if (content.charAt(j) == '\n') lineNum++;
            int endLineNum = lineNum + oldLines.length - 1;
            sb.append("  #").append(i + 1).append("：第 ").append(lineNum).append("~").append(endLineNum).append(" 行\n");
            int ctxStart = Math.max(0, pos - 30), ctxEnd = Math.min(content.length(), pos + oldText.length() + 30);
            String ctx = content.substring(ctxStart, ctxEnd).replace("\n", "\\n");
            if (ctxStart > 0) ctx = "..." + ctx;
            if (ctxEnd < content.length()) ctx += "...";
            sb.append("    预览：").append(ctx).append("\n");
        }
        return sb.toString();
    }

    // ---- 模糊匹配诊断 ----
    private String tryFuzzyMatch(String content, String oldText) {
        String trimmedContent = content.replaceAll("[ \t]+\n", "\n");
        String trimmedOld = oldText.replaceAll("[ \t]+\n", "\n");
        if (trimmedContent.contains(trimmedOld)) {
            return "\n【诊断】移除行尾空白后可匹配——old_text 可能含多余行尾空格。请用 file_explorer(action=read) 重新读取后直接复制。";
        }
        String tabContent = content.replace("\t", "    ");
        String tabOld = oldText.replace("\t", "    ");
        if (tabContent.contains(tabOld)) {
            return "\n【诊断】Tab/空格不一致——old_text 使用制表符但文件中是空格。请用 file_explorer(action=read) 重新读取后直接复制。";
        }
        String wsContent = content.replaceAll("[ \t]+", " ");
        String wsOld = oldText.replaceAll("[ \t]+", " ");
        if (wsContent.contains(wsOld)) {
            return "\n【诊断】缩进宽度不一致——old_text 缩进与文件不符。请用 file_explorer(action=read) 重新读取后直接复制。";
        }
        return findSimilarBlocks(content, oldText);
    }

    private String findSimilarBlocks(String content, String oldText) {
        String[] contentLines = content.split("\n", -1);
        String[] oldLines = oldText.split("\n", -1);
        if (oldLines.length == 0) return "";
        if (oldLines.length > contentLines.length) {
            return "\n【诊断】old_text 行数（" + oldLines.length + "）超过文件行数（" + contentLines.length + "）——old_text 比整个文件还长。";
        }
        List<BlockMatch> matches = new ArrayList<>();
        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            int matchCount = 0;
            for (int j = 0; j < oldLines.length; j++) {
                if (contentLines[i + j].trim().equals(oldLines[j].trim())) matchCount++;
            }
            matches.add(new BlockMatch(i + 1, oldLines.length - matchCount, matchCount));
        }
        matches.sort(Comparator.comparingInt((BlockMatch m) -> m.score)
                .thenComparingInt(m -> Math.abs(m.lineNum - contentLines.length / 2)));
        int resultCount = Math.min(MAX_FUZZY_RESULTS, matches.size());
        StringBuilder sb = new StringBuilder();
        if (matches.get(0).score == 0) {
            sb.append("\n【诊断】去除空白后完全匹配——old_text 缩进/空格与文件不一致。请从以下位置直接复制：\n");
        } else {
            sb.append("\n【诊断】文件中与 old_text 最相似的 ").append(resultCount).append(" 处：\n");
        }
        for (int i = 0; i < resultCount; i++) {
            BlockMatch m = matches.get(i);
            int endLine = m.lineNum + oldLines.length - 1;
            String rate = m.score == 0 ? "完全匹配" : m.matchingLines + "/" + oldLines.length + " 行匹配";
            sb.append("  #").append(i + 1).append("：第 ").append(m.lineNum).append("~").append(endLine).append(" 行（").append(rate).append("）\n");
        }
        return sb.toString();
    }

    // ---- 宽松匹配+替换 ----
    private String tryRelaxedReplace(Path filePath, String originalContent, String normalizedContent,
                                     String normalizedOld, String normalizedNew) {
        String result = tryRelaxedLevel(filePath, originalContent, normalizedContent, normalizedOld, normalizedNew,
                s -> s.replaceAll("[ \\t]+\\n", "\n").replaceAll("[ \\t]+$", ""), "行尾空白已自动修正");
        if (result != null) return result;
        result = tryRelaxedLevel(filePath, originalContent, normalizedContent, normalizedOld, normalizedNew,
                s -> s.replace("\t", "    "), "缩进字符已自动修正（Tab→空格）");
        if (result != null) return result;
        result = tryRelaxedLevel(filePath, originalContent, normalizedContent, normalizedOld, normalizedNew,
                s -> s.replaceAll("[ \\t]+", " "), "多余空白已自动压缩");
        return result;
    }

    private String tryRelaxedLevel(Path filePath, String originalContent, String normalizedContent,
                                   String normalizedOld, String normalizedNew,
                                   java.util.function.UnaryOperator<String> transform, String relaxNote) {
        String tc = transform.apply(normalizedContent);
        String to = transform.apply(normalizedOld);
        int idx = tc.indexOf(to);
        if (idx < 0 || tc.indexOf(to, idx + 1) >= 0) return null;

        int startLine = countLinesUpTo(tc, idx);
        int endLine = startLine + Math.max(1, countLines(normalizedOld)) - 1;
        int origStart = getLineStartPos(normalizedContent, startLine);
        int origEnd = getLineEndPos(normalizedContent, endLine);
        String actualOld = normalizedContent.substring(origStart, origEnd);
        if (!normalizedOld.endsWith("\n") && actualOld.endsWith("\n")) {
            actualOld = actualOld.substring(0, actualOld.length() - 1);
            origEnd--;
        }
        try {
            String resultContent = normalizedContent.substring(0, origStart) + normalizedNew + normalizedContent.substring(origEnd);
            String writeContent = convertLineEndings(resultContent, originalContent);
            Files.writeString(filePath, writeContent, StandardCharsets.UTF_8);
            PostEditPipeline.PostEditResult postResult = postEditPipeline.execute(filePath);

            int oldLineCount = Math.max(1, countLines(actualOld));
            int newLineCount = Math.max(1, countLines(normalizedNew));
            int delta = newLineCount - oldLineCount;
            String diff = generateDiff(actualOld, normalizedNew, MAX_DIFF_LINES);
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 编辑成功（宽松匹配）：").append(filePath.toAbsolutePath()).append("\n");
            sb.append("匹配方式：").append(relaxNote).append("\n");
            sb.append("匹配位置：第 ").append(startLine).append("~").append(endLine).append(" 行\n");
            sb.append("行数变化：").append(oldLineCount).append(" 行 → ").append(newLineCount).append(" 行");
            if (delta > 0) sb.append(" (+").append(delta).append(" 行)");
            else if (delta < 0) sb.append(" (").append(delta).append(" 行)");
            sb.append("\n");
            if (!diff.isEmpty()) sb.append("\n变更预览：\n").append(diff);
            if (postResult.hasInfo()) sb.append("\n").append(postResult.toReport());
            log.info("编辑文件成功(宽松匹配): {} ({}行→{}行, {})", filePath.toAbsolutePath(), oldLineCount, newLineCount, relaxNote);
            return sb.toString();
        } catch (IOException e) {
            log.error("宽松匹配替换失败: {}", filePath, e);
            return null;
        }
    }

    // ---- edit 辅助 ----
    private String generateDiff(String oldText, String newText, int maxLines) {
        if (oldText.equals(newText)) return "";
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);
        List<String> diff = new ArrayList<>();
        int oi = 0, ni = 0;
        while (oi < oldLines.length || ni < newLines.length) {
            if (oi < oldLines.length && ni < newLines.length && oldLines[oi].equals(newLines[ni])) {
                diff.add(" " + oldLines[oi]); oi++; ni++;
            } else {
                while (oi < oldLines.length && (ni >= newLines.length || !oldLines[oi].equals(newLines[ni]))) {
                    diff.add("-" + oldLines[oi++]);
                }
                while (ni < newLines.length && (oi >= oldLines.length || !oldLines[oi].equals(newLines[ni]))) {
                    diff.add("+" + newLines[ni++]);
                }
            }
        }
        if (diff.size() > maxLines) {
            int half = maxLines / 2;
            List<String> truncated = new ArrayList<>(diff.subList(0, half));
            truncated.add("... (" + (diff.size() - maxLines) + " 行被省略)");
            truncated.addAll(diff.subList(diff.size() - half, diff.size()));
            diff = truncated;
        }
        return String.join("\n", diff);
    }

    private int countLinesUpTo(String text, int pos) {
        int lines = 1;
        for (int i = 0; i < pos && i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        return lines;
    }

    private int getLineStartPos(String text, int targetLine) {
        if (targetLine <= 1) return 0;
        int cur = 1;
        for (int i = 0; i < text.length(); i++) { if (text.charAt(i) == '\n') { cur++; if (cur == targetLine) return i + 1; } }
        return text.length();
    }

    private int getLineEndPos(String text, int targetLine) {
        int cur = 1;
        for (int i = 0; i < text.length(); i++) { if (text.charAt(i) == '\n') { if (cur == targetLine) return i + 1; cur++; } }
        return text.length();
    }

    private record BlockMatch(int lineNum, int score, int matchingLines) {}

    // ================================================================
    //                      action=delete
    // ================================================================

    private String doDelete(JsonNode args) {
        String targetPath = args.path("path").asText();
        if (targetPath.isEmpty()) {
            return "【参数缺失】action=delete 需要 path 参数。示例：path='src/main/java/com/example/OldFile.java'";
        }

        String projectRoot = ProjectRootContext.get();
        Path root = Paths.get(projectRoot).normalize();
        Path target = Paths.get(targetPath);
        if (!target.isAbsolute()) target = root.resolve(targetPath).normalize();
        else target = target.normalize();

        // 安全检查
        String checkErr = safetyCheck(target, root);
        if (checkErr != null) return checkErr;

        if (!Files.exists(target)) {
            return "【文件不存在】" + toRelativePath(target, root) + " 不存在，无法删除。";
        }

        try {
            if (Files.isDirectory(target)) {
                String warn = checkLargeDirectory(target, root);
                if (warn != null) return warn;
                return deleteDirectory(target, root);
            } else {
                return deleteSingleFile(target, root);
            }
        } catch (IOException e) {
            log.error("删除失败: {}", toRelativePath(target, root), e);
            return "【删除失败】" + toRelativePath(target, root) + "：" + e.getMessage();
        }
    }

    private String safetyCheck(Path target, Path root) {
        if (target.equals(root)) return "【安全限制】不允许删除项目根目录。";
        for (String dir : deleteFileConfig.getProtectedDirectories()) {
            Path protectedDir = root.resolve(dir).normalize();
            if (target.equals(protectedDir) || target.startsWith(protectedDir)) {
                return "【安全限制】'" + dir + "' 是受保护目录，不允许删除。当前目标 '" + toRelativePath(target, root) + "' 命中此限制。";
            }
        }
        return null;
    }

    private String checkLargeDirectory(Path dir, Path root) {
        int threshold = deleteFileConfig.getDeleteWarningThreshold();
        if (threshold <= 0) return null;
        try {
            AtomicInteger count = new AtomicInteger(0);
            try (var walk = Files.walk(dir)) { walk.forEach(p -> count.incrementAndGet()); }
            int total = count.get() - 1;
            if (total > threshold) {
                return "【删除确认】目录 " + toRelativePath(dir, root) + " 包含 " + total + " 个文件/子目录，超过安全阈值 " + threshold + "。如需继续删除，请确认后重试。";
            }
        } catch (IOException e) { log.warn("统计目录文件数失败: {}", toRelativePath(dir, root), e); }
        return null;
    }

    private String deleteSingleFile(Path file, Path root) throws IOException {
        long size = Files.size(file);
        Files.delete(file);
        String rel = toRelativePath(file, root);
        log.info("删除文件: {} ({})", rel, formatSize(size));
        return "✅ 文件已删除：" + rel + "（" + formatSize(size) + "）";
    }

    private String deleteDirectory(Path dir, Path root) throws IOException {
        AtomicInteger totalCount = new AtomicInteger(0), fileCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                totalCount.incrementAndGet();
                if (!Files.isDirectory(p)) fileCount.incrementAndGet();
                try { Files.deleteIfExists(p); } catch (IOException e) {
                    log.warn("删除失败: {}", p, e);
                    failures.add(p.toAbsolutePath().toString());
                }
            });
        }
        String rel = toRelativePath(dir, root);
        if (Files.exists(dir)) {
            return "【部分失败】目录 " + rel + " 删除未完全成功。";
        }
        if (!failures.isEmpty()) {
            return "目录已删除（部分文件失败）：" + rel + "（" + fileCount.get() + " 个文件，" + totalCount.get() + " 个条目，失败：" + failures.size() + " 个）";
        }
        log.info("删除目录: {} ({} 个文件，{} 个条目)", rel, fileCount.get(), totalCount.get());
        return "✅ 目录已删除：" + rel + "（" + fileCount.get() + " 个文件，" + totalCount.get() + " 个条目）";
    }

    // ================================================================
    //                      共用工具方法
    // ================================================================

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        return path.isAbsolute() ? path.normalize() : Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    private String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String convertLineEndings(String text, String original) {
        if (original.contains("\r\n")) return text.replace("\n", "\r\n");
        if (original.contains("\r")) return text.replace("\n", "\r");
        return text;
    }

    private int countLines(String text) {
        if (text.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String toRelativePath(Path target, Path root) {
        try { return "./" + root.relativize(target).toString().replace("\\", "/"); }
        catch (Exception e) { return target.toAbsolutePath().toString(); }
    }
}
