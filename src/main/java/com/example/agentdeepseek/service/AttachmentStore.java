package com.example.agentdeepseek.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 上传附件暂存区
 * 用户上传的文件保存到临时目录，返回 attachment_id
 * LLM 调用 ChatAttachmentTool 时按 ID 取出路径进行解析
 */
@Slf4j
@Component
public class AttachmentStore {

    private final Path tempDir;
    private final long expireMinutes;

    /** 内存缓存：attachmentId → 附件元数据 */
    private final Cache<String, AttachmentMeta> cache;

    public AttachmentStore(
            @Value("${chat-attachment.store.temp-dir:#{systemProperties['java.io.tmpdir'] + '/codecraft-attachments'}}") String tempDirPath,
            @Value("${chat-attachment.store.expire-minutes:30}") long expireMinutes) {
        this.expireMinutes = expireMinutes;
        // 空字符串兜底：yml 配置了 temp-dir: "" 时 property 存在但值为空，SpEL 默认值不生效
        String resolvedPath = (tempDirPath == null || tempDirPath.isBlank())
                ? System.getProperty("java.io.tmpdir") + "/codecraft-attachments"
                : tempDirPath;
        this.tempDir = Path.of(resolvedPath);
        try {
            Files.createDirectories(this.tempDir);
            log.info("附件暂存目录: {}", this.tempDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("无法创建附件暂存目录: " + tempDirPath, e);
        }
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                .maximumSize(200)
                .build();
    }

    /**
     * 保存上传文件，返回 attachment_id
     */
    public String store(MultipartFile file) throws IOException {
        String attachmentId = UUID.randomUUID().toString().replace("-", "");
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot > 0) ext = originalName.substring(dot);
        }

        Path targetFile = tempDir.resolve(attachmentId + ext);
        file.transferTo(targetFile.toFile());

        AttachmentMeta meta = new AttachmentMeta();
        meta.setAttachmentId(attachmentId);
        meta.setFileName(originalName != null ? originalName : "unknown");
        meta.setExtension(ext.toLowerCase());
        meta.setSize(file.getSize());
        meta.setFilePath(targetFile);
        meta.setUploadTime(Instant.now());
        meta.setType(detectType(ext));

        cache.put(attachmentId, meta);
        log.info("附件已暂存: id={}, fileName={}, size={}, type={}", attachmentId, originalName, file.getSize(), meta.getType());
        return attachmentId;
    }

    /**
     * 按ID获取附件文件路径
     */
    public Path get(String attachmentId) {
        AttachmentMeta meta = cache.getIfPresent(attachmentId);
        if (meta == null) return null;
        if (!Files.exists(meta.getFilePath())) return null;
        return meta.getFilePath();
    }

    /**
     * 按ID获取附件元数据
     */
    public AttachmentMeta getMeta(String attachmentId) {
        return cache.getIfPresent(attachmentId);
    }

    /**
     * 定时清理过期文件
     */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanExpired() {
        try {
            long now = System.currentTimeMillis();
            Files.list(tempDir).forEach(path -> {
                try {
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    if (now - modified > expireMinutes * 60 * 1000) {
                        Files.deleteIfExists(path);
                        log.debug("清理过期附件: {}", path.getFileName());
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("清理附件目录失败", e);
        }
    }

    private String detectType(String ext) {
        return switch (ext) {
            case ".pdf" -> "pdf";
            case ".docx", ".doc" -> "word";
            case ".xlsx", ".xls", ".csv" -> "excel";
            case ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".ico", ".svg" -> "image";
            default -> "text";
        };
    }

    @Data
    public static class AttachmentMeta {
        private String attachmentId;
        private String fileName;
        private String extension;
        private long size;
        private Path filePath;
        private Instant uploadTime;
        /** 类型：text/pdf/word/excel/image */
        private String type;
    }
}
