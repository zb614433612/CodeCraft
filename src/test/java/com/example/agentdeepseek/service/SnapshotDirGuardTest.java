package com.example.agentdeepseek.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * {@link SnapshotService} 目录守卫自检（main 方法，非 JUnit）。
 *
 * <h3>为什么需要这个自检</h3>
 * <p>线上真实踩到过一次：{@code computeDiffStats} 抛出
 * {@code java.nio.file.AccessDeniedException}，报错路径以包名 {@code sso} 结尾 ——
 * 那是<b>目录</b>，不是 .java 文件。Windows 上对目录调 {@code Files.readAllBytes}
 * 必然抛这个异常（目录不是可读流）。</p>
 *
 * <p>根因链：{@code file_writer} 执行 {@code action=delete} 且 {@code path} 指向目录时，
 * {@code ToolExecutor.resolveFilePath} 把目录当成「待修改文件」传进来；
 * 备份阶段 {@code Files.copy(目录)} 只建出空目录；之后 diff 统计或回滚读它就会炸。
 * 由于 {@code SnapshotService} 相关方法是 {@code synchronized}，反复抛异常还会拖慢整条快照链。</p>
 *
 * <h3>回归目标（本次修复的核心契约）</h3>
 * <ol>
 *   <li><b>目录/非普通文件不得建快照</b>：{@code createSnapshot} 直接返回 null，
 *       且<b>不</b>在 {@code snapshots/} 下留下任何条目（避免脏 metadata 与索引）；</li>
 *   <li><b>普通文件不能被误伤</b>：正常的 .java 文件照常建快照；</li>
 *   <li><b>computeDiffStats 具备第二道防线</b>：即使传入目录也不抛异常
 *       （旧版本落盘的 metadata 可能残留目录条目，入口拦截只对新快照生效）。</li>
 * </ol>
 *
 * <h3>隔离手法</h3>
 * <p>快照根目录由 {@code getBasePath() = Path.of(System.getProperty("user.dir"))} 决定，
 * 因此本自检临时把 {@code user.dir} 指向系统临时目录，跑完恢复并整体删除，
 * 不会在仓库根留下 {@code snapshots/} 垃圾。</p>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -q -f "C:\work\IdeaProjects\CodeCraft\pom.xml" \
 *   org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *   -Dexec.mainClass=com.example.agentdeepseek.service.SnapshotDirGuardTest \
 *   -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class SnapshotDirGuardTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== SnapshotService 目录守卫自检开始 ===");

        String originalUserDir = System.getProperty("user.dir");
        Path tempRoot = Files.createTempDirectory("snap-guard-");
        try {
            // 快照根目录 = {user.dir}/snapshots，改向临时目录做隔离
            System.setProperty("user.dir", tempRoot.toString());

            SnapshotService svc = new SnapshotService();
            svc.init();

            // ---------- 准备素材 ----------
            Path dirPath = Files.createDirectories(tempRoot.resolve("sso-package-dir"));
            Files.writeString(dirPath.resolve("Inner.java"), "class Inner { }\n");
            Path filePath = tempRoot.resolve("Sample.java");
            Files.writeString(filePath, "class Sample { }\n");
            Path snapshotsDir = tempRoot.resolve("snapshots");

            // ---------- 用例 1：目录不得建快照 ----------
            SnapshotService.SnapshotSummary dirResult =
                    svc.createSnapshot("turn-dir", 1L, dirPath.toString());
            assertTrue("用例1 目录路径返回 null（不建快照）",
                    dirResult == null, "actual=" + dirResult);

            // ---------- 用例 2：目录操作不留快照子目录、不写索引 ----------
            /*
              注意口径：init() 会创建 snapshots/index.json（索引文件本身），它不属于「快照条目」。
              真正的契约是两件事：
                2a) snapshots/ 下不得出现任何快照子目录；
                2b) 索引里不得出现该 session 的快照记录。
              早期版本这里直接数 snapshots/ 下的条目数，把 index.json 也算进去，导致误报 ——
              属于断言口径错误，不是被测代码缺陷。
             */
            long snapshotDirCount = countSnapshotDirs(snapshotsDir);
            assertTrue("用例2a 目录操作未在 snapshots/ 下留下快照子目录",
                    snapshotDirCount == 0, "dirs=" + snapshotDirCount);
            java.util.List<SnapshotService.SnapshotSummary> indexedEmpty = svc.listSnapshots(1L);
            assertTrue("用例2b 目录操作未写入索引（listSnapshots 为空）",
                    indexedEmpty.isEmpty(), "indexed=" + indexedEmpty.size());

            // ---------- 用例 3：普通文件正常建快照（防误伤） ----------
            SnapshotService.SnapshotSummary fileResult =
                    svc.createSnapshot("turn-file", 1L, filePath.toString());
            assertTrue("用例3 普通文件正常建快照",
                    fileResult != null, "actual=null");

            // ---------- 用例 4：computeDiffStats 传目录不抛异常 ----------
            boolean diffSafe = true;
            try {
                // 4a：turnId 无对应快照 → 直接返回
                svc.computeDiffStats("turn-dir", dirPath.toString());
                // 4b：turnId 有对应快照，但传入路径是目录 → 应跳过统计而非抛异常
                svc.computeDiffStats("turn-file", dirPath.toString());
                // 4c：正常文件路径 → 正常统计（不抛异常即可）
                svc.computeDiffStats("turn-file", filePath.toString());
            } catch (Exception e) {
                diffSafe = false;
                System.out.println("  computeDiffStats 抛异常: " + e);
            }
            assertTrue("用例4 computeDiffStats 传目录不抛异常", diffSafe, "");

            // ---------- 用例 5：目录内文件不被当成单个文件快照 ----------
            Path nestedFile = dirPath.resolve("Inner.java");
            SnapshotService.SnapshotSummary nestedResult =
                    svc.createSnapshot("turn-nested", 1L, nestedFile.toString());
            assertTrue("用例5 目录内的普通文件仍可正常建快照（守卫只拦目录本身）",
                    nestedResult != null, "actual=null");

        } finally {
            System.setProperty("user.dir", originalUserDir);
            deleteRecursively(tempRoot);
        }

        System.out.printf("%n=== 自检结果: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 统计 snapshots 目录下的**快照子目录**数量（目录不存在视为 0）。
     *
     * <p>刻意只数目录、不数文件：{@code init()} 会创建 {@code index.json}，
     * 它是索引文件而非快照条目，混在一起数会造成假阳性。</p>
     */
    private static long countSnapshotDirs(Path snapshotsDir) throws Exception {
        if (!Files.isDirectory(snapshotsDir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(snapshotsDir)) {
            return s.filter(Files::isDirectory).count();
        }
    }

    /** 递归删除临时目录（不跟随符号链接，避免误删外部目标） */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响自检结论
                }
            });
        } catch (Exception ignored) {
            // 清理失败不影响自检结论
        }
    }

    private static void assertTrue(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + "  " + detail);
        }
    }
}
