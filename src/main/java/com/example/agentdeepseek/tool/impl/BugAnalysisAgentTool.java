package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bug 分析子 agent
 * 读取目标文件 + 调用 DeepSeek 分析潜在 bug 或问题根因
 */
@Slf4j
@Component
public class BugAnalysisAgentTool implements Tool {

    private static final int ANALYSIS_TIMEOUT = 120;

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final DeepSeekAnalyzer deepSeekAnalyzer;

    public BugAnalysisAgentTool(ObjectMapper objectMapper, ToolRegistry toolRegistry, DeepSeekAnalyzer deepSeekAnalyzer) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.deepSeekAnalyzer = deepSeekAnalyzer;
    }

    @Override
    public String getName() {
        return "bug_analysis_agent";
    }

    @Override
    public String getDescription() {
        return "Bug 分析子 agent：读取目标文件内容，调用 DeepSeek 分析潜在的 bug、异常或问题根因。"
                + "适用于排查代码异常、空指针、并发问题、资源泄漏、边界条件错误等场景。"
                + "区别于 code_review_agent（全面审查）和 refactor_agent（代码重构），本工具专注于找 Bug 和异常分析";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "要分析的源代码文件路径（相对于项目根目录）");
        properties.set("file_path", filePath);

        ObjectNode description = objectMapper.createObjectNode();
        description.put("type", "string");
        description.put("description", "问题描述或 bug 现象（可选），如「登录接口偶尔返回 500」");
        properties.set("description", description);

        ObjectNode additionalContext = objectMapper.createObjectNode();
        additionalContext.put("type", "string");
        additionalContext.put("description", "额外的上下文信息（可选），如错误堆栈、相关配置等");
        properties.set("additional_context", additionalContext);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String filePath = arguments.path("file_path").asText();
        String description = arguments.path("description").asText("");
        String additionalContext = arguments.path("additional_context").asText("");

        if (filePath.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }

        // 收集上下文：读取目标文件
        String fileContent = readFile(filePath);
        if (fileContent.startsWith("错误")) {
            return fileContent;
        }

        // 构建分析提示词
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("请对以下代码进行 Bug 分析。\n\n");

        userMessage.append("## 文件路径\n").append(filePath).append("\n\n");
        userMessage.append("## 文件内容\n```\n").append(fileContent).append("\n```\n\n");

        if (!description.isEmpty()) {
            userMessage.append("## 问题描述\n").append(description).append("\n\n");
        }
        if (!additionalContext.isEmpty()) {
            userMessage.append("## 额外上下文\n").append(additionalContext).append("\n\n");
        }

        userMessage.append("请从以下维度进行分析：\n");
        userMessage.append("1. 潜在 Bug：是否存在空指针、资源泄漏、并发问题、边界条件错误等\n");
        userMessage.append("2. 异常处理：try-catch 是否合理，异常是否会吞没\n");
        userMessage.append("3. 逻辑缺陷：条件判断、循环、状态转换是否有遗漏\n");
        userMessage.append("4. 安全风险：是否存在注入、权限绕过、敏感信息泄露\n");
        userMessage.append("5. 修复建议：对每个问题给出具体的修复方案和代码示例\n\n");
        userMessage.append("请以清晰的 Markdown 格式输出分析结果。");

        String systemPrompt = "你是一个资深的 Java/Web 全栈 Bug 分析专家。"
                + "你的任务是分析用户提供的代码，找出潜在的 Bug 和问题根因。"
                + "请保持客观严谨，只分析确实存在的问题，不要过度解读。"
                + "对每个发现的问题标注严重程度（高/中/低），并给出具体的修复建议。";

        return deepSeekAnalyzer.analyze(systemPrompt, userMessage.toString(), ANALYSIS_TIMEOUT);
    }

    /**
     * 通过 read_file 工具读取文件
     */
    private String readFile(String filePath) {
        try {
            Tool readFileTool = toolRegistry.getTool("read_file");
            if (readFileTool == null) {
                return "错误：找不到 read_file 工具";
            }
            ObjectNode args = objectMapper.createObjectNode();
            args.put("file_path", filePath);
            return readFileTool.execute(args);
        } catch (Exception e) {
            log.error("读取文件失败: {}", filePath, e);
            return "错误：读取文件失败 - " + e.getMessage();
        }
    }
}
