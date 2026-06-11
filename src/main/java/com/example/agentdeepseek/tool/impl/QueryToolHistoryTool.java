package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询历史工具调用详情工具
 * <p>
 * 在 compact 上下文模式下，非本轮对话的工具调用结果被精简为成功/失败摘要。
 * 此工具允许 LLM 按需获取指定工具调用的完整详细结果。
 * </p>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, affectsData = false, description = "查询历史工具调用的详细结果")
public class QueryToolHistoryTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ConversationMessageMapper conversationMessageMapper;

    public QueryToolHistoryTool(ObjectMapper objectMapper,
                                ConversationMessageMapper conversationMessageMapper) {
        this.objectMapper = objectMapper;
        this.conversationMessageMapper = conversationMessageMapper;
    }

    @Override
    public String getName() {
        return "query_tool_history";
    }

    @Override
    public String getDescription() {
        return "查询当前会话中历史工具调用的详细信息。\n"
                + "【适用场景】在 compact 上下文模式下，非本轮对话的工具调用结果已被精简。"
                + "如需查看某次工具调用的完整原始结果，使用此工具按需获取。\n"
                + "【使用方式】conversation_id 可选（工具会自动从上下文检测真实会话ID），"
                + "可选 tool_name 过滤特定工具、message_id 查询指定消息、limit 控制返回条数。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // conversation_id
        ObjectNode convId = objectMapper.createObjectNode();
        convId.put("type", "integer");
        convId.put("description", "【可选】会话ID。工具会自动从上下文检测真实会话ID，通常无需手动传入。仅在需要查询其他会话时使用");
        properties.set("conversation_id", convId);

        // tool_name
        ObjectNode toolName = objectMapper.createObjectNode();
        toolName.put("type", "string");
        toolName.put("description", "【可选】工具名称过滤。只返回匹配该名称的工具调用结果。"
                + "示例：file_explorer、command、file_writer。不传则返回所有工具");
        properties.set("tool_name", toolName);

        // message_id
        ObjectNode msgId = objectMapper.createObjectNode();
        msgId.put("type", "integer");
        msgId.put("description", "【可选】指定消息ID，查询单条消息的完整内容（优先级最高）");
        properties.set("message_id", msgId);

        // limit
        ObjectNode limit = objectMapper.createObjectNode();
        limit.put("type", "integer");
        limit.put("description", "【可选】返回条数上限，默认5，最大20");
        properties.set("limit", limit);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            // 优先从 ToolContext ThreadLocal 获取真实会话ID（系统设置，不受LLM幻觉影响）
            Long contextConvId = ToolContext.getConversationId();

            long conversationId = arguments.path("conversation_id").asLong(0);
            if (conversationId <= 0 && contextConvId == null) {
                return "【缺少参数】无法确定会话ID：参数未传入且上下文不可用";
            }

            // ThreadLocal 是权威来源：LLM 即使传错也会被自动修正
            if (contextConvId != null && contextConvId.longValue() != conversationId && conversationId > 0) {
                log.warn("LLM 传入了错误的 conversation_id={}，ThreadLocal 实际为 {}，已自动修正",
                        conversationId, contextConvId);
            }
            final long effectiveConvId = contextConvId != null ? contextConvId : conversationId;

            // 如果指定了 message_id，直接查询单条消息
            long messageId = arguments.path("message_id").asLong(-1);
            if (messageId > 0) {
                ConversationMessage msg = conversationMessageMapper.selectById(messageId);
                if (msg == null) {
                    return "【未找到】消息ID " + messageId + " 不存在";
                }
                return formatSingleMessage(msg);
            }

            // 加载会话的全部消息
            List<ConversationMessage> allMessages = conversationMessageMapper.selectByConversationId(effectiveConvId);
            if (allMessages == null || allMessages.isEmpty()) {
                return "【无数据】会话 " + effectiveConvId + " 没有消息记录";
            }

            // 过滤：只保留 TOOL 消息
            String toolNameFilter = arguments.path("tool_name").asText(null);
            int limitVal = arguments.path("limit").asInt(5);
            if (limitVal < 1) limitVal = 5;
            if (limitVal > 20) limitVal = 20;

            List<ConversationMessage> toolMessages = allMessages.stream()
                    .filter(m -> m.getRole() == MessageRole.TOOL)
                    .collect(Collectors.toList());

            // 按工具名过滤（需要从关联的 assistant 消息中提取）
            if (toolNameFilter != null && !toolNameFilter.isEmpty()) {
                // 构建 tool_call_id → tool_name 的映射
                java.util.Map<String, String> tcIdToName = buildToolCallIdNameMap(allMessages);
                toolMessages = toolMessages.stream()
                        .filter(m -> {
                            return matchesToolName(m, toolNameFilter, allMessages, tcIdToName);
                        })
                        .collect(Collectors.toList());
            }

            // 取最近 limit 条
            if (toolMessages.size() > limitVal) {
                toolMessages = toolMessages.subList(toolMessages.size() - limitVal, toolMessages.size());
            }

            if (toolMessages.isEmpty()) {
                String filterInfo = toolNameFilter != null ? "（过滤条件：工具名=" + toolNameFilter + "）" : "";
                return "【无匹配结果】会话 " + effectiveConvId + " 中没有匹配的工具调用记录" + filterInfo;
            }

            // 格式化输出
            StringBuilder sb = new StringBuilder();
            sb.append("会话 ").append(effectiveConvId).append(" 的工具调用历史（最近 ")
                    .append(toolMessages.size()).append(" 条）：\n\n");

            for (int i = 0; i < toolMessages.size(); i++) {
                ConversationMessage msg = toolMessages.get(i);
                sb.append("─── 消息 #").append(msg.getId()).append(" ───\n");
                sb.append("角色：TOOL\n");
                sb.append("时间：").append(msg.getCreatedAt()).append("\n");

                // 提取关联的工具名
                String associatedToolName = findAssociatedToolName(msg, allMessages);
                if (associatedToolName != null) {
                    sb.append("工具：").append(associatedToolName).append("\n");
                }

                String content = msg.getContent();
                if (content != null && !content.isEmpty()) {
                    sb.append("结果：\n").append(content).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("查询工具历史失败", e);
            return "【查询失败】" + e.getMessage();
        }
    }

    /**
     * 格式化单条消息
     */
    private String formatSingleMessage(ConversationMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("消息 #").append(msg.getId()).append("\n");
        sb.append("角色：").append(msg.getRole()).append("\n");
        sb.append("时间：").append(msg.getCreatedAt()).append("\n");

        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            sb.append("内容：\n").append(msg.getContent()).append("\n");
        }
        if (msg.getReasoning() != null && !msg.getReasoning().isEmpty()) {
            sb.append("思考过程：\n").append(msg.getReasoning()).append("\n");
        }
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            sb.append("工具调用：\n").append(msg.getToolCalls()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建 tool_call_id → tool_name 的映射
     */
    private java.util.Map<String, String> buildToolCallIdNameMap(List<ConversationMessage> allMessages) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (ConversationMessage msg : allMessages) {
            if (msg.getRole() == MessageRole.ASSISTANT && msg.getToolCalls() != null) {
                try {
                    JsonNode tcNode = objectMapper.readTree(msg.getToolCalls());
                    if (tcNode.isArray()) {
                        for (JsonNode tc : tcNode) {
                            String id = tc.path("id").asText();
                            String name = tc.path("function").path("name").asText();
                            if (!id.isEmpty() && !name.isEmpty()) {
                                map.put(id, name);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    /**
     * 检查 TOOL 消息是否匹配指定工具名
     */
    private boolean matchesToolName(ConversationMessage toolMsg, String targetName,
                                     List<ConversationMessage> allMessages,
                                     java.util.Map<String, String> tcIdToName) {
        String associatedName = findAssociatedToolName(toolMsg, allMessages);
        return associatedName != null && associatedName.equalsIgnoreCase(targetName);
    }

    /**
     * 找到 TOOL 消息关联的工具名
     * TOOL 消息位于其对应 assistant(tool_calls) 消息之后，
     * 通过计算 tool 消息相对于 assistant 的偏移索引精确匹配 tool_call。
     */
    private String findAssociatedToolName(ConversationMessage toolMsg, List<ConversationMessage> allMessages) {
        // 在 allMessages 中找到该 tool 消息的索引
        int toolIdx = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i).getId().equals(toolMsg.getId())) {
                toolIdx = i;
                break;
            }
        }
        if (toolIdx < 0) return null;

        // 向前查找最近的 assistant 消息（含 tool_calls）
        for (int i = toolIdx - 1; i >= 0; i--) {
            ConversationMessage prev = allMessages.get(i);
            if (prev.getRole() == MessageRole.ASSISTANT && prev.getToolCalls() != null) {
                try {
                    JsonNode tcNode = objectMapper.readTree(prev.getToolCalls());
                    if (tcNode.isArray() && tcNode.size() > 0) {
                        // 计算当前 tool 消息是 assistant 后第几个 tool 消息（从0开始）
                        int toolOffset = 0;
                        for (int j = i + 1; j < toolIdx; j++) {
                            if (allMessages.get(j).getRole() == MessageRole.TOOL) {
                                toolOffset++;
                            }
                        }
                        // 按偏移索引取对应的 tool_call
                        if (toolOffset < tcNode.size()) {
                            return tcNode.get(toolOffset).path("function").path("name").asText();
                        }
                        // 偏移越界时回退到第一个（兼容异常数据）
                        return tcNode.get(0).path("function").path("name").asText();
                    }
                } catch (Exception ignored) {
                }
            }
            // 如果遇到 user 或 system 消息，停止向前查找
            if (prev.getRole() == MessageRole.USER || prev.getRole() == MessageRole.SYSTEM) {
                break;
            }
        }
        return null;
    }
}
