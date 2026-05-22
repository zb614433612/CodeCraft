package com.example.agentdeepseek.service;

import com.example.agentdeepseek.util.FileEncodingDetector;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 附件文件读取服务
 * 支持文本文件、代码文件、图片文件的读取和内容提取
 */
@Slf4j
@Service
public class AttachmentReaderService {

    /** 允许上传的文件扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            // 纯文本 / 文档
            "txt", "md", "csv", "log",
            // 配置文件
            "json", "yml", "yaml", "xml", "properties", "cfg", "ini", "toml",
            // 前端代码
            "ts", "tsx", "vue", "js", "jsx", "css", "scss", "less", "html", "htm", "svg",
            // 后端代码
            "java", "kt", "groovy", "py", "go", "rb", "php", "rs", "swift",
            // 其他编程语言
            "c", "cpp", "h", "hpp", "sh", "bat", "ps1", "sql", "gradle", "m",
            // 数据交换格式
            "proto", "graphql", "dockerfile"
    ));

    /** 图片文件扩展名（仅记录元信息，不提取文本） */
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "svg"
    ));

    /** 最大文件大小：5MB */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /**
     * 读取上传的文件内容
     *
     * @param file 上传的文件
     * @return 读取结果
     */
    public AttachmentResult read(MultipartFile file) {
        AttachmentResult result = new AttachmentResult();
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "unknown";
        }
        result.setFileName(originalFilename);

        String ext = getExtension(originalFilename).toLowerCase();

        // 校验文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            result.setSuccess(false);
            result.setError("文件大小超过限制（最大 5MB），当前文件 " + (file.getSize() / 1024 / 1024) + "MB");
            return result;
        }

        result.setSize(file.getSize());
        result.setExtension(ext);

        // 校验扩展名
        if (!ALLOWED_EXTENSIONS.contains(ext) && !IMAGE_EXTENSIONS.contains(ext)) {
            result.setSuccess(false);
            result.setError("不支持的文件类型 ." + ext + "，允许的类型：文本文件、代码文件、图片文件");
            return result;
        }

        // 图片文件：仅记录元信息
        if (IMAGE_EXTENSIONS.contains(ext)) {
            result.setSuccess(true);
            result.setContent("");
            result.setImage(true);
            result.setLanguage("");
            return result;
        }

        // 文本/代码文件：读取内容
        try {
            // 读取原始字节并自动检测编码（支持 UTF-8/GB18030），防止中文乱码
            byte[] rawBytes = file.getBytes();
            Charset detectedCharset = FileEncodingDetector.detectCharset(rawBytes, rawBytes.length);
            String content = new String(rawBytes, detectedCharset);
            result.setSuccess(true);
            result.setContent(content);
            result.setImage(false);
            result.setLanguage(detectLanguage(ext));
        } catch (Exception e) {
            log.error("读取文件内容失败: {}", originalFilename, e);
            result.setSuccess(false);
            result.setError("读取文件内容失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 获取文件扩展名
     */
    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1);
    }

    /**
     * 根据扩展名检测编程语言（用于语法高亮）
     */
    private String detectLanguage(String ext) {
        return switch (ext) {
            case "java" -> "java";
            case "py" -> "python";
            case "ts", "tsx" -> "typescript";
            case "vue" -> "vue";
            case "js", "jsx" -> "javascript";
            case "go" -> "go";
            case "rs" -> "rust";
            case "kt" -> "kotlin";
            case "css", "scss", "less" -> "css";
            case "sql" -> "sql";
            case "xml", "html", "htm" -> "xml";
            case "json" -> "json";
            case "yml", "yaml" -> "yaml";
            case "sh", "bash" -> "bash";
            case "md" -> "markdown";
            case "properties" -> "properties";
            default -> "";
        };
    }

    @Data
    public static class AttachmentResult {
        /** 是否成功 */
        private boolean success;
        /** 文件名 */
        private String fileName;
        /** 文件扩展名 */
        private String extension;
        /** 文件大小（字节） */
        private long size;
        /** 文件文本内容（图片文件为空字符串） */
        private String content;
        /** 是否为图片文件 */
        private boolean image;
        /** 编程语言（语法高亮用） */
        private String language;
        /** 错误信息 */
        private String error;
    }
}
