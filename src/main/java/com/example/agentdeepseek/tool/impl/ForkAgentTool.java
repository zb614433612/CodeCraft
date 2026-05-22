package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.dto.ForkAgentRequest;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.service.impl.AgentForkManager;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建子Agent工具
 * 使主Agent可以创建子Agent在后台独立执行任务
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = false, description = "创建子Agent在后台执行任务")
public class ForkAgentTool implements Tool {

    private final ObjectMapper objectMapper;
    private final AgentForkManager agentForkManager;
    private final ConversationMessageMapper messageMapper;

    public ForkAgentTool(ObjectMapper objectMapper, @Lazy AgentForkManager agentForkManager,
                         ConversationMessageMapper messageMapper) {
        this.objectMapper = objectMapper;
        this.agentForkManager = agentForkManager;
        this.messageMapper = messageMapper;
    }

    @Override
    public String getName() {
        return "fork_agent";
    }

    @Override
    public String getDescription() {
        return "创建一个子Agent在后台独立执行任务，适用于任务拆解后的并行开发。\n"
                + "【适用场景】当主Agent的任务较多且可拆分为独立子任务时，使用此工具创建子Agent并行执行\n"
                + "【使用方式】指定 agent_id（唯一标识）、name（名称）、instructions（任务描述）、tools（工具列表）和可选的 skills（技能列表）\n"
                + "【注意事项】子Agent在后台独立运行。主Agent必须在本轮对话结束前通过 collect_agent 收集所有子Agent结果并汇总，不得提前结束对话。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // agent_id
        ObjectNode agentId = objectMapper.createObjectNode();
        agentId.put("type", "string");
        agentId.put("description", "【必填】子Agent唯一标识，如 sub-dal-1。主Agent后续通过此ID收集结果和查看详情");
        properties.set("agent_id", agentId);

        // name
        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "【必填】子Agent名称，如「数据访问层开发」。用于前端展示和日志标记");
        properties.set("name", name);

        // instructions
        ObjectNode instructions = objectMapper.createObjectNode();
        instructions.put("type", "string");
        instructions.put("description", "【必填】子Agent的完整任务描述。越详细越好，包括：目标、输入、输出、约束条件、注意事项等");
        properties.set("instructions", instructions);

        // tools
        ObjectNode tools = objectMapper.createObjectNode();
        tools.put("type", "array");
        tools.put("description", "【必填】子Agent可用的工具列表。示例：[\"read_file\", \"write_file\", \"edit_file\", \"grep_search\", \"run_command\"]");
        ObjectNode toolItem = objectMapper.createObjectNode();
        toolItem.put("type", "string");
        tools.set("items", toolItem);
        properties.set("tools", tools);

        // skills
        ObjectNode skills = objectMapper.createObjectNode();
        skills.put("type", "array");
        skills.put("description", "【可选】子Agent可用的技能名称或ID列表。子Agent启动后会自动加载这些技能的instructions注入到系统提示词。示例：[\"编码任务标准化流程\"] 或 [19]");
        ObjectNode skillItem = objectMapper.createObjectNode();
        skillItem.put("type", "string");
        skills.set("items", skillItem);
        properties.set("skills", skills);

        // context_mode
        ObjectNode contextMode = objectMapper.createObjectNode();
        contextMode.put("type", "string");
        contextMode.put("description", "【可选】上下文继承模式，默认 inherit_summary。inherit_summary=继承父Agent上下文摘要，inherit_full=继承完整上下文（token消耗大），none=不继承仅使用instructions");
        ArrayNode contextModeEnum = objectMapper.createArrayNode();
        contextModeEnum.add("inherit_summary");
        contextModeEnum.add("inherit_full");
        contextModeEnum.add("none");
        contextMode.set("enum", contextModeEnum);
        properties.set("context_mode", contextMode);

        // max_iterations
        ObjectNode maxIterations = objectMapper.createObjectNode();
        maxIterations.put("type", "integer");
        maxIterations.put("description", "【可选】子Agent最大迭代次数，默认30。超过后会自动触发评委评估是否继续");
        properties.set("max_iterations", maxIterations);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("agent_id");
        required.add("name");
        required.add("instructions");
        required.add("tools");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            // 解析参数
            String agentId = arguments.path("agent_id").asText();
            String name = arguments.path("name").asText();
            String instructions = arguments.path("instructions").asText();
            String contextMode = arguments.path("context_mode").asText("inherit_summary");
            int maxIterations = arguments.path("max_iterations").asInt(30);

