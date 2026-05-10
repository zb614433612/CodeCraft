package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DeleteFileConfig;
import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件删除工具
 * 直接物理删除文件或目录，手动模式下需要 ask_execution 授权
 */
@Slf4j
@Component
public class DeleteFileTool implements Tool {

    private final ObjectMapper objectMapper;
    private final DeleteFileConfig deleteFileConfig;

    public DeleteFileTool(ObjectMapper objectMapper, DeleteFileConfig deleteFileConfig) {
        this.objectMapper = objectMapper;
        this.deleteFileConfig = deleteFileConfig;
    }

    @Override
    public String getName() {
        return "delete_file";
    }

    @Override
    public String getDescription() {
        return "删除文件或目录。手动模式下需要 ask_execution 授权。"
                + "拒绝删除项目根目录、受保护目录（可通过配置文件定制）。"
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
        String projectRoot = ProjectRootContext.get();
        Path root = Paths.get(projectRoot).normalize();

        if (!target.isAbsolute()) {
            target = root.resolve(targetPath).normalize();
        } else {
            target = target.normalize();
        }

        // 安全检查
        String checkError = safetyCheck(target, root);
        if (checkError != null) {
            return checkError;
        }

        if (!Files.exists(target)) {
            return "错误：路径不存在 - " + toRelativePath(target, root);
        }

        try {
            if (Files.isDirectory(target)) {
                // 大规模删除前阈值检查
                String warning = checkLargeDirectory(target, root);
                if (warning != null) {
                    return warning;
                }
                return deleteDirectory(target, root);
            } else {
                return deleteSingleFile(target, root);
            }
        } catch (IOException e) {
            log.error("删除失败: {}", toRelativePath(target, root), e);
            return "错误：删除失败 - " + e.getMessage();
        }
    }

    /**
     * 获取相对路径（用于日志和返回值，避免泄露绝对路径）
     */
    private String toRelativePath(Path target, Path root) {
        try {
            return "./" + root.relativize(target).toString().replace("\\", "/");
        } catch (Exception e) {
            return target.toAbsolutePath().toString();
        }
    }

    /**
     * 安全检查：防止删除项目关键目录
     */
    private String safetyCheck(Path target, Path root) {
        // 不能删除项目根目录本身
        if (target.equals(root)) {
            return "错误：不允许删除项目根目录";
        }

        // 不能删除受保护目录及其子文件
        for (String dir : deleteFileConfig.getProtectedDirectories()) {
            Path protectedDir = root.resolve(dir).normalize();
            if (target.equals(protectedDir) || target.startsWith(protectedDir)) {
                return "错误：不允许删除受保护目录 " + dir + " 及其子文件";
            }
        }

        // 路径必须在项目范围内
        if (!target.startsWith(root)) {
            return "错误：删除路径不在项目范围内";
        }

        return null;
    }

    /**
     * 大规模删除阈值检查：超过配置的阈值时提前警告
     */
    private String checkLargeDirectory(Path dir, Path root) {
        int threshold = deleteFileConfig.getDeleteWarningThreshold();
        if (threshold <= 0) {
            return null; // 不检查
        }

        try {
            // 快速统计一级文件数
            AtomicInteger count = new AtomicInteger(0);
            try (var walk = Files.walk(dir)) {
                walk.forEach(p -> count.incrementAndGet());
            }
            // 减掉目录本身
            int total = count.get() - 1;
            if (total > threshold) {
                return "警告：目录 " + toRelativePath(dir, root) + " 包含 " + total
                        + " 个文件/子目录，超过阈值 " + threshold
                        + "。如需删除请确认后重试（可调整 delete-file.delete-warning-threshold 配置项）";
            }
        } catch (IOException e) {
            log.warn("统计目录文件数失败: {}", toRelativePath(dir, root), e);
        }
        return null;
    }

    /**
     * 删除单个文件
     */
    private String deleteSingleFile(Path file, Path root) throws IOException {
        long size = Files.size(file);
        Files.delete(file);
        String sizeStr = formatSize(size);
        String relativePath = toRelativePath(file, root);
        log.info("删除文件: {} ({})", relativePath, sizeStr);
        return "文件已删除：" + relativePath + "（" + sizeStr + "）";
    }

    /**
     * 递归删除目录
     * 一次 walk 完成计数和删除，消除 OOM 风险
     */
    private String deleteDirectory(Path dir, Path root) throws IOException {
        AtomicInteger totalCount = new AtomicInteger(0);
        AtomicInteger fileCount = new AtomicInteger(0);
        List<String> failures = new ArrayList<>();

        // 一次 walk 完成：计数（遇目录+1、遇文件+1且 fileCount+1）+ 删除（逆序删除子文件后删目录）
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        totalCount.incrementAndGet();
                        if (!Files.isDirectory(p)) {
                            fileCount.incrementAndGet();
                        }
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("删除失败: {}", p, e);
                            failures.add(p.toAbsolutePath().toString());
                        }
                    });
        }

        // 验证是否删除成功
        String relativePath = toRelativePath(dir, root);
        if (Files.exists(dir)) {
            String failMsg = failures.isEmpty() ? "" : "失败文件：" + String.join(", ", failures);
            return "错误：目录删除不完整，请检查文件权限" + (failMsg.isEmpty() ? "" : "。" + failMsg);
        }

        if (!failures.isEmpty()) {
            log.warn("目录删除部分失败: {} ({} 个文件，{} 个条目)，{} 个失败",
                    relativePath, fileCount.get(), totalCount.get(), failures.size());
            return "目录已删除（部分文件删除失败）：" + relativePath
                    + "（" + fileCount.get() + " 个文件，" + totalCount.get() + " 个条目，失败：" + failures.size() + " 个）";
        }

        log.info("删除目录: {} ({} 个文件，{} 个条目)", relativePath, fileCount.get(), totalCount.get());
        return "目录已删除：" + relativePath + "（" + fileCount.get() + " 个文件，" + totalCount.get() + " 个条目）";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
