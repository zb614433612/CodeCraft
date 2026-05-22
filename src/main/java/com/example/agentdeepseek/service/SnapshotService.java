package com.example.agentdeepseek.service;

import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 代码快照服务
 * 在文件被修改前备份原始内容，支持按对话消息回滚
 */
@Slf4j
@Service
public class SnapshotService {

    private static final long MAX_SNAPSHOT_SIZE = 500L * 1024 * 1024; // 500MB
    private static final long TARGET_SNAPSHOT_SIZE = 300L * 1024 * 1024; // 300MB
    private static final String SNAPSHOTS_DIR = "snapshots";
    private static final String INDEX_FILE = ".snapshots_index.json";
    private static final String METADATA_FILE = "snapshot.json";
    private static final String FILES_DIR = "files";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // 确保同一 turn 内多次文件修改使用同一个 snapshotId
    private final ConcurrentHashMap<String, String> turnIdToSnapshotId = new ConcurrentHashMap<>();

    // ==== 数据类 ====

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SnapshotIndex {
        private long totalSize = 0;
        private List<SnapshotEntry> snapshots = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SnapshotEntry {
        private String snapshotId;
        private String turnId;
        private Long sessionId;
        private String timestamp;
        private long totalSize;
        private int fileCount;
        private boolean rolledBack;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SnapshotMetadata {
        private String snapshotId;
        private String turnId;
        private Long sessionId;
        private String timestamp;
        /** 创建快照时的项目根目录（用于回滚时准确定位目标文件） */
        private String projectRoot;
        private List<FileEntry> files = new ArrayList<>();
        private boolean rolledBack;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileEntry {
        private String relativePath;
        private long originalSize;
        private boolean wasNewFile;
        /** 新增行数（快照后由 computeDiffStats 计算填充） */
        private int linesAdded;
        /** 删除行数 */
        private int linesDeleted;
        /** 是否已回滚（单个文件回滚时标记） */
        private boolean rolledBack;
    }

    @Data
    public static class SessionChanges {
        private int totalFiles;
        private int totalLinesAdded;
        private int totalLinesDeleted;
        private List<SessionFileChange> files = new ArrayList<>();
    }

    @Data
    public static class SessionFileChange {
        private String relativePath;
        private int linesAdded;
        private int linesDeleted;
        private String snapshotId;
        private String timestamp;
        private boolean wasNewFile;
        private boolean rolledBack;
    }

    @Data
    public static class SnapshotSummary {
        private String snapshotId;
        private String turnId;
        private Long sessionId;
        private String timestamp;
        private int fileCount;
        private long totalSize;
        private boolean rolledBack;
    }

    @Data
    public static class RollbackPreview {
        private String snapshotId;
        private String timestamp;
        private List<RollbackFileInfo> files = new ArrayList<>();
    }

    @Data
    public static class RollbackFileInfo {
        private String relativePath;
        private String action; // "restore" 或 "delete"
    }

    // ==== 路径辅助 ====

    private Path getBasePath() {
        return Path.of(System.getProperty("user.dir"));
    }

    /**
     * 获取用户当前操作的项目根目录（来自 ProjectRootContext）
     * 与 getBasePath() 不同，项目可能位于与应用程序不同的盘符
     */
    private Path getProjectRootPath() {
        return Path.of(ProjectRootContext.get()).normalize().toAbsolutePath();
    }

    private Path getSnapshotsDir() {
        return getBasePath().resolve(SNAPSHOTS_DIR);
    }

    private Path getIndexFile() {
        return getSnapshotsDir().resolve(INDEX_FILE);
    }

    private Path getSnapshotDir(String snapshotId) {
        return getSnapshotsDir().resolve(snapshotId);
    }

    private Path getMetadataFile(String snapshotId) {
        return getSnapshotDir(snapshotId).resolve(METADATA_FILE);
    }

    private Path getSnapshotFilesDir(String snapshotId) {
        return getSnapshotDir(snapshotId).resolve(FILES_DIR);
    }

    // ==== 初始化 ====

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(getSnapshotsDir());
            if (!Files.exists(getIndexFile())) {
                saveIndex(new SnapshotIndex());
                log.info("快照目录已初始化: {}", getSnapshotsDir().normalize());
            }
        } catch (IOException e) {
            log.error("初始化快照目录失败: {}", getSnapshotsDir(), e);
        }
    }

    // ==== 核心 API ====

    /**
     * 在文件修改前创建快照
     * 同一 turn 内每个文件只备份一次（首次修改前的状态）
     *
     * @param turnId          用户消息的 turnId（前端生成）
     * @param sessionId       会话ID
     * @param absoluteFilePath 要修改的文件的绝对路径
     * @return 快照摘要，null 表示无需创建
     */
    public synchronized SnapshotSummary createSnapshot(String turnId, Long sessionId, String absoluteFilePath) {
        if (turnId == null || sessionId == null || absoluteFilePath == null) {
            log.warn("createSnapshot 参数为空: turnId={}, sessionId={}, absoluteFilePath={}", turnId, sessionId, absoluteFilePath);
            return null;
        }

        Path absPath = Path.of(absoluteFilePath).normalize();
        Path projectRootPath = getProjectRootPath();
        String relativePath;
        try {
            relativePath = projectRootPath.relativize(absPath).toString().replace('\\', '/');
        } catch (Exception e) {
            log.warn("文件不在项目目录内，跳过快照: {}", absoluteFilePath);
            return null;
        }

        String snapshotId = getOrCreateSnapshotId(turnId);
        SnapshotMetadata metadata = readMetadata(snapshotId);

        try {
            if (metadata == null) {
                // 当前 turn 首次创建快照
                metadata = new SnapshotMetadata();
                metadata.setSnapshotId(snapshotId);
                metadata.setTurnId(turnId);
                metadata.setSessionId(sessionId);
                metadata.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                metadata.setProjectRoot(ProjectRootContext.get());
                Files.createDirectories(getSnapshotFilesDir(snapshotId));
            }

            // 检查是否已备份过该文件
            boolean alreadyBackedUp = metadata.getFiles().stream()
                    .anyMatch(f -> f.getRelativePath().equals(relativePath));
            if (alreadyBackedUp) {
                log.debug("文件已在本次对话中备份，跳过: {}", relativePath);
                return toSummary(metadata);
            }

            // 备份文件
            FileEntry entry = new FileEntry();
            entry.setRelativePath(relativePath);

            if (Files.exists(absPath)) {
                entry.setOriginalSize(Files.size(absPath));
                entry.setWasNewFile(false);
                Path targetPath = getSnapshotFilesDir(snapshotId).resolve(relativePath);
                Files.createDirectories(targetPath.getParent());
                Files.copy(absPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.debug("已备份文件: {} ({} bytes)", relativePath, entry.getOriginalSize());
            } else {
                // 文件尚不存在（即将由工具创建），记录为"新文件"
                entry.setOriginalSize(0);
                entry.setWasNewFile(true);
                log.debug("文件尚不存在，标记为新文件: {}", relativePath);
            }

            metadata.getFiles().add(entry);

            Path metaFile = getMetadataFile(snapshotId);
            Files.createDirectories(metaFile.getParent());
            objectMapper.writeValue(metaFile.toFile(), metadata);

            // 更新索引
            long totalSize = metadata.getFiles().stream().mapToLong(FileEntry::getOriginalSize).sum();
            updateIndex(snapshotId, turnId, sessionId, metadata.getTimestamp(),
                    metadata.getFiles().size(), totalSize);

            return toSummary(metadata);

        } catch (IOException e) {
            log.error("创建快照失败: file={}", absoluteFilePath, e);
            return null;
        }
    }

    /**
     * 列出指定会话的所有快照（按时间升序）
     */
    public List<SnapshotSummary> listSnapshots(Long sessionId) {
        if (sessionId == null) return List.of();
        SnapshotIndex index = readIndex();
        if (index == null) return List.of();

        return index.getSnapshots().stream()
                .filter(e -> sessionId.equals(e.getSessionId()))
                .sorted(Comparator.comparing(SnapshotEntry::getTimestamp))
                .map(e -> {
                    SnapshotSummary s = new SnapshotSummary();
                    s.setSnapshotId(e.getSnapshotId());
                    s.setTurnId(e.getTurnId());
                    s.setSessionId(e.getSessionId());
                    s.setTimestamp(e.getTimestamp());
                    s.setFileCount(e.getFileCount());
                    s.setTotalSize(e.getTotalSize());
                    s.setRolledBack(e.isRolledBack());
                    return s;
                })
                .collect(Collectors.toList());
    }

    /**
     * 预览回滚内容
     */
    public RollbackPreview previewRollback(String snapshotId) {
        SnapshotMetadata meta = readMetadata(snapshotId);
        if (meta == null) return null;

        RollbackPreview preview = new RollbackPreview();
        preview.setSnapshotId(meta.getSnapshotId());
        preview.setTimestamp(meta.getTimestamp());

        for (FileEntry fe : meta.getFiles()) {
            RollbackFileInfo info = new RollbackFileInfo();
            info.setRelativePath(fe.getRelativePath());
            info.setAction(fe.isWasNewFile() ? "delete" : "restore");
            preview.getFiles().add(info);
        }
        return preview;
    }

    /**
     * 执行回滚，恢复快照中的所有文件
     */
    public synchronized boolean rollback(String snapshotId) {
        SnapshotMetadata meta = readMetadata(snapshotId);
        if (meta == null) {
            log.warn("快照不存在: {}", snapshotId);
            return false;
        }

        // 使用快照创建时的项目根目录来定位目标文件，确保跨盘符/换项目时回滚路径正确
        String rootStr = meta.getProjectRoot();
        if (rootStr == null || rootStr.isEmpty()) {
            rootStr = ProjectRootContext.get();
        }
        Path projectRootPath = Path.of(rootStr).normalize().toAbsolutePath();
        Path snapshotFilesDir = getSnapshotFilesDir(snapshotId);

        int restoreCount = 0;
        int deleteCount = 0;

        for (FileEntry fe : meta.getFiles()) {
            Path targetPath = projectRootPath.resolve(fe.getRelativePath());
            try {
                if (fe.isWasNewFile()) {
                    // 删除工具创建的文件
                    Files.deleteIfExists(targetPath);
                    deleteCount++;
                    log.info("回滚删除文件: {}", fe.getRelativePath());
                } else {
                    // 恢复原始文件
                    Path sourcePath = snapshotFilesDir.resolve(fe.getRelativePath());
                    if (Files.exists(sourcePath)) {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        restoreCount++;
                        log.info("回滚恢复文件: {}", fe.getRelativePath());
                    } else {
                        log.warn("快照中文件丢失，跳过: {}", sourcePath);
                    }
                }
                fe.setRolledBack(true);
            } catch (IOException e) {
                log.error("回滚文件失败: {}", fe.getRelativePath(), e);
            }
        }

        // 标记快照为已回滚
        meta.setRolledBack(true);
        try {
            objectMapper.writeValue(getMetadataFile(snapshotId).toFile(), meta);
        } catch (IOException e) {
            log.error("保存回滚标记失败: {}", snapshotId, e);
        }

        // 同步更新索引中的回滚状态
        markIndexRolledBack(snapshotId);

        log.info("回滚完成: snapshotId={}, restore={}, delete={}", snapshotId, restoreCount, deleteCount);
        return true;
    }

    // ==== 内部方法 ====

    private String getOrCreateSnapshotId(String turnId) {
        return turnIdToSnapshotId.computeIfAbsent(turnId, id -> {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String shortId = id.length() > 8 ? id.substring(0, 8) : id;
            return ts + "-" + shortId;
        });
    }

    private SnapshotIndex readIndex() {
        try {
            Path file = getIndexFile();
            if (Files.exists(file)) {
                return objectMapper.readValue(file.toFile(), SnapshotIndex.class);
            }
        } catch (IOException e) {
            log.warn("读取快照索引失败", e);
        }
        SnapshotIndex idx = new SnapshotIndex();
        return idx;
    }

    private synchronized void saveIndex(SnapshotIndex index) {
        try {
            Files.createDirectories(getSnapshotsDir());
            objectMapper.writeValue(getIndexFile().toFile(), index);
        } catch (IOException e) {
            log.error("保存快照索引失败", e);
        }
    }

    private synchronized void updateIndex(String snapshotId, String turnId, Long sessionId,
                                          String timestamp, int fileCount, long totalSize) {
        SnapshotIndex index = readIndex();

        // 移除旧条目（更新场景）
        index.getSnapshots().removeIf(e -> e.getSnapshotId().equals(snapshotId));

        SnapshotEntry entry = new SnapshotEntry();
        entry.setSnapshotId(snapshotId);
        entry.setTurnId(turnId);
        entry.setSessionId(sessionId);
        entry.setTimestamp(timestamp);
        entry.setFileCount(fileCount);
        entry.setTotalSize(totalSize);
        entry.setRolledBack(false);
        index.getSnapshots().add(entry);

        index.setTotalSize(index.getSnapshots().stream().mapToLong(SnapshotEntry::getTotalSize).sum());
        saveIndex(index);

        enforceQuota(index);
    }

    /**
     * 更新索引中指定快照的回滚状态
     */
    private synchronized void markIndexRolledBack(String snapshotId) {
        SnapshotIndex index = readIndex();
        if (index == null) return;
        for (SnapshotEntry entry : index.getSnapshots()) {
            if (entry.getSnapshotId().equals(snapshotId)) {
                entry.setRolledBack(true);
                saveIndex(index);
                log.debug("索引回滚状态已更新: {}", snapshotId);
                return;
            }
        }
    }

    private SnapshotMetadata readMetadata(String snapshotId) {
        try {
            Path file = getMetadataFile(snapshotId);
            if (Files.exists(file)) {
                return objectMapper.readValue(file.toFile(), SnapshotMetadata.class);
            }
        } catch (IOException e) {
            log.warn("读取快照元数据失败: {}", snapshotId, e);
        }
        return null;
    }

    private SnapshotSummary toSummary(SnapshotMetadata meta) {
        SnapshotSummary s = new SnapshotSummary();
        s.setSnapshotId(meta.getSnapshotId());
        s.setTurnId(meta.getTurnId());
        s.setSessionId(meta.getSessionId());
        s.setTimestamp(meta.getTimestamp());
        s.setFileCount(meta.getFiles().size());
        s.setTotalSize(meta.getFiles().stream().mapToLong(FileEntry::getOriginalSize).sum());
        s.setRolledBack(meta.isRolledBack());
        return s;
    }

    /**
     * 在文件修改工具执行后调用，计算此行改动前后的 diff 统计
     *
     * @param turnId           用户消息的 turnId
     * @param absoluteFilePath 被修改文件的绝对路径
     */
    public synchronized void computeDiffStats(String turnId, String absoluteFilePath) {
        if (turnId == null || absoluteFilePath == null) return;

        String snapshotId = turnIdToSnapshotId.get(turnId);
        if (snapshotId == null) {
            log.debug("computeDiffStats: turnId 无对应快照: {}", turnId);
            return;
        }

        SnapshotMetadata meta = readMetadata(snapshotId);
        if (meta == null) return;

        Path absPath = Path.of(absoluteFilePath).normalize();
        Path projectRootPath = getProjectRootPath();
        String relativePath;
        try {
            relativePath = projectRootPath.relativize(absPath).toString().replace('\\', '/');
        } catch (Exception e) {
            log.warn("computeDiffStats: 无法计算相对路径: {}", absoluteFilePath);
            return;
        }

        FileEntry entry = meta.getFiles().stream()
                .filter(f -> f.getRelativePath().equals(relativePath))
                .findFirst().orElse(null);
        if (entry == null) return;

        try {
            Path backupPath = getSnapshotFilesDir(snapshotId).resolve(relativePath);
            if (entry.isWasNewFile()) {
                // 新建文件：全部新增行
                if (Files.exists(absPath)) {
                    String content = FileEncodingDetector.readString(absPath);
                    entry.setLinesAdded(countLines(content));
                    entry.setLinesDeleted(0);
                }
            } else if (Files.exists(backupPath)) {
                // 修改已有文件：计算 diff
                String oldContent = FileEncodingDetector.readString(backupPath);
                if (Files.exists(absPath)) {
                    String newContent = FileEncodingDetector.readString(absPath);
                    int[] stats = computeLineDiff(oldContent, newContent);
                    entry.setLinesAdded(stats[0]);
                    entry.setLinesDeleted(stats[1]);
                } else {
                    // 文件被删除：全部行算删除
                    entry.setLinesAdded(0);
                    entry.setLinesDeleted(countLines(oldContent));
                }
            }

            // 持久化更新
            objectMapper.writeValue(getMetadataFile(snapshotId).toFile(), meta);
            log.debug("diff 统计已更新: {} +{} -{}", relativePath, entry.getLinesAdded(), entry.getLinesDeleted());
        } catch (IOException e) {
            log.error("computeDiffStats 失败: file={}", absoluteFilePath, e);
        }
    }

    /**
     * 获取指定会话的所有文件改动汇总
     */
    public SessionChanges getSessionChanges(Long sessionId) {
        SessionChanges result = new SessionChanges();
        if (sessionId == null) return result;

        List<SnapshotSummary> summaries = listSnapshots(sessionId);
        if (summaries.isEmpty()) return result;

        Map<String, SessionFileChange> fileMap = new LinkedHashMap<>();
        // 按时间正序遍历，记录每条记录的改动以及最早的 snapshotId（用于回滚）
        for (SnapshotSummary ss : summaries) {
            SnapshotMetadata meta = readMetadata(ss.getSnapshotId());
            if (meta == null) continue;
            for (FileEntry fe : meta.getFiles()) {
                SessionFileChange change = fileMap.get(fe.getRelativePath());
                if (change == null) {
                    change = new SessionFileChange();
                    change.setRelativePath(fe.getRelativePath());
                    change.setSnapshotId(ss.getSnapshotId());
                    change.setTimestamp(ss.getTimestamp());
                    change.setWasNewFile(fe.isWasNewFile());
                    change.setRolledBack(fe.isRolledBack());
                    fileMap.put(fe.getRelativePath(), change);
                }
                // 累加改动行数
                change.setLinesAdded(change.getLinesAdded() + fe.getLinesAdded());
                change.setLinesDeleted(change.getLinesDeleted() + fe.getLinesDeleted());
                // 只要任意快照中该文件已回滚，就标记为已回滚
                if (fe.isRolledBack()) {
                    change.setRolledBack(true);
                }
            }
        }

        result.setFiles(new ArrayList<>(fileMap.values()));
        result.setTotalFiles(fileMap.size());
        result.setTotalLinesAdded(fileMap.values().stream().mapToInt(SessionFileChange::getLinesAdded).sum());
        result.setTotalLinesDeleted(fileMap.values().stream().mapToInt(SessionFileChange::getLinesDeleted).sum());
        return result;
    }

    /**
     * 回滚会话中指定文件的全部改动（恢复到该会话最早的快照版本）
     */
    public synchronized boolean rollbackFile(Long sessionId, String relativePath) {
        if (sessionId == null || relativePath == null) return false;

        List<SnapshotSummary> summaries = listSnapshots(sessionId);
        // 找到包含此文件的最早快照用于实际恢复
        SnapshotSummary earliest = null;
        FileEntry earliestEntry = null;
        for (SnapshotSummary ss : summaries) {
            SnapshotMetadata meta = readMetadata(ss.getSnapshotId());
            if (meta == null) continue;
            for (FileEntry fe : meta.getFiles()) {
                if (fe.getRelativePath().equals(relativePath)) {
                    if (earliest == null) {
                        earliest = ss;
                        earliestEntry = fe;
                    }
                    break;
                }
            }
        }
        if (earliest == null || earliestEntry == null) {
            log.warn("rollbackFile: 未找到文件 {} 的快照", relativePath);
            return false;
        }

        // 使用最早快照创建时的项目根目录来定位目标文件
        SnapshotMetadata earliestMeta = readMetadata(earliest.getSnapshotId());
        String rootStr = earliestMeta != null ? earliestMeta.getProjectRoot() : null;
        if (rootStr == null || rootStr.isEmpty()) {
            rootStr = ProjectRootContext.get();
        }
        Path projectRootPath = Path.of(rootStr).normalize().toAbsolutePath();
        Path snapshotFilesDir = getSnapshotFilesDir(earliest.getSnapshotId());

        try {
            if (earliestEntry.isWasNewFile()) {
                Path targetPath = projectRootPath.resolve(relativePath);
                Files.deleteIfExists(targetPath);
                log.info("回滚删除文件: {}", relativePath);
            } else {
                Path sourcePath = snapshotFilesDir.resolve(relativePath);
                if (Files.exists(sourcePath)) {
                    Path targetPath = projectRootPath.resolve(relativePath);
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("回滚恢复文件: {}", relativePath);
                } else {
                    log.warn("回滚跳过（快照文件丢失）: {}", sourcePath);
                    return false;
                }
            }

            // 标记所有快照中该文件为已回滚
            for (SnapshotSummary ss : summaries) {
                SnapshotMetadata meta = readMetadata(ss.getSnapshotId());
                if (meta == null) continue;
                boolean changed = false;
                for (FileEntry fe : meta.getFiles()) {
                    if (fe.getRelativePath().equals(relativePath) && !fe.isRolledBack()) {
                        fe.setRolledBack(true);
                        changed = true;
                    }
                }
                if (changed) {
                    objectMapper.writeValue(getMetadataFile(ss.getSnapshotId()).toFile(), meta);
                }
            }

            return true;
        } catch (IOException e) {
            log.error("rollbackFile 失败: {}", relativePath, e);
            return false;
        }
    }

    /**
     * 回滚指定会话的全部文件改动
     */
    public synchronized boolean rollbackSession(Long sessionId) {
        if (sessionId == null) return false;

        List<SnapshotSummary> summaries = listSnapshots(sessionId);
        if (summaries.isEmpty()) return false;

        boolean allSuccess = true;
        // 收集所有不同的文件路径
        Set<String> seenFiles = new LinkedHashSet<>();
        for (SnapshotSummary ss : summaries) {
            SnapshotMetadata meta = readMetadata(ss.getSnapshotId());
            if (meta == null) continue;
            for (FileEntry fe : meta.getFiles()) {
                if (!seenFiles.contains(fe.getRelativePath())) {
                    seenFiles.add(fe.getRelativePath());
                    if (!rollbackFile(sessionId, fe.getRelativePath())) {
                        allSuccess = false;
                    }
                }
            }
        }

        log.info("回滚会话完成: sessionId={}, files={}, allSuccess={}", sessionId, seenFiles.size(), allSuccess);
        return allSuccess;
    }

    // ==== diff 工具方法 ====

    /**
     * 简单的行级 diff 统计：返回 [addedLines, deletedLines]
     * 小文件使用 LCS，大文件使用快速近似算法
     */
    static int[] computeLineDiff(String oldText, String newText) {
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);

        int m = oldLines.length;
        int n = newLines.length;

        // 超大文件（>10000 行）直接返回总行数差作为近似值
        if (m > 10000 || n > 10000) {
            int added = Math.max(0, n - m);
            int deleted = Math.max(0, m - n);
            return new int[]{added, deleted};
        }

        // LCS 最长公共子序列，使用 2 行滚动数组优化内存
        int[][] dp = new int[2][n + 1];
        for (int i = 1; i <= m; i++) {
            int curr = i & 1;
            int prev = (i - 1) & 1;
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[curr][j] = dp[prev][j - 1] + 1;
                } else {
                    dp[curr][j] = Math.max(dp[prev][j], dp[curr][j - 1]);
                }
            }
        }

