package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
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
@ToolPermission(category = OperationCategory.READ, isPathSensitive = true, description = "按模式搜索文件")
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
        return "按文件名模式搜索文件，返回匹配的文件路径列表。\n"
                + "【适用场景】\n"
                + "  - 查找特定类型的文件，如所有Java文件(**/*.java)、所有配置文件(**/*.yml)\n"
                + "  - 定位已知名称的文件，如找 pom.xml、Dockerfile\n"
                + "  - 探索项目结构，如 src/**/*.ts 看 TypeScript 文件分布\n"
                + "【与 grep_search 的区别】\n"
                + "  - glob_files: 按【文件名】匹配，不知道文件内容\n"
                + "  - grep_search: 按【文件内容】匹配，能搜到文件中的文本\n"
                + "  - 需要同时按文件名+内容过滤时：先用 glob_files 缩小范围，再用 grep_search 在结果目录中搜索内容\n"
                + "【最佳实践】\n"
                + "  - 尽量指定 root 参数缩小搜索范围，提高速度\n"
                + "  - 结果超 200 条时说明模式太宽泛，应增加更具体的路径前缀\n"
                + "  - 排除无关目录（如 exclude_pattern=**/test/**）减少噪音";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "【必填】文件名匹配模式，使用 glob 语法。\n"
                + "示例：\n"
                + "  - **/*.java → 所有 Java 文件\n"
                + "  - src/**/*.ts → src 下所有 TypeScript 文件\n"
                + "  - **/pom.xml → 所有 pom.xml 文件\n"
                + "  - **/*.{js,ts} → 所有 JS 和 TS 文件\n"
                + "  - *.properties → 当前目录的属性文件（不含子目录）\n"
                + "注意：** 匹配任意层目录，* 匹配文件名，{a,b} 匹配多个扩展名");
        properties.set("pattern", pattern);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "string");
        root.put("description", "【可选】限制搜索范围到指定子目录，默认为项目根目录。\n"
                + "尽量指定此参数缩小范围。\n"
                + "示例：'src/main' → 只在 src/main 下搜索，'src/test' → 只在测试目录下搜索。\n"
                + "留空 → 全项目搜索（文件多时结果多）");
        properties.set("root", root);

        ObjectNode excludePattern = objectMapper.createObjectNode();
        excludePattern.put("type", "string");
        excludePattern.put("description", "【可选】排除不需要的文件或目录。\n"
                + "系统已自动排除 .git、node_modules、target 等目录，无需手动排除。\n"
                + "示例：'**/test/**' → 排除所有测试目录，'**/*.min.js' → 排除压缩后的 JS 文件");
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
            return "【缺少参数】请提供 pattern 参数。\n"
                    + "示例：pattern='**/*.java' 搜索所有Java文件\n"
                    + "如果不知道要搜什么，先用 read_project_tree 查看项目结构";
        }

        Path projectRoot = Paths.get(ProjectRootContext.get()).normalize().toAbsolutePath();
        Path rootPath;
        if (!rootStr.isEmpty()) {
            rootPath = resolvePath(rootStr);
            // 路径遍历防护：校验搜索结果必须在项目目录范围内
            if (!rootPath.startsWith(projectRoot)) {
                return "【路径越界】搜索路径超出项目目录范围。\n"
                    + "请求路径：" + rootPath.toAbsolutePath() + "\n"
                    + "项目根目录：" + projectRoot + "\n"
                    + "建议：使用相对于项目根目录的路径，如 'src/main'";
            }
        } else {
            rootPath = projectRoot;
        }

        if (!Files.exists(rootPath)) {
            return "【目录不存在】" + rootPath.toAbsolutePath() + "\n"
                    + "建议：先用 read_project_tree 查看项目有哪些目录，确认路径拼写正确";
        }
        if (!Files.isDirectory(rootPath)) {
            return "【不是目录】" + rootPath.toAbsolutePath() + " 是一个文件，不是目录。\n"
                    + "建议：root 参数应指向目录而非文件，如 'src/main/java'";
        }

        // 编译匹配器
        final String globPattern = patternStr.startsWith("glob:") ? patternStr : "glob:" + patternStr;
        final PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher(globPattern);
        } catch (Exception e) {
            return "【glob 语法错误】\n输入模式：" + patternStr + "\n错误原因：" + e.getMessage()
                    + "\n修正建议：检查通配符是否正确，** 匹配任意层目录，* 匹配文件名。"
                    + "\n常见模式参考：**/*.java / **/*.{js,ts} / **/pom.xml";
        }

        // 编译排除匹配器
        final PathMatcher excludeMatcher;
        if (!excludeStr.isEmpty()) {
            try {
                String excludeGlob = excludeStr.startsWith("glob:") ? excludeStr : "glob:" + excludeStr;
                excludeMatcher = FileSystems.getDefault().getPathMatcher(excludeGlob);
            } catch (Exception e) {
                return "【排除模式语法错误】\n输入：" + excludeStr + "\n错误原因：" + e.getMessage()
                        + "\n示例：'**/test/**' 排除测试目录，'**/*.min.js' 排除压缩文件";
            }
        } else {
            excludeMatcher = null;
        }

        // 递归搜索
        List<FileResult> results;
        try {
            results = walkFiles(rootPath, matcher, excludeMatcher);
        } catch (IOException e) {
            return "【搜索异常】读取文件系统时出错：" + e.getMessage()
                    + "\n建议：检查磁盘空间、文件权限，或缩小搜索范围重试";
        }

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
    private List<FileResult> walkFiles(Path rootPath, PathMatcher matcher, PathMatcher excludeMatcher) throws IOException {
        List<FileResult> results = new ArrayList<>();
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (DEFAULT_EXCLUDED_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (excludeMatcher != null) {
                        Path relDir = rootPath.relativize(dir);
                        // 目录本身匹配排除模式
                        if (excludeMatcher.matches(relDir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // 目录下的内容是否匹配排除模式（如排除模式 **/test/**，遍历到 src/test 时，
                        // src/test 本身不匹配，但 src/test/_ 匹配，应跳过整个子树）
                        // 使用虚拟文件名 _ 避免 Windows 下 * 非法字符问题
                        if (excludeMatcher.matches(relDir.resolve("_"))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
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
                    log.warn("访问文件失败，已跳过: {} - {}", file, exc.getMessage() != null ? exc.getMessage() : exc.getClass().getSimpleName());
                    return FileVisitResult.CONTINUE;
                }
        });
        results.sort(Comparator.comparing(r -> r.path));
        return results;
    }

    private Path resolvePath(String pathStr) {
        Path projectRoot = Paths.get(ProjectRootContext.get()).normalize().toAbsolutePath();
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return projectRoot.resolve(pathStr).normalize();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        return String.format("%.1fM", bytes / (1024.0 * 1024));
    }

    private record FileResult(String path, long size) {}
}
