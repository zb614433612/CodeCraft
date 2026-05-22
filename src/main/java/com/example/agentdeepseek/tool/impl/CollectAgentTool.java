package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.SubAgentResult;
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
 * 收集子Agent结果工具
 * 等待并获取子Agent的执行结果
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = false, description = "收集子Agent的执行结果")
public class CollectAgentTool implements Tool {

    private final ObjectMapper objectMapper;
    private final AgentForkManager agentForkManager;

    public CollectAgentTool(ObjectMapper objectMapper, @Lazy AgentForkManager agentForkManager) {
        this.objectMapper = objectMapper;
        this.agentForkManager = agentForkManager;
    }

    @Override
    public String getName() {
        return "collect_agent";
    }

    @Override
    public String getDescription() {
        return "阻塞等待并收集子Agent的执行结果。如果子Agent尚未完成，会一直等待直到完成或超时。\n"
                + "【适用场景】在所有子Agent创建完后，必须调用此工具收集并汇总结果\n"
                + "【使用方式】通过 agent_id 指定要收集的子Agent，通过 timeout 指定最大等待秒数\n"
                + "【返回内容】子Agent的结构化结果摘要：状态、文件变更清单、编译结果、关键决策等\n"
                + "【注意】此工具会阻塞等待子Agent完成，超时时间由 timeout 参数控制（默认120秒，建议设为300秒）";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // agent_id
        ObjectNode agentId = objectMapper.createObjectNode();
        agentId.put("type", "string");
        agentId.put("description", "【必填】要收集的子Agent ID。必须是之前通过 fork_agent 创建时指定的 agent_id");
        properties.set("agent_id", agentId);

        // timeout
        ObjectNode timeout = objectMapper.createObjectNode();
        timeout.put("type", "integer");
        timeout.put("description", "【可选】最大等待超时秒数，默认120。如果子Agent在该时间内未完成则返回timeout");
        properties.set("timeout", timeout);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("agent_id");
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

            int timeout = arguments.path("timeout").asInt(120);
            log.info("阻塞收集子Agent结果: agentId={}, timeout={}s", agentId, timeout);

            // 阻塞等待子Agent完成（最多等待 timeout 秒）
            SubAgentResult result = agentForkManager.collectAgent(agentId, timeout);

            return result.toResultString();
        } catch (Exception e) {
            log.error("收集子Agent结果失败", e);
            return "【收集失败】" + e.getMessage();
        }
    }
}
