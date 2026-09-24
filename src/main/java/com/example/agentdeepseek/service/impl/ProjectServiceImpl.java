package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.model.vo.DirectoryEntry;
import com.example.agentdeepseek.model.vo.ProjectTreeNode;
import com.example.agentdeepseek.service.ProjectService;
import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 项目文件树服务实现
 */
@Slf4j
@Service
public class ProjectServiceImpl implements ProjectService {

    /** 需要跳过的目录/文件名（大小写不敏感） */
    private static final Set<String> EXCLUDED_NAMES = Set.of(
        ".git", "node_modules", "target", ".mvn", "dist",
        "build", ".idea", ".vscode", ".settings", ".classpath",
        ".project", ".DS_Store", "Thumbs.db"
    );

    /** 需要跳过的路径前缀 */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
        "src/main/resources/static"
    );

    @Override
    public ProjectTreeNode getProjectTree(String rootPath, int depth) {
        if (rootPath == null || rootPath.trim().isEmpty()) {
            rootPath = System.getProperty("user.dir");
        }
        // 标准化路径分隔符
        String root = rootPath.replace('\\', '/');
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        log.debug("扫描项目文件树，根目录: {}, 深度: {}", root, depth);
        return buildNode(new File(root), root, depth, 0);
    }

    /**
     * 递归构建树节点
     */
    private ProjectTreeNode buildNode(File file, String rootPath, int maxDepth, int currentDepth) {
        String relPath = getRelativePath(file, rootPath);
        ProjectTreeNode node = new ProjectTreeNode();
        node.setName(file.getName());
        node.setPath(relPath);
        node.setDirectory(file.isDirectory());

        if (file.isDirectory() && currentDepth < maxDepth) {
            File[] children = file.listFiles();
            if (children != null) {
                // 排序：目录在前，按名称排序
                Arrays.sort(children, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                });

                List<ProjectTreeNode> childNodes = new ArrayList<>();
                for (File child : children) {
                    if (shouldInclude(child, rootPath)) {
                        childNodes.add(buildNode(child, rootPath, maxDepth, currentDepth + 1));
                    }
                }
                if (!childNodes.isEmpty()) {
                    node.setChildren(childNodes);
                }
            }
        }

        return node;
    }

    /**
     * 判断文件/目录是否应该包含在树中
     */
    private boolean shouldInclude(File file, String rootPath) {
        String name = file.getName();
        // 排除黑名单名称（如 .git、node_modules 等）
        if (EXCLUDED_NAMES.contains(name)) {
            return false;
        }
        // 排除黑名单路径前缀
        String relPath = getRelativePath(file, rootPath);
        for (String prefix : EXCLUDED_PREFIXES) {
            if (relPath.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<DirectoryEntry> listDrives() {
        File[] roots = File.listRoots();
        List<DirectoryEntry> result = new ArrayList<>();
        for (File root : roots) {
            String path = root.getAbsolutePath().replace('\\', '/');
            String name = path;
            if (path.endsWith("/")) {
                name = "本地磁盘 (" + path + ")";
            }
            result.add(new DirectoryEntry(name, path));
        }
        log.debug("列出系统盘符: {}", result);
        return result;
    }

    @Override
    public List<DirectoryEntry> listChildren(String parentPath, boolean includeFiles) {
        if (parentPath == null || parentPath.isEmpty()) {
            return List.of();
        }
        File parent = new File(parentPath);
        if (!parent.isDirectory()) {
            return List.of();
        }
        File[] files = parent.listFiles();
        if (files == null) {
            return List.of();
        }
        List<DirectoryEntry> result = new ArrayList<>();
        for (File f : files) {
            boolean isDir = f.isDirectory();
            // 目录：始终通过 shouldInclude 过滤
            // 文件：仅在 includeFiles=true 时包含，也通过 shouldInclude 过滤
            if (isDir && shouldInclude(f, parentPath)) {
                String childPath = f.getAbsolutePath().replace('\\', '/');
                result.add(new DirectoryEntry(f.getName(), childPath, true));
            } else if (includeFiles && !isDir && shouldInclude(f, parentPath)) {
                String childPath = f.getAbsolutePath().replace('\\', '/');
                result.add(new DirectoryEntry(f.getName(), childPath, false));
            }
        }
        // 排序：目录在前，再按名称
        result.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        log.debug("列出目录子项 {}: {} (includeFiles={})", parentPath, result.size(), includeFiles);
        return result;
    }

    @Override
    public String readFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        try {
            // 如果是绝对路径，直接读取
            Path path = Paths.get(filePath);
            if (!path.isAbsolute()) {
                // 相对路径，基于项目根目录（优先使用 ProjectRootContext，与工具层保持一致）
                path = Paths.get(ProjectRootContext.get(), filePath);
            }
            log.debug("读取文件: {}", path);
            return FileEncodingDetector.readString(path);
        } catch (java.io.IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] readFileBytes(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        try {
            Path path = Paths.get(filePath);
            if (!path.isAbsolute()) {
                path = Paths.get(ProjectRootContext.get(), filePath);
            }
            log.debug("读取文件字节: {}", path);
            return Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            log.error("读取文件字节失败: {}", filePath, e);
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }
    }

    @Override
    public void writeFile(String filePath, String content) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        try {
            Path path = Paths.get(filePath);
            if (!path.isAbsolute()) {
                path = Paths.get(ProjectRootContext.get(), filePath);
            }
            log.debug("写入文件: {}", path);
            // 确保父目录存在
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            log.error("写入文件失败: {}", filePath, e);
            throw new RuntimeException("写入文件失败: " + e.getMessage());
        }
    }

    /** resolveFilePath 候选数量上限（防御性：避免超大项目返回巨大候选列表） */
    private static final int RESOLVE_MAX_CANDIDATES = 50;

    @Override
    public List<String> resolveFilePath(String rootPath, String partialPath) {
        if (partialPath == null || partialPath.trim().isEmpty()) {
            return List.of();
        }
        // 归一化待解析路径：统一斜杠、去除前导 ./
        String partial = partialPath.trim().replace('\\', '/');
        while (partial.startsWith("./")) {
            partial = partial.substring(2);
        }
        if (partial.isEmpty()) {
            return List.of();
        }
        // 仅解析相对路径：绝对路径没有"后缀歧义"可解析
        if (partial.startsWith("/") || partial.matches("^[A-Za-z]:/.*")) {
            return List.of();
        }
        final String target = partial;

        String root = (rootPath == null || rootPath.trim().isEmpty())
                ? ProjectRootContext.get()
                : rootPath;
        root = root.replace('\\', '/');
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        Path rootDir = Paths.get(root);
        if (!Files.isDirectory(rootDir)) {
            return List.of();
        }

        List<String> exact = new ArrayList<>();
        List<String> suffix = new ArrayList<>();
        try {
            Files.walkFileTree(rootDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(rootDir)) {
                        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        // 排除黑名单目录（.git、node_modules、target、dist 等），整棵子树跳过
                        if (EXCLUDED_NAMES.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        String rel = rootDir.relativize(dir).toString().replace('\\', '/');
                        for (String prefix : EXCLUDED_PREFIXES) {
                            if (rel.startsWith(prefix)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (exact.size() + suffix.size() >= RESOLVE_MAX_CANDIDATES) {
                        return FileVisitResult.TERMINATE;
                    }
                    String rel = rootDir.relativize(file).toString().replace('\\', '/');
                    if (rel.equals(target)) {
                        exact.add(rel);
                    } else if (rel.endsWith("/" + target)) {
                        suffix.add(rel);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // 个别文件不可访问不影响整体解析
                }
            });
        } catch (IOException e) {
            log.warn("解析文件路径失败: root={}, partial={}", root, target, e);
            return List.of();
        }

        suffix.sort(Comparator.naturalOrder());
        List<String> result = new ArrayList<>(exact);
        result.addAll(suffix);
        if (result.size() > RESOLVE_MAX_CANDIDATES) {
            result = result.subList(0, RESOLVE_MAX_CANDIDATES);
        }
        log.debug("路径解析: partial={}, 候选数量={}", target, result.size());
        return result;
    }

    /**
     * 获取文件相对于项目根目录的路径
     */
    private String getRelativePath(File file, String rootPath) {
        String absPath = file.getAbsolutePath().replace('\\', '/');
        if (!absPath.startsWith(rootPath)) {
            log.warn("文件路径不在项目根目录下: absPath={}, root={}", absPath, rootPath);
            return absPath;
        }
        String relPath = absPath.substring(rootPath.length());
        if (relPath.startsWith("/")) {
            relPath = relPath.substring(1);
        }
        return relPath.isEmpty() ? "." : relPath;
    }
}
