package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.ExecutionTokenManager;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 编辑文件工具
 * 使用正则表达式精准替换文件中的内容
 */
@Slf4j
@Component
public class EditFileTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ExecutionTokenManager executionTokenManager;

    public EditFileTool(ObjectMapper objectMapper, ExecutionTokenManager executionTokenManager) {
        this.objectMapper = objectMapper;
        this.executionTokenManager = executionTokenManager;
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "使用正则表达式精准替换文件中的内容，支持多行模式（multiline=true 使 . 匹配换行符）。"
                + "适合对已有文件进行局部修改，区别于 write_file（整体覆盖）。"
                + "注意：pattern 为正则表达式，如需纯文本替换请转义特殊字符或使用 write_file 整体覆盖";
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
        replacement.put("description", "替换内容，支持 $1, $2 等反向引用");
        properties.set("replacement", replacement);

        ObjectNode multiline = objectMapper.createObjectNode();
        multiline.put("type", "boolean");
        multiline.put("description", "是否启用多行模式（让 . 匹配换行符），默认 false");
        properties.set("multiline", multiline);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path").add("pattern").add("replacement");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // manual 模式下检查执行权限
        if ("manual".equals(ToolContext.getMode())) {
            Long sessionId = ToolContext.getConversationId();
            if (sessionId == null || !executionTokenManager.tryConsume(sessionId)) {
                return "需要先通过 ask_execution 工具获得您的执行许可";
            }
        }

        String filePathStr = arguments.path("file_path").asText();
        String patternStr = arguments.path("pattern").asText();
        String replacement = arguments.path("replacement").asText();
        boolean multiline = arguments.path("multiline").asBoolean(false);

        if (filePathStr.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }
        if (patternStr.isEmpty()) {
            return "错误：缺少必要参数 pattern";
        }
        if (replacement.isEmpty()) {
            return "错误：缺少必要参数 replacement";
        }

        Path filePath = resolvePath(filePathStr);
        if (!Files.exists(filePath)) {
            return "错误：文件不存在 - " + filePath.toAbsolutePath();
        }
        if (!Files.isRegularFile(filePath)) {
            return "错误：路径不是文件 - " + filePath.toAbsolutePath();
        }
        if (!Files.isWritable(filePath)) {
            return "错误：文件不可写 - " + filePath.toAbsolutePath();
        }

        try {
            // 编译正则
            int flags = Pattern.DOTALL;
            if (!multiline) {
                flags = 0;
            }
            Pattern pattern = Pattern.compile(patternStr, flags);

            // 读取文件内容
            String content = Files.readString(filePath, StandardCharsets.UTF_8);

            // 执行替换
            java.util.regex.Matcher matcher = pattern.matcher(content);
            int matchCount = 0;
            StringBuilder preview = new StringBuilder();

            // 先统计匹配数，获取预览
            String previewContent = content;
            String previewReplaced = previewContent.replaceAll(patternStr, replacement);

            // 执行实际替换
            String newContent = matcher.replaceAll(replacement);

            // 计算替换次数
            java.util.regex.Matcher countMatcher = pattern.matcher(content);
            while (countMatcher.find()) {
                matchCount++;
                // 记录匹配位置的上下文（前2行+匹配行+后1行）
                if (preview.length() < 2000) { // 防止预览过长
                    String matched = countMatcher.group();
                    int start = Math.max(0, countMatcher.start() - 60);
                    int end = Math.min(content.length(), countMatcher.end() + 40);
                    String context = (start > 0 ? "..." : "") + content.substring(start, end).replace("\n", "\\n") + (end < content.length() ? "..." : "");
                    preview.append("  #").append(matchCount).append(": ").append(context).append("\n");
                }
            }

            if (matchCount == 0) {
                return "提示：未找到匹配的内容。请检查正则表达式是否正确。\n"
                        + "模式: " + patternStr + "\n"
                        + (multiline ? "（多行模式已启用）" : "（提示：跨行匹配需设置 multiline=true）");
            }

            // 写入新内容
            Files.writeString(filePath, newContent, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

            log.info("编辑文件成功: {} ({} 处替换)", filePath.toAbsolutePath(), matchCount);

            StringBuilder sb = new StringBuilder();
            sb.append("编辑成功：").append(filePath.toAbsolutePath()).append("\n");
            sb.append("替换次数：").append(matchCount).append("\n");
            sb.append("模　　式：").append(patternStr).append("\n");
            sb.append("替换内容：").append(replacement).append("\n");

            if (preview.length() > 0) {
                sb.append("\n匹配位置预览：\n").append(preview);
            }

            return sb.toString();
        } catch (PatternSyntaxException e) {
            return "错误：正则表达式语法错误 - " + e.getMessage();
        } catch (IOException e) {
            log.error("编辑文件失败: {}", filePath, e);
            return "错误：编辑文件失败 - " + e.getMessage();
        }
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }
}
