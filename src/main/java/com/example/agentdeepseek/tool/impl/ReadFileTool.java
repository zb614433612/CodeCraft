package com.example.agentdeepseek.tool.impl;
import com.example.agentdeepseek.util.ProjectRootContext;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
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
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 最大读取文件大小 100MB
    private static final double BINARY_NULL_THRESHOLD = 0.05; // 空字节比例超过此阈值视为二进制文件

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

        Path filePath;
        try {
            filePath = resolvePath(filePathStr);
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        }
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
            // 检查文件大小
            long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE) {
                return "错误：文件太大（" + fileSize + " 字节），超过最大限制 " + MAX_FILE_SIZE + " 字节";
            }

            // 检测是否为二进制文件
            if (isBinaryFile(filePath)) {
                return "错误：文件包含大量二进制数据，不是文本文件 - " + filePath.toAbsolutePath();
            }

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

            // 参数冲突提示：行范围和分页参数不能同时使用
            if ((hasStartLine || hasEndLine) && hasPage) {
                log.warn("同时指定了行范围参数(start_line/end_line)和分页参数(page)，优先使用行范围模式");
            }

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
        Path projectRoot = Paths.get(ProjectRootContext.get()).normalize();
        Path resolved;
        if (path.isAbsolute()) {
            resolved = path.normalize();
        } else {
            resolved = Paths.get(ProjectRootContext.get(), pathStr).normalize();
        }
        // 路径穿越防护：确保解析后的路径在项目目录范围内
        if (!resolved.startsWith(projectRoot)) {
            throw new SecurityException("访问被拒绝：路径不在项目目录范围内 - " + resolved);
        }
        return resolved;
    }

    /**
     * 检测文件编码
     * 优先检测BOM，然后用 CharsetDecoder 实际解码样本来判断编码
     */
    private Charset detectCharset(Path filePath) throws IOException {
        int sampleSize = (int) Math.min(Files.size(filePath), 4096);
        if (sampleSize <= 0) return StandardCharsets.UTF_8;

        try (InputStream is = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(is)) {

            // BOM检测
            byte[] bom = new byte[3];
            bis.mark(4);
            int bomRead = bis.read(bom);
            if (bomRead >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF)
                return StandardCharsets.UTF_8;
            if (bomRead >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF)
                return Charset.forName("UTF-16BE");
            if (bomRead >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE)
                return Charset.forName("UTF-16LE");

            // 读取样本
            bis.reset();
            byte[] sampleBytes = new byte[sampleSize];
            int sampleLen = bis.read(sampleBytes);
            if (sampleLen <= 0) return StandardCharsets.UTF_8;

            // 检查是否为纯ASCII（所有字节 < 128）
            boolean isPureAscii = true;
            for (int i = 0; i < sampleLen; i++) {
                if ((sampleBytes[i] & 0xFF) >= 128) {
                    isPureAscii = false;
                    break;
                }
            }
            if (isPureAscii) return StandardCharsets.UTF_8;

            // 用CharsetDecoder严格模式实际解码样本来判断编码
            // 优先级：UTF-8 > GB18030（兼容GBK） > 兜底用UTF-8
            if (canDecode(sampleBytes, sampleLen, StandardCharsets.UTF_8))
                return StandardCharsets.UTF_8;
            if (canDecode(sampleBytes, sampleLen, Charset.forName("GB18030")))
                return Charset.forName("GB18030");
            // 兜底：现代项目基本使用UTF-8，不再回退到ISO-8859-1
            return StandardCharsets.UTF_8;
        }

    }

    /**
     * 判断字节数组能否用指定编码无错误解码
     */
    private boolean canDecode(byte[] bytes, int length, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = ByteBuffer.wrap(bytes, 0, length);
            CharBuffer cb = CharBuffer.allocate(length);
            CoderResult result = decoder.decode(bb, cb, true);
            if (result.isError()) return false;
            result = decoder.flush(cb);
            return !result.isError();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测文件是否为二进制文件（通过检查空字节比例）
     */
    private boolean isBinaryFile(Path filePath) throws IOException {
        int sampleSize = (int) Math.min(Files.size(filePath), 4096);
        if (sampleSize <= 0) return false;

        byte[] sample = new byte[sampleSize];
        try (InputStream is = Files.newInputStream(filePath)) {
            int bytesRead = is.read(sample);
            if (bytesRead <= 0) return false;

            int nullCount = 0;
            for (int i = 0; i < bytesRead; i++) {
                if (sample[i] == 0) nullCount++;
            }
            return (double) nullCount / bytesRead > BINARY_NULL_THRESHOLD;
        }
    }

    /**
     * 读取文件所有行，限制单行最大长度防止OOM
     * 遇到 MalformedInputException 时自动降级尝试 GBK
     */
    private List<String> readAllLines(Path filePath, Charset charset) throws IOException {
        try {
            return readAllLinesInternal(filePath, charset);
        } catch (MalformedInputException e) {
            log.warn("使用 {} 解码失败 ({}), 降级尝试 GBK", charset, e.getMessage());
            log.warn("使用 {} 解码失败 ({}), 降级尝试 GB18030", charset, e.getMessage());
            return readAllLinesInternal(filePath, Charset.forName("GB18030"));
        }
    }

    private List<String> readAllLinesInternal(Path filePath, Charset charset) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch == '\n') {
                    lines.add(sb.toString());
                    sb = new StringBuilder();
                } else if (ch == '\r') {
                    // 处理 CR（单独换行或 CRLF）
                    lines.add(sb.toString());
                    sb = new StringBuilder();
                    // 如果是 CRLF，吞掉紧随其后的 \n
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n') {
                        reader.reset();
                    }
                } else {
                    if (sb.length() < MAX_LINE_LENGTH) {
                        sb.append((char) ch);
                    }
                }
            }
            if (sb.length() > 0 || lines.isEmpty()) {
                lines.add(sb.toString());
            }
        } catch (MalformedInputException e) {
            throw e; // 上层 catch 处理降级
        }
        return lines;
    }
}
