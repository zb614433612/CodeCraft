package com.example.agentdeepseek.tool.impl.readfile;

import lombok.Getter;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件读取结果
 * 承载流式读取引擎的全部产出，供格式化器使用
 */
@Getter
public class FileReadResult {

    /** 文件路径 */
    private final Path filePath;

    /** 检测到的编码 */
    private final Charset charset;

    /** 读取到的行内容 */
    private final List<String> lines;

    /** 实际起始行号（1-based） */
    private final int startLine;

    /** 实际结束行号（1-based） */
    private final int endLine;

    /** 已遍历的总行数（含跳过的行），有 hasMore 时是下界 */
    private final long totalLines;

    /** 是否还有更多行未读取 */
    private final boolean hasMore;

    /** 是否有行因超长被截断 */
    private final boolean lineTruncated;

    /** 输出是否因大小上限被截断 */
    private final boolean outputTruncated;

    /** 文件是否为目录 */
    private final boolean isDirectory;

    /** 目录条目列表（仅 isDirectory=true 时有值） */
    private final List<DirectoryEntry> directoryEntries;

    public FileReadResult(Path filePath, Charset charset, List<String> lines,
                          int startLine, int endLine, long totalLines,
                          boolean hasMore, boolean lineTruncated, boolean outputTruncated) {
        this.filePath = filePath;
        this.charset = charset;
        this.lines = lines;
        this.startLine = startLine;
        this.endLine = endLine;
        this.totalLines = totalLines;
        this.hasMore = hasMore;
        this.lineTruncated = lineTruncated;
        this.outputTruncated = outputTruncated;
        this.isDirectory = false;
        this.directoryEntries = null;
    }

    public FileReadResult(Path filePath, List<DirectoryEntry> directoryEntries) {
        this.filePath = filePath;
        this.charset = null;
        this.lines = null;
        this.startLine = 0;
        this.endLine = 0;
        this.totalLines = directoryEntries.size();
        this.hasMore = false;
        this.lineTruncated = false;
        this.outputTruncated = false;
        this.isDirectory = true;
        this.directoryEntries = directoryEntries;
    }

    /** 目录条目 */
    @Getter
    public static class DirectoryEntry {
        private final String name;
        private final boolean isDirectory;
        private final boolean isSymlink;

        public DirectoryEntry(String name, boolean isDirectory, boolean isSymlink) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.isSymlink = isSymlink;
        }

        public String displayName() {
            String suffix = isDirectory ? "/" : "";
            String prefix = isSymlink ? "@ " : "  ";
            return prefix + name + suffix;
        }
    }
}
