package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Code Review 子 agent
 * 读取目标文件 + 调用 DeepSeek 进行全面代码审查
 */
@Slf4j
@Component
public class CodeReviewAgentTool implements Tool {

    private static final int ANALYSIS_TIMEOUT = 120;

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final DeepSeekAnalyzer deepSeekAnalyzer;

    public CodeReviewAgentTool(ObjectMapper objectMapper, ToolRegistry toolRegistry, DeepSeekAnalyzer deepSeekAnalyzer) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.deepSeekAnalyzer = deepSeekAnalyzer;
    }

    @Override
    public String getName() {
        return "code_review_agent";
    }

    @Override
    public String getDescription() {
        return "Code Review 子 agent：读取目标文件内容，调用 DeepSeek 进行全面代码审查（覆盖率、代码质量、最佳实践、性能、安全、可维护性等）。"
                + "区别于 bug_analysis_agent（只找 Bug）和 refactor_agent（重构方案），本工具提供全方位的代码审查报告";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode filePath = objectMapper.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "要审查的源代码文件路径（相对于项目根目录）");
        properties.set("file_path", filePath);

        ObjectNode reviewScope = objectMapper.createObjectNode();
        reviewScope.put("type", "string");
        reviewScope.put("description", "审查范围（可选），如「全部」「安全性」「性能」「可维护性」「最佳实践」");
        properties.set("review_scope", reviewScope);

        ObjectNode additionalContext = objectMapper.createObjectNode();
        additionalContext.put("type", "string");
        additionalContext.put("description", "额外的上下文信息（可选），如项目规范、团队约定等");
        properties.set("additional_context", additionalContext);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String filePath = arguments.path("file_path").asText();
        String reviewScope = arguments.path("review_scope").asText("");
        String additionalContext = arguments.path("additional_context").asText("");

        if (filePath.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }

        // 收集上下文：读取目标文件
        String fileContent = readFile(filePath);
        if (fileContent.startsWith("错误")) {
            return fileContent;
        }

        // 构建审查提示词
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("请对以下代码进行全面审查。\n\n");

        userMessage.append("## 文件路径\n").append(filePath).append("\n\n");
        userMessage.append("## 文件内容\n```\n").append(fileContent).append("\n```\n\n");

        if (!reviewScope.isEmpty()) {
            userMessage.append("## 审查重点\n").append(reviewScope).append("\n\n");
        }
        if (!additionalContext.isEmpty()) {
            userMessage.append("## 额外上下文\n").append(additionalContext).append("\n\n");
        }

        userMessage.append("请从以下维度进行审查：\n");
        userMessage.append("1. 代码质量：命名、注释、格式、函数长度、职责单一性\n");
        userMessage.append("2. 最佳实践：是否遵循语言/框架的惯用写法，设计模式是否合理\n");
        userMessage.append("3. 性能：是否存在性能瓶颈、不必要的对象创建、N+1 查询等\n");
        userMessage.append("4. 安全：是否存在注入、XSS、敏感信息泄露、权限绕过等风险\n");
        userMessage.append("5. 可维护性：耦合度、可测试性、扩展性\n");
        userMessage.append("6. 错误处理：异常处理是否完善，边界条件是否覆盖\n\n");
        userMessage.append("请对每个发现的问题标注严重程度（阻塞/重要/建议），并提供改进示例。"
                + "以清晰的 Markdown 格式输出审查结果。");

        String systemPrompt = "你是一个资深的 Code Review 专家，拥有 15 年以上软件开发经验。"
                + "你的审查标准严格但建设性，对每个问题都给出具体的改进建议和代码示例。"
                + "审查时保持客观，区分「必须修改」和「建议优化」的级别。";

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
