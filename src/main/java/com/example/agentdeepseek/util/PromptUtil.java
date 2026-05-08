package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统提示词工具类
 * 从resources文件夹读取txt格式的提示词文件
 * 注意处理打包后生产环境文件读取路径问题
 */
@Slf4j
public class PromptUtil {

    // 缓存已加载的提示词，避免重复读取文件
    private static final Map<String, String> PROMPT_CACHE = new ConcurrentHashMap<>();

    // 提示词文件基础路径
    private static final String PROMPT_BASE_PATH = "prompts/";

    /**
     * 根据提示词文件名称获取系统提示词内容
     *
     * @param promptFileName 提示词文件名称（不带路径，例如：system_prompt.txt）
     * @return 提示词内容，如果文件不存在或读取失败返回空字符串
     */
    public static String getPrompt(String promptFileName) {
        if (promptFileName == null || promptFileName.trim().isEmpty()) {
            log.warn("提示词文件名称不能为空");
            return "";
        }

        // 检查缓存
        String cachedPrompt = PROMPT_CACHE.get(promptFileName);
        if (cachedPrompt != null) {
            return cachedPrompt;
        }

        // 构建完整资源路径
        String resourcePath = PROMPT_BASE_PATH + promptFileName;
        if (!resourcePath.endsWith(".txt")) {
            resourcePath += ".txt";
        }

        try {
            // 使用ClassPathResource读取资源文件，适用于开发环境和打包后环境
            Resource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("提示词文件不存在: {}", resourcePath);
                // 尝试直接使用文件名作为路径（兼容旧方式）
                resource = new ClassPathResource(promptFileName);
                if (!resource.exists()) {
                    log.error("提示词文件不存在: {}", promptFileName);
                    PROMPT_CACHE.put(promptFileName, ""); // 缓存空结果避免重复尝试
                    return "";
                }
            }

            // 读取文件内容
            String content;
            try (InputStream inputStream = resource.getInputStream()) {
                byte[] bytes = FileCopyUtils.copyToByteArray(inputStream);
                content = new String(bytes, StandardCharsets.UTF_8);
            }

            // 去除BOM等特殊字符
            content = content.replace("\uFEFF", "").trim();

            // 缓存结果
            PROMPT_CACHE.put(promptFileName, content);
            log.debug("成功加载提示词文件: {}, 内容长度: {}", promptFileName, content.length());
            return content;
        } catch (IOException e) {
            log.error("读取提示词文件失败: {}, 错误: {}", promptFileName, e.getMessage());
            PROMPT_CACHE.put(promptFileName, ""); // 缓存空结果避免重复尝试
            return "";
        }
    }

    /**
     * 清除缓存，用于开发环境热重载
     */
    public static void clearCache() {
        PROMPT_CACHE.clear();
        log.debug("提示词缓存已清除");
    }

    /**
     * 获取缓存中的提示词数量
     */
    public static int getCacheSize() {
        return PROMPT_CACHE.size();
    }

    /**
     * 检查提示词文件是否存在
     *
     * @param promptFileName 提示词文件名称
     * @return 是否存在
     */
    public static boolean exists(String promptFileName) {
        if (promptFileName == null || promptFileName.trim().isEmpty()) {
            return false;
        }

        String resourcePath = PROMPT_BASE_PATH + promptFileName;
        if (!resourcePath.endsWith(".txt")) {
            resourcePath += ".txt";
        }

        try {
            Resource resource = new ClassPathResource(resourcePath);
            return resource.exists();
        } catch (Exception e) {
            log.debug("检查提示词文件存在性失败: {}, 错误: {}", promptFileName, e.getMessage());
            return false;
        }
    }
}