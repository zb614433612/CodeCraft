package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.tool.postedit.PostEditPipeline;
import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 编辑文件工具 — Search & Replace 精确匹配模式
 * <p>
 * 核心策略：AI 模型从文件中直接复制待修改的代码段作为 old_text，
 * 工具负责在文件中找到该文本并替换为 new_text。
 * <p>
 * 消除以下常见失败场景：
 * - 行号漂移：不依赖行号，依赖文本内容匹配
 * - 重复代码误改：严格校验唯一匹配
 * - 正则错误：不依赖正则表达式
 * - 替换漏行：old_text 作为锚点，new_text 是整个替换结果
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.WRITE, affectsData = true, isPathSensitive = true, description = "编辑文件内容")
public class EditFileTool implements Tool {

    /** 文件级并发写入锁，防止同一文件并发写入导致数据竞争 */
    private static final ConcurrentHashMap<Path, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    /** 模糊匹配时最多返回的相似结果数 */
    private static final int MAX_FUZZY_RESULTS = 5;

    /** 返回结果中 diff 预览的最大行数 */
    private static final int MAX_DIFF_LINES = 50;

    private final ObjectMapper objectMapper;
    private final PostEditPipeline postEditPipeline;

    public EditFileTool(ObjectMapper objectMapper, PostEditPipeline postEditPipeline) {
        this.objectMapper = objectMapper;
        this.postEditPipeline = postEditPipeline;
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "【适用场景】对已有文件进行局部精确修改——替换几行代码、重命名变量、删除某个方法、修复一个小 bug。一次调用只改一处匹配的代码片段。"
                + "【与write_file的区别】edit_file 是「精准局部替换」（old_text + new_text 只改匹配到的片段），write_file 是「全量创建/覆盖」（content 是文件完整内容）。改少量代码请用本工具，不要用 write_file 重写整个文件——那样容易误改其他部分。"
                + "【使用方式】1) 先用 read_file 读取目标文件的最新内容；2) 从 read_file 返回的内容中，直接复制要替换的代码段作为 old_text（不要手打）；3) 提供替换后的新代码段作为 new_text；4) 工具自动在文件中查找 old_text（唯一匹配校验），替换为 new_text，并返回 diff 预览。"
                + "【注意事项】1) old_text 建议从文件中复制以保持格式一致，但缩进/空格差异会自动适配（工具支持宽松匹配），无需手动调整；2) old_text 需在文件中唯一出现，建议复制至少 3~5 行以包含足够上下文；3) new_text 为空字符串时表示删除匹配到的内容；4) 替换后自动执行格式化和诊断，结果会附在返回值中。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "【必填】要编辑的文件路径。示例：src/main/java/com/example/Demo.java。支持项目相对路径（推荐）和绝对路径。文件必须已存在（edit_file 用于修改已有文件，新建请用 write_file）。");
        properties.set("file_path", filePath);

        ObjectNode oldText = objectMapper.createObjectNode();
        oldText.put("type", "string");
        oldText.put("description", "【必填】文件中要替换的原始文本。建议从 read_file 返回的文件内容中直接复制，至少 3~5 行以确保唯一匹配。示例：复制一段完整的方法定义（从 public void foo() { 到对应的 }）。缩进/空格差异会自动适配，无需手动调整。");
        properties.set("old_text", oldText);

        ObjectNode newText = objectMapper.createObjectNode();
        newText.put("type", "string");
        newText.put("description", "【可选，默认为空】替换后的新文本。为空字符串时表示删除 old_text 匹配到的内容。示例：修正后的方法定义、重命名后的变量名、或要插入的新代码块。注意：new_text 只替换匹配到的 old_text 片段，不影响文件其他部分。");
        properties.set("new_text", newText);

        params.set("properties", properties);
        params.putArray("required").add("file_path").add("old_text");
        return params;
    }

