package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.impl.readfile.FileNotFoundHandler;
import com.example.agentdeepseek.tool.impl.readfile.FileReadEngine;
import com.example.agentdeepseek.tool.impl.readfile.FileOutputFormatter;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文件探索工具 — 合并 read_file / glob_files / grep_search / read_project_tree
 * 通过 action 参数区分操作，覆盖文件读取、文件名搜索、内容搜索、目录树展示四大能力。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, isPathSensitive = true, description = "文件探索（读/搜/树）")
public class FileExplorerTool implements Tool {

    // ============ 通用常量 ============
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", ".mvn", "dist",
            "build", ".idea", ".vscode", ".settings", ".classpath",
            ".project", "__pycache__", "venv", ".env", "coverage",
            ".gitlab", ".svn", "nbproject"
    );
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
            ".woff", ".woff2", ".ttf", ".eot",
            ".jar", ".zip", ".tar", ".gz", ".7z", ".rar",
            ".exe", ".dll", ".so", ".dylib",
            ".class", ".o", ".a", ".lib"
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".groovy", ".scala",
            ".ts", ".tsx", ".js", ".jsx", ".vue", ".css", ".scss", ".less", ".html",
            ".json", ".xml", ".yml", ".yaml",
            ".py", ".rb", ".go", ".rs", ".php", ".pl", ".pm", ".sh", ".bat", ".cmd",
            ".md", ".txt", ".properties", ".cfg", ".conf", ".ini", ".toml",
            ".sql", ".gradle", ".mvn", ".dockerfile",
            ".c", ".cpp", ".h", ".hpp", ".cs", ".swift", ".m", ".mm"
    );

    private static final int GLOB_MAX_RESULTS = 200;
    private static final int GREP_DEFAULT_MAX_RESULTS = 50;
    private static final long GREP_MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int GREP_MAX_MATCHES_PER_FILE = 100;
    private static final int TREE_DEFAULT_DEPTH = 3;
    private static final int TREE_MAX_DEPTH = 5;

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== Tool 接口 ====================

    @Override
    public String getName() {
        return "file_explorer";
    }

    @Override
    public String getDescription() {
        return "【适用场景】文件探索一站式工具，通过 action 参数选择操作模式。\n"
                + "⚠️ 调用示例：{\"action\":\"read\",\"file_path\":\"src/main/App.java\"} | "
                + "{\"action\":\"glob\",\"pattern\":\"**/*.java\"} | "
                + "{\"action\":\"tree\"}\n"
                + "【action 说明】\n"
                + "  read — 读取文件内容或列出目录，支持行范围/分页\n"
                + "  glob — 按文件名模式搜索文件（如 **/*.java）\n"
                + "  grep — 按文件内容搜索文本或正则表达式\n"
                + "  tree — 查看项目目录树结构及文件类型统计\n"
                + "【典型工作流】\n"
                + "  1) 用 tree 了解项目结构\n"
                + "  2) 用 glob/grep 找到目标文件\n"
                + "  3) 用 read 读取具体内容\n"
                + "【注意事项】read 必填 file_path；glob/grep 必填 pattern；action 必填不可省略。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        // === action（公共必填） ===
        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】操作类型。read=读取文件/目录；glob=按文件名搜索；grep=按内容搜索；tree=目录树。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("read");
        actionEnum.add("glob");
        actionEnum.add("grep");
        actionEnum.add("tree");

        // === read 参数 ===
        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "【read 时必填】文件或目录路径。支持相对路径如 'src/main/App.java'。传入目录时列出目录内容。");

        ObjectNode startLine = props.putObject("start_line");
        startLine.put("type", "integer");
        startLine.put("description", "【read 可选】起始行号（从1开始），与 end_line 配合。不指定则从第1行开始。");

        ObjectNode endLine = props.putObject("end_line");
        endLine.put("type", "integer");
        endLine.put("description", "【read 可选】结束行号（含该行），与 start_line 配合。与 page/page_size 互斥，同时指定时行范围优先。");

        ObjectNode page = props.putObject("page");
        page.put("type", "integer");
        page.put("description", "【read 可选】页码（从1开始），与 page_size 配合分页读取。默认读取前2000行。");

        ObjectNode pageSize = props.putObject("page_size");
        pageSize.put("type", "integer");
        pageSize.put("description", "【read 可选】每页行数，默认50。");

        // === glob 参数 ===
        ObjectNode globPattern = props.putObject("pattern");
        globPattern.put("type", "string");
        globPattern.put("description", "【glob/grep 时必填】glob 模式或搜索文本。glob 示例：'**/*.java'、'**/pom.xml'。grep 示例：'userService'、'class\\\\s+\\\\w+'。");

        ObjectNode globRoot = props.putObject("root");
        globRoot.put("type", "string");
        globRoot.put("description", "【glob 可选】限制搜索范围到指定子目录。示例：'src/main'。留空=全项目搜索。");

        ObjectNode excludePattern = props.putObject("exclude_pattern");
        excludePattern.put("type", "string");
        excludePattern.put("description", "【glob 可选】排除文件/目录。系统已自动排除 .git/node_modules/target 等。示例：'**/test/**'。");

        // === grep 参数 ===
        ObjectNode include = props.putObject("include");
        include.put("type", "string");
        include.put("description", "【grep 可选】限定文件类型。示例：'*.java'（仅搜 Java）、'*.{ts,tsx}'（仅搜 TS）。");

        ObjectNode searchPath = props.putObject("path");
        searchPath.put("type", "string");
        searchPath.put("description", "【grep/tree 可选】限定目录范围。grep 时强烈建议指定！示例：'src/main/java'。tree 时指定子目录。");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "【grep 可选】最大返回匹配行数，默认50。超过限制时结果截断。");

        ObjectNode ignoreCase = props.putObject("ignore_case");
        ignoreCase.put("type", "boolean");
        ignoreCase.put("description", "【grep 可选】是否忽略大小写，默认 false。Java 代码建议 false，配置文件可设 true。");

        ObjectNode regex = props.putObject("regex");
        regex.put("type", "boolean");
        regex.put("description", "【grep 可选】是否正则模式，默认 true。搜普通变量名/方法名建议设 false 做精确文本匹配。");

        ObjectNode contextLines = props.putObject("context_lines");
        contextLines.put("type", "integer");
        contextLines.put("description", "【grep 可选】匹配行上下各显示 N 行，默认0。建议 2~3 理解代码语境，5+ 查看完整函数。");

        // === tree 参数 ===
        ObjectNode depth = props.putObject("depth");
        depth.put("type", "integer");
        depth.put("description", "【tree 可选】展开深度，默认3，最大5。初览用2-3层，深入分析用4-5层。");

        ObjectNode showFileCount = props.putObject("show_file_count");
        showFileCount.put("type", "boolean");
        showFileCount.put("description", "【tree 可选】是否显示文件类型分布统计图，默认 true。");

        ArrayNode required = root.putArray("required");
        required.add("action");
        return root;
    }

    // ==================== 执行入口 ====================

    @Override
    public String execute(JsonNode args) {
        String action = args.has("action") ? args.get("action").asText() : "";
        if (action.isEmpty()) {
            return "【参数缺失】'action' 参数缺失或为空。file_explorer 的 action 必须设为 'read' / 'glob' / 'grep' / 'tree' 之一。\n"
                    + "正确示例：{ \"action\": \"read\", \"file_path\": \"src/main/App.java\" }\n"
                    + "错误示例：{ \"file_path\": \"src/main/App.java\" } ← 缺少 action！";
        }
        return switch (action) {
            case "read" -> doRead(args);
            case "glob" -> doGlob(args);
            case "grep" -> doGrep(args);
            case "tree"  -> doTree(args);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 read / glob / grep / tree 四种取值，请改为其中之一。";
        };
    }

    // ==================== action=read ====================

    private String doRead(JsonNode args) {
        String filePathStr = args.path("file_path").asText();
        if (filePathStr.isEmpty()) {
            return "【缺少参数】action=read 需要 file_path 参数。\n示例：file_path='src/main/App.java' 或 file_path='pom.xml'";
        }

        Path filePath = resolvePath(filePathStr);
        if (!Files.exists(filePath)) {
            Path searchDir = filePath.getParent();
            if (searchDir == null) searchDir = Paths.get(ProjectRootContext.get());
            String suggestion = FileNotFoundHandler.suggest(filePath, searchDir);
            if (suggestion != null) return suggestion;
            return "【文件不存在】" + filePath.toAbsolutePath() + "\n"
                    + "建议：先用 file_explorer action=glob 搜索文件名，或用 action=tree 了解项目结构";
        }
        if (!Files.isReadable(filePath)) {
            return "错误：路径不可读 - " + filePath.toAbsolutePath();
        }

        try {
            if (Files.isDirectory(filePath)) {
                var dirResult = FileReadEngine.readDirectory(filePath);
                return FileOutputFormatter.format(dirResult);
            }

            long fileSize = Files.size(filePath);
            if (fileSize > 100 * 1024 * 1024) {
                return "错误：文件太大（" + fileSize + " 字节），超过最大限制 100MB";
            }
            if (FileReadEngine.isBinaryFile(filePath)) {
                return "错误：文件包含大量二进制数据，不是文本文件 - " + filePath.toAbsolutePath();
            }

            Charset charset = FileEncodingDetector.detectCharset(filePath);

            boolean hasStartLine = args.has("start_line") && !args.path("start_line").isNull();
            boolean hasEndLine = args.has("end_line") && !args.path("end_line").isNull();
            boolean hasPage = args.has("page") && !args.path("page").isNull();

            if ((hasStartLine || hasEndLine) && hasPage) {
                log.warn("同时指定行范围和分页参数，优先使用行范围模式");
            }

            Integer start = hasStartLine ? safeInteger(args, "start_line") : null;
            Integer end = hasEndLine ? safeInteger(args, "end_line") : null;
            Integer pg = hasPage ? safeInteger(args, "page") : null;
            Integer ps = (args.has("page_size") && !args.path("page_size").isNull())
                    ? safeInteger(args, "page_size") : null;

            var result = FileReadEngine.read(filePath, charset, start, end, pg, ps);

            if (result.getLines().isEmpty() && result.getTotalLines() == 0) {
                return "提示：文件为空";
            }
            if (result.getLines().isEmpty() && start != null && start > 1) {
                return "提示：起始行 " + start + " 超出文件范围，文件共 " + result.getTotalLines() + " 行";
            }

            return FileOutputFormatter.format(result);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            return "错误：读取文件失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("读取文件异常: {}", filePath, e);
            return "错误：读取文件异常 - " + e.getMessage();
        }
    }

    // ==================== action=glob ====================

    private String doGlob(JsonNode args) {
        String patternStr = args.path("pattern").asText();
        String rootStr = args.path("root").asText();
        String excludeStr = args.path("exclude_pattern").asText();

        if (patternStr.isEmpty()) {
            return "【缺少参数】action=glob 需要 pattern 参数。\n示例：pattern='**/*.java' 搜索所有 Java 文件\n"
                    + "如果不知道搜什么，先用 action=tree 查看项目结构";
        }

        Path projectRoot = Paths.get(ProjectRootContext.get()).normalize().toAbsolutePath();
        Path rootPath;
        if (!rootStr.isEmpty()) {
            rootPath = resolvePath(rootStr);
            if (!rootPath.startsWith(projectRoot)) {
                return "【路径越界】搜索路径超出项目目录范围。\n请求路径：" + rootPath.toAbsolutePath()
                        + "\n项目根目录：" + projectRoot + "\n建议：使用相对于项目根目录的路径，如 'src/main'";
            }
        } else {
            rootPath = projectRoot;
        }

        if (!Files.exists(rootPath)) {
            return "【目录不存在】" + rootPath.toAbsolutePath() + "\n"
                    + "建议：先用 action=tree 查看项目有哪些目录，确认路径拼写正确";
        }
        if (!Files.isDirectory(rootPath)) {
            return "【不是目录】" + rootPath.toAbsolutePath() + " 是一个文件，不是目录。\n"
                    + "建议：root 参数应指向目录而非文件，如 'src/main/java'";
        }

        String globPattern = patternStr.startsWith("glob:") ? patternStr : "glob:" + patternStr;
        final PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher(globPattern);
        } catch (Exception e) {
            return "【glob 语法错误】\n输入模式：" + patternStr + "\n错误原因：" + e.getMessage()
                    + "\n修正建议：检查通配符，** 匹配任意层目录，* 匹配文件名。"
                    + "\n常见模式：**/*.java / **/*.{js,ts} / **/pom.xml";
        }

        final PathMatcher excludeMatcher;
        if (!excludeStr.isEmpty()) {
            try {
                String exGlob = excludeStr.startsWith("glob:") ? excludeStr : "glob:" + excludeStr;
                excludeMatcher = FileSystems.getDefault().getPathMatcher(exGlob);
            } catch (Exception e) {
                return "【排除模式语法错误】\n输入：" + excludeStr + "\n错误原因：" + e.getMessage()
                        + "\n示例：'**/test/**' 排除测试目录，'**/*.min.js' 排除压缩文件";
            }
        } else {
            excludeMatcher = null;
        }

        List<FileEntry> results;
        try {
            results = walkGlob(rootPath, matcher, excludeMatcher);
        } catch (IOException e) {
            return "【搜索异常】读取文件系统时出错：" + e.getMessage()
                    + "\n建议：检查磁盘空间、文件权限，或缩小搜索范围重试";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("搜索模式：").append(patternStr).append("\n");
        if (!rootStr.isEmpty()) sb.append("搜索目录：").append(rootPath.toAbsolutePath()).append("\n");
        if (!excludeStr.isEmpty()) sb.append("排除模式：").append(excludeStr).append("\n");
        sb.append("匹配结果：共 ").append(results.size()).append(" 个文件");
        if (results.size() >= GLOB_MAX_RESULTS) {
            sb.append("（仅显示前 ").append(GLOB_MAX_RESULTS).append(" 个，请细化搜索模式）");
        }
        sb.append("\n────────────────────────────────────────\n");

        for (FileEntry r : results) {
            sb.append(String.format("  %-6s %s\n", formatSize(r.size), r.path));
        }
        if (results.isEmpty()) {
            sb.append("  （无匹配结果）\n\n提示：glob 模式示例\n");
            sb.append("  **/*.java        → 所有 Java 文件\n");
            sb.append("  src/**/*.ts       → src 下所有 TypeScript 文件\n");
            sb.append("  **/pom.xml        → 所有 pom.xml 文件\n");
            sb.append("  **/*.{js,ts}      → 所有 JS 和 TS 文件\n");
        }
        return sb.toString();
    }

    private List<FileEntry> walkGlob(Path rootPath, PathMatcher matcher, PathMatcher excludeMatcher) throws IOException {
        List<FileEntry> results = new ArrayList<>();
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (EXCLUDED_DIRS.contains(dirName)) return FileVisitResult.SKIP_SUBTREE;
                if (excludeMatcher != null) {
                    Path relDir = rootPath.relativize(dir);
                    if (excludeMatcher.matches(relDir)) return FileVisitResult.SKIP_SUBTREE;
                    if (excludeMatcher.matches(relDir.resolve("_"))) return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (results.size() >= GLOB_MAX_RESULTS) return FileVisitResult.TERMINATE;
                Path relPath = rootPath.relativize(file);
                if (matcher.matches(relPath)) {
                    if (excludeMatcher == null || !excludeMatcher.matches(relPath)) {
                        results.add(new FileEntry(relPath.toString().replace('\\', '/'), attrs.size()));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("访问文件失败，已跳过: {} - {}", file,
                        exc.getMessage() != null ? exc.getMessage() : exc.getClass().getSimpleName());
                return FileVisitResult.CONTINUE;
            }
        });
        results.sort(Comparator.comparing(r -> r.path));
        return results;
    }

    // ==================== action=grep ====================

    private String doGrep(JsonNode args) {
        String patternStr = args.path("pattern").asText();
        String includeGlob = args.path("include").asText();
        String searchPathStr = args.path("path").asText();
        int maxResults = safeInt(args, "max_results", GREP_DEFAULT_MAX_RESULTS, 1, 500);
        boolean ignoreCaseFlag = args.has("ignore_case") && args.get("ignore_case").asBoolean();
        boolean isRegex = !args.has("regex") || args.get("regex").asBoolean(); // 默认 true
        int ctxLines = safeInt(args, "context_lines", 0, 0, 20);

        if (patternStr.isEmpty()) {
            return "【缺少参数】action=grep 需要 pattern 参数。\n"
                    + "示例：pattern='userService'（搜索变量）、pattern='class\\\\s+\\\\w+'（搜索类定义，需 regex=true）";
        }

        Pattern searchPattern;
        try {
            int flags = ignoreCaseFlag ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            searchPattern = isRegex ? Pattern.compile(patternStr, flags)
                                    : Pattern.compile(Pattern.quote(patternStr), flags);
        } catch (PatternSyntaxException e) {
            return "【正则表达式语法错误】\n输入：" + patternStr + "\n错误原因：" + e.getMessage()
                    + "\n方案A（推荐）：设 regex=false 进行纯文本搜索\n"
                    + "方案B：修正正则语法，注意特殊字符需要转义";
        }

        Path searchRoot;
        if (!searchPathStr.isEmpty()) {
            searchRoot = resolvePath(searchPathStr);
        } else {
            searchRoot = Paths.get(ProjectRootContext.get());
        }
        if (!Files.exists(searchRoot)) {
            return "【目录不存在】" + searchRoot.toAbsolutePath() + "\n"
                    + "建议：先用 action=tree 查看项目有哪些目录，确认 path 参数拼写正确";
        }
        if (!Files.isDirectory(searchRoot)) {
            return "【不是目录】" + searchRoot.toAbsolutePath() + " 是一个文件。\n"
                    + "建议：path 参数应指向目录而非文件，如 'src/main/java'";
        }

        final PathMatcher includeMatcher;
        if (!includeGlob.isEmpty()) {
            try {
                String g = includeGlob.startsWith("glob:") ? includeGlob : "glob:" + includeGlob;
                includeMatcher = FileSystems.getDefault().getPathMatcher(g);
            } catch (Exception e) {
                return "【include 语法错误】\n输入：" + includeGlob + "\n错误原因：" + e.getMessage()
                        + "\ninclude 使用 glob 语法：'*.java' 匹配所有 Java 文件";
            }
        } else {
            includeMatcher = null;
        }

        List<GrepHit> hits = searchGrep(searchRoot, searchPattern, includeMatcher, maxResults, ctxLines);

        StringBuilder sb = new StringBuilder();
        sb.append("搜索模式：").append(patternStr);
        if (!isRegex) sb.append("（纯文本）");
        if (ignoreCaseFlag) sb.append("（忽略大小写）");
        sb.append("\n");
        if (!includeGlob.isEmpty()) sb.append("文件过滤：").append(includeGlob).append("\n");
        sb.append("搜索目录：").append(searchRoot.toAbsolutePath()).append("\n");
        sb.append("匹配结果：共 ").append(hits.size()).append(" 个");
        if (hits.size() >= maxResults) {
            sb.append("（仅显示前 ").append(maxResults).append(" 个，请细化搜索条件）");
        }
        sb.append("\n────────────────────────────────────────\n");

        if (hits.isEmpty()) {
            sb.append("  （无匹配结果）\n\n【搜索建议】\n");
            sb.append("  1. 检查关键词拼写是否正确\n");
            sb.append("  2. 尝试 ignore_case=true 忽略大小写\n");
            sb.append("  3. 去掉 path 或 include 限制扩大搜索范围\n");
            sb.append("  4. 尝试更短或更通用的关键词\n");
            sb.append("  5. 如果是正则搜索，尝试 regex=false 做纯文本搜索\n");
        } else {
            String currentFile = null;
            int fileMatchCount = 0;
            for (GrepHit r : hits) {
                if (!r.filePath.equals(currentFile)) {
                    if (currentFile != null) sb.append("    ... ").append(fileMatchCount).append(" 处匹配\n");
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
                sb.append("    ... ").append(fileMatchCount).append(" 处匹配\n");
            }
        }
        return sb.toString();
    }

    private List<GrepHit> searchGrep(Path searchRoot, Pattern pattern, PathMatcher includeMatcher,
                                     int maxResults, int ctxLines) {
        List<GrepHit> results = new ArrayList<>();
        try {
            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dn = dir.getFileName().toString();
                    if (EXCLUDED_DIRS.contains(dn) || (dn.startsWith(".") && !dn.equals(".")))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= maxResults) return FileVisitResult.TERMINATE;
                    try {
                        if (attrs.size() > GREP_MAX_FILE_SIZE || attrs.size() == 0) return FileVisitResult.CONTINUE;
                        String ext = getExtension(file.getFileName().toString()).toLowerCase();
                        if (BINARY_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;
                        if (includeMatcher != null) {
                            boolean m = includeMatcher.matches(file.getFileName())
                                    || includeMatcher.matches(searchRoot.relativize(file));
                            if (!m) return FileVisitResult.CONTINUE;
                        }
                        // 只跳过有已知扩展名但不在文本扩展名列表中的文件（如 .jar/.png），
                        // 无扩展名文件（Dockerfile/Makefile/.gitignore 等）保留，可能是文本文件
                        if (!ext.isEmpty() && !TEXT_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;
                        searchInFile(file, searchRoot, pattern, ctxLines, maxResults, results);
                    } catch (Exception e) { log.debug("跳过文件: {} - {}", file, e.getMessage()); }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { log.error("搜索文件失败", e); }
        return results;
    }

    private void searchInFile(Path file, Path root, Pattern pattern, int ctxLines,
                              int maxResults, List<GrepHit> results) {
        String relPath = root.relativize(file).toString().replace('\\', '/');
        Charset charset = FileEncodingDetector.detectCharset(file);
        int matchCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            List<String> allLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) allLines.add(line);
            for (int i = 0; i < allLines.size(); i++) {
                if (results.size() >= maxResults) return;
                if (matchCount >= GREP_MAX_MATCHES_PER_FILE) break;
                if (pattern.matcher(allLines.get(i)).find()) {
                    matchCount++;
                    List<String> before = new ArrayList<>(), after = new ArrayList<>();
                    for (int j = Math.max(0, i - ctxLines); j < i; j++) before.add(allLines.get(j));
                    for (int j = i + 1; j <= Math.min(allLines.size() - 1, i + ctxLines); j++) after.add(allLines.get(j));
                    results.add(new GrepHit(relPath, i + 1, allLines.get(i), before, after));
                }
            }
        } catch (Exception e) { log.debug("搜索文件内容失败: {} - {}", file, e.getMessage()); }
    }

    // ==================== action=tree ====================

    private String doTree(JsonNode args) {
        String pathStr = args.path("path").asText();
        int depth = safeInt(args, "depth", TREE_DEFAULT_DEPTH, 1, TREE_MAX_DEPTH);
        boolean showStats = !args.has("show_file_count") || args.get("show_file_count").asBoolean();

        Path rootPath;
        if (!pathStr.isEmpty()) {
            rootPath = resolvePath(pathStr);
        } else {
            rootPath = Paths.get(ProjectRootContext.get());
        }

        if (!Files.exists(rootPath)) {
            return "【路径不存在】目录 " + rootPath.toAbsolutePath() + " 不存在。"
                    + "建议：1) 检查路径拼写 2) 不传 path 查看根目录确认目录名 3) 确认目录是否已被删除或重命名";
        }
        if (!Files.isDirectory(rootPath)) {
            return "【类型错误】路径 " + rootPath.toAbsolutePath() + " 是文件而非目录，无法展开。"
                    + "如需查看文件内容，请使用 action=read。";
        }

        TreeNode root = buildTree(rootPath, depth);
        if (root.children == null || root.children.isEmpty()) return "（空目录）";

        StringBuilder sb = new StringBuilder();
        sb.append("项目目录：").append(rootPath.toAbsolutePath()).append("\n");
        sb.append("────────────────────────────────────────\n");

        int overviewDepth = Math.min(2, depth);
        sb.append("【概览树】\n");
        renderTreeLevels(root.children, 0, overviewDepth - 1, "", sb);
        if (depth > 2) sb.append("  ...（完整 ").append(depth).append(" 层目录树见下方）\n");

        sb.append("\n【完整目录树（共 ").append(depth).append(" 层）】\n");
        if (depth <= 2) {
            renderTreeLevels(root.children, 0, depth - 1, "", sb);
        } else {
            sb.append("（第1-2层见上方概览树，以下为第3层及更深层展开）\n\n");
            renderDeepSubtrees(root, 0, new ArrayList<>(), depth, sb);
        }

        if (showStats) {
            AtomicInteger dirCount = new AtomicInteger(1), fileCount = new AtomicInteger(0);  // dirCount 含根节点
            Map<String, Integer> extCount = new TreeMap<>();
            collectStats(root, extCount, dirCount, fileCount);
            sb.append("\n【统计信息】\n");
            sb.append("  目录数：").append(dirCount.get()).append("\n");
            sb.append("  文件数：").append(fileCount.get()).append("\n");
            sb.append("  总条目：").append(dirCount.get() + fileCount.get()).append("\n");
            if (!extCount.isEmpty()) {
                sb.append("\n【文件类型分布】\n");
                int maxCount = extCount.values().stream().max(Integer::compare).orElse(1);
                extCount.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .forEach(e -> {
                            int barLen = Math.max(1, e.getValue() * 20 / maxCount);
                            sb.append(String.format("  %-12s %4d  %s%n", e.getKey(), e.getValue(), "█".repeat(barLen)));
                        });
            }
        }
        return sb.toString();
    }

    // ==================== tree 内部实现 ====================

    private static class TreeNode {
        final String name;
        final boolean isDirectory;
        List<TreeNode> children;
        TreeNode(String name, boolean isDirectory) { this.name = name; this.isDirectory = isDirectory; }
    }

    private record DirEntry(Path path, boolean isDirectory) {}

    private TreeNode buildTree(Path dir, int remainingDepth) {
        TreeNode node = new TreeNode(dir.getFileName().toString(), true);
        List<DirEntry> entries = listDir(dir);
        if (entries.isEmpty()) return node;
        List<TreeNode> children = new ArrayList<>();
        for (DirEntry entry : entries) {
            TreeNode child = new TreeNode(entry.path.getFileName().toString(), entry.isDirectory);
            if (entry.isDirectory && remainingDepth > 0) {
                TreeNode subNode = buildTree(entry.path, remainingDepth - 1);
                if (subNode.children != null && !subNode.children.isEmpty()) child.children = subNode.children;
            }
            children.add(child);
        }
        node.children = children;
        return node;
    }

    private List<DirEntry> listDir(Path dir) {
        List<DirEntry> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (EXCLUDED_DIRS.contains(name) || name.startsWith(".")) continue;
                if (Files.isSymbolicLink(entry)) { log.debug("跳过符号链接: {}", entry); continue; }
                result.add(new DirEntry(entry, Files.isDirectory(entry)));
            }
        } catch (IOException e) { log.warn("读取目录失败: {}", dir, e); }
        result.sort((a, b) -> {
            if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
            return a.path.getFileName().toString().compareToIgnoreCase(b.path.getFileName().toString());
        });
        return result;
    }

    private void renderTreeLevels(List<TreeNode> siblings, int curLevel, int maxLevel, String prefix, StringBuilder sb) {
        for (int i = 0; i < siblings.size(); i++) {
            TreeNode node = siblings.get(i);
            boolean isLast = i == siblings.size() - 1;
            String connector = isLast ? "└── " : "├── ";
            sb.append(prefix).append(connector).append(node.name);
            if (node.isDirectory) {
                sb.append("/\n");
                if (curLevel < maxLevel && node.children != null) {
                    renderTreeLevels(node.children, curLevel + 1, maxLevel, prefix + (isLast ? "    " : "│   "), sb);
                }
            } else {
                sb.append("\n");
            }
        }
    }

    private void renderDeepSubtrees(TreeNode node, int curDepth, List<String> ancestors, int maxDepth, StringBuilder sb) {
        if (node.children == null) return;
        for (TreeNode child : node.children) {
            if (!child.isDirectory) continue;
            List<String> childAncestors = new ArrayList<>(ancestors);
            childAncestors.add(child.name);
            if (curDepth == 1) {
                sb.append(String.join("/", childAncestors)).append("/\n");
                if (child.children != null) renderChildrenRecursive(child.children, "", sb);
                sb.append("\n");
            } else {
                renderDeepSubtrees(child, curDepth + 1, childAncestors, maxDepth, sb);
            }
        }
    }

    private void renderChildrenRecursive(List<TreeNode> siblings, String prefix, StringBuilder sb) {
        for (int i = 0; i < siblings.size(); i++) {
            TreeNode node = siblings.get(i);
            boolean isLast = i == siblings.size() - 1;
            sb.append(prefix).append(isLast ? "└── " : "├── ").append(node.name)
                    .append(node.isDirectory ? "/\n" : "\n");
            if (node.isDirectory && node.children != null) {
                renderChildrenRecursive(node.children, prefix + (isLast ? "    " : "│   "), sb);
            }
        }
    }

    private void collectStats(TreeNode node, Map<String, Integer> extCount, AtomicInteger dirCount, AtomicInteger fileCount) {
        if (node.children == null) { if (node.isDirectory) dirCount.incrementAndGet(); return; }
        for (TreeNode child : node.children) {
            if (child.isDirectory) { dirCount.incrementAndGet(); collectStats(child, extCount, dirCount, fileCount); }
            else { fileCount.incrementAndGet(); String ext = getExtension(child.name);
                   extCount.merge(ext.isEmpty() ? "(无扩展名)" : ext, 1, Integer::sum); }
        }
    }

    // ==================== 工具方法 ====================

    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) return path.normalize();
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return "";
        return fileName.substring(dot).toLowerCase();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        return String.format("%.1fM", bytes / (1024.0 * 1024));
    }

    private String escapeLine(String line) {
        return line.replace("\t", "  ").replace("\r", "").replace("\n", "");
    }

    private int safeInt(JsonNode args, String key, int defaultVal, int min, int max) {
        if (!args.has(key) || args.get(key).isNull()) return defaultVal;
        try {
            int v = args.get(key).asInt();
            if (v < min) return defaultVal;
            if (v > max) return max;
            return v;
        } catch (Exception e) {
            log.warn("file_explorer {}: 解析失败，使用默认值 {}", key, defaultVal);
            return defaultVal;
        }
    }

    /** safeInt 的可空版本，解析失败时返回 null 而非默认值 */
    private Integer safeInteger(JsonNode args, String key) {
        if (!args.has(key) || args.get(key).isNull()) return null;
        try {
            return args.get(key).asInt();
        } catch (Exception e) {
            log.warn("file_explorer {}: 解析失败，返回 null。原始值: {}", key, args.get(key));
            return null;
        }
    }

    // ==================== 内部数据类 ====================

    private record FileEntry(String path, long size) {}
    private record GrepHit(String filePath, int lineNum, String lineContent,
                           List<String> contextBefore, List<String> contextAfter) {}
}
