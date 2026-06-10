package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天附件工具配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "chat-attachment")
public class ChatAttachmentConfig {

    /** 上传暂存配置 */
    private Store store = new Store();

    /** 解析限制配置 */
    private Parse parse = new Parse();

    @Data
    public static class Store {
        /** 临时目录路径 */
        private String tempDir;
        /** 过期时间（分钟） */
        private int expireMinutes = 30;
    }

    @Data
    public static class Parse {
        /** PDF 最大解析页数 */
        private int pdfMaxPages = 50;
        /** Excel 最大行数 */
        private int excelMaxRows = 500;
        /** 单文件最大输出字符数 */
        private int contentMaxLength = 30000;
    }
}