    @Override
    public String execute(JsonNode arguments) {

        String filePathStr = arguments.path("file_path").asText();
        String oldText = arguments.path("old_text").asText();
        String newText = arguments.path("new_text").asText();

        if (filePathStr.isEmpty()) {
            return "【参数缺失】缺少必填参数 file_path。请提供要编辑的文件路径。示例：file_path=src/main/java/com/example/Demo.java";
        }
        if (oldText.isEmpty()) {
            return "【参数缺失】缺少必填参数 old_text。请先用 read_file 读取目标文件，然后从返回内容中直接复制要替换的代码段（不要手打，精确复制含缩进和换行）。示例：old_text=\"    public void oldMethod() {\\n        doSomething();\\n    }\"";
        }

        // 解析路径
        Path filePath = resolvePath(filePathStr);

        // 验证文件
        if (!Files.exists(filePath)) {
            return "【文件不存在】文件 " + filePath.toAbsolutePath() + " 不存在。\n【修正建议】1) 确认 file_path 拼写和大小写是否正确；2) edit_file 只能修改已有文件——如需创建新文件，请使用 write_file 工具；3) 先用 list_files 或 search_files 确认文件的实际路径。";
        }
        if (!Files.isRegularFile(filePath)) {
            return "【路径类型错误】" + filePath.toAbsolutePath() + " 不是普通文件（可能是一个目录）。\n【修正建议】edit_file 只能编辑文件，不能编辑目录。请确认 file_path 指向的是一个文件而非目录。如需在目录中创建新文件，请使用 write_file。";
        }
        if (!Files.isWritable(filePath)) {
            return "【权限不足】文件不可写 - " + filePath.toAbsolutePath()
                    + "\n【修正建议】1) 检查文件是否被其他进程（编辑器、编译器）占用；2) 使用 execute_command 工具运行 chmod 修改文件权限后重试；3) 确认当前用户对该文件有写入权限。";
        }

        // 文件级锁
        Path normalizedPath = filePath.normalize();
        ReentrantLock fileLock = FILE_LOCKS.computeIfAbsent(normalizedPath, k -> new ReentrantLock());
        fileLock.lock();
        try {
            return executeReplace(filePath, oldText, newText);
        } finally {
            fileLock.unlock();
        }
    }

    // ==================== 核心 Search & Replace ====================

    /**
     * 执行搜索替换的核心逻辑
     * <p>
     * 策略：
     * 1. 精确匹配 — 逐字符完全匹配，严格校验唯一性
     * 2. 模糊匹配 — 精确失败后尝试空白归一化、行尾去空等宽松匹配
     * 3. 相似推荐 — 编辑距离 Top-5 推荐，辅助 AI 自纠正
     */
    private String executeReplace(Path filePath, String oldText, String newText) {
        try {
            // 自动检测编码读取（支持 UTF-8/GB18030 等），防止中文乱码导致搜索替换失败
            String originalContent = FileEncodingDetector.readString(filePath);

            // 统一换行符为 \n 便于匹配
            String normalizedContent = normalizeLineEndings(originalContent);
            String normalizedOld = normalizeLineEndings(oldText);
            String normalizedNew = normalizeLineEndings(newText);

            // ---------- 第一步：精确匹配 ----------
            int index = normalizedContent.indexOf(normalizedOld);

            if (index >= 0) {
                // 检查是否唯一匹配
                int secondIndex = normalizedContent.indexOf(normalizedOld, index + 1);
                if (secondIndex >= 0) {
                    return formatMultipleMatches(normalizedContent, normalizedOld);
                }
                // 恰好匹配一次 → 执行替换
                return doReplace(filePath, originalContent, normalizedContent,
                        normalizedOld, normalizedNew, index);
            }

            // ---------- 第二步：宽松匹配并执行替换（精确匹配失败）----------
            String relaxedResult = tryRelaxedReplace(filePath, originalContent, normalizedContent,
                    normalizedOld, normalizedNew);
            if (relaxedResult != null) {
                return relaxedResult;
            }

            // ---------- 第三步：模糊匹配诊断（所有匹配都失败）----------
            String suggestion = tryFuzzyMatch(normalizedContent, normalizedOld, oldText.length());
            return "【匹配失败】在文件中未找到 old_text 的精确匹配。\n"
                    + "【修正建议】1) 先用 read_file 读取文件最新内容（可能已被之前的操作修改）；2) 从 read_file 返回的内容中直接复制要替换的代码段（不要手打，不要调整缩进）；3) 确认 old_text 的缩进、空格、换行与文件中完全一致；4) 文件编码差异也可能导致匹配失败，read_file 返回的就是文件的实际编码内容。\n"
                    + suggestion;

        } catch (IOException e) {
            log.error("编辑文件失败: {}", filePath, e);
            return "【编辑失败】编辑文件时发生 I/O 错误：" + e.getMessage()
                    + "\n【修正建议】1) 检查文件是否被其他进程占用（关闭其他编辑器窗口）；2) 确认磁盘空间充足；3) 如持续失败，可先用 read_file 读取文件内容，再用 write_file 覆盖（注意：write_file 需要完整文件内容，原文件中未被 old_text 覆盖的部分也需要保留）。";
        }
    }

