package com.example.agentdeepseek.tool.impl.readfile;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件不存在时的模糊搜索建议处理器
 * <p>
 * 当用户请求的文件不存在时，在同级目录下搜索文件名相似的候选文件，
 * 基于 Levenshtein 距离排序，返回前 N 个建议。
 */
@Slf4j
public final class FileNotFoundHandler {

    private FileNotFoundHandler() {}

    /** 最多返回的建议数 */
    private static final int MAX_SUGGESTIONS = 3;

    /**
     * 为不存在的文件生成模糊匹配建议
     *
     * @param requestedPath 用户请求的文件路径（已确认不存在）
     * @param searchDir     搜索的父目录
     * @return 建议文本，无建议时返回 null
     */
    public static String suggest(Path requestedPath, Path searchDir) {
        String targetName = requestedPath.getFileName().toString();

        if (!Files.isDirectory(searchDir)) {
            return null;
        }

        List<String> suggestions;
        try (Stream<Path> files = Files.list(searchDir)) {
            suggestions = files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        String lowerName = name.toLowerCase();
                        String lowerTarget = targetName.toLowerCase();
                        return lowerName.contains(lowerTarget) || lowerTarget.contains(lowerName);
                    })
                    .sorted(Comparator.comparingInt(name -> levenshtein(name.toLowerCase(), targetName.toLowerCase())))
                    .limit(MAX_SUGGESTIONS)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.debug("搜索相似文件名失败: {}", searchDir, e);
            return null;
        }

        if (suggestions.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("文件不存在：").append(requestedPath.toAbsolutePath()).append("\n");
        sb.append("您是不是要找：\n");
        for (String s : suggestions) {
            sb.append("  - ").append(s).append("\n");
        }
        sb.append("请检查文件名拼写（包括大小写、扩展名）后重试。");
        return sb.toString();
    }

    /**
     * 计算 Levenshtein 编辑距离
     */
    static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                    dp[i][j] = Math.min(
                            Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                            dp[i - 1][j - 1] + cost
                    );
                }
            }
        }
        return dp[a.length()][b.length()];
    }
}