            // 解析 tools 列表
            List<String> tools = new ArrayList<>();
            JsonNode toolsNode = arguments.path("tools");
            if (toolsNode.isArray()) {
                for (JsonNode t : toolsNode) {
                    tools.add(t.asText());
                }
            }

            // 解析 skills 列表（可选）
            List<String> skills = new ArrayList<>();
            JsonNode skillsNode = arguments.path("skills");
            if (skillsNode.isArray()) {
                for (JsonNode s : skillsNode) {
                    skills.add(s.asText());
                }
            }

            // 从 ToolContext 获取会话信息
            Long conversationId = ToolContext.getConversationId();
            String turnId = ToolContext.getTurnId();
            Long userId = ToolContext.getUserId();
            String mode = ToolContext.getMode();

            if (conversationId == null) {
                return "【错误】无法获取当前会话ID，请在对话中调用此工具";
            }

            // 构建请求
            ForkAgentRequest request = new ForkAgentRequest();
            request.setAgentId(agentId);
            request.setName(name);
            request.setInstructions(instructions);
            request.setTools(tools);
            request.setSkills(skills);
            request.setContextMode(contextMode);
            request.setMaxIterations(maxIterations);

            // 调用 AgentForkManager 创建子Agent（传递 userId 和 mode 用于权限上下文）
            String parentContext = buildParentContext(conversationId, contextMode);
            String createdAgentId = agentForkManager.forkAgent(request, conversationId, turnId, parentContext, userId, mode);

            log.info("子Agent创建成功: agentId={}, name={}, tools={}, skills={}",
                    agentId, name, tools, skills);

            // 构建返回消息
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 子Agent「").append(name).append("」(ID: ").append(agentId).append(") 已创建并在后台运行\n\n");
            sb.append("【任务描述】").append(instructions.length() > 100 ? instructions.substring(0, 100) + "..." : instructions).append("\n");
            sb.append("【可用工具】").append(String.join(", ", tools)).append("\n");
            if (!skills.isEmpty()) {
                sb.append("【可用技能】").append(String.join(", ", skills)).append("\n");
            }
            sb.append("【上下文模式】").append(contextMode).append("\n");
            sb.append("【最大迭代】").append(maxIterations).append(" 次\n\n");
            sb.append("【注意】子Agent已在后台独立运行。你必须继续执行其他任务（如果有），然后在本轮对话结束前通过 collect_agent 收集所有子Agent的结果并汇总。");

            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "【参数错误】" + e.getMessage();
        } catch (IllegalStateException e) {
            return "【并发限制】" + e.getMessage() + "。请先 collect 已完成的任务后再创建新的子Agent。";
        } catch (Exception e) {
            log.error("创建子Agent失败", e);
            return "【创建失败】" + e.getMessage();
        }
    }

    /**
     * 根据 contextMode 构建父Agent上下文
     */
    private String buildParentContext(Long conversationId, String contextMode) {
        if (conversationId == null || "none".equals(contextMode)) return null;

        List<ConversationMessage> messages = messageMapper.selectByConversationId(conversationId);
        if (messages == null || messages.isEmpty()) return null;

        if ("inherit_full".equals(contextMode)) {
            // 完整上下文：拼接所有消息，最大 10000 字符
            StringBuilder sb = new StringBuilder("【父Agent对话历史】\n");
            for (ConversationMessage msg : messages) {
                String role = msg.getRole() != null ? msg.getRole().getValue() : "unknown";
                String content = msg.getContent();
                if (content == null) content = "";
                // 单条消息超长时截断，防止 total > 10000 后才检查
                int remaining = 10000 - sb.length();
                if (remaining <= 0) {
                    sb.append("...（上下文过长已截断）");
                    break;
                }
                if (content.length() > remaining) {
                    content = content.substring(0, remaining) + "...";
                }
                sb.append(role).append(": ").append(content).append("\n");
            }
            return sb.toString();
        }

        // inherit_summary（默认）：取最近 10 条消息摘要
        StringBuilder sb = new StringBuilder("【父Agent上下文摘要】\n");
        int start = Math.max(0, messages.size() - 10);
        for (int i = start; i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            String role = msg.getRole() != null ? msg.getRole().getValue() : "unknown";
            String content = msg.getContent();
            if (content == null) content = "";
            if (content.length() > 200) {
                content = content.substring(0, 200) + "...";
            }
            sb.append(role).append(": ").append(content).append("\n");
        }
        return sb.toString();
    }
}
