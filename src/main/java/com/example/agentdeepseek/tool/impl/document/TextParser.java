package com.example.agentdeepseek.tool.impl.document;

import com.example.agentdeepseek.util.FileEncodingDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 纯文本解析器 — 读取文本/代码文件内容
 * 支持所有常见代码和文本格式，作为兜底解析器
 */
@Slf4j
@Component
public class TextParser implements Parser {

    private static final Set<String> EXTENSIONS = Set.of(
            "txt", "md", "csv", "log", "json", "yml", "yaml", "xml",
            "properties", "cfg", "ini", "toml",
            "ts", "tsx", "vue", "js", "jsx", "css", "scss", "less",
            "html", "htm", "svg",
            "java", "kt", "groovy", "py", "go", "rb", "php", "rs", "swift",
            "c", "cpp", "h", "hpp", "sh", "bat", "ps1", "sql", "gradle", "m",
            "proto", "graphql", "dockerfile"
    );

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(Path filePath, Map<String, Object> options) throws Exception {
        long start = System.currentTimeMillis();
        byte[] rawBytes = Files.readAllBytes(filePath);
        Charset charset = FileEncodingDetector.detectCharset(rawBytes, rawBytes.length);
        String content = new String(rawBytes, charset);

        ParsedDocument doc = ParsedDocument.builder()
                .title(filePath.getFileName().toString())
                .textContent(content)
                .pageCount(1)
                .parserType("text")
                .success(true)
                .parseTimeMs(System.currentTimeMillis() - start)
                .build();

        log.debug("文本解析完成: {} ({} 字符, 编码: {})", filePath.getFileName(), content.length(), charset);
        return doc;
    }
}
