package com.example.agentdeepseek.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link LogService#search} 分页语义自检（main 方法，非 JUnit）。
 *
 * <h3>为什么需要这个自检</h3>
 * <p>这是一次「静默失效」型 bug：代码不报错、不抛异常、接口照常返回 200，
 * 只是<b>返回的是文件里最旧的那一段</b>。用户看到的现象是「09:55 之后的日志不见了」，
 * 实际一条都没丢 —— 这类问题只能靠数据断言发现，肉眼审查极易漏掉。</p>
 *
 * <h3>回归目标（本次修复的核心契约）</h3>
 * <ol>
 *   <li><b>page=1 必须是最新的一页</b>，而非文件开头的一页；</li>
 *   <li><b>total 必须是真实命中总数</b>，不受分页窗口大小影响；</li>
 *   <li><b>page 递增 = 向更早翻页</b>，且相邻页不重叠；</li>
 *   <li><b>滑动窗口不能丢最新数据</b>：即使命中数远超 page*size，最新几条仍要取得到。</li>
 * </ol>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -q -f "C:\work\IdeaProjects\CodeCraft\pom.xml" \
 *   org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *   -Dexec.mainClass=com.example.agentdeepseek.log.LogServiceSearchTest \
 *   -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class LogServiceSearchTest {

    private static int passed = 0;
    private static int failed = 0;

    /** 自检用日志行数：远超 page*size，用来暴露「只收集最旧 N 条」的旧缺陷 */
    private static final int TOTAL_LINES = 5000;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        System.out.println("=== LogService.search 分页语义自检开始 ===");

        Path tempDir = Files.createTempDirectory("logsvc-test-");
        try {
            Path appLog = tempDir.resolve("app.log");
            // 生成 TOTAL_LINES 行递增时间戳日志：第 1 行最旧，最后一行最新
            // 每行都带唯一序号 SEQ=i，便于断言「取到的是哪一段」
            StringBuilder sb = new StringBuilder();
            LocalDate base = LocalDate.now();
            for (int i = 0; i < TOTAL_LINES; i++) {
                String time = base.atStartOfDay()
                        .plusSeconds(i)
                        .atZone(ZoneId.systemDefault())
                        .format(TIME_FMT);
                sb.append(time).append(" INFO  com.example.Demo - line SEQ=").append(i).append('\n');
            }
            Files.writeString(appLog, sb.toString(), StandardCharsets.UTF_8);
            // app.log 的文件修改时间必须是今天，否则 getAvailableDates() 不会把它算进来
            Files.setLastModifiedTime(appLog, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

            LogService service = new LogService();
            System.setProperty("LOG_PATH", tempDir.toString());
            try {
                testPage1IsNewest(service);
                testTotalIsAccurate(service);
                testPagingGoesBackward(service);
                testNoOverlapBetweenPages(service);
                testWindowKeepsNewestRows(service);
            } finally {
                System.clearProperty("LOG_PATH");
            }
        } finally {
            deleteQuietly(tempDir);
        }

        System.out.println("=== 结果: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 用例 1：page=1 必须是**最新**一页（旧实现在这里返回最旧的 20 行） */
    private static void testPage1IsNewest(LogService service) {
        int size = 20;
        LogService.SearchResult r = service.search(null, null, null, 1, size);
        List<LogService.LogLine> lines = r.lines();

        boolean ok = !lines.isEmpty();
        if (ok) {
            // 该页最后一行应当是整个文件中最新的一行
            ok = lines.get(lines.size() - 1).content().contains("SEQ=" + (TOTAL_LINES - 1));
        }
        // 该页最早一行 = 最新一条往前数 size-1 条
        if (ok) {
            ok = lines.get(0).content().contains("SEQ=" + (TOTAL_LINES - size));
        }
        report("page=1 返回最新一页（而非文件开头）", ok);
    }

    /** 用例 2：total 必须是真实命中数，不能被分页窗口截断 */
    private static void testTotalIsAccurate(LogService service) {
        // 注意：搜索时不带关键词 = 全部行命中
        LogService.SearchResult r = service.search(null, null, null, 1, 20);
        boolean ok = r.total() == TOTAL_LINES;
        report("total 为真实命中总数 " + TOTAL_LINES + "（实际 " + r.total() + "）", ok);
    }

    /** 用例 3：page=2 必须比 page=1 **更旧** */
    private static void testPagingGoesBackward(LogService service) {
        int size = 20;
        LogService.SearchResult p1 = service.search(null, null, null, 1, size);
        LogService.SearchResult p2 = service.search(null, null, null, 2, size);

        boolean ok = !p1.lines().isEmpty() && !p2.lines().isEmpty();
        if (ok) {
            // page2 的最后一行应紧邻 page1 第一行的前一条
            String p1First = p1.lines().get(0).content();
            String p2Last = p2.lines().get(p2.lines().size() - 1).content();
            int p1FirstSeq = extractSeq(p1First);
            int p2LastSeq = extractSeq(p2Last);
            ok = p2LastSeq < p1FirstSeq;
        }
        report("page=2 比 page=1 更旧", ok);
    }

    /** 用例 4：相邻页不得重叠（否则滚动加载会出现重复行） */
    private static void testNoOverlapBetweenPages(LogService service) {
        int size = 20;
        LogService.SearchResult p1 = service.search(null, null, null, 1, size);
        LogService.SearchResult p2 = service.search(null, null, null, 2, size);
        boolean ok = !p1.lines().isEmpty() && !p2.lines().isEmpty();
        if (ok) {
            int p1FirstSeq = extractSeq(p1.lines().get(0).content());
            int p2LastSeq = extractSeq(p2.lines().get(p2.lines().size() - 1).content());
            ok = p2LastSeq == p1FirstSeq - 1;
        }
        report("相邻页首尾衔接、无重叠无缺口", ok);
    }

    /** 用例 5：滑动窗口不能把**最新**的行丢掉（旧实现收满即停，丢的正是最新数据） */
    private static void testWindowKeepsNewestRows(LogService service) {
        // 取一个很大的 page，让 maxCollect 远超总行数，验证窗口未丢数据
        LogService.SearchResult r = service.search(null, null, null, 1, TOTAL_LINES);
        boolean ok = r.lines().size() == TOTAL_LINES
                && r.lines().get(r.lines().size() - 1).content().contains("SEQ=" + (TOTAL_LINES - 1));
        report("大页尺寸下窗口保留全部行且末行最新", ok);

        // 小窗口场景：page=1 size=5，必须是最新 5 行
        LogService.SearchResult small = service.search(null, null, null, 1, 5);
        boolean ok2 = small.lines().size() == 5
                && extractSeq(small.lines().get(4).content()) == TOTAL_LINES - 1
                && extractSeq(small.lines().get(0).content()) == TOTAL_LINES - 5;
        report("小窗口下仍取最新 5 行", ok2);
    }

    /** 从句子里抽 SEQ=N */
    private static int extractSeq(String line) {
        int idx = line.lastIndexOf("SEQ=");
        if (idx < 0) return -1;
        int start = idx + 4;
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        return Integer.parseInt(line.substring(start, end));
    }

    private static void report(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