    // ==================== 替换执行 ====================

    /**
     * 执行替换并返回结果（含行数统计和 diff 预览）
     */
    private String doReplace(Path filePath, String originalContent, String normalizedContent,
                             String normalizedOld, String normalizedNew, int matchIndex) throws IOException {
        // 计算匹配位置的行号
        String beforeMatch = normalizedContent.substring(0, matchIndex);
        int startLine = countLines(beforeMatch) + 1;
        int endLine = startLine + countLines(normalizedOld) - 1;
        if (endLine < startLine) endLine = startLine;

        // 执行替换（在归一化的内容上进行）
        String resultContent = normalizedContent.substring(0, matchIndex)
                + normalizedNew
                + normalizedContent.substring(matchIndex + normalizedOld.length());

        // 恢复原始换行符风格写入
        String writeContent = convertLineEndings(resultContent, originalContent);
        Files.writeString(filePath, writeContent, StandardCharsets.UTF_8);

        // 编辑后处理：格式化 + 诊断（不影响替换结果，失败不阻塞）
        PostEditPipeline.PostEditResult postEditResult = postEditPipeline.execute(filePath);

        // 统计
        int oldLineCount = Math.max(1, countLines(normalizedOld));
        int newLineCount = Math.max(1, countLines(normalizedNew));
        int delta = newLineCount - oldLineCount;
        int totalLines = countLines(resultContent);

        // 生成 diff
        String diff = generateDiff(normalizedOld, normalizedNew, MAX_DIFF_LINES);

        StringBuilder sb = new StringBuilder();
        sb.append("编辑成功：").append(filePath.toAbsolutePath()).append("\n");
        sb.append("匹配位置：第 ").append(startLine).append("~").append(endLine).append(" 行\n");
        sb.append("行数变化：").append(oldLineCount).append(" 行 → ").append(newLineCount).append(" 行");
        if (delta > 0) {
            sb.append(" (+").append(delta).append(" 行)");
        } else if (delta < 0) {
            sb.append(" (").append(delta).append(" 行)");
        }
        sb.append("\n");
        sb.append("文件现总行数：").append(totalLines).append(" 行\n");

        if (!diff.isEmpty()) {
            sb.append("\n变更预览：\n").append(diff);
        }

        // 追加后处理结果（格式化/诊断信息），让 AI 模型在 tool 返回结果中自动感知
        if (postEditResult.hasInfo()) {
            sb.append("\n").append(postEditResult.toReport());
        }

        log.info("编辑文件成功: {} (第{}~{}行, {}行→{}行)", filePath.toAbsolutePath(),
                startLine, endLine, oldLineCount, newLineCount);

        return sb.toString();
    }

