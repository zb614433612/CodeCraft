package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 代码搜索工具
 * 在代码库中搜索关键字或正则表达式，支持文件类型过滤、上下文显示等
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, description = "搜索文件内容")
public class GrepSearchTool implements Tool {

    private static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", ".mvn", "dist",
            "build", ".idea", ".vscode", ".settings", ".classpath",
            ".project", "__pycache__", "venv", ".env", "coverage"
    );

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
            ".woff", ".woff2", ".ttf", ".eot",
            ".jar", ".zip", ".tar", ".gz", ".7z", ".rar",
            ".exe", ".dll", ".so", ".dylib",
            ".class", ".o", ".a", ".lib"
    );

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int DEFAULT_CONTEXT_LINES = 0;
    private static final int MAX_MATCHES_PER_FILE = 100;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".groovy", ".scala",
            ".ts", ".tsx", ".js", ".jsx", ".vue", ".css", ".scss", ".less", ".html", ".json", ".xml", ".yml", ".yaml",
            ".py", ".rb", ".go", ".rs", ".php", ".pl", ".pm", ".sh", ".bat", ".cmd",
            ".md", ".txt", ".properties", ".cfg", ".conf", ".ini", ".toml",
            ".sql", ".gradle", ".mvn", ".dockerfile",
            ".c", ".cpp", ".h", ".hpp", ".cs", ".swift", ".m", ".mm"
    );

    private final ObjectMapper objectMapper;

    public GrepSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "grep_search";
    }

    @Override
    public String getDescription() {
        return "在文件内容中搜索文本或正则表达式，返回匹配的文件路径、行号和行内容。\n"
                + "【适用场景】\n"
                + "  - 搜索函数定义：pattern='function\\s+\\w+' 或 'def \\w+'\n"
                + "  - 搜索变量引用：pattern='userService' 并设 regex=false 做纯文本搜索\n"
                + "  - 搜索 API 调用：pattern='httpClient\\.get\\('\n"
                + "  - 搜索错误/日志：pattern='NullPointerException'\n"
                + "【与 glob_files 的区别】\n"
                + "  - grep_search: 按【文件内容】匹配，能搜到文件中的文本\n"
                + "  - glob_files: 按【文件名】匹配，不知道文件内容\n"
                + "  - 先 glob 缩小文件范围，再 grep 搜内容，组合使用效率最高\n"
                + "【正则 vs 纯文本选择】\n"
                + "  - regex=true（默认）：用于模式匹配，如 '\\\\d{4}-\\\\d{2}-\\\\d{2}' 搜日期\n"
                + "  - regex=false：精确搜某个字符串，如搜 'myMethod()' 不会把 'myMethodX()' 也匹配到\n"
                + "  - 注意：regex=true 无匹配时会自动降级为纯文本再搜一次\n"
                + "【性能建议】\n"
                + "  - 优先使用 path 和 include 缩小范围\n"
                + "  - 短关键字（<3字符）可能匹配过多结果，建议加路径限制";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "【必填】要搜索的文本或正则表达式。\n"
                + "当 regex=true（默认）时支持正则语法。\n"
                + "示例：\n"
                + "  - 'findUser' → 搜索包含 findUser 的行\n"
                + "  - 'function\\s+\\w+' → 匹配函数定义\n"
                + "  - '\\\\d{4}-\\\\d{2}-\\\\d{2}' → 匹配日期格式\n"
                + "  - 'class\\s+\\w+' → 匹配类定义\n"
                + "提示：搜索普通变量名/方法名时建议设 regex=false 更精确");
        properties.set("pattern", pattern);

        ObjectNode include = objectMapper.createObjectNode();
        include.put("type", "string");
        include.put("description", "【可选】限定只搜索特定类型的文件。\n"
                + "示例：'*.java' → 只搜 Java 文件，'*.{ts,tsx}' → 只搜 TypeScript 文件，'*.{yml,yaml}' → YAML 配置文件\n"
                + "留空 → 搜索所有文本文件。结合 path 参数可进一步缩小范围");
        properties.set("include", include);

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "【可选】限定搜索目录范围。强烈建议指定此参数！\n"
                + "空着=搜全项目，速度慢且结果噪音多。\n"
                + "示例：'src/main/java' → 只在核心源码中搜，'src/test' → 只在测试代码中搜");
        properties.set("path", path);

        ObjectNode maxResults = objectMapper.createObjectNode();
        maxResults.put("type", "integer");
        maxResults.put("description", "【可选】最大返回的匹配行数，默认50。\n"
                + "超过限制时结果会被截断，末尾会提示。如果结果被截断，可通过 path/include/更精确的pattern 缩小范围");
        properties.set("max_results", maxResults);

        ObjectNode ignoreCase = objectMapper.createObjectNode();
        ignoreCase.put("type", "boolean");
        ignoreCase.put("description", "【可选】是否忽略大小写，默认 false（大小写敏感）。\n"
                + "false → 'User' 不匹配 'user'；true → 'User' 匹配 'user'/'USER'/'uSeR'\n"
                + "提示：Java 代码搜索建议 false，配置文件搜索可设 true");
        properties.set("ignore_case", ignoreCase);

        ObjectNode regex = objectMapper.createObjectNode();
        regex.put("type", "boolean");
        regex.put("description", "【可选】搜索模式类型，默认 true（正则模式）。\n"
                + "true（默认）→ 支持正则语法，如 '\\d+' 匹配数字\n"
                + "false → 精确文本匹配，搜 'myMethod()' 不会误匹配 'myMethodX()'\n"
                + "提示：搜普通变量名/方法名时设 false 更精确。"
                + "正则无匹配时会自动降级为纯文本重试一次");
        properties.set("regex", regex);

        ObjectNode contextLines = objectMapper.createObjectNode();
        contextLines.put("type", "integer");
        contextLines.put("description", "【可选】匹配行上下各显示 N 行，帮助定位代码上下文。\n"
                + "建议值：0（默认，只显示匹配行本身）、2~3（查看前后几行，适合理解代码语境）、5+（查看完整函数片段）\n"
                + "注意：context_lines 越大，输出越多，可能触发截断");
        properties.set("context_lines", contextLines);

        parameters.set("properties", properties);
        parameters.putArray("required").add("pattern");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String patternStr = arguments.path("pattern").asText();
        String includeGlob = arguments.path("include").asText();
        String searchPathStr = arguments.path("path").asText();
        int maxResults = arguments.path("max_results").asInt(DEFAULT_MAX_RESULTS);
        boolean ignoreCase = arguments.path("ignore_case").asBoolean(false);
        boolean isRegex = arguments.path("regex").asBoolean(true);
        int contextLines = arguments.path("context_lines").asInt(DEFAULT_CONTEXT_LINES);

        if (patternStr.isEmpty()) {
            return "【缺少参数】请提供 pattern 参数。\n"
                    + "示例：pattern='userService' 搜索变量引用\n"
                    + "或 pattern='class\\s+\\w+' 搜索类定义";
        }
        if (maxResults <= 0) maxResults = DEFAULT_MAX_RESULTS;
        if (contextLines < 0) contextLines = 0;

        // 编译搜索模式
        final Pattern searchPattern;
        try {
            int flags = 0;
            if (ignoreCase) {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }
            if (isRegex) {
                searchPattern = Pattern.compile(patternStr, flags);
            } else {
                searchPattern = Pattern.compile(Pattern.quote(patternStr), flags);
            }
        } catch (PatternSyntaxException e) {
            return "【正则表达式语法错误】\n输入：" + patternStr + "\n错误原因：" + e.getMessage()
                    + "\n方案A（推荐）：设 regex=false 进行纯文本搜索\n"
                    + "方案B：修正正则语法，注意特殊字符需要转义如 \\( \\) \\. \\+ \\*";
        }

        // 解析搜索根目录
        Path searchRoot;
        if (!searchPathStr.isEmpty()) {
            searchRoot = resolvePath(searchPathStr);
        } else {
            searchRoot = Paths.get(ProjectRootContext.get());
        }

        if (!Files.exists(searchRoot)) {
            return "【目录不存在】" + searchRoot.toAbsolutePath() + "\n"
                    + "建议：先用 read_project_tree 查看项目有哪些目录，确认 path 参数拼写正确";
        }
        if (!Files.isDirectory(searchRoot)) {
            return "【不是目录】" + searchRoot.toAbsolutePath() + " 是一个文件。\n"
                    + "建议：path 参数应指向目录而非文件，如 'src/main/java'";
        }

        // 编译文件包含匹配器
        final PathMatcher includeMatcher;
        if (!includeGlob.isEmpty()) {
            try {
                String globPattern = includeGlob.startsWith("glob:") ? includeGlob : "glob:" + includeGlob;
                includeMatcher = FileSystems.getDefault().getPathMatcher(globPattern);
            } catch (Exception e) {
                return "【include 语法错误】\n输入：" + includeGlob + "\n错误原因：" + e.getMessage()
                        + "\ninclude 使用 glob 语法：'*.java' 匹配所有Java文件，'*.{ts,tsx}' 匹配TypeScript文件";
            }
        } else {
            includeMatcher = null;
        }

        // 执行搜索
        List<SearchResult> allResults = searchFiles(searchRoot, searchPattern, includeMatcher, maxResults, contextLines);

        // 构建结果
        StringBuilder sb = new StringBuilder();
        sb.append("搜索模式：").append(patternStr);
        if (!isRegex) sb.append("（纯文本）");
        if (ignoreCase) sb.append("（忽略大小写）");
        sb.append("\n");
        if (!includeGlob.isEmpty()) {
            sb.append("文件过滤：").append(includeGlob).append("\n");
        }
        sb.append("搜索目录：").append(searchRoot.toAbsolutePath()).append("\n");
        sb.append("匹配结果：共 ").append(allResults.size()).append(" 个");
        if (allResults.size() >= maxResults) {
            sb.append("（仅显示前 ").append(maxResults).append(" 个，请细化搜索条件）");
        }
        sb.append("\n");
        sb.append("────────────────────────────────────────\n");

        String currentFile = null;
        int fileMatchCount = 0;
        for (SearchResult r : allResults) {
            if (!r.filePath.equals(currentFile)) {
                if (currentFile != null) {
                    sb.append("    ... ").append(fileMatchCount).append(" 处匹配").append("\n");
                }
                sb.append(r.filePath).append("\n");
                currentFile = r.filePath;
                fileMatchCount = 0;
            }
            fileMatchCount++;

            for (String line : r.contextBefore) {
                sb.append("  ").append(String.format("%5d", r.lineNum - r.contextBefore.size())).append(" - ").append(escapeLine(line)).append("\n");
            }
            sb.append("  ").append(String.format("%5d", r.lineNum)).append(" > ").append(escapeLine(r.lineContent)).append("\n");
            for (String line : r.contextAfter) {
                sb.append("  ").append(String.format("%5d", r.lineNum + 1)).append(" - ").append(escapeLine(line)).append("\n");
            }
        }
        if (currentFile != null && fileMatchCount > 0) {
            sb.append("    ... ").append(fileMatchCount).append(" 处匹配").append("\n");
        }

        if (allResults.isEmpty()) {
            // 自动降级：正则模式无匹配时，用纯文本再搜一次
            if (isRegex) {
                Pattern textPattern;
                try {
                    int textFlags = 0;
                    if (ignoreCase) textFlags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    textPattern = Pattern.compile(Pattern.quote(patternStr), textFlags);
                } catch (PatternSyntaxException e) {
                    textPattern = null;
                }
                if (textPattern != null) {
                    List<SearchResult> textResults = searchFiles(searchRoot, textPattern, includeMatcher, maxResults, contextLines);
                    if (!textResults.isEmpty()) {
                        sb.append("（正则模式未匹配到结果，已自动降级为纯文本搜索，找到 ").append(textResults.size()).append(" 个结果。"
                                + "如果纯文本结果符合预期，下次请设置 regex=false 以避免降级开销）\n");
                        allResults = textResults;
                        // 重新输出结果
                        currentFile = null;
                        fileMatchCount = 0;
                        for (SearchResult r : allResults) {
                            if (!r.filePath.equals(currentFile)) {
                                if (currentFile != null) {
                                    sb.append("    ... ").append(fileMatchCount).append(" 处匹配\n");
                                }
                                sb.append(r.filePath).append("\n");
                                currentFile = r.filePath;
                                fileMatchCount = 0;
                            }
                            fileMatchCount++;
                            sb.append("  ").append(String.format("%5d", r.lineNum)).append(" > ").append(escapeLine(r.lineContent)).append("\n");
                        }
                        if (currentFile != null && fileMatchCount > 0) {
                            sb.append("    ... ").append(fileMatchCount).append(" 处匹配\n");
                        }
                    }
                }
            }

            // 仍无结果（包括降级后仍无），输出提示
            if (allResults.isEmpty()) {
                sb.append("  （无匹配结果）\n");
                sb.append("\n【搜索建议】\n");
                sb.append("  1. 检查关键词拼写是否正确\n");
                sb.append("  2. 尝试 ignore_case=true 忽略大小写\n");
                sb.append("  3. 去掉 path 或 include 限制扩大搜索范围\n");
                sb.append("  4. 尝试更短或更通用的关键词\n");
                sb.append("  5. 如果是正则搜索，尝试 regex=false 做纯文本搜索\n");
            }
        }

        return sb.toString();
    }

    private List<SearchResult> searchFiles(Path searchRoot, Pattern searchPattern, PathMatcher includeMatcher,
                                           int maxResults, int contextLines) {
        List<SearchResult> results = new ArrayList<>();
        try {
            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (DEFAULT_EXCLUDED_DIRS.contains(dirName) || (dirName.startsWith(".") && !dirName.equals("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        if (attrs.size() > MAX_FILE_SIZE || attrs.size() == 0) return FileVisitResult.CONTINUE;

                        String fileName = file.getFileName().toString();
                        String ext = getExtension(fileName).toLowerCase();
                        if (BINARY_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;

                        if (includeMatcher != null) {
                            // 先匹配文件名（简单模式如 *.java），再匹配相对路径（跨目录模式如 **/util/*.java）
                            boolean matches = includeMatcher.matches(file.getFileName())
                                    || includeMatcher.matches(searchRoot.relativize(file));
                            if (!matches) return FileVisitResult.CONTINUE;
                        }
                        if (ext.isEmpty() && !isTextExtension(fileName)) return FileVisitResult.CONTINUE;

                        searchInFile(file, searchRoot, searchPattern, contextLines, maxResults, results);
                    } catch (Exception e) {
                        log.debug("跳过文件: {} - {}", file, e.getMessage());
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
        return results;
    }

    private void searchInFile(Path file, Path root, Pattern pattern, int contextLines,
                              int maxResults, List<SearchResult> results) {
        String relativePath = root.relativize(file).toString().replace('\\', '/');
        Charset charset = detectSimpleCharset(file);

        int matchCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            List<String> allLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }

            for (int i = 0; i < allLines.size(); i++) {
                if (results.size() >= maxResults) return;
                if (matchCount >= MAX_MATCHES_PER_FILE) break;

                String lineContent = allLines.get(i);
                if (pattern.matcher(lineContent).find()) {
                    matchCount++;

                    List<String> contextBefore = new ArrayList<>();
                    List<String> contextAfter = new ArrayList<>();
                    for (int j = Math.max(0, i - contextLines); j < i; j++) {
                        contextBefore.add(allLines.get(j));
                    }
                    for (int j = i + 1; j <= Math.min(allLines.size() - 1, i + contextLines); j++) {
                        contextAfter.add(allLines.get(j));
                    }

                    results.add(new SearchResult(relativePath, i + 1, lineContent, contextBefore, contextAfter));
                }
            }
        } catch (Exception e) {
            log.debug("搜索文件内容失败: {} - {}", file, e.getMessage());
        }
    }

    /**
     * 检测文件编码，自动识别 UTF-8/GB18030（兼容 GBK），解决中文乱码
     */
    private Charset detectSimpleCharset(Path file) {
        return FileEncodingDetector.detectCharset(file);
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private boolean isTextExtension(String fileName) {
        return TEXT_EXTENSIONS.contains(getExtension(fileName).toLowerCase());
    }

    private String escapeLine(String line) {
        return line.replace("\t", "  ").replace("\r", "").replace("\n", "");
    }

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) return path.normalize();
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    private record SearchResult(String filePath, int lineNum, String lineContent,
                                List<String> contextBefore, List<String> contextAfter) {}
}
