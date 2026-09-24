package com.example.agentdeepseek.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/**
 * 头像本地存储
 * <p>
 * 目录：{@code data/avatars/}（可配置 {@code avatar.store.dir}）；存储文件名 {@code <uuid>.<ext>}；
 * 返回给前端的 URL 形如 {@code /api/avatar/file/<uuid>.<ext>}，由 {@code AvatarController} 直接流式返回。
 * </p>
 * <p>
 * 设计说明：头像为本地图片（不上传云端 Files API）。用户表/Agent 表的 avatar 列直接存该相对 URL；
 * 当 avatar 值不是本前缀 URL 时（如默认 emoji），前端按 emoji/文字渲染。
 * </p>
 */
@Slf4j
@Component
public class AvatarStore {

    /** 头像 URL 前缀（前端据此判定 avatar 是否为上传图片） */
    public static final String URL_PREFIX = "/api/avatar/file/";

    /** 单文件大小上限：5MB（头像） */
    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    /** 允许的图片扩展名 */
    private static final Set<String> ALLOWED_EXT = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");

    private final Path storeDir;

    public AvatarStore(@Value("${avatar.store.dir:data/avatars}") String dirPath) {
        String resolved = (dirPath == null || dirPath.isBlank()) ? "data/avatars" : dirPath;
        this.storeDir = Path.of(resolved).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storeDir);
            log.info("头像本地存储目录: {}", this.storeDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建头像存储目录: " + resolved, e);
        }
    }

    /**
     * 保存上传的头像文件，返回可访问的相对 URL。
     *
     * @param file 上传文件（multipart）
     * @return URL，形如 {@code /api/avatar/file/<uuid>.<ext>}
     */
    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("头像文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("头像文件不能超过 5MB");
        }

        String ext = resolveExtension(file);
        String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = storeDir.resolve(storedName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存头像失败: " + e.getMessage(), e);
        }
        log.info("头像已保存: {} ({} bytes)", storedName, file.getSize());
        return URL_PREFIX + storedName;
    }

    /**
     * 解析存储文件名的完整路径（含目录逃逸防护）。
     */
    public Path resolve(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return null;
        }
        Path path = storeDir.resolve(storedName).normalize();
        if (!path.startsWith(storeDir)) {
            log.warn("非法的头像文件名（目录逃逸）: {}", storedName);
            return null;
        }
        return path;
    }

    /**
     * 按 URL best-effort 删除本地头像文件（非本前缀 URL 静默忽略）。
     */
    public void deleteByUrl(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return;
        }
        String name = url.substring(URL_PREFIX.length());
        if (name.isBlank() || name.contains("/") || name.contains("..")) {
            return;
        }
        Path path = resolve(name);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
            log.info("已删除旧头像文件: {}", name);
        } catch (IOException e) {
            log.warn("删除旧头像文件失败: {} - {}", name, e.getMessage());
        }
    }

    /** 存储目录（排查用） */
    public Path getStoreDir() {
        return storeDir;
    }

    private String resolveExtension(MultipartFile file) {
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null) {
            int dot = original.lastIndexOf('.');
            if (dot >= 0) {
                ext = original.substring(dot).toLowerCase();
            }
        }
        if (ALLOWED_EXT.contains(ext)) {
            return ext;
        }
        // 文件名无有效扩展名时按 Content-Type 兜底推断
        String fromType = extFromContentType(file.getContentType());
        if (fromType != null) {
            return fromType;
        }
        throw new IllegalArgumentException("不支持的图片格式，仅支持 png/jpg/jpeg/gif/webp/bmp");
    }

    private String extFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String ct = contentType.toLowerCase();
        if (ct.contains("png")) return ".png";
        if (ct.contains("jpeg") || ct.contains("jpg")) return ".jpg";
        if (ct.contains("gif")) return ".gif";
        if (ct.contains("webp")) return ".webp";
        if (ct.contains("bmp")) return ".bmp";
        return null;
    }
}
