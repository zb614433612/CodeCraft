package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件删除工具
 * 直接物理删除文件或目录，手动模式下需要 ask_execution 授权
 */
@Slf4j
@Component
public class DeleteFileTool implements Tool {

    private final ObjectMapper objectMapper;

    public DeleteFileTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "delete_file";
    }

    @Override
    public String getDescription() {
        return "删除文件或目录。手动模式下需要 ask_execution 授权。"
                + "拒绝删除项目根目录、.git 目录、node_modules 目录。"
                + "删除目录时递归删除所有子文件和子目录";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "要删除的文件或目录路径，支持绝对路径或相对于项目根目录的路径");
        properties.set("path", path);

        parameters.set("properties", properties);
        parameters.putArray("required").add("path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response;
        }

        String targetPath = arguments.path("path").asText();
        if (targetPath.isEmpty()) {
            return "错误：缺少必要参数 path";
        }

        Path target = Paths.get(targetPath);
        if (!target.isAbsolute()) {
            String projectRoot = com.example.agentdeepseek.util.ProjectRootContext.get();
            target = Paths.get(projectRoot, targetPath).normalize();
        } else {
            target = target.normalize();
        }

        // 安全检查
        String checkError = safetyCheck(target);
        if (checkError != null) {
            return checkError;
        }

        if (!Files.exists(target)) {
            return "错误：路径不存在 - " + target.toAbsolutePath();
        }

        try {
            if (Files.isDirectory(target)) {
                return deleteDirectory(target);
            } else {
                return deleteSingleFile(target);
            }
        } catch (IOException e) {
            log.error("删除失败: {}", target, e);
            return "错误：删除失败 - " + e.getMessage();
        }
    }

    /**
     * 安全检查：防止删除项目关键目录
     */
    private String safetyCheck(Path target) {
        String projectRoot = com.example.agentdeepseek.util.ProjectRootContext.get();
        Path root = Paths.get(projectRoot).normalize();

        // 不能删除项目根目录本身
        if (target.equals(root)) {
            return "错误：不允许删除项目根目录";
        }

        // 不能删除 .git 目录
        Path gitDir = root.resolve(".git").normalize();
        if (target.equals(gitDir) || target.startsWith(gitDir)) {
            return "错误：不允许删除 .git 目录";
        }

        // 不能删除 node_modules 目录
        Path nodeModules = root.resolve("node_modules").normalize();
        if (target.equals(nodeModules) || target.startsWith(nodeModules)) {
            return "错误：不允许删除 node_modules 目录";
        }

        // 路径必须在项目范围内
        if (!target.startsWith(root)) {
            return "错误：删除路径不在项目范围内";
        }

        return null;
    }

    /**
     * 删除单个文件
     */
    private String deleteSingleFile(Path file) throws IOException {
        long size = Files.size(file);
        Files.delete(file);
        String sizeStr = formatSize(size);
        log.info("删除文件: {} ({} )", file.toAbsolutePath(), sizeStr);
        return "文件已删除：" + file.toAbsolutePath() + "（" + sizeStr + "）";
    }

    /**
     * 递归删除目录
     */
    private String deleteDirectory(Path dir) throws IOException {
        // 先统计文件数
        List<Path> allFiles = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            walk.forEach(allFiles::add);
        }
        int totalCount = allFiles.size();
        int fileCount = (int) allFiles.stream().filter(p -> !Files.isDirectory(p)).count();

        // 递归删除
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除失败: {}", p, e);
                        }
                    });
        }

        // 验证是否删除成功
        if (Files.exists(dir)) {
            return "错误：目录删除不完整，请检查文件权限";
        }

        log.info("删除目录: {} ({} 个文件，{} 个条目)", dir.toAbsolutePath(), fileCount, totalCount);
        return "目录已删除：" + dir.toAbsolutePath() + "（" + fileCount + " 个文件，" + totalCount + " 个条目）";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
