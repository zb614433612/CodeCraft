package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.service.impl.AgentForkManager;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 查看子Agent详情工具
 * 按需获取子Agent的完整diff/thinking/调用历史
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = false, description = "查看子Agent的执行详情")
public class InspectAgentTool implements Tool {

    private final ObjectMapper objectMapper;
    private final AgentForkManager agentForkManager;

    public InspectAgentTool(ObjectMapper objectMapper, @Lazy AgentForkManager agentForkManager) {
        this.objectMapper = objectMapper;
        this.agentForkManager = agentForkManager;
    }

    @Override
    public String getName() {
        return "inspect_agent";
    }

    @Override
    public String getDescription() {
        return "查看子Agent的执行详情。主Agent可以通过此工具按需获取子Agent的完整diff、执行结果摘要、thinking过程或工具调用历史。\n"
                + "【适用场景】collect_agent 返回的结构化摘要信息不足时，使用此工具深入查看详情\n"
                + "【scope说明】summary=完整执行结果摘要（含文件变更/编译结果等），diff=文件变更JSON，thinking=思考过程，calls=工具调用历史，full_log=完整执行日志";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // agent_id
        ObjectNode agentId = objectMapper.createObjectNode();
        agentId.put("type", "string");
        agentId.put("description", "【必填】要查看的子Agent ID");
        properties.set("agent_id", agentId);

        // scope
        ObjectNode scope = objectMapper.createObjectNode();
        scope.put("type", "string");
        scope.put("description", "【必填】查看范围：summary=完整执行结果摘要（含文件变更/编译结果/关键决策等），diff=文件变更JSON，thinking=思考过程，calls=工具调用历史，full_log=完整执行日志");
        ArrayNode scopeEnum = objectMapper.createArrayNode();
        scopeEnum.add("summary");
        scopeEnum.add("diff");
        scopeEnum.add("thinking");
        scopeEnum.add("calls");
        scopeEnum.add("full_log");
        scope.set("enum", scopeEnum);
        properties.set("scope", scope);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("agent_id");
        required.add("scope");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            String agentId = arguments.path("agent_id").asText();
            if (agentId.isEmpty()) {
                return "【缺少参数】agent_id 不能为空";
            }

            String scope = arguments.path("scope").asText("summary");
            if (scope.isEmpty()) {
                return "【缺少参数】scope 不能为空。可选值：diff / thinking / calls / full_log";
            }

            log.info("查看子Agent详情: agentId={}, scope={}", agentId, scope);

            // 调用 AgentForkManager 查询详情
            return agentForkManager.inspectAgent(agentId, scope);
        } catch (Exception e) {
            log.error("查看子Agent详情失败", e);
            return "【查询失败】" + e.getMessage();
        }
    }
}
