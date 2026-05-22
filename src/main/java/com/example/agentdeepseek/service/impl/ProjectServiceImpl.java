package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.model.vo.DirectoryEntry;
import com.example.agentdeepseek.model.vo.ProjectTreeNode;
import com.example.agentdeepseek.service.ProjectService;
import com.example.agentdeepseek.util.FileEncodingDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
    public List<DirectoryEntry> listChildren(String parentPath) {
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
            if (f.isDirectory() && shouldInclude(f, parentPath)) {
                String childPath = f.getAbsolutePath().replace('\\', '/');
                result.add(new DirectoryEntry(f.getName(), childPath));
            }
        }
        // 按名称排序
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        log.debug("列出目录子项 {}: {}", parentPath, result.size());
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
                // 相对路径，基于项目根目录
                path = Paths.get(System.getProperty("user.dir"), filePath);
            }
            log.debug("读取文件: {}", path);
            return FileEncodingDetector.readString(path);
        } catch (java.io.IOException e) {
            log.error("读取文件失败: {}", filePath, e);
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
                path = Paths.get(System.getProperty("user.dir"), filePath);
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
