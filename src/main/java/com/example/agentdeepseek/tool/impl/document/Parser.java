package com.example.agentdeepseek.tool.impl.document;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 文档解析器接口
 * 每种文件格式（PDF/Word/Excel/图片/纯文本）各有一个实现
 */
public interface Parser {

    /**
     * 支持的扩展名集合，如 ["pdf"]、["docx", "doc"]、["png", "jpg"]
     */
    Set<String> supportedExtensions();

    /**
     * 是否支持该文件
     */
    default boolean supports(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) return false;
        String ext = fileName.substring(dotIndex + 1);
        return supportedExtensions().contains(ext);
    }

    /**
     * 核心解析方法
     * @param filePath 文件路径
     * @param options  可选参数（如PDF页码等）
     * @return 解析结果
     */
    ParsedDocument parse(Path filePath, Map<String, Object> options) throws Exception;
}
