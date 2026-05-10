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
import java.nio.file.StandardOpenOption;

/**
 * 写入文件工具
 * 创建新文件或覆盖写入，支持安全模式（文件已存在时报错）
 */
@Slf4j
@Component
public class WriteFileTool implements Tool {

    private static final long MAX_CONTENT_SIZE = 50 * 1024 * 1024; // 最大写入内容大小 50MB

    private final ObjectMapper objectMapper;

    public WriteFileTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "创建新文件或覆盖已有文件的内容。安全模式下（force=false），文件已存在时会报错防止误覆盖。"
                + "当需要修改已有文件的局部内容而非整体覆盖时，请使用 edit_file 工具进行精准替换";
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

        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "string");
        content.put("description", "写入的文件内容");
        properties.set("content", content);

        ObjectNode force = objectMapper.createObjectNode();
        force.put("type", "boolean");
        force.put("description", "是否强制覆盖已存在的文件，默认 false。当文件已存在且 force=false 时将报错");
        properties.set("force", force);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path").add("content");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response; // 用户拒绝或自定义输入
        }

        String filePathStr = arguments.path("file_path").asText();
        String content = arguments.path("content").asText();
        boolean force = arguments.path("force").asBoolean(false);

        if (filePathStr.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }
        if (!arguments.has("content") || arguments.path("content").isNull()) {
            return "错误：缺少必要参数 content";
        }

        // 检查写入内容大小
        if (content.length() > MAX_CONTENT_SIZE) {
            return "错误：内容太大（" + content.length() + " 字符），超过最大限制 " + MAX_CONTENT_SIZE + " 字符";
        }

        Path filePath;
        try {
            filePath = resolvePath(filePathStr);
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        }

        // 安全模式检查
        if (Files.exists(filePath)) {
            if (!Files.isWritable(filePath)) {
                return "错误：文件不可写 - " + filePath.toAbsolutePath();
            }
            if (!force) {
                return "错误：文件已存在 - " + filePath.toAbsolutePath()
                        + "\n如需覆盖请设置 force=true，或使用 edit_file 工具进行精准替换";
            }
            log.debug("强制覆盖文件: {}", filePath.toAbsolutePath());
        }

        // 文件不存在时检查父目录是否可写
        Path parent = filePath.getParent();
        if (parent != null && Files.exists(parent) && !Files.isWritable(parent)) {
            return "错误：目录不可写 - " + parent.toAbsolutePath();
        }

        try {
            // 自动创建父目录（parent 已在外部声明）
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.debug("已创建目录: {}", parent);
            }

            // 写入文件
            Files.writeString(filePath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            String absPath = filePath.toAbsolutePath().toString();
            long size = Files.size(filePath);
            log.info("写入文件成功: {} ({} bytes)", absPath, size);

            return "写入成功：" + absPath + "\n"
                    + "大小：" + formatSize(size) + "\n"
                    + "内容长度：" + content.length() + " 字符";
        } catch (IOException e) {
            log.error("写入文件失败: {}", filePath, e);
            return "错误：写入文件失败 - " + e.getMessage();
        }
    }

    /**
     * 解析文件路径，如果是相对路径则拼接项目根目录
     */
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

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