    // ==================== 多匹配检测 ====================

    private String formatMultipleMatches(String content, String oldText) {
        String[] oldLines = oldText.split("\n", -1);

        // 找到所有匹配位置
        List<Integer> positions = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int idx = content.indexOf(oldText, searchFrom);
            if (idx < 0) break;
            positions.add(idx);
            searchFrom = idx + 1;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【匹配冲突】old_text 在文件中匹配到 ").append(positions.size()).append(" 处，但需要唯一匹配（一次只改一处）。\n");
        sb.append("【修正建议】增加 old_text 的行数（向上或向下多复制几行上下文代码），以区分不同位置的相似代码段。一般复制 5~8 行即可确保唯一。\n\n");

        for (int i = 0; i < positions.size(); i++) {
            int pos = positions.get(i);
            int lineNum = 1;
            for (int j = 0; j < pos; j++) {
                if (content.charAt(j) == '\n') lineNum++;
            }
            int endLineNum = lineNum + oldLines.length - 1;

            sb.append("  #").append(i + 1).append("：第 ").append(lineNum);
            sb.append("~").append(endLineNum).append(" 行\n");

            // 显示前后上下文预览
            int contextStart = Math.max(0, pos - 30);
            int contextEnd = Math.min(content.length(), pos + oldText.length() + 30);
            String context = content.substring(contextStart, contextEnd)
                    .replace("\n", "\\n");
            if (contextStart > 0) context = "..." + context;
            if (contextEnd < content.length()) context = context + "...";
            sb.append("    预览：").append(context).append("\n");
        }

        return sb.toString();
    }

    // ==================== 模糊匹配（精确失败后的降级策略）====================

    /**
     * 尝试多级模糊匹配，返回辅助信息
     */
    private String tryFuzzyMatch(String content, String oldText, int rawOldLen) {
        // Level 1: 行尾空白归一化
        String trimmedContent = content.replaceAll("[ \t]+\n", "\n");
        String trimmedOld = oldText.replaceAll("[ \t]+\n", "\n");
        if (trimmedContent.contains(trimmedOld)) {
            return "\n【诊断】移除行尾空白后可以匹配——old_text 中可能含有多余的行尾空格（\\s+ 在行末）。"
                    + "【修正建议】请用 read_file 重新读取文件，从返回内容中直接复制要替换的代码段作为 old_text（不要手打，复制时会自动保留正确的行尾格式）。";
        }

        // Level 2: 所有制表符转为空格
        String tabNormalizedContent = content.replace("\t", "    ");
        String tabNormalizedOld = oldText.replace("\t", "    ");
        if (tabNormalizedContent.contains(tabNormalizedOld)) {
            return "\n【诊断】将制表符替换为空格后可以匹配——old_text 中使用了制表符(\\t)，但文件中对应位置使用空格缩进，两者不一致。"
                    + "【修正建议】请用 read_file 重新读取文件，从返回内容中直接复制（不要手打）要替换的代码段。复制操作会自动保留文件实际使用的缩进字符。";
        }

        // Level 3: 所有连续空白压缩为单个空格
        String wsCollapsedContent = content.replaceAll("[ \t]+", " ");
        String wsCollapsedOld = oldText.replaceAll("[ \t]+", " ");
        if (wsCollapsedContent.contains(wsCollapsedOld)) {
            return "\n【诊断】去除连续多余空白后可以匹配——old_text 的缩进宽度或空格数量与文件不一致（例如你手打了8个空格，但文件中是1个Tab）。"
                    + "【修正建议】请用 read_file 重新读取文件，从返回内容中直接复制（不要手打）要替换的代码段。复制操作会自动保留文件实际的缩进宽度。";
        }

        // Level 4: 行级相似度搜索
        return findSimilarBlocks(content, oldText);
    }

