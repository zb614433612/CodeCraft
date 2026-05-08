package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取文件工具
 * 支持行范围读取、分页读取和自动编码检测
 */
@Slf4j
@Component
public class ReadFileTool implements Tool {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_LINE_LENGTH = 100000; // 单行最大字符数，防止超大行内存溢出

    private final ObjectMapper objectMapper;

    public ReadFileTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "读取指定文件的内容，支持行范围读取（start_line/end_line）和分页读取（page/page_size）。"
                + "自动检测文件编码（UTF-8/GBK 等）。用于查看源代码、配置文件、日志等各类文件";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "文件路径，相对于项目根目录的路径或绝对路径");
        properties.set("file_path", filePath);

        ObjectNode startLine = objectMapper.createObjectNode();
        startLine.put("type", "integer");
        startLine.put("description", "起始行号（从1开始），与end_line配合使用");
        properties.set("start_line", startLine);

        ObjectNode endLine = objectMapper.createObjectNode();
        endLine.put("type", "integer");
        endLine.put("description", "结束行号，与start_line配合使用");
        properties.set("end_line", endLine);

        ObjectNode page = objectMapper.createObjectNode();
        page.put("type", "integer");
        page.put("description", "页码（从1开始），与page_size配合使用");
        properties.set("page", page);

        ObjectNode pageSize = objectMapper.createObjectNode();
        pageSize.put("type", "integer");
        pageSize.put("description", "每页行数，默认50");
        properties.set("page_size", pageSize);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String filePathStr = arguments.path("file_path").asText();
        if (filePathStr.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }

        Path filePath = resolvePath(filePathStr);
        if (!Files.exists(filePath)) {
            return "错误：文件不存在 - " + filePath.toAbsolutePath();
        }
        if (!Files.isRegularFile(filePath)) {
            return "错误：路径不是文件 - " + filePath.toAbsolutePath();
        }
        if (!Files.isReadable(filePath)) {
            return "错误：文件不可读 - " + filePath.toAbsolutePath();
        }

        try {
            // 检测编码
            Charset charset = detectCharset(filePath);
            if (charset == null) {
                charset = StandardCharsets.UTF_8;
            }

            // 读取所有行
            List<String> allLines = readAllLines(filePath, charset);

            // 解析参数
            boolean hasStartLine = arguments.has("start_line") && !arguments.path("start_line").isNull();
            boolean hasEndLine = arguments.has("end_line") && !arguments.path("end_line").isNull();
            boolean hasPage = arguments.has("page") && !arguments.path("page").isNull();

            int startIdx, endIdx;

            if (hasStartLine || hasEndLine) {
                // 行范围模式
                int startLineNum = hasStartLine ? Math.max(1, arguments.path("start_line").asInt(1)) : 1;
                int endLineNum = hasEndLine ? arguments.path("end_line").asInt(allLines.size()) : allLines.size();
                startIdx = startLineNum - 1;
                endIdx = Math.min(endLineNum, allLines.size());
            } else if (hasPage) {
                // 分页模式
                int pageNum = Math.max(1, arguments.path("page").asInt(1));
                int pageSize = arguments.has("page_size") ? Math.max(1, arguments.path("page_size").asInt(DEFAULT_PAGE_SIZE))
                        : DEFAULT_PAGE_SIZE;
                startIdx = (pageNum - 1) * pageSize;
                endIdx = Math.min(startIdx + pageSize, allLines.size());
            } else {
                // 默认读取全部
                startIdx = 0;
                endIdx = allLines.size();
            }

            if (startIdx >= allLines.size()) {
                return "提示：起始位置超出文件范围，文件共 " + allLines.size() + " 行";
            }
            if (startIdx < 0) startIdx = 0;

            List<String> resultLines = allLines.subList(startIdx, endIdx);

            // 构建结果
            StringBuilder sb = new StringBuilder();
            sb.append("文件：").append(filePath.toAbsolutePath()).append("\n");
            sb.append("编码：").append(charset.name()).append("\n");
            sb.append("总行数：").append(allLines.size()).append("\n");
            sb.append("显示行：").append(startIdx + 1).append(" - ").append(endIdx).append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            int lineNumWidth = String.valueOf(endIdx).length();
            for (int i = 0; i < resultLines.size(); i++) {
                String lineNum = String.format("%" + lineNumWidth + "d", startIdx + i + 1);
                sb.append(" ").append(lineNum).append(" │ ").append(resultLines.get(i)).append("\n");
            }
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            // 如果是行范围模式且有更多行，提示用户
            if (endIdx < allLines.size()) {
                sb.append("提示：还有 ").append(allLines.size() - endIdx).append(" 行未显示");
                if (!hasPage && !hasStartLine) {
                    sb.append("，可使用 page / page_size 参数分页查看");
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            return "错误：读取文件失败 - " + e.getMessage();
        }
    }

    /**
     * 解析文件路径，如果是相对路径则拼接项目根目录
     */
    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }

    /**
     * 检测文件编码
     * 优先检测BOM，然后尝试 UTF-8 → GBK → ISO-8859-1
     */
    private Charset detectCharset(Path filePath) throws IOException {
        // 读取文件开头几个字节检测编码
        try (InputStream is = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(is)) {
            bis.mark(4);
            byte[] bom = new byte[4];
            int read = bis.read(bom, 0, 4);

            // BOM检测
            if (read >= 2
                    && (bom[0] & 0xFF) == 0xFE
                    && (bom[1] & 0xFF) == 0xFF) {
                return Charset.forName("UTF-16BE");
            }
            if (read >= 2
                    && (bom[0] & 0xFF) == 0xFF
                    && (bom[1] & 0xFF) == 0xFE) {
                return Charset.forName("UTF-16LE");
            }
            if (read >= 3
                    && (bom[0] & 0xFF) == 0xEF
                    && (bom[1] & 0xFF) == 0xBB
                    && (bom[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }

            // 无BOM时，尝试检测编码：读一部分内容试解码
            bis.reset();
            byte[] sampleBytes = new byte[Math.min((int) Files.size(filePath), 4096)];
            int sampleLen = bis.read(sampleBytes);

            // 尝试 UTF-8
            if (isValidUtf8(sampleBytes, sampleLen)) {
                return StandardCharsets.UTF_8;
            }

            // 尝试 GBK（GBK编码的字节特征）
            // 这里简单通过检测是否包含特定范围的字节来推断，更准确的方法是用 CharsetDecoder
            String sampleStr = new String(sampleBytes, 0, sampleLen, Charset.forName("GBK"));
            String checkRoundtrip = new String(sampleStr.getBytes(Charset.forName("GBK")), 0,
                    sampleStr.getBytes(Charset.forName("GBK")).length, Charset.forName("GBK"));
            // 粗略检查：如果GBK解码后重新编码能匹配大部分字节，则可能是GBK
            byte[] reEncoded = sampleStr.getBytes(Charset.forName("GBK"));
            int matchCount = 0;
            for (int i = 0; i < Math.min(reEncoded.length, sampleLen); i++) {
                if (i < sampleLen && reEncoded[i] == sampleBytes[i]) {
                    matchCount++;
                }
            }
            if ((double) matchCount / Math.min(reEncoded.length, sampleLen) > 0.8) {
                return Charset.forName("GBK");
            }
        }

        // 默认返回 UTF-8
        return StandardCharsets.UTF_8;
    }

    /**
     * 简单检测字节数组是否为合法的UTF-8编码
     */
    private boolean isValidUtf8(byte[] bytes, int length) {
        int i = 0;
        while (i < length) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                i++;
            } else if (b >= 0xC2 && b <= 0xDF) {
                // 2字节序列：110xxxxx 10xxxxxx
                if (i + 1 >= length) return false;
                if ((bytes[i + 1] & 0xC0) != 0x80) return false;
                i += 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                // 3字节序列：1110xxxx 10xxxxxx 10xxxxxx
                if (i + 2 >= length) return false;
                if ((bytes[i + 1] & 0xC0) != 0x80) return false;
                if ((bytes[i + 2] & 0xC0) != 0x80) return false;
                i += 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                // 4字节序列：11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
                if (i + 3 >= length) return false;
                if ((bytes[i + 1] & 0xC0) != 0x80) return false;
                if ((bytes[i + 2] & 0xC0) != 0x80) return false;
                if ((bytes[i + 3] & 0xC0) != 0x80) return false;
                i += 4;
            } else {
                return false; // 非法字节
            }
        }
        return true;
    }

    /**
     * 读取文件所有行，限制单行最大长度防止OOM
     */
    private List<String> readAllLines(Path filePath, Charset charset) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
            char[] buf = new char[MAX_LINE_LENGTH];
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch == '\n') {
                    lines.add(sb.toString());
                    sb = new StringBuilder();
                } else if (ch == '\r') {
                    // skip CR, handle CRLF
                } else {
                    if (sb.length() < MAX_LINE_LENGTH) {
                        sb.append((char) ch);
                    }
                }
            }
            if (sb.length() > 0 || lines.isEmpty()) {
                lines.add(sb.toString());
            }
        }
        return lines;
    }
}
