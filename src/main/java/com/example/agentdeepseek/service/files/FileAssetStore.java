package com.example.agentdeepseek.service.files;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 文件资产本地副本存储
 * <p>
 * 目录：{@code data/file-assets/}（可配置 {@code file-asset.store.dir}）；
 * 存储文件名 {@code <uuid>.<ext>}；file_asset.local_path 列存该文件名，由本组件负责目录拼接
 * （与存储目录解耦：迁移目录仅需改配置）。
 * </p>
 * <p>
 * 注意：保存采用流拷贝（而非 {@link MultipartFile#transferTo}），
 * 避免部分容器下 transferTo 移动临时文件导致 MultipartFile 二次读取失败。
 * </p>
 */
@Slf4j
@Component
public class FileAssetStore {

    private final Path storeDir;

    public FileAssetStore(@Value("${file-asset.store.dir:data/file-assets}") String dirPath) {
        String resolved = (dirPath == null || dirPath.isBlank()) ? "data/file-assets" : dirPath;
        this.storeDir = Path.of(resolved).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storeDir);
            log.info("文件资产本地副本目录: {}", this.storeDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建文件资产目录: " + resolved, e);
        }
    }

    /**
     * 保存上传文件到本地副本目录（流拷贝）
     *
     * @param file      上传文件
     * @param extension 扩展名（含点，如 ".png"）
     * @return 存储文件名（{@code <uuid><ext>}）
     * @throws IOException 读取/写入失败
     */
    public String save(MultipartFile file, String extension) throws IOException {
        String storedName = UUID.randomUUID().toString().replace("-", "") + (extension == null ? "" : extension);
        Path target = resolve(storedName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return storedName;
    }

    /**
     * 保存字节数组到本地副本目录（截图工具等复用）
     *
     * @param bytes     文件字节
     * @param extension 扩展名（含点）
     * @return 存储文件名（{@code <uuid><ext>}）
     * @throws IOException 写入失败
     */
    public String saveBytes(byte[] bytes, String extension) throws IOException {
        String storedName = UUID.randomUUID().toString().replace("-", "") + (extension == null ? "" : extension);
        Path target = resolve(storedName);
        Files.write(target, bytes);
        return storedName;
    }

    /**
     * 解析存储文件名的完整路径（含目录逃逸防护）
     *
     * @param storedName 存储文件名
     * @return 完整路径；非法输入返回 null
     */
    public Path resolve(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return null;
        }
        Path path = storeDir.resolve(storedName).normalize();
        if (!path.startsWith(storeDir)) {
            log.warn("非法的存储文件名（目录逃逸）: {}", storedName);
            return null;
        }
        return path;
    }

    /**
     * 静默删除本地副本（不存在/失败均不抛异常）
     *
     * @param storedName 存储文件名
     */
    public void deleteQuietly(String storedName) {
        Path path = resolve(storedName);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除本地副本失败: {} - {}", storedName, e.getMessage());
        }
    }

    /** 存储目录（预览/排查用） */
    public Path getStoreDir() {
        return storeDir;
    }
}
