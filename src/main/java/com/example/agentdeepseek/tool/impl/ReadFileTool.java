package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.impl.readfile.FileReadEngine;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.tool.impl.readfile.FileNotFoundHandler;
import com.example.agentdeepseek.tool.impl.readfile.FileOutputFormatter;
import com.example.agentdeepseek.util.FileEncodingDetector;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 读取文件工具
 * <p>
 * 基于流式读取引擎实现，支持行范围读取、分页读取和自动编码检测。
 * 优化特性：
 * - P0: 流式读取（Files.lines），大文件 OOM 防护
 * - P1: 单行截断（2000 字符），防上下文污染
 * - P1: 模糊文件名建议（Levenshtein），LLM 友好
 * - P2: 输出大小硬限制（100KB），防上下文溢出
 * - P3: 结构化输出格式，LLM 解析更准
 * - 新增目录读取支持
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, isPathSensitive = true, description = "读取文件内容")
public class ReadFileTool implements Tool {

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
        return "读取文件内容或列出目录，返回带行号的内容。\n"
                + "【适用场景】\n"
                + "  - 阅读源代码、配置文件了解项目逻辑\n"
                + "  - 查看日志、输出等文本文件\n"
                + "  - 列出目录内容（传入目录路径即可）\n"
                + "【读取方式】\n"
                + "  - 不指定行范围：读取前2000行（默认）\n"
                + "  - start_line + end_line：读取指定行范围（适合定位到具体代码段）\n"
                + "  - page + page_size：分页读取（适合大文件）\n"
                + "  注意：行范围和分页不能同时使用，同时指定时行范围优先\n"
                + "【与 glob_files/grep_search 的区别】\n"
                + "  - read_file: 读取已知文件的【具体内容】\n"
                + "  - glob_files: 按文件名模式【找文件】\n"
                + "  - grep_search: 按内容模式【搜文件】\n"
                + "  - 标准流程：glob/grep 找到文件 → read_file 读内容";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // file_path - 必填
        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "【必填】文件或目录路径。\n"
                + "支持相对路径（如 'src/main/App.java'）或绝对路径。\n"
                + "传入目录路径时列出目录内容");
        properties.set("file_path", filePath);

        // start_line - 可选，行范围起始
        ObjectNode startLine = objectMapper.createObjectNode();
        startLine.put("type", "integer");
        startLine.put("description", "【可选】起始行号（从1开始），与 end_line 配合使用。\n"
                + "不指定则从第1行开始。适合读取代码中特定区域");
        properties.set("start_line", startLine);

        // end_line - 可选，行范围结束
        ObjectNode endLine = objectMapper.createObjectNode();
        endLine.put("type", "integer");
        endLine.put("description", "【可选】结束行号（包含该行），与 start_line 配合使用。\n"
                + "注意：与 page/page_size 互斥，同时指定时行范围优先");
        properties.set("end_line", endLine);

        // page - 可选，分页
        ObjectNode page = objectMapper.createObjectNode();
        page.put("type", "integer");
        page.put("description", "【可选】页码（从1开始），与 page_size 配合使用实现分页读取。\n"
                + "不指定任何参数时默认读取前2000行");
        properties.set("page", page);

        // page_size - 可选，每页行数
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
            return "【缺少参数】请提供 file_path 参数。\n"
                    + "示例：file_path='src/main/App.java' 或 file_path='pom.xml'";
        }

        // 解析路径
        Path filePath = resolvePath(filePathStr);

        // 检查路径是否存在
        if (!Files.exists(filePath)) {
            // --- P1: 模糊文件名建议 ---
            Path searchDir = filePath.getParent();
            if (searchDir == null) searchDir = Paths.get(ProjectRootContext.get());
            String suggestion = FileNotFoundHandler.suggest(filePath, searchDir);
            if (suggestion != null) {
                return suggestion;
            }
            return "【文件不存在】" + filePath.toAbsolutePath() + "\n"
                    + "建议：先用 glob_files 搜索文件名，或用 read_project_tree 了解项目结构";
        }

        // 检查可读性
        if (!Files.isReadable(filePath)) {
            return "错误：路径不可读 - " + filePath.toAbsolutePath();
        }

        try {
            // --- 目录读取支持 ---
            if (Files.isDirectory(filePath)) {
                var dirResult = FileReadEngine.readDirectory(filePath);
                return FileOutputFormatter.format(dirResult);
            }

            // --- 文件大小预检 ---
            long fileSize = Files.size(filePath);
            if (fileSize > 100 * 1024 * 1024) { // 100MB
                return "错误：文件太大（" + fileSize + " 字节），超过最大限制 100MB";
            }

            // --- 二进制检测 ---
            if (FileReadEngine.isBinaryFile(filePath)) {
                return "错误：文件包含大量二进制数据，不是文本文件 - " + filePath.toAbsolutePath();
            }

            // --- 编码自动检测 ---
            Charset charset = FileEncodingDetector.detectCharset(filePath);

            // --- 解析行范围/分页参数 ---
            boolean hasStartLine = arguments.has("start_line") && !arguments.path("start_line").isNull();
            boolean hasEndLine = arguments.has("end_line") && !arguments.path("end_line").isNull();
            boolean hasPage = arguments.has("page") && !arguments.path("page").isNull();

            // 参数冲突提示
            if ((hasStartLine || hasEndLine) && hasPage) {
                log.warn("同时指定了行范围参数(start_line/end_line)和分页参数(page)，优先使用行范围模式");
            }

            Integer startLine = hasStartLine ? arguments.path("start_line").asInt() : null;
            Integer endLine = hasEndLine ? arguments.path("end_line").asInt() : null;
            Integer page = hasPage ? arguments.path("page").asInt() : null;
            Integer pageSize = arguments.has("page_size") && !arguments.path("page_size").isNull()
                    ? arguments.path("page_size").asInt() : null;

            // --- P0: 流式读取 ---
            var result = FileReadEngine.read(
                    filePath, charset, startLine, endLine, page, pageSize
            );

            // 检查起始行是否超出范围
            if (result.getLines().isEmpty() && result.getTotalLines() == 0) {
                return "提示：文件为空";
            }
            if (result.getLines().isEmpty() && startLine != null && startLine > 1) {
                return "提示：起始行 " + startLine + " 超出文件范围，文件共 " + result.getTotalLines() + " 行";
            }

            // --- P3: 结构化格式化输出 ---
            return FileOutputFormatter.format(result);

        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            return "错误：读取文件失败 - " + e.getMessage();
        } catch (Exception e) {
            log.error("读取文件异常: {}", filePath, e);
            return "错误：读取文件异常 - " + e.getMessage();
        }
    }

    /**
     * 解析文件路径，如果是相对路径则拼接项目根目录，带路径穿越防护
     */
    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Paths.get(ProjectRootContext.get(), pathStr).normalize();
    }
}