    /**
     * 基于行级比较的模糊匹配，找出与 old_text 最相似的代码块
     */
    private String findSimilarBlocks(String content, String oldText) {
        String[] contentLines = content.split("\n", -1);
        String[] oldLines = oldText.split("\n", -1);

        if (oldLines.length == 0) return "";
        if (oldLines.length > contentLines.length) {
            return "\n【诊断】old_text 的行数（" + oldLines.length + " 行）超过了文件总行数（"
                    + contentLines.length + " 行），无法匹配——old_text 比整个文件还长。"
                    + "【修正建议】请检查 old_text 是否误包含了其他文件或对话上下文的内容。用 read_file 重新读取文件，只复制该文件中实际存在的那部分代码。";
        }

        // 对每个可能的起始位置计算匹配得分
        List<BlockMatch> matches = new ArrayList<>();
        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            int matchCount = 0;
            for (int j = 0; j < oldLines.length; j++) {
                if (contentLines[i + j].trim().equals(oldLines[j].trim())) {
                    matchCount++;
                }
            }
            // score = 不匹配行数（越小越相似）
            int score = oldLines.length - matchCount;
            matches.add(new BlockMatch(i + 1, score, matchCount));
        }

        matches.sort(Comparator.comparingInt((BlockMatch m) -> m.score)
                .thenComparingInt(m -> Math.abs(m.lineNum - contentLines.length / 2)));

        int resultCount = Math.min(MAX_FUZZY_RESULTS, matches.size());

        StringBuilder sb = new StringBuilder();

        // 检查是否有去除缩进后的完全匹配
        if (matches.get(0).score == 0) {
            sb.append("\n【诊断】文件中有去除空白后完全匹配的代码块——你打的代码逻辑正确，但缩进/空格与文件不一致。"
                    + "【修正建议】请用 read_file 读取文件，从以下推荐位置中直接复制内容作为 old_text：\n");
        } else {
            sb.append("\n【诊断】文件中与 old_text 最相似的 ").append(resultCount).append(" 处位置（按匹配度排序）：\n");
        }

        for (int i = 0; i < resultCount; i++) {
            BlockMatch m = matches.get(i);
            int endLine = m.lineNum + oldLines.length - 1;
            String rate = m.score == 0
                    ? "完全匹配"
                    : m.matchingLines + "/" + oldLines.length + " 行匹配";
            sb.append("  #").append(i + 1).append("：第 ").append(m.lineNum)
                    .append("~").append(endLine).append(" 行（").append(rate).append("）\n");

            // 预览内容
            if (oldLines.length <= 2) {
                sb.append("    内容：").append(contentLines[m.lineNum - 1].trim()).append("\n");
            } else {
                sb.append("    起始：").append(contentLines[m.lineNum - 1].trim()).append("\n");
                sb.append("    结束：").append(contentLines[endLine - 1].trim()).append("\n");
            }
        }

        if (matches.get(0).score > 0) {
            sb.append("\n【建议】如果上述位置都不匹配你的预期，说明 old_text 中的代码可能已被之前的操作修改或删除。请先用 read_file 重新读取文件最新内容，再确定正确的 old_text。\n");
        }

