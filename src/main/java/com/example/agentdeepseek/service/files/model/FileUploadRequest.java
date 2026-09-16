package com.example.agentdeepseek.service.files.model;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 文件上传请求（本地侧输入）
 * <p>
 * 支持两种文件来源（二选一）：
 * <ul>
 *   <li>{@link MultipartFile}：HTTP 上传的暂存文件（M2 上传接口场景）</li>
 *   <li>{@link Path}：本地路径（工具 / 冒烟测试场景）</li>
 * </ul>
 * {@code expiresAfterSeconds} 为 0 时不上传 expires_after 字段（= 永久保存，当前业务默认）。
 */
@Data
public class FileUploadRequest {

    /** HTTP 上传的文件（与 localPath 二选一） */
    private MultipartFile file;

    /** 本地文件路径（与 file 二选一） */
    private Path localPath;

    /** 覆盖文件名（可空：由来源自动推导） */
    private String filename;

    /** 远端过期秒数（0 = 不传 expires_after，永久保存） */
    private long expiresAfterSeconds;

    /**
     * 创建基于 HTTP 上传文件的请求
     */
    public static FileUploadRequest of(MultipartFile file) {
        FileUploadRequest request = new FileUploadRequest();
        request.setFile(file);
        return request;
    }

    /**
     * 创建基于本地路径的请求
     */
    public static FileUploadRequest of(Path localPath) {
        FileUploadRequest request = new FileUploadRequest();
        request.setLocalPath(localPath);
        return request;
    }

    /**
     * 推导上传用文件名：显式 filename &gt; MultipartFile 原名 &gt; Path 文件名
     */
    public String resolveFilename() {
        if (filename != null && !filename.isBlank()) {
            return filename;
        }
        if (file != null && file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank()) {
            return file.getOriginalFilename();
        }
        if (localPath != null) {
            return localPath.getFileName().toString();
        }
        return "unnamed";
    }
}
