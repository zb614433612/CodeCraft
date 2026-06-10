package com.example.agentdeepseek.tool.impl.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 解析器工厂 — 根据文件扩展名自动路由到对应的 Parser
 */
@Slf4j
@Component
public class ParserFactory {

    private final List<Parser> parsers;

    /**
     * Spring 自动注入所有 Parser 实现
     */
    public ParserFactory(List<Parser> parsers) {
        this.parsers = parsers;
        log.info("ParserFactory 已注册 {} 个解析器: {}", parsers.size(),
                parsers.stream().map(p -> String.join(",", p.supportedExtensions())).toList());
    }

    /**
     * 根据文件路径选择合适的解析器
     * @param filePath 文件路径
     * @return 匹配的解析器
     * @throws IllegalArgumentException 如果文件格式不支持
     */
    public Parser select(Path filePath) {
        for (Parser parser : parsers) {
            if (parser.supports(filePath)) {
                log.debug("文件 {} → 解析器 {}", filePath.getFileName(), parser.getClass().getSimpleName());
                return parser;
            }
        }
        throw new IllegalArgumentException("不支持的文件格式: " + filePath.getFileName());
    }
}
