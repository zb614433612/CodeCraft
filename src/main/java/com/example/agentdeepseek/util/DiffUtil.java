package com.example.agentdeepseek.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本对比工具类
 * <p>
 * 对两段文本进行逐行对比，生成精简的 diff 摘要（+n/-n 格式）。
 * 不依赖第三方 diff 库，纯 JDK 实现。
 * 适用于工具返回结果中给 LLM 展示变更概览。
 */
public final class DiffUtil {

    private static final int MAX_DIFF_LINES = 50;

    private DiffUtil() {}

    /**
     * Diff 结果
     */
    public static class DiffResult {
        private final int additions;
        private final int deletions;
        private final String diffPreview;

        public DiffResult(int additions, int deletions, String diffPreview) {
            this.additions = additions;
            this.deletions = deletions;
            this.diffPreview = diffPreview;
        }

        public int additions() { return additions; }
        public int deletions() { return deletions; }
        public String diffPreview() { return diffPreview; }
        public boolean hasChanges() { return additions > 0 || deletions > 0; }

        /**
         * 生成简洁的变更摘要文本
         */
        public String toSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("变更概览：+").append(additions).append(" 行 -").append(deletions).append(" 行");
            if (additions + deletions > 0) {
                int netChange = additions - deletions;
                if (netChange > 0) {
                    sb.append("（净增 ").append(netChange).append(" 行）");
                } else if (netChange < 0) {
                    sb.append("（净减 ").append(-netChange).append(" 行）");
                }
            }
            return sb.toString();
        }

        /**
         * 完整的 diff 报告
         */
        public String toReport() {
            if (!hasChanges()) return "无变更";
            StringBuilder sb = new StringBuilder();
            sb.append(toSummary()).append("\n");
            if (!diffPreview.isEmpty()) {
                sb.append("变更预览：\n").append(diffPreview);
            }
            return sb.toString();
        }
    }

    /**
     * 对比两段文本，生成 diff 结果
     *
     * @param oldText 原文本
     * @param newText 新文本
     * @return DiffResult
     */
    public static DiffResult diff(String oldText, String newText) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";

        if (oldText.equals(newText)) {
            return new DiffResult(0, 0, "");
        }

        String[] oldLines = splitLines(oldText);
        String[] newLines = splitLines(newText);

        // 使用 LCS（最长公共子序列）算法计算 diff
        List<String> diffLines = computeDiff(oldLines, newLines);

        int additions = 0;
        int deletions = 0;
        for (String line : diffLines) {
            if (line.startsWith("+")) additions++;
            else if (line.startsWith("-")) deletions++;
        }

        // 截断过长 diff
        String preview = truncateDiff(diffLines);

        return new DiffResult(additions, deletions, preview);
    }

    /**
     * 将文本按换行符分割为行数组
     */
    private static String[] splitLines(String text) {
        if (text.isEmpty()) return new String[0];
        return text.split("\n", -1);
    }

    /**
     * 基于 LCS 的逐行 diff 算法
     */
    private static List<String> computeDiff(String[] oldLines, String[] newLines) {
        int m = oldLines.length;
        int n = newLines.length;

        // 构建 LCS 长度矩阵
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // 回溯构建 diff
        List<String> result = new ArrayList<>();
        int i = m, j = n;
        List<String> reversed = new ArrayList<>();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                reversed.add(" " + oldLines[i - 1]);
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                reversed.add("+" + newLines[j - 1]);
                j--;
            } else {
                reversed.add("-" + oldLines[i - 1]);
                i--;
            }
        }

        // 反转回正确顺序
        for (int k = reversed.size() - 1; k >= 0; k--) {
            result.add(reversed.get(k));
        }

        return result;
    }

    /**
     * 截断过长的 diff 预览
     */
    private static String truncateDiff(List<String> diffLines) {
        if (diffLines.isEmpty()) return "";

        if (diffLines.size() <= MAX_DIFF_LINES) {
            return String.join("\n", diffLines);
        }

        int half = MAX_DIFF_LINES / 2;
        List<String> truncated = new ArrayList<>(diffLines.subList(0, half));
        truncated.add("...（" + (diffLines.size() - MAX_DIFF_LINES) + " 行被省略）");
        truncated.addAll(diffLines.subList(diffLines.size() - half, diffLines.size()));

        return String.join("\n", truncated);
    }

    /**
     * 生成结构化的 JSON 片段（供 LLM 解析）
     */
    public static String toJsonSnippet(DiffResult diff, String filePath, long size) {
        return "\n[TOOL_RESULT]\n"
                + "{\n"
                + "  \"status\": \"success\",\n"
                + "  \"filePath\": \"" + escapeJson(filePath) + "\",\n"
                + "  \"size\": " + size + ",\n"
                + "  \"additions\": " + diff.additions() + ",\n"
                + "  \"deletions\": " + diff.deletions() + "\n"
                + "}\n"
                + "[/TOOL_RESULT]";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
