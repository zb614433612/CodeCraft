package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 代码重构子 agent
 * 读取目标文件 + 调用 DeepSeek 分析代码结构并给出重构方案
 */
@Slf4j
@Component
public class RefactorAgentTool implements Tool {

    private static final int ANALYSIS_TIMEOUT = 120;

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final DeepSeekAnalyzer deepSeekAnalyzer;

    public RefactorAgentTool(ObjectMapper objectMapper, ToolRegistry toolRegistry, DeepSeekAnalyzer deepSeekAnalyzer) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.deepSeekAnalyzer = deepSeekAnalyzer;
    }

    @Override
    public String getName() {
        return "refactor_agent";
    }

    @Override
    public String getDescription() {
        return "代码重构子 agent：读取目标文件内容，调用 DeepSeek 分析代码结构并提供重构方案。"
                + "适用于优化代码结构、改进设计模式、消除重复代码、提升可维护性等场景。"
                + "区别于 bug_analysis_agent（找 Bug）和 code_review_agent（全面审查），本工具专注于给出重构方案和优化建议";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "要重构的源代码文件路径（相对于项目根目录）");
        properties.set("file_path", filePath);

        ObjectNode goal = objectMapper.createObjectNode();
        goal.put("type", "string");
        goal.put("description", "重构目标（可选），如「提取公共方法」「改用策略模式」「提高可读性」");
        properties.set("goal", goal);

        ObjectNode scope = objectMapper.createObjectNode();
        scope.put("type", "string");
        scope.put("description", "重构范围（可选），如「单一文件」「跨文件」「模块级别」");
        properties.set("scope", scope);

        ObjectNode additionalContext = objectMapper.createObjectNode();
        additionalContext.put("type", "string");
        additionalContext.put("description", "额外的上下文信息（可选），如相关配置文件、依赖关系等");
        properties.set("additional_context", additionalContext);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String filePath = arguments.path("file_path").asText();
        String goal = arguments.path("goal").asText("");
        String scope = arguments.path("scope").asText("");
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
        userMessage.append("请对以下代码进行重构分析。\n\n");

        userMessage.append("## 文件路径\n").append(filePath).append("\n\n");
        userMessage.append("## 文件内容\n```\n").append(fileContent).append("\n```\n\n");

        if (!goal.isEmpty()) {
            userMessage.append("## 重构目标\n").append(goal).append("\n\n");
        }
        if (!scope.isEmpty()) {
            userMessage.append("## 重构范围\n").append(scope).append("\n\n");
        }
        if (!additionalContext.isEmpty()) {
            userMessage.append("## 额外上下文\n").append(additionalContext).append("\n\n");
        }

        userMessage.append("请从以下维度进行分析：\n");
        userMessage.append("1. 当前问题：代码中存在哪些设计问题（重复代码、过长方法、职责不单一等）\n");
        userMessage.append("2. 重构方案：针对每个问题给出具体的重构策略和步骤\n");
        userMessage.append("3. 重构后的代码：展示关键部分的重构后代码\n");
        userMessage.append("4. 风险说明：该重构可能引入的风险和注意事项\n");
        userMessage.append("5. 依赖影响：重构可能影响的其他模块或文件\n\n");
        userMessage.append("请以清晰的 Markdown 格式输出分析结果。");

        String systemPrompt = "你是一个资深的软件架构师和代码重构专家。"
                + "你的任务是分析用户提供的代码，识别设计问题并给出可落地的重构方案。"
                + "请遵循 SOLID 原则和常见设计模式，确保重构方案切实可行。"
                + "如果涉及跨文件重构，请明确指出需要创建/修改的文件列表。";

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
