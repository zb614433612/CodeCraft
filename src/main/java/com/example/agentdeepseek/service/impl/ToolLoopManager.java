package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.model.SubAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.*;

/**
 * 工具调用循环辅助组件
 * <p>
 * 从 DeepSeekServiceImpl 拆分出来，提供工具循环中的辅助方法：
 * - 重复调用检测
 * - SSE 事件创建
 * - 工具结果格式化
 * - 评委评估上下文构建
 * - 任务取消与状态更新
 * </p>
 */
@Slf4j
@Component
public class ToolLoopManager {

    private final DeepSeekConfig deepSeekConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;
    private final AgentEventBus agentEventBus;

    public ToolLoopManager(DeepSeekConfig deepSeekConfig,
                           JdbcTemplate jdbcTemplate,
                           AgentEventBus agentEventBus) {
        this.deepSeekConfig = deepSeekConfig;
        this.jdbcTemplate = jdbcTemplate;
        this.agentEventBus = agentEventBus;
    }

    // ==================== 评委评估内部类 ====================

    /**
     * 评委判断结果
     */
    public static class JudgeResult {
        public String judgment; // "extend" or "reject"
        public String reason;
        public int additionalIterations;
        public String summary;
    }

    // ==================== 任务管理 ====================

    /**
     * 取消正在运行的后台任务
     * @param conversationId 会话ID
     * @param taskSubscriptions 任务订阅映射（由调用方持有）
     * @param judgeGrantedIterations 评委授予映射（由调用方持有）
     */
    public void cancelRunningTask(Long conversationId,
                                   Map<Long, Disposable> taskSubscriptions,
                                   Map<Long, Integer> judgeGrantedIterations) {
        Disposable sub = taskSubscriptions.remove(conversationId);
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
            log.info("取消会话 {} 的正在运行的后台任务", conversationId);
        }
        agentEventBus.unregister(conversationId);
        judgeGrantedIterations.remove(conversationId);
        try {
            jdbcTemplate.update("UPDATE agent_task SET status = 'cancelled', updated_at = NOW() WHERE conversation_id = ? AND status = 'running'", conversationId);
        } catch (Exception e) {
            log.debug("取消任务记录失败: {}", e.getMessage());
        }
    }

    /**
     * 持久化/清除待审批问题到任务记录（支持页面刷新后重连展示审批对话框）
     */
    public void updatePendingQuestion(Long conversationId, String uuid, String text) {
        try {
            if (uuid != null) {
                jdbcTemplate.update("UPDATE agent_task SET pending_question_uuid = ?, pending_question_text = ?, updated_at = NOW() WHERE conversation_id = ? AND status = 'running' ORDER BY id DESC LIMIT 1",
                        uuid, text, conversationId);
            } else {
                jdbcTemplate.update("UPDATE agent_task SET pending_question_uuid = NULL, pending_question_text = NULL, updated_at = NOW() WHERE conversation_id = ? AND status = 'running' ORDER BY id DESC LIMIT 1",
                        conversationId);
            }
        } catch (Exception e) {
            log.debug("更新待审批问题失败: {}", e.getMessage());
        }
    }

    // ==================== 重复调用检测 ====================

    /**
     * 检测最近连续的工具调用是否重复（相同工具+相同关键参数）
     */
    public boolean hasRepeatedCalls(List<Map<String, Object>> messages, int threshold) {
        int repeatCount = 0;
        String lastPattern = null;

        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role")) || !msg.containsKey("tool_calls")) {
                continue;
            }

            JsonNode toolCalls = (JsonNode) msg.get("tool_calls");
            String pattern = buildToolCallPattern(toolCalls);
            if (pattern == null || pattern.isEmpty()) continue;

            if (lastPattern == null) {
                lastPattern = pattern;
                repeatCount = 1;
            } else if (pattern.equals(lastPattern)) {
                repeatCount++;
                if (repeatCount >= threshold) return true;
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * 构建工具调用的特征签名，用于比较是否重复
     */
    public String buildToolCallPattern(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) return "";
        List<String> signatures = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            String name = call.path("function").path("name").asText("");
            String args = call.path("function").path("arguments").asText("");
            signatures.add(extractToolKey(name, args));
        }
        Collections.sort(signatures);
        return String.join("|", signatures);
    }

    /**
     * 提取工具调用的关键参数标识，忽略不影响结果的参数差异
     */
    public String extractToolKey(String toolName, String argsJson) {
        if (argsJson == null || argsJson.isEmpty() || "null".equals(argsJson)) {
            return toolName;
        }
        try {
            JsonNode args = objectMapper.readTree(argsJson);
            return switch (toolName) {
                case "read_file", "write_file", "edit_file", "delete_file" -> {
                    String path = args.path("file_path").asText("");
                    if (path.isEmpty()) path = args.path("path").asText("");
                    yield toolName + ":" + (path.isEmpty() ? "*" : path);
                }
                case "run_command", "run_server" -> {
                    String cmd = args.path("command").asText("");
                    yield toolName + ":" + (cmd.isEmpty() ? "*" : cmd);
                }
                case "execute_sql" -> {
                    String sql = args.path("sql").asText("");
                    yield toolName + ":" + (sql.isEmpty() ? "*" : sql);
                }
                case "git_add" -> {
                    String files = args.path("files").asText("");
                    yield toolName + ":" + (files.isEmpty() ? "*" : files);
                }
                case "git_commit" -> {
                    String msg = args.path("message").asText("");
                    yield toolName + ":" + (msg.isEmpty() ? "*" : msg);
                }
                case "git_push", "service_control" -> {
                    String action = args.path("action").asText("");
                    yield toolName + ":" + (action.isEmpty() ? "*" : action);
                }
                default -> toolName;
            };
        } catch (Exception e) {
            return toolName;
        }
    }

    // ==================== 子Agent汇总 ====================

    /**
     * 构建子Agent结果汇总输入文本
     */
    public String buildSubAgentSummaryInput(List<SubAgentResult> subResults) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < subResults.size(); i++) {
            sb.append("===== 子Agent ").append(i + 1).append(" =====\n");
            sb.append(subResults.get(i).toResultString()).append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 工具调用完整性 ====================

    /**
     * 检查所有工具调用参数是否已完整接收
     */
    public boolean isToolCallsComplete(Map<Integer, ObjectNode> accumulatedToolCalls) {
        if (accumulatedToolCalls.isEmpty()) {
            return false;
        }
        for (ObjectNode toolCall : accumulatedToolCalls.values()) {
            JsonNode function = toolCall.path("function");
            if (function.isMissingNode()) {
                return false;
            }
            JsonNode arguments = function.path("arguments");
            if (arguments.isMissingNode() || arguments.asText("").isEmpty()) {
                return false;
            }
            String argsText = arguments.asText();
            try {
                objectMapper.readTree(argsText);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将累积的工具调用映射转换为JsonNode数组
     */
    public JsonNode convertAccumulatedToolCallsToArray(Map<Integer, ObjectNode> accumulatedToolCalls) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        accumulatedToolCalls.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> arrayNode.add(entry.getValue()));
        return arrayNode;
    }

    // ==================== SSE 事件创建 ====================

    /**
     * 创建包含reasoning_content的SSE事件
     */
    public String createReasoningSSEEvent(String reasoningContent) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("id", UUID.randomUUID().toString());
            root.put("object", "chat.completion.chunk");
            root.put("created", Instant.now().getEpochSecond());
            root.put("model", deepSeekConfig.getDefaultModel());
            root.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().substring(0, 8));

            ObjectNode choices = objectMapper.createObjectNode();
            choices.put("index", 0);

            ObjectNode delta = objectMapper.createObjectNode();
            delta.putNull("content");
            delta.put("reasoning_content", reasoningContent != null ? reasoningContent : "");

            choices.set("delta", delta);
            choices.putNull("logprobs");
            choices.putNull("finish_reason");

            ArrayNode choicesArray = objectMapper.createArrayNode();
            choicesArray.add(choices);
            root.set("choices", choicesArray);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("创建SSE事件失败", e);
            return "{\"error\": \"无法生成工具调用事件\"}";
        }
    }

    /**
     * 创建工具调用开始的SSE事件
     */
    public String createToolCallStartEvent(List<String> toolNames) {
        String msg;
        if (toolNames.isEmpty()) {
            msg = "正在调用工具...";
        } else {
            msg = "调用 " + String.join(", ", toolNames);
        }
        return createReasoningSSEEvent(msg);
    }

    /**
     * 从 tool_calls JSON 数组中提取工具名称
     */
    public List<String> extractToolNames(JsonNode toolCalls) {
        List<String> names = new ArrayList<>();
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode func = call.path("function");
                String name = func.path("name").asText("");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * 格式化工具调用结果
     */
    public String formatToolResult(String toolResult, String toolName) {
        String dashLine = "-".repeat(48);
        String header = dashLine + "工具调用:" + dashLine;
        String toolNameLine = (toolName != null && !toolName.isEmpty()) ? "> **" + toolName + "**\n" : "";

        if (toolResult == null || toolResult.isEmpty()) {
            return "\n\n" + header + "\n" + toolNameLine + "\n\n";
        }
        return "\n\n" + header + "\n" + toolNameLine + toolResult + "\n\n";
    }

    /**
     * 创建工具调用结果的SSE事件（以 thinking 事件发送，前端按标记折叠展示）
     */
    public String createToolResultEvent(String toolName, String toolResult) {
        String dashLine = "-".repeat(48);
        String header = dashLine + "工具调用:" + dashLine;
        String toolNameLine = "> **" + toolName + "**";
        if (toolResult == null || toolResult.isEmpty()) {
            return createReasoningSSEEvent("\n\n" + header + "\n" + toolNameLine + "\n\n");
        }
        return createReasoningSSEEvent("\n\n" + header + "\n" + toolNameLine + "\n" + toolResult + "\n\n");
    }

    /**
     * 创建 ask_user 事件的 SSE 事件
     */
    public String createAskUserEvent(String uuid, String question, String askType) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("event", "ask_user");
            root.put("uuid", uuid);
            root.put("question", question);
            root.put("askType", askType != null ? askType : "clarification");
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("创建ask_user事件失败", e);
            return "{\"event\":\"error\",\"message\":\"创建ask_user事件失败\"}";
        }
    }

    /**
     * 创建恢复事件的 SSE 事件（通知前端继续接收流）
     */
    public String createResumeEvent() {
        return "{\"event\":\"resume\"}";
    }

    // ==================== 评委评估 ====================

    /**
     * 解析评委 JSON 响应
     */
    public JudgeResult parseJudgeResult(String response) {
        try {
            if (response.startsWith("错误：")) {
                log.warn("评委 API 调用返回错误: {}", response);
                return null;
            }
            String jsonStr = response;
            if (jsonStr.contains("```json")) {
                jsonStr = jsonStr.substring(jsonStr.indexOf("```json") + 7);
                jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
            } else if (jsonStr.contains("```")) {
                jsonStr = jsonStr.substring(jsonStr.indexOf("```") + 3);
                jsonStr = jsonStr.substring(0, jsonStr.indexOf("```"));
            }
            jsonStr = jsonStr.trim();

            JsonNode root = objectMapper.readTree(jsonStr);
            JudgeResult result = new JudgeResult();
            result.judgment = root.path("judgment").asText("");
            result.reason = root.path("reason").asText("");
            result.additionalIterations = root.path("additional_iterations").asInt(10);
            result.summary = root.path("summary").asText("");

            if (!"extend".equals(result.judgment) && !"reject".equals(result.judgment)) {
                log.warn("评委返回未知判断: {}", result.judgment);
                return null;
            }
            if (result.judgment.isEmpty()) return null;

            return result;
        } catch (Exception e) {
            log.warn("解析评委响应失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建评委评估上下文
     */
    public String buildJudgeContext(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();

        // ===== 1. 提取多轮对话历史 =====
        sb.append("## 对话历史（多轮）\n");
        int round = 0;
        for (Map<String, Object> msg : messages) {
            if ("user".equals(msg.get("role"))) {
                round++;
                String uc = (String) msg.get("content");
                if (uc != null && !uc.isEmpty()) {
                    String truncated = uc.length() > 300 ? uc.substring(0, 300) + "..." : uc;
                    sb.append("[轮次 ").append(round).append("] ").append(truncated).append("\n");
                }
            }
        }
        if (round == 0) {
            sb.append("(无用户消息)\n");
        }

        // ===== 2. 提取工具调用历史摘要 =====
        sb.append("\n## 工具调用历史\n");
        int iter = 0;
        int currentRound = 0;

        List<Integer> userMsgPositions = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).get("role"))) {
                userMsgPositions.add(i);
            }
        }

        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            for (int pos : userMsgPositions) {
                if (i == pos) {
                    currentRound++;
                    break;
                }
            }
            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls")) {
                JsonNode toolCalls = (JsonNode) msg.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String name = tc.path("function").path("name").asText("");
                        String args = tc.path("function").path("arguments").asText("");
                        if (args.length() > 200) args = args.substring(0, 200) + "...";
                        sb.append("  [R").append(currentRound).append("][I").append(iter).append("] ")
                                .append(name).append("(").append(args).append(")\n");
                    }
                }
                iter++;
            }
        }

        // ===== 3. 重复检测 =====
        boolean hasRepeat = hasRepeatedCalls(messages, 3);
        sb.append("\n## 重复检测\n");
        sb.append("连续重复调用: ").append(hasRepeat ? "是（可能存在死循环）" : "否（调用模式正常）").append("\n");
        sb.append("总迭代次数: ").append(iter).append("\n");

        return sb.toString();
    }
}
