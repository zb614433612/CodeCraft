package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 代码搜索工具
 * 在代码库中搜索关键字或正则表达式，支持文件类型过滤、上下文显示等
 */
@Slf4j
@Component
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
        return "在代码库中搜索关键字或正则表达式，支持文件类型过滤（include）、上下文显示（context_lines）、"
                + "大小写敏感控制（ignore_case）、纯文本搜索（regex=false）。"
                + "适用于搜索函数定义、变量引用、特定 API 调用等代码内容。"
                + "区别于 glob_files（按文件名搜索），本工具用于搜索文件内容";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode pattern = objectMapper.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "搜索关键字或正则表达式");
        properties.set("pattern", pattern);

        ObjectNode include = objectMapper.createObjectNode();
        include.put("type", "string");
        include.put("description", "文件类型过滤（可选），glob 模式，如 *.java 只搜索Java文件，*.{ts,tsx} 搜索TypeScript文件");
        properties.set("include", include);

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "搜索目录（可选），默认为当前项目根目录");
        properties.set("path", path);

        ObjectNode maxResults = objectMapper.createObjectNode();
        maxResults.put("type", "integer");
        maxResults.put("description", "最大结果数，默认50");
        properties.set("max_results", maxResults);

        ObjectNode ignoreCase = objectMapper.createObjectNode();
        ignoreCase.put("type", "boolean");
        ignoreCase.put("description", "是否忽略大小写，默认 false");
        properties.set("ignore_case", ignoreCase);

        ObjectNode regex = objectMapper.createObjectNode();
        regex.put("type", "boolean");
        regex.put("description", "是否将pattern视为正则表达式，默认 true。设为 false 时进行纯文本搜索");
        properties.set("regex", regex);

        ObjectNode contextLines = objectMapper.createObjectNode();
        contextLines.put("type", "integer");
        contextLines.put("description", "匹配行上下各显示的行数，默认0");
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
            return "错误：缺少必要参数 pattern";
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
            return "错误：正则表达式语法错误 - " + e.getMessage()
                    + "\n提示：如果要搜索纯文本，请设置 regex=false";
        }

        // 解析搜索根目录
        Path searchRoot;
        if (!searchPathStr.isEmpty()) {
            searchRoot = resolvePath(searchPathStr);
        } else {
            searchRoot = Paths.get(ProjectRootContext.get());
        }

        if (!Files.exists(searchRoot)) {
            return "错误：搜索目录不存在 - " + searchRoot.toAbsolutePath();
        }
        if (!Files.isDirectory(searchRoot)) {
            return "错误：搜索路径不是目录 - " + searchRoot.toAbsolutePath();
        }

        // 编译文件包含匹配器
        final PathMatcher includeMatcher;
        if (!includeGlob.isEmpty()) {
            try {
                String globPattern = includeGlob.startsWith("glob:") ? includeGlob : "glob:" + includeGlob;
                includeMatcher = FileSystems.getDefault().getPathMatcher(globPattern);
            } catch (Exception e) {
                return "错误：文件类型过滤语法错误 - " + e.getMessage();
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
            // èªå¨éçº§ï¼æ­£åæ¨¡å¼æ å¹éæ¶ï¼ç¨çº¯ææ¬åæä¸æ¬¡
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
                        sb.append("ï¼æ­£åæ¨¡å¼æªå¹éï¼èªå¨éçº§ä¸ºçº¯ææ¬æç´¢ï¼æ¾å° ").append(textResults.size()).append(" ä¸ªç»æï¼\n");
                        allResults = textResults;
                        // éæ°è¾åºç»æ
                        currentFile = null;
                        fileMatchCount = 0;
                        for (SearchResult r : allResults) {
                            if (!r.filePath.equals(currentFile)) {
                                if (currentFile != null) {
                                    sb.append("    ... ").append(fileMatchCount).append(" å¤å¹é\n");
                                }
                                sb.append(r.filePath).append("\n");
                                currentFile = r.filePath;
                                fileMatchCount = 0;
                            }
                            fileMatchCount++;
                            sb.append("  ").append(String.format("%5d", r.lineNum)).append(" > ").append(escapeLine(r.lineContent)).append("\n");
                        }
                        if (currentFile != null && fileMatchCount > 0) {
                            sb.append("    ... ").append(fileMatchCount).append(" å¤å¹é\n");
                        }
                    }
                }
            }

            // ä»æ ç»æï¼åæ¬éçº§åä»æ ï¼ï¼è¾åºæç¤º
            if (allResults.isEmpty()) {
                sb.append("  ï¼æ å¹éç»æï¼\n");
                sb.append("\næç´¢æç¤ºï¼\n");
                sb.append("  - ä½¿ç¨ include è¿æ»¤æä»¶ç±»åï¼å¦ *.java\n");
                sb.append("  - ä½¿ç¨ path æå®æç´¢ç®å½\n");
                sb.append("  - ä½¿ç¨ ignore_case=true å¿½ç¥å¤§å°å\n");
                sb.append("  - ä½¿ç¨ regex=false è¿è¡çº¯ææ¬æç´¢\n");
                sb.append("  - ä½¿ç¨ context_lines=N æ¾ç¤ºä¸ä¸æè¡\n");
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

                        if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                            return FileVisitResult.CONTINUE;
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

    private Charset detectSimpleCharset(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String utf8Str = new String(bytes, StandardCharsets.UTF_8);
            byte[] reEncoded = utf8Str.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == reEncoded.length) {
                boolean valid = true;
                for (int i = 0; i < bytes.length; i++) {
                    if (bytes[i] != reEncoded[i]) { valid = false; break; }
                }
                if (valid) return StandardCharsets.UTF_8;
            }
            return Charset.forName("GBK");
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
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
