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

/**
 * 文件搜索工具
 * 使用 glob 模式匹配查找文件，支持排除模式
 */
@Slf4j
@Component
public class GlobFilesTool implements Tool {

    /** 默认排除的目录名 */
    private static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", ".mvn", "dist",
            "build", ".idea", ".vscode", ".settings", ".classpath",
            ".project", ".DS_Store", "__pycache__", ".gitlab",
            "venv", ".env", "coverage"
    );

    /** 最大返回结果数，防止输出过大 */
    private static final int MAX_RESULTS = 200;

    private final ObjectMapper objectMapper;

    public GlobFilesTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "glob_files";
    }

    @Override
    public String getDescription() {
        return "使用 glob 模式匹配查找文件，支持指定搜索根目录和排除模式。"
                + "例如：**/*.java 搜索所有 Java 文件，src/**/*.ts 搜索 src 下的 TypeScript 文件。"
                + "适用于查找特定类型文件或定位项目中符合命名模式的文件。搜索文件内容请使用 grep_search 工具";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "glob 搜索模式，如 **/*.java 搜索所有Java文件，src/**/*.ts 搜索src下的TypeScript文件");
        properties.set("pattern", pattern);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "string");
        root.put("description", "搜索根目录（可选），默认为当前项目根目录");
        properties.set("root", root);

        ObjectNode excludePattern = objectMapper.createObjectNode();
        excludePattern.put("type", "string");
        excludePattern.put("description", "排除的 glob 模式（可选），如 **/test/** 排除测试目录");
        properties.set("exclude_pattern", excludePattern);

        parameters.set("properties", properties);
        parameters.putArray("required").add("pattern");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String patternStr = arguments.path("pattern").asText();
        String rootStr = arguments.path("root").asText();
        String excludeStr = arguments.path("exclude_pattern").asText();

        if (patternStr.isEmpty()) {
            return "错误：缺少必要参数 pattern";
        }

        Path rootPath;
        if (!rootStr.isEmpty()) {
            rootPath = resolvePath(rootStr);
        } else {
            rootPath = Paths.get(ProjectRootContext.get());
        }

        if (!Files.exists(rootPath)) {
            return "错误：搜索根目录不存在 - " + rootPath.toAbsolutePath();
        }
        if (!Files.isDirectory(rootPath)) {
            return "错误：搜索根路径不是目录 - " + rootPath.toAbsolutePath();
        }

        // 编译匹配器
        final String globPattern = patternStr.startsWith("glob:") ? patternStr : "glob:" + patternStr;
        final PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher(globPattern);
        } catch (Exception e) {
            return "错误：glob 模式语法错误 - " + e.getMessage()
                    + "\n提示：使用 ** 匹配任意目录，* 匹配文件名，如 **/*.java";
        }

        // 编译排除匹配器
        final PathMatcher excludeMatcher;
        if (!excludeStr.isEmpty()) {
            try {
                String excludeGlob = excludeStr.startsWith("glob:") ? excludeStr : "glob:" + excludeStr;
                excludeMatcher = FileSystems.getDefault().getPathMatcher(excludeGlob);
            } catch (Exception e) {
                return "错误：排除模式语法错误 - " + e.getMessage();
            }
        } else {
            excludeMatcher = null;
        }

        // 递归搜索
        List<FileResult> results = walkFiles(rootPath, matcher, excludeMatcher);

        // 构建结果
        StringBuilder sb = new StringBuilder();
        sb.append("搜索模式：").append(patternStr).append("\n");
        if (!rootStr.isEmpty()) {
            sb.append("搜索目录：").append(rootPath.toAbsolutePath()).append("\n");
        }
        if (!excludeStr.isEmpty()) {
            sb.append("排除模式：").append(excludeStr).append("\n");
        }
        sb.append("匹配结果：共 ").append(results.size()).append(" 个文件");
        if (results.size() >= MAX_RESULTS) {
            sb.append("（仅显示前 ").append(MAX_RESULTS).append(" 个，请细化搜索模式）");
        }
        sb.append("\n");
        sb.append("────────────────────────────────────────\n");

        for (FileResult r : results) {
            sb.append(String.format("  %-6s %s\n", formatSize(r.size), r.path));
        }

        if (results.isEmpty()) {
            sb.append("  （无匹配结果）\n");
            sb.append("\n提示：glob 模式示例\n");
            sb.append("  **/*.java        → 所有 Java 文件\n");
            sb.append("  src/**/*.ts       → src 下所有 TypeScript 文件\n");
            sb.append("  **/pom.xml        → 所有 pom.xml 文件\n");
            sb.append("  **/*.{js,ts}      → 所有 JS 和 TS 文件\n");
        }

        return sb.toString();
    }

    /**
     * 递归遍历目录，收集匹配的文件
     */
    private List<FileResult> walkFiles(Path rootPath, PathMatcher matcher, PathMatcher excludeMatcher) {
        List<FileResult> results = new ArrayList<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (DEFAULT_EXCLUDED_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (excludeMatcher != null && excludeMatcher.matches(rootPath.relativize(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path relativePath = rootPath.relativize(file);
                    if (matcher.matches(relativePath)) {
                        if (excludeMatcher == null || !excludeMatcher.matches(relativePath)) {
                            results.add(new FileResult(relativePath.toString().replace('\\', '/'), attrs.size()));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("搜索文件失败", e);
        }
        results.sort(Comparator.comparing(r -> r.path));
        return results;
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        return String.format("%.1fM", bytes / (1024.0 * 1024));
    }

    private record FileResult(String path, long size) {}
}
