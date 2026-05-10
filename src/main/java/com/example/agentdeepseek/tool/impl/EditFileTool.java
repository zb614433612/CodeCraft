package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 编辑文件工具
 * 使用正则表达式或纯文本精准替换文件中的内容
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
        return "使用正则表达式或纯文本精准替换文件中的内容，支持多行模式（multiline=true 使 . 匹配换行符）。"
                + "适合对已有文件进行局部修改，区别于 write_file（整体覆盖）。"
                + "注意：pattern 为正则表达式，设置 literal=true 可将 pattern 视为纯文本，自动转义特殊字符";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "文件路径，相对于项目根目录的路径或绝对路径");
        properties.set("file_path", filePath);

        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "正则表达式模式，用于匹配要替换的内容。默认不使用多行模式，跨行匹配需设置 multiline=true");
        properties.set("pattern", pattern);

        ObjectNode replacement = objectMapper.createObjectNode();
        replacement.put("type", "string");
        replacement.put("description", "替换内容（$ 和 \\ 按字面处理，不支持反向引用）");
        properties.set("replacement", replacement);

        ObjectNode multiline = objectMapper.createObjectNode();
        multiline.put("type", "boolean");
        multiline.put("description", "是否启用多行模式（让 . 匹配换行符），默认 false");
        properties.set("multiline", multiline);

        ObjectNode literal = objectMapper.createObjectNode();
        literal.put("type", "boolean");
        literal.put("description", "是否将 pattern 视为纯文本（自动转义正则特殊字符），默认 false");
        properties.set("literal", literal);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path").add("pattern").add("replacement");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response;
        }

        String filePathStr = arguments.path("file_path").asText();
        String patternStr = arguments.path("pattern").asText();
        String replacement = arguments.path("replacement").asText();
        boolean multiline = arguments.path("multiline").asBoolean(false);
        boolean literal = arguments.path("literal").asBoolean(false);

        if (filePathStr.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }
        if (patternStr.isEmpty()) {
            return "错误：缺少必要参数 pattern";
        }
        if (replacement.isEmpty()) {
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
            try {
            // 如果 literal=true，将 pattern 转义为纯文本匹配
            if (literal) {
                patternStr = "\\Q" + patternStr + "\\E";
            }
            // 编译正则
            int flags = Pattern.DOTALL;
            if (!multiline) {
                flags = 0;
            }
            Pattern pattern = Pattern.compile(patternStr, flags);

            // 读取文件内容
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String escapedReplacement = java.util.regex.Matcher.quoteReplacement(replacement);

            // 将正则匹配+替换+计数封装为 Callable，支持超时控制（防止 ReDoS）
            class RegexResult {
                final String newContent;
                final int matchCount;
                final String preview;

                RegexResult(String newContent, int matchCount, String preview) {
                    this.newContent = newContent;
                    this.matchCount = matchCount;
                    this.preview = preview;
                }
            }

            Callable<RegexResult> regexTask = () -> {
                java.util.regex.Matcher matcher = pattern.matcher(content);
                StringBuilder sb = new StringBuilder();
                StringBuilder prev = new StringBuilder();
                int count = 0;

                // 一次扫描完成：替换 + 计数 + 预览收集
                // 使用 appendReplacement/appendTail 替代 replaceAll，避免二次扫描
                while (matcher.find()) {
                    count++;
                    // 收集预览（用 codePoint 安全截断，避免代理对乱码）
                    if (prev.length() < 2000) {
                        int remaining = 2000 - prev.length();
                        if (remaining > 0) {
                            int start = Math.max(0, matcher.start() - 60);
                            int end = Math.min(content.length(), matcher.end() + 40);
                            // 使用 codePoint 安全截断，确保不会切在代理对中间
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
                    }
                    matcher.appendReplacement(sb, escapedReplacement);
                }
                matcher.appendTail(sb);

                return new RegexResult(sb.toString(), count, prev.toString());
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
                return "提示：未找到匹配的内容。请检查正则表达式是否正确。\n"
                        + "模式: " + patternStr + "\n"
                        + (multiline ? "（多行模式已启用）" : "（提示：跨行匹配需设置 multiline=true）");
            }

            // 写入新内容
            Files.writeString(filePath, result.newContent, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            log.info("编辑文件成功: {} ({} 处替换)", filePath.toAbsolutePath(), result.matchCount);

            StringBuilder sb = new StringBuilder();
            sb.append("编辑成功：").append(filePath.toAbsolutePath()).append("\n");
            sb.append("替换次数：").append(result.matchCount).append("\n");
            sb.append("模　　式：").append(patternStr).append("\n");
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
        } finally {
            fileLock.unlock();
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
