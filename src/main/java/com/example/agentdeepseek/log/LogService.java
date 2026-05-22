package com.example.agentdeepseek.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 日志搜索服务
 * 读取持久化的日志文件，提供按日期/级别/关键词搜索
 */
@Slf4j
@Service
public class LogService {

    /** 匹配归档文件 app.YYYY-MM-dd.0.log 和当前活跃文件 app.log */
    private static final Pattern FILE_DATE_PATTERN = Pattern.compile("app(?:\\.(\\d{4}-\\d{2}-\\d{2})\\.\\d+)?\\.log$");
    private static final Pattern APP_LOG_PATTERN = Pattern.compile("^app\\.log$");
    private static final List<String> LEVEL_KEYWORDS = List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Path getLogDir() {
        String logPath = System.getProperty("LOG_PATH", "./logs");
        return Paths.get(logPath).toAbsolutePath().normalize();
    }

    /**
     * 获取有日志文件的日期列表（从新到旧）
     * 同时兼容归档文件 app.YYYY-MM-dd.*.log 和当前活跃文件 app.log
     */
    public List<String> getAvailableDates() {
        Path logDir = getLogDir();
        if (!Files.isDirectory(logDir)) return List.of();

        Set<String> dates = new HashSet<>();

        try (Stream<Path> dirStream = Files.list(logDir)) {
            dirStream.forEach(p -> {
                String name = p.getFileName().toString();
                Matcher m = FILE_DATE_PATTERN.matcher(name);
                if (m.find()) {
                    // 归档文件：从正则提取日期
                    if (m.group(1) != null) {
                        dates.add(m.group(1));
                    }
                }
                // 当前活跃文件 app.log：用文件最后修改时间作为日期
                if (APP_LOG_PATTERN.matcher(name).find()) {
                    try {
                        LocalDate fileDate = Files.getLastModifiedTime(p).toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDate();
                        dates.add(fileDate.format(DATE_FMT));
                    } catch (IOException ignored) {}
                }
            });
        } catch (IOException e) {
            log.warn("读取日志目录失败: {}", e.getMessage());
            return List.of();
        }

        return dates.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList());
    }

    /**
     * 搜索日志
     * 同时搜索归档文件和当前活跃文件 app.log
     */
    public SearchResult search(String keyword, String level, String date, int page, int size) {
        Path logDir = getLogDir();
        if (!Files.isDirectory(logDir)) return SearchResult.empty();

        final String targetDate;
        if (date != null && !date.isBlank()) {
            targetDate = date;
        } else {
            List<String> dates = getAvailableDates();
            if (dates.isEmpty()) return SearchResult.empty();
            targetDate = dates.get(0);
        }

        List<Path> logFiles = new ArrayList<>();
        try (Stream<Path> dirStream = Files.list(logDir)) {
            // 搜索指定日期的归档文件
            dirStream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.matches("app\\." + targetDate + "\\.\\d+\\.log$")) {
                    logFiles.add(p);
                }
            });
        } catch (IOException e) {
            log.warn("搜索日志文件失败: {}", e.getMessage());
            return SearchResult.empty();
        }

        // 如果目标日期是今天，也搜索当前活跃文件 app.log
        String today = LocalDate.now().format(DATE_FMT);
        if (targetDate.equals(today)) {
            Path appLog = logDir.resolve("app.log");
            if (Files.isRegularFile(appLog) && !logFiles.contains(appLog)) {
                logFiles.add(appLog);
            }
        }

        Collections.sort(logFiles);

        if (logFiles.isEmpty()) return SearchResult.empty();

        List<LogLine> allMatches = new ArrayList<>();
        long totalCount = 0;
        final int maxCollect = page * size;
        final String upperLevel = (level != null) ? level.toUpperCase() : null;

        for (Path file : logFiles) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8))) {
                String line;
                int fileLineNum = 0;
                while ((line = reader.readLine()) != null) {
                    fileLineNum++;
                    if (line.trim().isEmpty()) continue;

                    // 级别过滤
                    if (upperLevel != null) {
                        boolean levelMatch = false;
                        for (String lvl : LEVEL_KEYWORDS) {
                            if (line.contains(" " + lvl + " ") || line.startsWith(lvl + " ") || line.contains(" " + lvl + " -")) {
                                if (lvl.equals(upperLevel)) levelMatch = true;
                                break;
                            }
                        }
                        if (!levelMatch) continue;
                    }

                    // 关键词过滤（大小写不敏感）
                    if (keyword != null && !keyword.isBlank()) {
                        if (!line.toLowerCase().contains(keyword.toLowerCase())) continue;
                    }

                    totalCount++;
                    if (allMatches.size() < maxCollect) {
                        allMatches.add(new LogLine(targetDate, file.getFileName().toString(), fileLineNum, line));
                    }
                }
            } catch (IOException e) {
                log.warn("读取日志文件失败: {}", file, e);
            }
        }

        int startIndex = (page - 1) * size;
        int endIndex = Math.min(startIndex + size, allMatches.size());
        if (startIndex >= allMatches.size()) {
            return new SearchResult(targetDate, List.of(), totalCount, page, size);
        }

        return new SearchResult(targetDate, allMatches.subList(startIndex, endIndex), totalCount, page, size);
    }

    /**
     * 从日志文件末尾读取最后 N 行（用于前端滚动展示）
     * @param maxLines 最多读取的行数
     * @param skip 从文件末尾跳过多少行后再开始读（用于滚动加载更多）
     * @return 行文本列表（从旧到新排序）
     */
    public List<String> tail(int maxLines, int skip) {
        Path logDir = getLogDir();
        Path appLog = logDir.resolve("app.log");
        if (!Files.isRegularFile(appLog)) return List.of();

        // 先搜索当天的归档文件（按序号从大到小排序），如果 app.log 不够就补充
        String today = LocalDate.now().format(DATE_FMT);
        List<Path> logFiles = new ArrayList<>();

        try (Stream<Path> dirStream = Files.list(logDir)) {
            dirStream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.matches("app\\." + today + "\\.\\d+\\.log$")) {
                    logFiles.add(p);
                }
            });
        } catch (IOException ignored) {}

        // 按文件名排序（序号小的在前）
        Collections.sort(logFiles);
        // 加入当前活跃文件（最新）
        logFiles.add(appLog);

        // 从最新的文件开始，从后往前读取
        List<String> allLines = new ArrayList<>();
        int remaining = maxLines + skip;
        int toSkip = skip;

        for (int i = logFiles.size() - 1; i >= 0 && remaining > 0; i--) {
            Path file = logFiles.get(i);
            if (!Files.isRegularFile(file)) continue;

            List<String> fileLines = tailFile(file, remaining);
            // fileLines 是从旧到新的，reverse 后从新到旧
            Collections.reverse(fileLines);

            for (String line : fileLines) {
                if (toSkip > 0) {
                    toSkip--;
                    continue;
                }
                allLines.add(line);
                remaining--;
                if (remaining <= 0) break;
            }
        }

        // now allLines 是从新到旧的，reverse 回来后从旧到新
        Collections.reverse(allLines);
        return allLines;
    }

    /**
     * 用 RandomAccessFile 从文件末尾读取最后 N 行（UTF-8 编码）
     */
    private List<String> tailFile(Path file, int maxLines) {
        List<String> lines = new ArrayList<>();
        try (var raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) return lines;

            long pointer = fileLength - 1;
            int linesRead = 0;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            while (pointer >= 0 && linesRead < maxLines) {
                raf.seek(pointer);
                byte b = raf.readByte();
                if (b == '\n') {
                    if (baos.size() > 0) {
                        lines.add(decodeReversedBytes(baos));
                        baos.reset();
                        linesRead++;
                    }
                } else if (b != '\r') {
                    baos.write(b);
                }
                pointer--;
            }

            // 处理文件第一行
            if (baos.size() > 0) {
                lines.add(decodeReversedBytes(baos));
            }

            // lines 是从新到旧的，反转后从旧到新
            Collections.reverse(lines);
            return lines;
        } catch (IOException e) {
            log.warn("读取日志文件失败: {}", file, e);
            return List.of();
        }
    }

    /** 将 ByteArrayOutputStream 中反向的字节翻转并解码为 UTF-8 字符串 */
    private String decodeReversedBytes(ByteArrayOutputStream baos) {
        byte[] reversed = baos.toByteArray();
        byte[] correctOrder = new byte[reversed.length];
        for (int i = 0; i < reversed.length; i++) {
            correctOrder[i] = reversed[reversed.length - 1 - i];
        }
        return new String(correctOrder, StandardCharsets.UTF_8);
    }

    public record LogLine(String date, String fileName, int lineNumber, String content) {}

    public record SearchResult(String date, List<LogLine> lines, long total, int page, int size) {
        public static SearchResult empty() {
            return new SearchResult("", List.of(), 0, 1, 20);
        }
    }
}