        return sb.toString();
    }

    // ==================== 宽松匹配 + 执行替换 ====================

    /**
     * 当精确匹配失败时，尝试多级宽松匹配并直接执行替换。
     * 返回替换结果字符串，如果所有级别都失败则返回 null。
     * 因为编辑后会自动格式化，缩进/空格的小差异可由 formatter 修正。
     */
    private String tryRelaxedReplace(Path filePath, String originalContent, String normalizedContent,
                                     String normalizedOld, String normalizedNew) {
        // Level 1: 去除每行行尾空白
        String result = tryRelaxedLevel(filePath, originalContent, normalizedContent,
                normalizedOld, normalizedNew, this::stripTrailingWhitespace, "行尾空白已自动修正");
        if (result != null) return result;

        // Level 2: 所有制表符 → 空格（4空格）
        result = tryRelaxedLevel(filePath, originalContent, normalizedContent,
                normalizedOld, normalizedNew, s -> s.replace("\t", "    "), "缩进字符已自动修正（Tab→空格）");
        if (result != null) return result;

        // Level 3: 所有连续空白压缩为单个空格
        result = tryRelaxedLevel(filePath, originalContent, normalizedContent,
                normalizedOld, normalizedNew, s -> s.replaceAll("[ \\t]+", " "), "多余空白已自动压缩");
        if (result != null) return result;

        return null;
    }

    /**
     * 单个宽松级别的匹配+替换尝试
     * @param transform 内容变换函数（对 content 和 oldText 同时应用）
     * @param relaxNote 宽松方式说明
     * @return 替换结果，匹配失败或非唯一时返回 null
     */
    private String tryRelaxedLevel(Path filePath, String originalContent, String normalizedContent,
                                   String normalizedOld, String normalizedNew,
                                   java.util.function.UnaryOperator<String> transform, String relaxNote) {
        String transformedContent = transform.apply(normalizedContent);
        String transformedOld = transform.apply(normalizedOld);

        int idx = transformedContent.indexOf(transformedOld);
        if (idx < 0) return null;

        // 唯一性检查
        int secondIdx = transformedContent.indexOf(transformedOld, idx + 1);
        if (secondIdx >= 0) return null;

        // 将变换后内容中的位置映射回原始内容的行号范围
        int startLine = countLinesUpTo(transformedContent, idx);
        int endLine = startLine + Math.max(1, countLines(normalizedOld)) - 1;

        // 在原始归一化内容中定位对应行的字符位置
        int origStart = getLineStartPos(normalizedContent, startLine);
        int origEnd = getLineEndPos(normalizedContent, endLine);

        // 以原始文件中的实际文本作为被替换的 old 段
        String actualOld = normalizedContent.substring(origStart, origEnd);

        // 对齐尾部换行：oldText 不以 \n 结尾时，actualOld 也不应包含尾部换行
        if (!normalizedOld.endsWith("\n") && actualOld.endsWith("\n")) {
            actualOld = actualOld.substring(0, actualOld.length() - 1);
            origEnd--;
        }

        try {
            String resultContent = normalizedContent.substring(0, origStart)
                    + normalizedNew
                    + normalizedContent.substring(origEnd);

            String writeContent = convertLineEndings(resultContent, originalContent);
            Files.writeString(filePath, writeContent, StandardCharsets.UTF_8);

            PostEditPipeline.PostEditResult postEditResult = postEditPipeline.execute(filePath);

            int oldLineCount = Math.max(1, countLines(actualOld));
            int newLineCount = Math.max(1, countLines(normalizedNew));
            int delta = newLineCount - oldLineCount;
            int totalLines = countLines(resultContent);
            String diff = generateDiff(actualOld, normalizedNew, MAX_DIFF_LINES);

            StringBuilder sb = new StringBuilder();
            sb.append("编辑成功（宽松匹配）：").append(filePath.toAbsolutePath()).append("\n");
            sb.append("匹配方式：").append(relaxNote).append("\n");
            sb.append("匹配位置：第 ").append(startLine).append("~").append(endLine).append(" 行\n");
            sb.append("行数变化：").append(oldLineCount).append(" 行 → ").append(newLineCount).append(" 行");
            if (delta > 0) {
                sb.append(" (+").append(delta).append(" 行)");
            } else if (delta < 0) {
                sb.append(" (").append(delta).append(" 行)");
            }
            sb.append("\n");
            sb.append("文件现总行数：").append(totalLines).append(" 行\n");

            if (!diff.isEmpty()) {
                sb.append("\n变更预览：\n").append(diff);
            }
            if (postEditResult.hasInfo()) {
                sb.append("\n").append(postEditResult.toReport());
            }

            log.info("编辑文件成功(宽松匹配): {} (第{}~{}行, {}行→{}行, {})",
                    filePath.toAbsolutePath(), startLine, endLine, oldLineCount, newLineCount, relaxNote);
            return sb.toString();
        } catch (IOException e) {
            log.error("宽松匹配替换失败: {}", filePath, e);
            return null;
        }
    }

    /** 去除每行末尾的空白字符 */
    private String stripTrailingWhitespace(String text) {
        return text.replaceAll("[ \\t]+\\n", "\n")
                   .replaceAll("[ \\t]+$", "");
    }

    /** 计算从文本开头到指定字符位置的行数（0-based index） */
    private int countLinesUpTo(String text, int position) {
        int lines = 1;
        for (int i = 0; i < position && i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /** 获取指定行（1-based）在文本中的起始字符位置 */
    private int getLineStartPos(String text, int targetLine) {
        if (targetLine <= 1) return 0;
        int currentLine = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                currentLine++;
                if (currentLine == targetLine) return i + 1;
            }
        }
        return text.length();
    }

    /** 获取指定行（1-based）在文本中的结束字符位置（行尾换行符之后） */
    private int getLineEndPos(String text, int targetLine) {
        int currentLine = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (currentLine == targetLine) return i + 1;
                currentLine++;
            }
        }
        return text.length();
    }

    // ==================== Diff 生成 ====================

    /**
     * 生成简单的逐行 diff（类 unified diff 风格）
     */
    private String generateDiff(String oldText, String newText, int maxLines) {
        if (oldText.equals(newText)) return "";

        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);

        List<String> diff = new ArrayList<>();
        int oi = 0, ni = 0;

        while (oi < oldLines.length || ni < newLines.length) {
            if (oi < oldLines.length && ni < newLines.length
                    && oldLines[oi].equals(newLines[ni])) {
                diff.add(" " + oldLines[oi]);
                oi++;
                ni++;
            } else {
                // 输出不匹配的旧行（被删除）
                while (oi < oldLines.length
                        && (ni >= newLines.length || !oldLines[oi].equals(newLines[ni]))) {
                    diff.add("-" + oldLines[oi]);
                    oi++;
                }
                // 输出不匹配的新行（被添加）
                while (ni < newLines.length
                        && (oi >= oldLines.length || !oldLines[oi].equals(newLines[ni]))) {
                    diff.add("+" + newLines[ni]);
                    ni++;
                }
            }
        }

        // 截断过长 diff
        if (diff.size() > maxLines) {
            int half = maxLines / 2;
            List<String> truncated = new ArrayList<>(diff.subList(0, half));
            truncated.add("... (" + (diff.size() - maxLines) + " 行被省略)");
            truncated.addAll(diff.subList(diff.size() - half, diff.size()));
            diff = truncated;
        }

        return String.join("\n", diff);
    }

    // ==================== 辅助方法 ====================

    /**
     * 统一换行符为 LF (\n)，支持 CRLF、CR、LF 三种格式
     */
    private String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 将结果文本的换行符转换为与原始文件一致的风格
     */
    private String convertLineEndings(String text, String originalContent) {
        if (originalContent.contains("\r\n")) {
            return text.replace("\n", "\r\n");
        } else if (originalContent.contains("\r")) {
            return text.replace("\n", "\r");
        }
        return text; // 默认 LF
    }

    /**
     * 计算文本行数（基于 \n 计数）
     */
    private int countLines(String text) {
        if (text.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * 解析文件路径，防止路径穿越
     */
    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    /**
     * 模糊匹配辅助数据结构
     */
    private static class BlockMatch {
        final int lineNum;
        final int score;
        final int matchingLines;

        BlockMatch(int lineNum, int score, int matchingLines) {
            this.lineNum = lineNum;
            this.score = score;
            this.matchingLines = matchingLines;
        }
    }
}
