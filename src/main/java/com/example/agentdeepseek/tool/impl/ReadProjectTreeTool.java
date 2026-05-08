package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 项目目录树读取工具
 * 读取项目目录树结构，输出概览树 + 文件类型统计
 */
@Slf4j
@Component
public class ReadProjectTreeTool implements Tool {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", ".mvn", "dist",
            "build", ".idea", ".vscode", ".settings", ".classpath",
            ".project", "__pycache__", "venv", ".env", "coverage",
            ".gitlab", ".svn", "nbproject"
    );

    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_FILE_LIST = 500;

    private final ObjectMapper objectMapper;

    public ReadProjectTreeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "read_project_tree";
    }

    @Override
    public String getDescription() {
        return "读取项目目录树结构，展示项目布局概览和文件类型统计。默认显示 3 层深度（最大 5 层）。"
                + "适用于快速了解项目结构、目录组织方式和各类型文件分布情况。"
                + "需要查看具体文件内容请使用 read_file 工具";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "目录路径（可选），默认为项目根目录");
        properties.set("path", path);

        ObjectNode depth = objectMapper.createObjectNode();
        depth.put("type", "integer");
        depth.put("description", "目录树深度，默认3，最大5");
        properties.set("depth", depth);

        ObjectNode showFileCount = objectMapper.createObjectNode();
        showFileCount.put("type", "boolean");
        showFileCount.put("description", "是否显示各类型文件数量统计，默认 true");
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
        if (depth > 5) depth = 5;

        Path rootPath;
        if (!pathStr.isEmpty()) {
            rootPath = resolvePath(pathStr);
        } else {
            rootPath = Paths.get(ProjectRootContext.get());
        }

        if (!Files.exists(rootPath)) {
            return "错误：目录不存在 - " + rootPath.toAbsolutePath();
        }
        if (!Files.isDirectory(rootPath)) {
            return "错误：路径不是目录 - " + rootPath.toAbsolutePath();
        }

        // 遍历收集文件和目录信息
        List<FileEntry> entries = walkTree(rootPath, rootPath, depth);
        if (entries.isEmpty()) {
            return "（空目录）";
        }

        // 构建输出
        StringBuilder sb = new StringBuilder();
        sb.append("项目目录：").append(rootPath.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");

        // 输出概览树（2层）
        sb.append("【概览树】\n");
        buildOverviewTree(rootPath, rootPath, 0, Math.min(2, depth), "", sb);
        if (depth > 2) {
            sb.append("  ...（完整 ").append(depth).append(" 层目录树见下方）\n");
        }

        // 输出完整树
        sb.append("\n【完整目录树（共 ").append(depth).append(" 层）】\n");
        buildFullTree(rootPath, rootPath, 0, depth, "", true, sb);

        // 文件类型统计
        if (showFileCount) {
            Map<String, Integer> extCount = new TreeMap<>();
            int totalFiles = 0;
            for (FileEntry e : entries) {
                if (!e.isDirectory) {
                    totalFiles++;
                    String ext = getExtension(e.name);
                    if (!ext.isEmpty()) {
                        extCount.merge(ext, 1, Integer::sum);
                    } else {
                        extCount.merge("(无扩展名)", 1, Integer::sum);
                    }
                }
            }

            long dirCount = entries.stream().filter(e -> e.isDirectory).count();

            sb.append("\n【统计信息】\n");
            sb.append("  目录数：").append(dirCount).append("\n");
            sb.append("  文件数：").append(totalFiles).append("\n");
            sb.append("  总条目：").append(entries.size()).append("\n");

            if (!extCount.isEmpty()) {
                sb.append("\n【文件类型分布】\n");
                extCount.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> {
                            String ext = e.getKey();
                            int count = e.getValue();
                            int barLen = Math.max(1, count * 20 / extCount.values().stream().max(Integer::compare).orElse(1));
                            String bar = "█".repeat(barLen);
                            sb.append(String.format("  %-12s %4d  %s%n", ext, count, bar));
                        });
            }
        }

        return sb.toString();
    }

    /**
     * 遍历目录树，收集文件和目录信息
     */
    private List<FileEntry> walkTree(Path root, Path current, int maxDepth) {
        List<FileEntry> entries = new ArrayList<>();
        int rootDepth = root.getNameCount();
        try {
            Files.walkFileTree(current, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    int relativeDepth = dir.getNameCount() - rootDepth;
                    if (relativeDepth > maxDepth) return FileVisitResult.SKIP_SUBTREE;

                    String dirName = dir.getFileName().toString();
                    if (!dir.equals(root) && (EXCLUDED_DIRS.contains(dirName) || dirName.startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root)) {
                        entries.add(new FileEntry(dirName, true, relativeDepth));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    int relativeDepth = file.getNameCount() - rootDepth;
                    if (relativeDepth <= maxDepth && entries.size() < MAX_FILE_LIST) {
                        entries.add(new FileEntry(file.getFileName().toString(), false, relativeDepth));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历目录失败: {}", current, e);
        }
        return entries;
    }

    /**
     * 使用 DirectoryStream 直接构建概览树（更可靠的层级控制）
     */
    private void buildOverviewTree(Path root, Path dir, int currentDepth, int maxDepth,
                                   String prefix, StringBuilder sb) {
        if (currentDepth > maxDepth) return;

        List<Path> children = listDirectory(dir);
        if (children.isEmpty()) return;

        for (int i = 0; i < children.size(); i++) {
            Path child = children.get(i);
            boolean isLast = i == children.size() - 1;
            String connector = isLast ? "└── " : "├── ";
            String name = child.getFileName().toString();

            sb.append(prefix).append(connector);
            if (Files.isDirectory(child)) {
                sb.append(name).append("/\n");
                if (currentDepth < maxDepth) {
                    String childPrefix = prefix + (isLast ? "    " : "│   ");
                    buildOverviewTree(root, child, currentDepth + 1, maxDepth, childPrefix, sb);
                }
            } else {
                sb.append(name).append("\n");
            }
        }
    }

    /**
     * 用递归 + DirectoryStream 构建完整树（支持 depth 控制且跳过排除目录）
     */
    private void buildFullTree(Path root, Path dir, int currentDepth, int maxDepth,
                               String prefix, boolean isRoot, StringBuilder sb) {
        if (currentDepth > maxDepth) return;

        List<Path> children = listDirectory(dir);
        if (children.isEmpty()) return;

        for (int i = 0; i < children.size(); i++) {
            Path child = children.get(i);
            boolean isLast = i == children.size() - 1;

            if (!isRoot) {
                String connector = isLast ? "└── " : "├── ";
                sb.append(prefix).append(connector);
            }

            String name = child.getFileName().toString();

            if (Files.isDirectory(child)) {
                sb.append(name).append("/\n");
                String childPrefix = isRoot ? "" : prefix + (isLast ? "    " : "│   ");
                buildFullTree(root, child, currentDepth + 1, maxDepth, childPrefix, false, sb);
            } else {
                sb.append(name).append("\n");
            }
        }
    }

    /**
     * 列出目录内容，排除忽略的目录
     */
    private List<Path> listDirectory(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (EXCLUDED_DIRS.contains(name) || name.startsWith(".")) continue;
                result.add(entry);
            }
        } catch (IOException e) {
            log.debug("读取目录失败: {}", dir, e);
        }
        result.sort((a, b) -> {
            boolean aDir = Files.isDirectory(a);
            boolean bDir = Files.isDirectory(b);
            if (aDir != bDir) return aDir ? -1 : 1;
            return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
        });
        return result;
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase() : "";
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) return path.normalize();
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    private record FileEntry(String name, boolean isDirectory, int depth) {}
}
