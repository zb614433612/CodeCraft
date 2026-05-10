package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 文件删除工具配置
 * 从 application.yml 的 delete-file 前缀读取
 */
@Configuration
@ConfigurationProperties(prefix = "delete-file")
@Data
public class DeleteFileConfig {

    /** 受保护目录列表（相对项目根目录），删除操作会拒绝删除这些目录及其子文件 */
    private List<String> protectedDirectories = Arrays.asList(".git", "node_modules");

    /** 目录删除前警告阈值，超过此文件数会要求用户二次确认 */
    private int deleteWarningThreshold = 50;
}