        int lcsLen = dp[m & 1][n];
        int added = n - lcsLen;
        int deleted = m - lcsLen;
        return new int[]{added, deleted};
    }

    private static int countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.split("\n", -1).length;
    }

    /**
     * 快照配额管理：超过 500MB 时删除最旧快照，直到低于 300MB
     */
    private synchronized void enforceQuota(SnapshotIndex index) {
        if (index.getTotalSize() <= MAX_SNAPSHOT_SIZE) return;

        log.warn("快照空间超限 ({} / {}), 开始清理",
                formatSize(index.getTotalSize()), formatSize(MAX_SNAPSHOT_SIZE));

        List<SnapshotEntry> sorted = index.getSnapshots().stream()
                .sorted(Comparator.comparing(SnapshotEntry::getTimestamp))
                .collect(Collectors.toList());

        List<String> removed = new ArrayList<>();
        for (SnapshotEntry entry : sorted) {
            if (index.getTotalSize() <= TARGET_SNAPSHOT_SIZE) break;

            try {
                Path dir = getSnapshotDir(entry.getSnapshotId());
                if (Files.exists(dir)) {
                    deleteDirectory(dir);
                }
            } catch (IOException e) {
                log.warn("删除快照目录失败: {}", entry.getSnapshotId(), e);
            }

            index.setTotalSize(index.getTotalSize() - entry.getTotalSize());
            removed.add(entry.getSnapshotId());
        }

        index.getSnapshots().removeIf(e -> removed.contains(e.getSnapshotId()));
        saveIndex(index);

        log.info("快照清理完成: 删除 {} 个, 当前大小 {}", removed.size(), formatSize(index.getTotalSize()));
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
