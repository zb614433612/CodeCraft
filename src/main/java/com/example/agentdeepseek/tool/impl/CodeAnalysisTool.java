package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 代码分析工具（合并 bug_analysis_agent / code_review_agent / refactor_agent）
 * 根据 mode 参数切换分析类型：bug（找 Bug）、review（全面审查）、refactor（重构方案）
 */
@Slf4j
@Component
public class CodeAnalysisTool implements Tool {

    private static final int ANALYSIS_TIMEOUT = 120;

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final DeepSeekAnalyzer deepSeekAnalyzer;

    public CodeAnalysisTool(ObjectMapper objectMapper, ToolRegistry toolRegistry, DeepSeekAnalyzer deepSeekAnalyzer) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.deepSeekAnalyzer = deepSeekAnalyzer;
    }

    @Override
    public String getName() {
        return "code_analysis";
    }

    @Override
    public String getDescription() {
        return "代码分析工具：读取目标文件内容并调用 DeepSeek 进行分析。"
                + "支持三种模式：bug（查找 Bug 和异常根因）、review（全面代码审查）、refactor（重构方案和优化建议）。"
                + "通过 mode 参数切换分析类型";
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

        ObjectNode mode = objectMapper.createObjectNode();
        mode.put("type", "string");
        mode.put("description", "分析模式：bug（查找 Bug 和异常）、review（全面代码审查）、refactor（重构方案）");
        mode.putArray("enum").add("bug").add("review").add("refactor");
        properties.set("mode", mode);

        ObjectNode description = objectMapper.createObjectNode();
        description.put("type", "string");
        description.put("description", "额外说明（可选）：bug 模式下描述问题现象，review 模式下指定审查重点，refactor 模式下描述重构目标");
        properties.set("description", description);

        ObjectNode additionalContext = objectMapper.createObjectNode();
        additionalContext.put("type", "string");
        additionalContext.put("description", "额外的上下文信息（可选），如错误堆栈、相关配置、项目规范等");
        properties.set("additional_context", additionalContext);

        parameters.set("properties", properties);
        parameters.putArray("required").add("file_path").add("mode");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String filePath = arguments.path("file_path").asText();
        String mode = arguments.path("mode").asText("");
        String description = arguments.path("description").asText("");
        String additionalContext = arguments.path("additional_context").asText("");

        if (filePath.isEmpty()) {
            return "错误：缺少必要参数 file_path";
        }
        if (mode.isEmpty()) {
            return "错误：缺少必要参数 mode（可选值：bug / review / refactor）";
        }

        // 收集上下文：读取目标文件
        String fileContent = readFile(filePath);
        if (fileContent.startsWith("错误")) {
            return fileContent;
        }

        switch (mode) {
            case "bug":
                return analyzeBug(filePath, fileContent, description, additionalContext);
            case "review":
                return analyzeReview(filePath, fileContent, description, additionalContext);
            case "refactor":
                return analyzeRefactor(filePath, fileContent, description, additionalContext);
            default:
                return "错误：不支持的 mode 值 '" + mode + "'，可选值：bug / review / refactor";
        }
    }

    private String analyzeBug(String filePath, String fileContent, String description, String additionalContext) {
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

    private String analyzeReview(String filePath, String fileContent, String reviewScope, String additionalContext) {
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

    private String analyzeRefactor(String filePath, String fileContent, String goal, String additionalContext) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append("请对以下代码进行重构分析。\n\n");

        userMessage.append("## 文件路径\n").append(filePath).append("\n\n");
        userMessage.append("## 文件内容\n```\n").append(fileContent).append("\n```\n\n");

        if (!goal.isEmpty()) {
            userMessage.append("## 重构目标\n").append(goal).append("\n\n");
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
