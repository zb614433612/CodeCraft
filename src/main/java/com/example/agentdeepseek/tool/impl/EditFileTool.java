package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.PatternSyntaxException;
/**
 * 编辑文件工具
 * 支持两种模式：
 * - line 模式（推荐）：按行号定位替换，简单精确
 * - regex 模式：正则匹配替换，先尝试精确文本匹配，失败后自动按正则处理
 */
@Slf4j
@Component
public class EditFileTool implements Tool {

    /** 正则执行超时秒数，防止 ReDoS 攻击导致线程阻塞 */
    private static final int REGEX_TIMEOUT_SECONDS = 10;

    /** 共享线程池，用于执行带超时控制的正则操作 */
    private static final ExecutorService REGEX_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "edit-file-regex");
        t.setDaemon(true);
        return t;
    });

    /** 文件级并发写入锁，确保同一文件不会并发读写 */
    private static final ConcurrentHashMap<Path, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(REGEX_EXECUTOR::shutdownNow));
    }

    private final ObjectMapper objectMapper;

    public EditFileTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "编辑文件内容，支持两种模式：\n"
                + "1. line 模式（推荐）：按行号定位替换，简单精确。传入 start_line/end_line 指定行范围，替换为 replacement。无需正则知识，最稳定。\n"
                + "2. regex 模式（默认）：使用正则表达式匹配替换。先尝试精确文本匹配，失败后自动用正则。支持多行模式（multiline=true 使 . 匹配换行符）。\n"
                + "适合对已有文件进行局部修改，区别于 write_file（整体覆盖）。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode mode = objectMapper.createObjectNode();
        mode.put("type", "string");
        mode.put("description", "编辑模式：line（按行号替换，推荐）或 regex（正则匹配替换，默认）。line 模式更稳定可靠");
        properties.set("mode", mode);

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "文件路径，相对于项目根目录的路径或绝对路径");
        properties.set("file_path", filePath);

        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "regex 模式下使用：正则表达式。工具会先尝试精确文本匹配，失败后自动按正则处理。line 模式下忽略此参数");
        properties.set("pattern", pattern);

        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.put("type", "string");
        replacement.put("description", "替换内容（$ 和 \\ 按字面处理，不支持反向引用）。line 模式下替换整个行范围，为空则删除对应行");
        properties.set("replacement", replacement);
        properties.set("replacement", replacement);

        ObjectNode multiline = objectMapper.createObjectNode();
        multiline.put("type", "boolean");
        multiline.put("description", "regex æ¨¡å¼ä¸ï¼æ¯å¦å¯ç¨å¤è¡æ¨¡å¼ï¼è®© . å¹éæ¢è¡ç¬¦ï¼ï¼é»è®¤ falseãline æ¨¡å¼ä¸å¿½ç¥æ­¤åæ°");
        properties.set("multiline", multiline);

        ObjectNode startLine = objectMapper.createObjectNode();
        startLine.put("type", "integer");
        startLine.put("description", "line æ¨¡å¼ä¸ï¼èµ·å§è¡å·ï¼ä»1å¼å§ï¼ï¼å¿å¡«ãæå®è¦æ¿æ¢çèµ·å§è¡ãå½ä½¿ç¨ search_after æ¶ï¼æ­¤å¼ä½ä¸ºç¸å¯¹åç§»é");
        properties.set("start_line", startLine);

        ObjectNode endLine = objectMapper.createObjectNode();
        endLine.put("type", "integer");
        endLine.put("description", "line æ¨¡å¼ä¸ï¼ç»æè¡å·ï¼ä»1å¼å§ï¼ï¼å¯éãä¸ä¼ ååªæ¿æ¢ start_line é£ä¸è¡ï¼åå«ä¸¤ç«¯ï¼");

        ObjectNode searchText = objectMapper.createObjectNode();
        searchText.put("type", "string");
        searchText.put("description", "line mode: optional search text to locate line by content (solves line number drift)."
                + " Tool searches file for this text, uses the matching line as start_line."
                + " Only works if exactly one line matches; multiple matches return error listing all matches.");
        properties.set("search_text", searchText);

        ObjectNode searchAfter = objectMapper.createObjectNode();
        searchAfter.put("type", "string");
        searchAfter.put("description", "line æ¨¡å¼ï¼å®ä½éç¹ææ¬ãå·¥å·åæ¾å°åå«æ­¤ææ¬çæåä¸è¡ï¼ç¶åä»è¯¥è¡ä¹åæ start_line/end_line çç¸å¯¹åç§»å®ä½ã"
                + " éç¨äºè§£å³è¡å·æ¼ç§»ââç¨ä¸åçä»£ç ç¹å¾è¡åéç¹ãä¾å¦ search_after=\"/* ===== æ ç­¾æ  ===== */\", start_line=1\n"
                + " è¡¨ç¤ºå¨éç¹è¡ä¹åçç¬¬1è¡å¼å§æä½ãä¸ search_text äºæ¥ï¼ä¼åçº§ä½äº search_textã");
        properties.set("search_after", searchAfter);

        ObjectNode insertMode = objectMapper.createObjectNode();
        insertMode.put("type", "string");
        insertMode.put("description", "æå¥æ¨¡å¼ï¼ä» regex æ¨¡å¼ææï¼ï¼replaceï¼é»è®¤ï¼- å¹éå¹¶æ¿æ¢ï¼after - å¨å¹éåå®¹ä¹åè¿½å  replacementï¼before - å¨å¹éåå®¹ä¹åæå¥ replacement");
        ArrayNode insertModeEnum = objectMapper.createArrayNode();
        insertModeEnum.add("replace");
        insertModeEnum.add("after");
        insertModeEnum.add("before");
        insertMode.set("enum", insertModeEnum);
        properties.set("insert_mode", insertMode);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path").add("replacement");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response;
        }

        String mode = arguments.path("mode").asText("regex");
        String filePathStr = arguments.path("file_path").asText();
        String replacement = arguments.path("replacement").asText();

        if (filePathStr == null || filePathStr.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }
        if (replacement == null) {
            return "错误：缺少必要参数 replacement";
        }

        Path filePath;
        try {
            filePath = resolvePath(filePathStr);
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        }
        if (!Files.exists(filePath)) {
            return "错误：文件不存在 - " + filePath.toAbsolutePath();
        }
        if (!Files.isRegularFile(filePath)) {
            return "错误：路径不是文件 - " + filePath.toAbsolutePath();
        }
        if (!Files.isWritable(filePath)) {
            return "错误：文件不可写 - " + filePath.toAbsolutePath();
        }

        // 获取文件级锁，防止并发写入导致数据竞争
        Path normalizedPath = filePath.normalize();
        ReentrantLock fileLock = FILE_LOCKS.computeIfAbsent(normalizedPath, k -> new ReentrantLock());
        fileLock.lock();
        try {
            if ("line".equals(mode)) {
                return executeLineMode(filePath, replacement, arguments);
            } else {
                return executeRegexMode(filePath, replacement, arguments);
            }
        } finally {
            fileLock.unlock();
        }
    }

    // ==================== Line 模式 ====================

    /**
     * 按行号替换模式：根据 start_line/end_line 定位，将指定行范围替换为 replacement
     */
    private String executeLineMode(Path filePath, String replacement, JsonNode arguments) {
        String searchText = arguments.path("search_text").asText("");
        String searchAfter = arguments.path("search_after").asText("");
        int startLine = arguments.path("start_line").asInt(0);
        int endLine = arguments.path("end_line").asInt(0);

        if (startLine <= 0 && searchText.isEmpty() && searchAfter.isEmpty()) {
            return "éè¯¯ï¼line æ¨¡å¼ä¸éè¦æä¾ start_lineãsearch_text æ search_after";
        }

        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            // 如果提供了 search_text，优先用它定位行号（解决行号漂移问题）
            if (!searchText.isEmpty()) {
                List<Integer> matchedLines = new ArrayList<>();
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(searchText)) {
                        matchedLines.add(i + 1);
                    }
                }
                if (matchedLines.isEmpty()) {
                    return "错误：未找到包含「" + searchText + "」的行";
                }
                if (matchedLines.size() > 1) {
                    return "错误：搜索「" + searchText + "」匹配到多行（第 "
                        + matchedLines.toString().replaceAll("[\\[\\]]", "") + " 行），"
                        + "请使用更精确的 search_text 或改用 start_line 指定行号";
                }
                startLine = matchedLines.get(0);
                log.info("search_text 定位到第 {} 行: {}", startLine, searchText);
                log.info("search_text å®ä½å°ç¬¬ {} è¡: {}", startLine, searchText);
            }

            // å¦ææä¾äº search_afterï¼åæ¾å°éç¹è¡ï¼åå ä¸ç¸å¯¹åç§»
            if (!searchAfter.isEmpty() && searchText.isEmpty()) {
                int anchorLine = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(searchAfter)) {
                        anchorLine = i + 1;
                    }
                }
                if (anchorLine == -1) {
                    return "éè¯¯ï¼æªæ¾å°åå«ã" + searchAfter + "ãçéç¹è¡";
                }
                int offset = startLine;
                startLine = anchorLine + offset;
                if (endLine > 0) {
                    endLine = anchorLine + endLine;
                }
                log.info("search_after éç¹å¨ç¬¬ {} è¡ï¼åç§» {} è¡å start_line={}", anchorLine, offset, startLine);
            }

            if (startLine > lines.size()) {
                return "错误：start_line (" + startLine + ") 超出文件行数 (" + lines.size() + ")";
            }

            if (endLine <= 0) {
                endLine = startLine;
            }

            if (endLine > lines.size()) {
                return "错误：end_line (" + endLine + ") 超出文件行数 (" + lines.size() + ")";
            }

            if (endLine < startLine) {
                return "错误：end_line (" + endLine + ") 不能小于 start_line (" + startLine + ")";
            }

            // 提取被替换内容的预览
            StringBuilder oldPreview = new StringBuilder();
            for (int i = startLine - 1; i < endLine; i++) {
                if (i > startLine - 1) oldPreview.append("\n");
                oldPreview.append(lines.get(i));
            }

            // 构建新文件内容
            List<String> newLines = new ArrayList<>();
            // start_line 之前的内容
            for (int i = 0; i < startLine - 1; i++) {
                newLines.add(lines.get(i));
            }
            // 替换内容（支持多行，replacement 为空则删除对应行）
            if (!replacement.isEmpty()) {
                String[] replacementLines = replacement.split("\n", -1);
                for (String line : replacementLines) {
                    newLines.add(line);
                }
            }
            // end_line 之后的内容
            for (int i = endLine; i < lines.size(); i++) {
                newLines.add(lines.get(i));
            }

            // 写入文件
            Files.write(filePath, newLines, StandardCharsets.UTF_8);

            int replacedLines = endLine - startLine + 1;
            log.info("编辑文件成功: {} (行 {}-{}, 替换 {} 行)", filePath.toAbsolutePath(), startLine, endLine, replacedLines);

            StringBuilder sb = new StringBuilder();
            sb.append("编辑成功：").append(filePath.toAbsolutePath()).append("\n");
            sb.append("模　　式：line\n");
            if (startLine == endLine) {
                sb.append("替换位置：第 ").append(startLine).append(" 行\n");
            } else {
                sb.append("替换位置：第 ").append(startLine).append("~").append(endLine).append(" 行\n");
            }
            int newLineCount = replacement.isEmpty() ? 0 : replacement.split("\n", -1).length;
            sb.append("行数变化：").append(replacedLines).append(" 行 → ").append(newLineCount).append(" 行\n");
            sb.append("文件现总行数：").append(newLines.size()).append(" 行\n");

            sb.append("\n被替换内容（第 ").append(startLine).append("~").append(endLine).append(" 行）：\n");
            sb.append(oldPreview).append("\n");

            return sb.toString();
        } catch (IOException e) {
            log.error("编辑文件失败: {}", filePath, e);
            return "错误：编辑文件失败 - " + e.getMessage();
        }
    }

    // ==================== Regex 模式 ====================

    /**
     * 正则匹配替换模式
     * 策略：先尝试精确文本匹配（自动转义特殊字符），失败后自动降级到正则匹配
     */
    private String executeRegexMode(Path filePath, String replacement, JsonNode arguments) {
        String patternStr = arguments.path("pattern").asText();
        boolean multiline = arguments.path("multiline").asBoolean(false);
        String insertMode = arguments.path("insert_mode").asText("replace");

        if (patternStr == null || patternStr.isEmpty()) {
            return "éè¯¯ï¼regex æ¨¡å¼ä¸ç¼ºå°å¿è¦åæ° pattern";
        }

        if (!"replace".equals(insertMode) && !"after".equals(insertMode) && !"before".equals(insertMode)) {
            return "éè¯¯ï¼æ æç insert_mode - " + insertMode + "ï¼æ¯æ replace / after / before";
        }

        try {
            // è¯»åæä»¶åå®¹
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String escapedReplacement = java.util.regex.Matcher.quoteReplacement(replacement);

            // insert_mode ä¸º after/before æ¶ï¼ä½¿ç¨ç²¾ç¡®ææ¬å¹éï¼é¿åæ­£åå¹éç ´åç»æ
            if ("after".equals(insertMode) || "before".equals(insertMode)) {
                String quoted = java.util.regex.Pattern.quote(patternStr);
                int idx;
                if ("after".equals(insertMode)) {
                    // å¨å¹éåå®¹ä¹åè¿½å 
                    idx = content.indexOf(patternStr);
                    if (idx == -1) {
                        return "æç¤ºï¼æªæ¾å°å¹éçåå®¹ã\\nå¹éæ¨¡å¼: " + patternStr;
                    }
                    String newContent = content.substring(0, idx + patternStr.length())
                            + "\n" + replacement
                            + content.substring(idx + patternStr.length());
                    Files.writeString(filePath, newContent, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                    log.info("ç¼è¾æä»¶æå: {} (insert_mode=after)", filePath.toAbsolutePath());
                    return "ç¼è¾æåï¼" + filePath.toAbsolutePath() + "\\n"
                            + "æ¨¡ããå¼ï¼regexï¼insert_mode=afterï¼\\n"
                            + "å¨ã" + patternStr + "ãä¹åè¿½å åå®¹";
                } else {
                    // å¨å¹éåå®¹ä¹åæå¥
                    idx = content.indexOf(patternStr);
                    if (idx == -1) {
                        return "æç¤ºï¼æªæ¾å°å¹éçåå®¹ã\\nå¹éæ¨¡å¼: " + patternStr;
                    }
                    String newContent = content.substring(0, idx)
                            + replacement + "\n"
                            + content.substring(idx);
                    Files.writeString(filePath, newContent, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                    log.info("ç¼è¾æä»¶æå: {} (insert_mode=before)", filePath.toAbsolutePath());
                    return "ç¼è¾æåï¼" + filePath.toAbsolutePath() + "\\n"
                            + "æ¨¡ããå¼ï¼regexï¼insert_mode=beforeï¼\\n"
                            + "å¨ã" + patternStr + "ãä¹åæå¥åå®¹";
                }
            }

            int flags = multiline ? java.util.regex.Pattern.DOTALL : 0;
            // 将正则匹配+替换+计数封装为 Callable，支持超时控制（防止 ReDoS）
            class RegexResult {
                final String newContent;
                final int matchCount;
                final String preview;
                final boolean usedExactMatch;

                RegexResult(String newContent, int matchCount, String preview, boolean usedExactMatch) {
                    this.newContent = newContent;
                    this.matchCount = matchCount;
                    this.preview = preview;
                    this.usedExactMatch = usedExactMatch;
                }
            }

            Callable<RegexResult> regexTask = () -> {
                // -------------------- 第一步：精确文本匹配（自动转义）--------------------
                java.util.regex.Pattern exactPattern = java.util.regex.Pattern.compile(
                        java.util.regex.Pattern.quote(patternStr), flags);
                java.util.regex.Matcher exactMatcher = exactPattern.matcher(content);

                if (exactMatcher.find()) {
                    // 精确文本匹配成功
                    exactMatcher.reset();
                    StringBuilder sb = new StringBuilder();
                    StringBuilder prev = new StringBuilder();
                    int count = 0;

                    while (exactMatcher.find()) {
                        count++;
                        collectPreview(content, exactMatcher, prev, count);
                        exactMatcher.appendReplacement(sb, escapedReplacement);
                    }
                    exactMatcher.appendTail(sb);

                    return new RegexResult(sb.toString(), count, prev.toString(), true);
                }

                // -------------------- 第二步：精确匹配失败，尝试正则匹配 --------------------
                java.util.regex.Pattern regexPattern = java.util.regex.Pattern.compile(patternStr, flags);
                java.util.regex.Matcher regexMatcher = regexPattern.matcher(content);

                if (regexMatcher.find()) {
                    regexMatcher.reset();
                    StringBuilder sb = new StringBuilder();
                    StringBuilder prev = new StringBuilder();
                    int count = 0;

                    while (regexMatcher.find()) {
                        count++;
                        collectPreview(content, regexMatcher, prev, count);
                        regexMatcher.appendReplacement(sb, escapedReplacement);
                    }
                    regexMatcher.appendTail(sb);

                    return new RegexResult(sb.toString(), count, prev.toString(), false);
                }

                // 两种方式都匹配不上
                return new RegexResult(null, 0, "", false);
            };

            RegexResult result;
            try {
                Future<RegexResult> future = REGEX_EXECUTOR.submit(regexTask);
                result = future.get(REGEX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return "错误：正则匹配超时（超过" + REGEX_TIMEOUT_SECONDS + "秒），请检查正则表达式是否存在性能问题";
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof PatternSyntaxException) {
                    return "错误：正则表达式语法错误 - " + cause.getMessage();
                }
                log.error("正则执行失败: {}", filePath, cause);
                return "错误：正则执行失败 - " + cause.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "错误：正则执行被中断";
            }

            if (result.matchCount == 0) {
                return "提示：未找到匹配的内容。\n"
                        + "已尝试：\n"
                        + "  1. 精确文本匹配（已自动转义特殊字符）—— 未匹配\n"
                        + "  2. 正则匹配 —— 未匹配\n"
                        + "建议改用 line 模式（设置 mode=\"line\"，传入 start_line 和 end_line）按行号定位，更加可靠。\n"
                        + "匹配模式: " + patternStr + "\n"
                        + (multiline ? "（多行模式已启用）" : "（提示：跨行匹配需设置 multiline=true）");
            }

            // 写入新内容
            Files.writeString(filePath, result.newContent, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            log.info("编辑文件成功: {} ({} 处替换)", filePath.toAbsolutePath(), result.matchCount);

            StringBuilder sb = new StringBuilder();
            sb.append("编辑成功：").append(filePath.toAbsolutePath()).append("\n");
            sb.append("替换次数：").append(result.matchCount).append("\n");
            sb.append("模　　式：regex");
            if (result.usedExactMatch) {
                sb.append("（精确文本匹配）");
            } else {
                sb.append("（正则匹配）");
            }
            sb.append("\n");
            sb.append("匹配模式：").append(patternStr).append("\n");
            sb.append("替换内容：").append(replacement).append("\n");

            if (!result.preview.isEmpty()) {
                sb.append("\n匹配位置预览：\n").append(result.preview);
            }

            return sb.toString();
        } catch (PatternSyntaxException e) {
            return "错误：正则表达式语法错误 - " + e.getMessage();
        } catch (IOException e) {
            log.error("编辑文件失败: {}", filePath, e);
            return "错误：编辑文件失败 - " + e.getMessage();
        }
    }

    /**
     * 收集匹配位置的预览内容（前后各带一些上下文）
     */
    private void collectPreview(String content, java.util.regex.Matcher matcher, StringBuilder prev, int count) {
        if (prev.length() >= 2000) return;
        int remaining = 2000 - prev.length();
        if (remaining <= 0) return;

        int start = Math.max(0, matcher.start() - 60);
        int end = Math.min(content.length(), matcher.end() + 40);
        // 使用 codePoint 安全截断，避免代理对乱码
        int safeStart = content.offsetByCodePoints(0, content.codePointCount(0, start));
        int safeEnd = content.offsetByCodePoints(0, content.codePointCount(0, end));
        String context = (start > 0 ? "..." : "")
                + content.substring(safeStart, safeEnd).replace("\n", "\\n")
                + (end < content.length() ? "..." : "");
        String line = "  #" + count + ": " + context + "\n";
        if (line.length() <= remaining) {
            prev.append(line);
        } else {
            prev.append(line, 0, remaining);
        }
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        Path projectRoot = Paths.get(ProjectRootContext.get()).normalize();
        Path resolved;
        if (path.isAbsolute()) {
            resolved = path.normalize();
        } else {
            resolved = Paths.get(ProjectRootContext.get(), pathStr).normalize();
        }
        // 路径穿越防护：确保解析后的路径在项目目录范围内
        if (!resolved.startsWith(projectRoot)) {
            throw new SecurityException("访问被拒绝：路径不在项目目录范围内 - " + resolved);
        }
        return resolved;
    }
}
