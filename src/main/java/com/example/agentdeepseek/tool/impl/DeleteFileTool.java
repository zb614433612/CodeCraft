package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DeleteFileConfig;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ProjectRootContext;
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
 * 直接物理删除文件或目录，手动模式下需要用户授权
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.DELETE, affectsData = true, isPathSensitive = true, highRisk = true, description = "删除文件或目录")
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
        return "【适用场景】删除项目中不再需要的文件或空目录，清理临时文件、过时代码。"
                + "【与 EditFileTool / WriteFile 的区别】本工具直接物理删除，不可恢复；如需修改文件内容请用 EditFileTool，如需创建文件请用 WriteFile。"
                + "【使用方式】传入要删除的文件或目录路径（支持相对路径和绝对路径）。手动模式下需要用户授权。删除目录时递归删除所有子文件和子目录。"
                + "【限制】拒绝删除项目根目录和受保护目录（可通过配置文件定制）。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "【必填】要删除的文件或目录路径。示例：'src/main/java/com/example/OldFile.java' 或 './temp/logs'。支持相对路径（相对于项目根目录）和绝对路径。");
        properties.set("path", path);

        parameters.set("properties", properties);
        parameters.putArray("required").add("path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {

        String targetPath = arguments.path("path").asText();
        if (targetPath.isEmpty()) {
            return "【参数缺失】未提供 path 参数。请传入要删除的文件或目录路径，例如：path=\"src/main/java/com/example/OldFile.java\"";
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
            return "【文件不存在】路径 " + toRelativePath(target, root) + " 不存在，无法删除。建议：1) 检查文件名拼写是否正确 2) 先调用 read_project_tree 确认实际文件路径 3) 确认文件是否已被移动或删除";
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
            return "【删除失败】" + toRelativePath(target, root) + " 删除时发生 I/O 错误：" + e.getMessage() + "。建议：1) 检查文件是否被其他进程占用 2) 确认是否有足够的文件权限 3) 可尝试手动删除后继续操作";
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
            return "【安全限制】不允许删除项目根目录。如需清理项目，请指定根目录下的具体子目录或文件路径。";
        }

        // 不能删除受保护目录及其子文件
        for (String dir : deleteFileConfig.getProtectedDirectories()) {
            Path protectedDir = root.resolve(dir).normalize();
            if (target.equals(protectedDir) || target.startsWith(protectedDir)) {
                return "【安全限制】'" + dir + "' 是受保护目录，不允许删除该目录及其内部文件。当前目标 '" + toRelativePath(target, root) + "' 命中此限制。如需清理，请使用系统文件管理器手动操作。";
            }
        }

        // 路径范围交由 ToolExecutionPipeline 层面二（PathSecurityChecker）统一校验
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
                return "【删除确认】目录 " + toRelativePath(dir, root) + " 包含 " + total
                        + " 个文件/子目录，超过安全阈值 " + threshold
                        + "。如需继续删除，请确认后重试。提示：可通过配置项 delete-file.delete-warning-threshold 调整阈值。";
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
            return "【部分失败】目录 " + relativePath + " 删除未完全成功，部分文件可能因权限不足或文件被占用无法删除" + (failMsg.isEmpty() ? "" : "。" + failMsg) + "。建议：1) 检查文件是否被其他进程占用 2) 以管理员权限重试 3) 手动清理残留文件";
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
