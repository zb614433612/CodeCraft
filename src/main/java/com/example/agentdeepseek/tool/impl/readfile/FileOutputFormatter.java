package com.example.agentdeepseek.tool.impl.readfile;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件读取结果格式化器
 * <p>
 * 将 {@link FileReadResult} 转换为结构化的文本输出，供 LLM 解析。
 * 输出格式包含文件元信息（路径、编码、行数范围）和带行号的内容。
 */
public final class FileOutputFormatter {

    private FileOutputFormatter() {}

    /** 分隔线长度 */
    private static final int SEPARATOR_LENGTH = 60;

    /**
     * 格式化文件读取结果
     */
    public static String format(FileReadResult result) {
        if (result.isDirectory()) {
            return formatDirectory(result);
        }
        return formatFile(result);
    }

    /**
     * 格式化目录列表
     */
    private static String formatDirectory(FileReadResult result) {
        Path dirPath = result.getFilePath();
        List<FileReadResult.DirectoryEntry> entries = result.getDirectoryEntries();

        StringBuilder sb = new StringBuilder();
        sb.append("路径: ").append(dirPath.toAbsolutePath()).append("\n");
        sb.append("类型: 目录\n");
        sb.append("条目数: ").append(entries.size()).append("\n");
        sb.append(separator()).append("\n");

        for (FileReadResult.DirectoryEntry entry : entries) {
            sb.append(entry.displayName()).append("\n");
        }

        sb.append(separator());
        return sb.toString();
    }

    /**
     * 格式化文件内容
     */
    private static String formatFile(FileReadResult result) {
        Path filePath = result.getFilePath();
        List<String> lines = result.getLines();
        int startLine = result.getStartLine();
        int endLine = result.getEndLine();
        long totalLines = result.getTotalLines();
        boolean hasMore = result.isHasMore();
        boolean lineTruncated = result.isLineTruncated();
        boolean outputTruncated = result.isOutputTruncated();

        StringBuilder sb = new StringBuilder();

        // --- 文件元信息 ---
        sb.append("路径: ").append(filePath.toAbsolutePath()).append("\n");
        sb.append("编码: ").append(result.getCharset().name()).append("\n");
        sb.append("类型: 文件\n");

        // 行数信息
        if (hasMore) {
            sb.append("行数: 超过 ").append(totalLines).append(" 行");
        } else {
            sb.append("行数: ").append(totalLines).append(" 行");
        }
        sb.append(" | 显示: ").append(startLine).append(" - ").append(endLine);
        if (hasMore) {
            sb.append(" (未完)");
        }
        sb.append("\n");

        // 截断标记
        if (lineTruncated) {
            sb.append("注意: 部分行因超长已被截断（上限 ").append(FileReadEngine.MAX_LINE_LENGTH).append(" 字符）\n");
        }
        if (outputTruncated) {
            sb.append("注意: 输出已达大小上限（")
                    .append(FileReadEngine.MAX_OUTPUT_BYTES / 1024).append(" KB），内容已截断\n");
        }

        sb.append(separator()).append("\n");

        // --- 内容主体 ---
        if (lines.isEmpty()) {
            sb.append("（文件为空）\n");
        } else {
            int lineNumWidth = String.valueOf(endLine).length();
            for (int i = 0; i < lines.size(); i++) {
                String lineNum = String.format("%" + lineNumWidth + "d", startLine + i);
                sb.append(" ").append(lineNum).append(" │ ").append(lines.get(i)).append("\n");
            }
        }

        sb.append(separator()).append("\n");

        // --- 翻页提示 ---
        if (hasMore) {
            long remaining = totalLines - endLine;
            if (remaining > 0) {
                sb.append("还有约 ").append(remaining).append(" 行未显示");
            } else {
                sb.append("还有更多行未显示");
            }
            sb.append("，使用 page/page_size 参数分页查看。\n");
        }

        return sb.toString();
    }

    private static String separator() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SEPARATOR_LENGTH; i++) {
            sb.append('━');
        }
        return sb.toString();
    }
}
