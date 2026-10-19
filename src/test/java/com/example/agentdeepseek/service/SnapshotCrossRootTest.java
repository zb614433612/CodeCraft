package com.example.agentdeepseek.service;

import com.example.agentdeepseek.util.ProjectRootContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 跨项目根 / 跨盘快照能力验证（main 方法式，与仓库内既有测试风格一致）。
 *
 * <p>背景：旧实现用 {@code projectRoot.relativize(absPath)} 算相对路径，文件不在项目根内时
 * 要么抛异常（跨盘）要么得到 {@code ../..}（同盘不同目录），两种情况都会放弃快照 ——
 * 表现为聊天里「所有轮次都没有回滚按钮」。本测试覆盖修复后的备份、diff、预览、回滚全链路。</p>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.SnapshotCrossRootTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class SnapshotCrossRootTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tempRoot = Files.createTempDirectory("cc-snap-test-");
        String originalUserDir = System.getProperty("user.dir");
        // 快照目录固定挂在 user.dir 下，改到临时目录避免污染真实快照库
        System.setProperty("user.dir", tempRoot.toString());

        try {
            Path projectRoot = tempRoot.resolve("proj");
            Files.createDirectories(projectRoot);
            // 同盘但不在项目根内（模拟「工作目录是子目录、实际改的是上级/旁支项目」）
            Path outsideFile = tempRoot.resolve("outside").resolve("demo.txt");
            Files.createDirectories(outsideFile.getParent());
            Files.writeString(outsideFile, "line1\nline2\nline3\n", StandardCharsets.UTF_8);

            SnapshotService service = new SnapshotService();
            service.init();

            // ---------- 1. 根外文件（同盘不同目录）能建快照 ----------
            ProjectRootContext.set(projectRoot.toString());
            SnapshotService.SnapshotSummary summary =
                    service.createSnapshot("turn-outside-001", 9001L, outsideFile.toString());
            check("createSnapshot(根外文件) 返回非 null", summary != null);
            check("fileCount = 1", summary != null && summary.getFileCount() == 1);
            String displayPath = summary == null ? "" : summary.getSnapshotId();
            check("snapshotId 非空", displayPath != null && !displayPath.isEmpty());

            SnapshotService.SnapshotMetadata meta = readMeta(tempRoot, summary);
            check("metadata 里记录了 absolutePath", meta != null
                    && meta.getFiles().get(0).getAbsolutePath() != null
                    && meta.getFiles().get(0).getAbsolutePath().endsWith("demo.txt"));
            check("relativePath 不是 ../ 逃逸路径", meta != null
                    && !meta.getFiles().get(0).getRelativePath().contains(".."));

            Path backup = findBackup(tempRoot, summary, meta.getFiles().get(0));
            check("备份文件落在快照目录内（未逃逸）", backup != null
                    && backup.normalize().startsWith(tempRoot.resolve("snapshots").normalize()));
            check("备份内容与原文件一致", backup != null
                    && Files.readString(backup).equals("line1\nline2\nline3\n"));

            // ---------- 2. diff 统计 ----------
            Files.writeString(outsideFile, "line1\nline2-changed\nline3\nline4\n", StandardCharsets.UTF_8);
            service.computeDiffStats("turn-outside-001", outsideFile.toString());
            meta = readMeta(tempRoot, summary);
            SnapshotService.FileEntry entry = meta.getFiles().get(0);
            check("diff 统计已写入（+行 > 0）", entry.getLinesAdded() > 0);
            check("diff 统计已写入（-行 > 0）", entry.getLinesDeleted() > 0);

            // ---------- 3. 预览 + 回滚 ----------
            SnapshotService.RollbackPreview preview = service.previewRollback(summary.getSnapshotId());
            check("preview 返回 1 个文件", preview != null && preview.getFiles().size() == 1);
            check("动作是 restore", preview != null && "restore".equals(preview.getFiles().get(0).getAction()));

            boolean rolled = service.rollback(summary.getSnapshotId());
            check("rollback 返回 true", rolled);
            check("文件内容已恢复", Files.readString(outsideFile).equals("line1\nline2\nline3\n"));

            // ---------- 4. 跨盘（不同盘符）能建快照 ----------
            Path otherDrive = Path.of("E:\\");
            if (Files.exists(otherDrive)) {
                ProjectRootContext.set("E:\\");
                Path crossFile = tempRoot.resolve("crossdrive.txt");
                Files.writeString(crossFile, "cross\n", StandardCharsets.UTF_8);
                SnapshotService.SnapshotSummary cross = service.createSnapshot("turn-cross-002", 9002L, crossFile.toString());
                check("createSnapshot(跨盘) 返回非 null", cross != null);
                SnapshotService.SnapshotMetadata crossMeta = readMeta(tempRoot, cross);
                check("跨盘文件备份存在", findBackup(tempRoot, cross, crossMeta.getFiles().get(0)) != null);
                check("跨盘回滚成功", service.rollback(cross.getSnapshotId())
                        && Files.readString(crossFile).equals("cross\n"));
            } else {
                check("跨盘分支跳过（无 E 盘）", true);
            }

            // ---------- 5. 新建文件的回滚（删除） ----------
            ProjectRootContext.set(projectRoot.toString());
            Path newFile = tempRoot.resolve("created-later.txt");
            SnapshotService.SnapshotSummary newSummary = service.createSnapshot("turn-new-003", 9003L, newFile.toString());
            Files.writeString(newFile, "created by tool\n", StandardCharsets.UTF_8);
            service.computeDiffStats("turn-new-003", newFile.toString());
            check("新文件快照标记 wasNewFile", readMeta(tempRoot, newSummary).getFiles().get(0).isWasNewFile());
            check("新文件回滚后被删除", service.rollback(newSummary.getSnapshotId()) && !Files.exists(newFile));

            // ---------- 6. rollbackFile / getSnapshotFileContent（用独立轮次，避免受上面 rolledBack 影响） ----------
            ProjectRootContext.set(projectRoot.toString());
            Files.writeString(outsideFile, "v3\n", StandardCharsets.UTF_8);
            SnapshotService.SnapshotSummary fileSummary =
                    service.createSnapshot("turn-file-004", 9004L, outsideFile.toString());
            Files.writeString(outsideFile, "v4\n", StandardCharsets.UTF_8);
            service.computeDiffStats("turn-file-004", outsideFile.toString());
            String fileRelativePath = readMeta(tempRoot, fileSummary).getFiles().get(0).getRelativePath();

            String content = service.getSnapshotFileContent(9004L, fileRelativePath);
            check("getSnapshotFileContent 能读到备份内容", content != null && content.startsWith("v3"));
            check("rollbackFile 按展示路径回滚成功",
                    service.rollbackFile(9004L, fileRelativePath)
                            && Files.readString(outsideFile).equals("v3\n"));

        } finally {
            ProjectRootContext.clear();
            System.setProperty("user.dir", originalUserDir);
            deleteRecursively(tempRoot);
        }

        System.out.println();
        System.out.println("结果: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static SnapshotService.SnapshotMetadata readMeta(Path tempRoot, SnapshotService.SnapshotSummary summary)
            throws IOException {
        Path metaFile = tempRoot.resolve("snapshots").resolve(summary.getSnapshotId()).resolve("snapshot.json");
        if (!Files.exists(metaFile)) return null;
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(metaFile.toFile(), SnapshotService.SnapshotMetadata.class);
    }

    /** 在快照目录里定位某文件的备份（不依赖具体编码规则，递归找文件名即可） */
    private static Path findBackup(Path tempRoot, SnapshotService.SnapshotSummary summary,
                                   SnapshotService.FileEntry entry) throws IOException {
        Path filesDir = tempRoot.resolve("snapshots").resolve(summary.getSnapshotId()).resolve("files");
        if (!Files.exists(filesDir)) return null;
        String fileName = Path.of(entry.getAbsolutePath()).getFileName().toString();
        try (var stream = Files.walk(filesDir)) {
            List<Path> hits = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .toList();
            return hits.isEmpty() ? null : hits.get(0);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 清理失败不影响测试结论
                }
            });
        } catch (IOException ignore) {
            // 同上
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
