package com.example.agentdeepseek.tool.impl;

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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 项目目录树读取工具
 * 读取项目目录树结构，输出概览树 + 文件类型统计
 * <p>
 * 核心优化：一次 DirectoryStream 递归遍历构建内存树，
 * 基于同一棵树分别渲染概览树、完整树（去重展开）和统计信息，
 * 消除旧版三遍遍历的性能浪费。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, isPathSensitive = true, description = "读取项目目录树")
public class ReadProjectTreeTool implements Tool {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", ".mvn", "dist",
            "build", ".idea", ".vscode", ".settings", ".classpath",
            ".project", "__pycache__", "venv", ".env", "coverage",
            ".gitlab", ".svn", "nbproject"
    );

    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_DEPTH = 5;

    private final ObjectMapper objectMapper;

    public ReadProjectTreeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== Tool 接口 ====================

    @Override
    public String getName() {
        return "read_project_tree";
    }

    @Override
    public String getDescription() {
        return "【适用场景】快速了解项目目录布局、查看有哪些模块/包、统计各类型文件分布。在开始一个新任务时通常应先调用本工具了解项目结构。"
                + "【与 list_files / read_file 的区别】本工具输出树形结构的项目概览和文件类型统计，适合宏观了解项目全貌；list_files 列出单个目录内容；read_file 读取具体文件内容。"
                + "【使用方式】默认不传参数即可查看整个项目（3层深度）。可指定 path 查看子目录，depth 控制展开深度（最大5层）。默认自动排除 .git、node_modules、target 等 build/IDE 目录。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "【可选】要查看的目录路径，默认为项目根目录。示例：'src/main/java/com/example' 查看特定包结构。支持相对路径和绝对路径。");
        properties.set("path", path);

        ObjectNode depth = objectMapper.createObjectNode();
        depth.put("type", "integer");
        depth.put("description", "【可选】目录树展开深度，默认3，最大5。建议：初览项目用2-3层，深入分析特定模块时用4-5层。深度越大输出越长，5层可能输出大量内容。");
        properties.set("depth", depth);

        ObjectNode showFileCount = objectMapper.createObjectNode();
        showFileCount.put("type", "boolean");
        showFileCount.put("description", "【可选】是否在输出末尾显示按扩展名统计的文件类型分布图（含柱状图）。默认 true。若只关心目录结构可设为 false 以减少输出。");
        properties.set("show_file_count", showFileCount);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String pathStr = arguments.path("path").asText();
        int depth = arguments.path("depth").asInt(DEFAULT_DEPTH);
        boolean showFileCount = arguments.path("show_file_count").asBoolean(true);

        if (depth <= 0) depth = DEFAULT_DEPTH;
        if (depth > MAX_DEPTH) depth = MAX_DEPTH;

        Path rootPath;
        if (!pathStr.isEmpty()) {
            rootPath = resolvePath(pathStr);
        } else {
            rootPath = Paths.get(ProjectRootContext.get());
        }

        if (!Files.exists(rootPath)) {
            return "【路径不存在】目录 " + rootPath.toAbsolutePath() + " 不存在。建议：1) 检查路径拼写是否正确 2) 先不传 path 参数查看项目根目录确认目录名 3) 确认目录是否已被删除或重命名";
        }
        if (!Files.isDirectory(rootPath)) {
            return "【类型错误】路径 " + rootPath.toAbsolutePath() + " 是文件而非目录，无法展开为树形结构。如需查看文件内容，请使用 read_file 工具。";
        }

        // ===== 一次遍历构建内存树 =====
        TreeNode root = buildTree(rootPath, depth);
        if (root.children == null || root.children.isEmpty()) {
            return "（空目录）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("项目目录：").append(rootPath.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");

        // ===== 概览树（最多 2 层） =====
        int overviewDepth = Math.min(2, depth);
        sb.append("【概览树】\n");
        renderTreeLevels(root.children, 0, overviewDepth - 1, "", sb);
        if (depth > 2) {
            sb.append("  ...（完整 ").append(depth).append(" 层目录树见下方）\n");
        }

        // ===== 完整树 =====
        sb.append("\n【完整目录树（共 ").append(depth).append(" 层）】\n");
        if (depth <= 2) {
            // 概览树已覆盖全部深度，完整树同概览树
            renderTreeLevels(root.children, 0, depth - 1, "", sb);
        } else {
            // 深度 > 2：概览树已展示前 2 层，完整树从第 3 层开始展开
            sb.append("（第1-2层见上方概览树，以下为第3层及更深层展开）\n\n");
            renderDeepSubtrees(root, 0, new ArrayList<>(), depth, sb);
        }

        // ===== 统计信息 =====
        if (showFileCount) {
            AtomicInteger dirCount = new AtomicInteger(0);
            AtomicInteger fileCount = new AtomicInteger(0);
            Map<String, Integer> extCount = new TreeMap<>();
            collectStats(root, extCount, dirCount, fileCount);

            sb.append("\n【统计信息】\n");
            sb.append("  目录数：").append(dirCount.get()).append("\n");
            sb.append("  文件数：").append(fileCount.get()).append("\n");
            sb.append("  总条目：").append(dirCount.get() + fileCount.get()).append("\n");

            if (!extCount.isEmpty()) {
                sb.append("\n【文件类型分布】\n");
                // 提前计算最大值，避免 O(n²)
                int maxCount = extCount.values().stream().max(Integer::compare).orElse(1);
                extCount.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> {
                            String ext = e.getKey();
                            int count = e.getValue();
                            int barLen = Math.max(1, count * 20 / maxCount);
                            String bar = "█".repeat(barLen);
                            sb.append(String.format("  %-12s %4d  %s%n", ext, count, bar));
                        });
            }
        }

        return sb.toString();
    }

    // ==================== 内存树结构 ====================

    /**
     * 内存中的树节点，一次遍历构建，后续所有渲染和统计都基于此结构。
     */
    private static class TreeNode {
        final String name;
        final boolean isDirectory;
        /** 目录的子节点；文件为 null；目录未展开（达到深度上限）时为 null */
        List<TreeNode> children;

        TreeNode(String name, boolean isDirectory) {
            this.name = name;
            this.isDirectory = isDirectory;
        }
    }

    /**
     * listDirectory 返回的条目，缓存 isDirectory 避免重复系统调用。
     */
    private record DirEntry(Path path, boolean isDirectory) {}

    // ==================== 构建内存树（一次遍历） ====================

    /**
     * 递归构建内存树。remainingDepth 控制递归深度：
     * remainingDepth = 0 时只列直接子节点但不递归进入子目录（子目录 children 为 null）。
     */
    private TreeNode buildTree(Path dir, int remainingDepth) {
        TreeNode node = new TreeNode(dir.getFileName().toString(), true);
        List<DirEntry> entries = listDirectory(dir);
        if (entries.isEmpty()) {
            return node;
        }

        List<TreeNode> children = new ArrayList<>();
        for (DirEntry entry : entries) {
            TreeNode child = new TreeNode(entry.path.getFileName().toString(), entry.isDirectory);
            if (entry.isDirectory && remainingDepth > 0) {
                // 递归进入子目录
                TreeNode subNode = buildTree(entry.path, remainingDepth - 1);
                if (subNode.children != null && !subNode.children.isEmpty()) {
                    child.children = subNode.children;
                }
            }
            // 文件：children 保持 null
            children.add(child);
        }
        node.children = children;
        return node;
    }

    // ==================== 目录列表（带缓存 + 符号链接防护） ====================

    /**
     * 列出目录内容，过滤排除目录和隐藏文件，跳过符号链接。
     * 返回带 isDirectory 缓存的条目列表，避免后续重复系统调用。
     */
    private List<DirEntry> listDirectory(Path dir) {
        List<DirEntry> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                // 跳过排除目录和隐藏文件
                if (EXCLUDED_DIRS.contains(name) || name.startsWith(".")) {
                    continue;
                }
                // 跳过符号链接，防止潜在循环
                if (Files.isSymbolicLink(entry)) {
                    log.debug("跳过符号链接: {}", entry);
                    continue;
                }
                boolean isDir = Files.isDirectory(entry);
                result.add(new DirEntry(entry, isDir));
            }
        } catch (IOException e) {
            log.warn("读取目录失败: {}", dir, e);
        }
        // 排序：目录在前，同类型按名称不区分大小写排序
        result.sort((a, b) -> {
            if (a.isDirectory != b.isDirectory) {
                return a.isDirectory ? -1 : 1;
            }
            return a.path.getFileName().toString()
                    .compareToIgnoreCase(b.path.getFileName().toString());
        });
        return result;
    }

    // ==================== 树形渲染 ====================

    /**
     * 渲染指定层级的树形结构。
     * 用于概览树和 depth<=2 时的完整树。
     *
     * @param siblings     当前层级的兄弟节点列表
     * @param currentLevel 当前层级（0 = 根的直接子节点）
     * @param maxLevel     最大渲染层级
     * @param prefix       前缀字符串（用于绘制 │ 和空格）
     */
    private void renderTreeLevels(List<TreeNode> siblings, int currentLevel, int maxLevel,
                                  String prefix, StringBuilder sb) {
        for (int i = 0; i < siblings.size(); i++) {
            TreeNode node = siblings.get(i);
            boolean isLast = i == siblings.size() - 1;
            String connector = isLast ? "└── " : "├── ";

            sb.append(prefix).append(connector).append(node.name);
            if (node.isDirectory) {
                sb.append("/\n");
                if (currentLevel < maxLevel && node.children != null) {
                    String childPrefix = prefix + (isLast ? "    " : "│   ");
                    renderTreeLevels(node.children, currentLevel + 1, maxLevel, childPrefix, sb);
                }
            } else {
                sb.append("\n");
            }
        }
    }

    /**
     * 渲染深度 >= 2 的子树（用于 depth > 2 时完整树的去重输出）。
     * 遍历树找到所有深度=1（从根算起第2层）的目录节点，
     * 输出其完整路径，然后展开其子节点。
     */
    private void renderDeepSubtrees(TreeNode node, int currentDepth,
                                    List<String> ancestors, int maxDepth,
                                    StringBuilder sb) {
        if (node.children == null) return;
        for (TreeNode child : node.children) {
            if (!child.isDirectory) continue;

            List<String> childAncestors = new ArrayList<>(ancestors);
            childAncestors.add(child.name);

            if (currentDepth == 1) {
                // 这是概览树末层目录，输出路径并展开其子节点（第3层+）
                String path = String.join("/", childAncestors) + "/";
                sb.append(path).append("\n");
                if (child.children != null) {
                    renderChildrenRecursive(child.children, "", sb);
                }
                sb.append("\n");
            } else {
                // currentDepth == 0，继续向下寻找深度=1的目录
                renderDeepSubtrees(child, currentDepth + 1, childAncestors, maxDepth, sb);
            }
        }
    }

    /**
     * 递归渲染子节点（无深度限制，由 buildTree 已确定边界）。
     */
    private void renderChildrenRecursive(List<TreeNode> siblings, String prefix, StringBuilder sb) {
        for (int i = 0; i < siblings.size(); i++) {
            TreeNode node = siblings.get(i);
            boolean isLast = i == siblings.size() - 1;
            String connector = isLast ? "└── " : "├── ";

            sb.append(prefix).append(connector).append(node.name);
            if (node.isDirectory) {
                sb.append("/\n");
                if (node.children != null) {
                    String childPrefix = prefix + (isLast ? "    " : "│   ");
                    renderChildrenRecursive(node.children, childPrefix, sb);
                }
            } else {
                sb.append("\n");
            }
        }
    }

    // ==================== 统计信息收集 ====================

    /**
     * 递归遍历内存树收集统计信息：目录数、文件数、扩展名分布。
     */
    private void collectStats(TreeNode node, Map<String, Integer> extCount,
                              AtomicInteger dirCount, AtomicInteger fileCount) {
        if (node.children == null) {
            // 叶子目录（未展开）或文件
            if (node.isDirectory) {
                dirCount.incrementAndGet();
            }
            return;
        }

        for (TreeNode child : node.children) {
            if (child.isDirectory) {
                dirCount.incrementAndGet();
                collectStats(child, extCount, dirCount, fileCount);
            } else {
                fileCount.incrementAndGet();
                String ext = getExtension(child.name);
                extCount.merge(ext.isEmpty() ? "(无扩展名)" : ext, 1, Integer::sum);
            }
        }
    }

    // ==================== 辅助方法 ====================

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return ""; // dot=0 表示 .gitignore 等隐藏文件，视为无扩展名
        return fileName.substring(dot).toLowerCase();
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) return path.normalize();
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }
}
