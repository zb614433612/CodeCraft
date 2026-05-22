package com.example.agentdeepseek.tool.impl.readfile;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 流式文件读取引擎
 * <p>
 * 基于 {@link Files#lines(Path, Charset)} 实现流式读取，避免大文件 OOM。
 * 支持行范围模式、分页模式、单行截断、输出大小限制。
 */
@Slf4j
public final class FileReadEngine {

    private FileReadEngine() {}

    /** 单行最大字符数，超限截断 */
    public static final int MAX_LINE_LENGTH = 2000;

    /** 单行截断后缀 */
    public static final String LINE_TRUNCATION_SUFFIX =
            "... (line truncated to " + MAX_LINE_LENGTH + " chars)";

    /** 输出大小硬上限（字节） */
    public static final long MAX_OUTPUT_BYTES = 100 * 1024; // 100KB

    /** 未指定范围时默认读取行数 */
    public static final int DEFAULT_LIMIT = 2000;

    /** 默认分页大小 */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** 二进制检测采样大小 */
    private static final int BINARY_SAMPLE_SIZE = 4096;

    /** 二进制判定阈值（空字节比例） */
    private static final double BINARY_NULL_THRESHOLD = 0.05;

    // =========================== 主入口 ===========================

    /**
     * 流式读取文件内容
     *
     * @param filePath  文件路径
     * @param charset   字符编码（由外部检测传入）
     * @param startLine 起始行号（1-based），null 表示从第1行开始
     * @param endLine   结束行号（1-based），null 表示读到末尾/上限
     * @param page      页码（1-based），null 表示不使用分页
     * @param pageSize  每页行数，null 使用默认值
     * @return 读取结果
     * @throws IOException 读取异常
     */
    public static FileReadResult read(Path filePath, Charset charset,
                                      Integer startLine, Integer endLine,
                                      Integer page, Integer pageSize) throws IOException {
        // 确定读取范围
        int fromLine;
        int maxLines;

        boolean hasStartEnd = startLine != null || endLine != null;
        boolean hasPage = page != null;

        if (hasStartEnd) {
            // 行范围模式
            fromLine = (startLine != null) ? Math.max(1, startLine) : 1;
            int rawEnd = (endLine != null) ? endLine : Integer.MAX_VALUE;
            maxLines = (rawEnd >= fromLine) ? (rawEnd - fromLine + 1) : 0;
            // 不设硬上限，让 endLine 决定
            if (maxLines <= 0 || maxLines == Integer.MAX_VALUE) {
                maxLines = Integer.MAX_VALUE;
            }
        } else if (hasPage) {
            // 分页模式
            int p = Math.max(1, page);
            int ps = (pageSize != null) ? Math.max(1, pageSize) : DEFAULT_PAGE_SIZE;
            fromLine = (p - 1) * ps + 1;
            maxLines = ps;
        } else {
            // 默认模式：读取前 DEFAULT_LIMIT 行
            fromLine = 1;
            maxLines = DEFAULT_LIMIT;
        }

        // 流式读取
        return readLines(filePath, charset, fromLine, maxLines);
    }

    // =========================== 流式核心 ===========================

    /**
     * 流式读取指定范围的行
     */
    private static FileReadResult readLines(Path filePath, Charset charset,
                                             int fromLine, int maxLines) throws IOException {
        List<String> lines = new ArrayList<>();
        long totalSeen = 0;          // 所有遍历过的行（跳过+读取）
        boolean hasMore = false;
        boolean lineTruncated = false;
        boolean outputTruncated = false;
        long outputBytes = 0;

        try (Stream<String> stream = Files.lines(filePath, charset)) {
            Iterator<String> it = stream.iterator();

            while (it.hasNext()) {
                String rawLine = it.next();
                totalSeen++;

                if (totalSeen < fromLine) {
                    // 跳过起始行之前的内容
                    continue;
                }

                if (lines.size() >= maxLines) {
                    hasMore = true;
                    break;
                }

                // --- P1: 单行截断 ---
                String line = rawLine;
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + LINE_TRUNCATION_SUFFIX;
                    lineTruncated = true;
                }

                lines.add(line);

                // --- P2: 输出大小限制 ---
                outputBytes += estimateBytes(line, charset);
                if (outputBytes > MAX_OUTPUT_BYTES && lines.size() >= 10) {
                    // 至少输出 10 行，避免空结果
                    outputTruncated = true;
                    // 继续检查是否还有更多行
                    hasMore = it.hasNext();
                    break;
                }
            }
        }

        int actualStart = fromLine;
        int actualEnd = fromLine + lines.size() - 1;

        return new FileReadResult(
                filePath, charset, lines,
                actualStart, actualEnd, totalSeen,
                hasMore, lineTruncated, outputTruncated
        );
    }

    // =========================== 目录读取 ===========================

    /**
     * 读取目录条目列表
     */
    public static FileReadResult readDirectory(Path dirPath) throws IOException {
        List<FileReadResult.DirectoryEntry> entries;
        try (Stream<Path> paths = Files.list(dirPath)) {
            entries = paths
                    .map(path -> {
                        String name = path.getFileName().toString();
                        boolean isDir = Files.isDirectory(path);
                        boolean isLink = Files.isSymbolicLink(path);
                        return new FileReadResult.DirectoryEntry(name, isDir, isLink);
                    })
                    .sorted(Comparator.comparing(FileReadResult.DirectoryEntry::displayName))
                    .collect(Collectors.toList());
        }
        return new FileReadResult(dirPath, entries);
    }

    // =========================== 二进制检测 ===========================

    /**
     * 检测文件是否为二进制（检查前 4KB 的空字节比例）
     */
    public static boolean isBinaryFile(Path filePath) throws IOException {
        long size = Files.size(filePath);
        if (size <= 0) return false;

        int sampleSize = (int) Math.min(size, BINARY_SAMPLE_SIZE);
        byte[] sample = new byte[sampleSize];
        try (InputStream is = Files.newInputStream(filePath)) {
            int bytesRead = is.read(sample);
            if (bytesRead <= 0) return false;

            int nullCount = 0;
            for (int i = 0; i < bytesRead; i++) {
                if (sample[i] == 0) nullCount++;
            }
            return (double) nullCount / bytesRead > BINARY_NULL_THRESHOLD;
        }
    }

    // =========================== 工具方法 ===========================

    /**
     * 估算字符串的字节数
     */
    private static long estimateBytes(String text, Charset charset) {
        return text.getBytes(charset).length + 1L; // +1 换行符
    }

    /**
     * 截断单行
     */
    public static String truncateLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH) + LINE_TRUNCATION_SUFFIX;
    }
}
