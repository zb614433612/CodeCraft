package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.SubAgentResult;
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
 * 多Agent协作工具 — 合并 fork_agent / collect_agent / inspect_agent
 * 通过 action 参数区分操作，覆盖创建子Agent、收集结果、查看详情三大能力。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = false, description = "多Agent协作（创建/收集/查看）")
public class AgentTool implements Tool {

    private final ObjectMapper objectMapper;
    private final AgentForkManager agentForkManager;
    private final ConversationMessageMapper messageMapper;

    public AgentTool(ObjectMapper objectMapper, @Lazy AgentForkManager agentForkManager,
                     ConversationMessageMapper messageMapper) {
        this.objectMapper = objectMapper;
        this.agentForkManager = agentForkManager;
        this.messageMapper = messageMapper;
    }

    // ==================== Tool 接口 ====================

    @Override
    public String getName() { return "agent"; }

    @Override
    public String getDescription() {
        return "【适用场景】多Agent协作一站式工具，通过 action 参数选择操作模式。\n"
                + "【action 说明】\n"
                + "  fork    — 创建子Agent在后台独立执行任务\n"
                + "  collect — 阻塞等待并收集单个子Agent的执行结果\n"
                + "  batch_collect — 一次性批量收集多个子Agent的结果（推荐：fork 完所有子Agent后一次性收集）\n"
                + "  inspect — 查看子Agent的执行详情（diff/thinking/调用历史）\n"
                + "【典型工作流】fork 创建多个子Agent → batch_collect 一次性收集所有结果 → 汇总输出\n"
                + "【注意事项】\n"
                + "  1) 创建子Agent后必须等待所有子Agent完成并收集结果，才能给出最终回答\n"
                + "  2) collect 会阻塞等待，timeout 默认120秒（建议300秒）\n"
                + "  3) batch_collect 一次性传入所有 agent_id 数组，默认超时300秒，省去逐轮 LLM 等待\n"
                + "  4) 最多同时运行 20 个子Agent，batch_collect 完成后释放并发槽位";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        // === action（公共必填） ===
        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】操作类型。fork=创建子Agent；collect=收集单个结果；batch_collect=批量收集所有结果；inspect=查看详情。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("fork");
        actionEnum.add("collect");
        actionEnum.add("batch_collect");
        actionEnum.add("inspect");

        // === fork 参数 ===
        ObjectNode agentId = props.putObject("agent_id");
        agentId.put("type", "string");
        agentId.put("description", "【fork/collect 时必填，inspect 可选】子Agent唯一标识，如 sub-dal-1。后续通过此ID收集和查看。");

        // === batch_collect 参数 ===
        ObjectNode agentIds = props.putObject("agent_ids");
        agentIds.put("type", "array");
        agentIds.put("description", "【batch_collect 时必填】要批量收集的子Agent ID数组。示例：[\"sub-1\", \"sub-2\", \"sub-3\"]。会把所有 fork 创建的 agent_id 一次性传入。");
        ObjectNode agentIdsItem = objectMapper.createObjectNode();
        agentIdsItem.put("type", "string");
        agentIds.set("items", agentIdsItem);

        ObjectNode name = props.putObject("name");
        name.put("type", "string");
        name.put("description", "【fork 时必填】子Agent名称，如「数据访问层开发」。用于前端展示和日志标记。");

        ObjectNode instructions = props.putObject("instructions");
        instructions.put("type", "string");
        instructions.put("description", "【fork 时必填】子Agent的完整任务描述。越详细越好：目标、输入、输出、约束条件、注意事项等。");

        ObjectNode tools = props.putObject("tools");
        tools.put("type", "array");
        tools.put("description", "【fork 时必填】子Agent可用的工具列表。示例：[\"file_explorer\", \"file_writer\", \"command\"]");
        ObjectNode toolItem = objectMapper.createObjectNode();
        toolItem.put("type", "string");
        tools.set("items", toolItem);

        ObjectNode skills = props.putObject("skills");
        skills.put("type", "array");
        skills.put("description", "【fork 可选】子Agent可用的技能名称或ID列表。示例：[\"编码任务标准化流程\"] 或 [19]");
        ObjectNode skillItem = objectMapper.createObjectNode();
        skillItem.put("type", "string");
        skills.set("items", skillItem);

        ObjectNode contextMode = props.putObject("context_mode");
        contextMode.put("type", "string");
        contextMode.put("description", "【fork 可选，默认 inherit_summary】上下文继承模式。inherit_summary=继承父Agent上下文摘要；inherit_full=继承完整上下文（token消耗大）；none=不继承。");
        ArrayNode contextModeEnum = contextMode.putArray("enum");
        contextModeEnum.add("inherit_summary");
        contextModeEnum.add("inherit_full");
        contextModeEnum.add("none");

        ObjectNode maxIterations = props.putObject("max_iterations");
        maxIterations.put("type", "integer");
        maxIterations.put("description", "【fork 可选，默认30】子Agent最大迭代次数。");

        // === collect 参数 ===
        ObjectNode timeout = props.putObject("timeout");
        timeout.put("type", "integer");
        timeout.put("description", "【collect/batch_collect 可选，默认120/300】最大等待超时秒数。collect 默认120s；batch_collect 默认300s。");

        // === inspect 参数 ===
        ObjectNode scope = props.putObject("scope");
        scope.put("type", "string");
        scope.put("description", "【inspect 时必填】查看范围。summary=完整执行结果摘要（含文件变更/编译结果）；diff=文件变更JSON；thinking=思考过程；calls=工具调用历史；full_log=完整执行日志。");
        ArrayNode scopeEnum = scope.putArray("enum");
        scopeEnum.add("summary");
        scopeEnum.add("diff");
        scopeEnum.add("thinking");
        scopeEnum.add("calls");
        scopeEnum.add("full_log");

        ArrayNode required = root.putArray("required");
        required.add("action");
        return root;
    }

    // ==================== 执行入口 ====================

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText("");
        if (action.isEmpty()) {
            return "【参数缺失】'action' 参数缺失或为空。agent 的 action 必须设为 'fork' / 'collect' / 'batch_collect' / 'inspect' 之一。\n"
                    + "正确示例：{ \"action\": \"fork\", \"agent_id\": \"sub-1\", \"name\": \"子任务\", \"instructions\": \"...\" }\n"
                    + "错误示例：{ \"agent_id\": \"sub-1\" } ← 缺少 action！";
        }
        return switch (action) {
            case "fork"         -> doFork(arguments);
            case "collect"      -> doCollect(arguments);
            case "batch_collect" -> doBatchCollect(arguments);
            case "inspect"      -> doInspect(arguments);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 fork / collect / batch_collect / inspect 四种取值，请改为其中之一。";
        };
    }

    // ================================================================
    //                      action=fork（原 fork_agent）
    // ================================================================

    private String doFork(JsonNode args) {
        try {
            String agentId = args.path("agent_id").asText();
            String name = args.path("name").asText();
            String instructions = args.path("instructions").asText();
            String contextModeStr = args.path("context_mode").asText("inherit_summary");
            int maxIter = safeInt(args, "max_iterations", 30);

            if (agentId.isEmpty()) return "【参数缺失】action=fork 需要 agent_id 参数。示例：agent_id=\"sub-dal-1\"";
            if (name.isEmpty()) return "【参数缺失】action=fork 需要 name 参数。示例：name=\"数据访问层开发\"";
            if (instructions.isEmpty()) return "【参数缺失】action=fork 需要 instructions 参数。请提供子Agent的详细任务描述。";

            List<String> toolsList = parseStringList(args, "tools");
            if (toolsList.isEmpty()) return "【参数缺失】action=fork 需要 tools 参数。示例：tools=[\"file_explorer\", \"file_writer\", \"command\"]";

            List<String> skillsList = parseStringList(args, "skills");

            Long conversationId = ToolContext.getConversationId();
            String turnId = ToolContext.getTurnId();
            Long userId = ToolContext.getUserId();
            String mode = ToolContext.getMode();

            if (conversationId == null) {
                return "【错误】无法获取当前会话ID，请在对话中调用此工具";
            }

            ForkAgentRequest request = new ForkAgentRequest();
            request.setAgentId(agentId);
            request.setName(name);
            request.setInstructions(instructions);
            request.setTools(toolsList);
            request.setSkills(skillsList);
            request.setContextMode(contextModeStr);
            request.setMaxIterations(maxIter);
            request.setTemperature(ToolContext.getTemperature());

            String parentContext = buildParentContext(conversationId, contextModeStr);
            agentForkManager.forkAgent(request, conversationId, turnId, parentContext, userId, mode);

            log.info("子Agent创建成功: agentId={}, name={}, tools={}, skills={}", agentId, name, toolsList, skillsList);

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 子Agent「").append(name).append("」(ID: ").append(agentId).append(") 已创建并在后台运行\n\n");
            sb.append("【任务描述】").append(instructions.length() > 100 ? instructions.substring(0, 100) + "..." : instructions).append("\n");
            sb.append("【可用工具】").append(String.join(", ", toolsList)).append("\n");
            if (!skillsList.isEmpty()) {
                sb.append("【可用技能】").append(String.join(", ", skillsList)).append("\n");
            }
            sb.append("【上下文模式】").append(contextModeStr).append("\n");
            sb.append("【最大迭代】").append(maxIter).append(" 次\n\n");
            sb.append("【注意】子Agent已在后台独立运行。建议继续 fork 其他子Agent（如需要），"
                    + "全部创建完成后，用 agent action=batch_collect agent_ids=[\"")
                    .append(agentId).append("\", ...] 一次性收集所有结果并汇总。");

            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "【参数错误】" + e.getMessage();
        } catch (IllegalStateException e) {
            return "【并发限制】" + e.getMessage() + "。请先 collect / batch_collect 已完成的任务后再创建新的子Agent。";
        } catch (Exception e) {
            log.error("创建子Agent失败", e);
            return "【创建失败】" + e.getMessage();
        }
    }

    // ================================================================
    //                      action=collect（原 collect_agent）
    // ================================================================

    private String doCollect(JsonNode args) {
        try {
            String agentId = args.path("agent_id").asText();
            if (agentId.isEmpty()) {
                return "【参数缺失】action=collect 需要 agent_id 参数。\n"
                        + "请回顾之前的 agent action=fork 操作确认 agent_id，然后重试。";
            }
            int collectTimeout = safeInt(args, "timeout", 120);
            log.info("阻塞收集子Agent结果: agentId={}, timeout={}s", agentId, collectTimeout);

            SubAgentResult result = agentForkManager.collectAgent(agentId, collectTimeout);
            return result.toResultString();
        } catch (Exception e) {
            log.error("收集子Agent结果失败", e);
            return "【收集失败】" + e.getMessage();
        }
    }

    // ================================================================
    //                      action=batch_collect
    // ================================================================

    private String doBatchCollect(JsonNode args) {
        try {
            List<String> agentIds = parseStringList(args, "agent_ids");
            if (agentIds.isEmpty()) {
                return "【参数缺失】action=batch_collect 需要 agent_ids 参数（字符串数组）。\n"
                        + "示例：agent_ids=[\"sub-1\", \"sub-2\", \"sub-3\"]";
            }
            int collectTimeout = safeInt(args, "timeout", 300);

            log.info("批量收集子Agent结果: agentIds={}, timeout={}s", agentIds, collectTimeout);
            List<SubAgentResult> results = agentForkManager.batchCollectAgents(agentIds, collectTimeout);

            return formatBatchResults(results);
        } catch (Exception e) {
            log.error("批量收集子Agent结果失败", e);
            return "【批量收集失败】" + e.getMessage();
        }
    }

    // ================================================================
    //                      action=inspect（原 inspect_agent）
    // ================================================================

    private String doInspect(JsonNode args) {
        try {
            String agentId = args.path("agent_id").asText();
            if (agentId.isEmpty()) {
                return "【参数缺失】action=inspect 需要 agent_id 参数。\n"
                        + "请回顾之前的 agent action=fork 操作确认 agent_id，然后重试。";
            }
            String scopeStr = args.path("scope").asText("summary");
            if (scopeStr.isEmpty()) {
                return "【参数缺失】action=inspect 需要 scope 参数。可选值：summary / diff / thinking / calls / full_log";
            }

            log.info("查看子Agent详情: agentId={}, scope={}", agentId, scopeStr);
            return agentForkManager.inspectAgent(agentId, scopeStr);
        } catch (Exception e) {
            log.error("查看子Agent详情失败", e);
            return "【查询失败】" + e.getMessage();
        }
    }

    // ==================== 工具方法 ====================

    private List<String> parseStringList(JsonNode args, String key) {
        List<String> result = new ArrayList<>();
        JsonNode node = args.path(key);
        if (node.isArray()) {
            for (JsonNode item : node) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private int safeInt(JsonNode args, String key, int defaultVal) {
        if (!args.has(key) || args.get(key).isNull()) return defaultVal;
        try {
            return args.get(key).asInt();
        } catch (Exception e) {
            log.warn("agent {}: 解析失败，使用默认值 {}", key, defaultVal);
            return defaultVal;
        }
    }

    private String buildParentContext(Long conversationId, String contextMode) {
        if (conversationId == null || "none".equals(contextMode)) return null;

        List<ConversationMessage> messages = messageMapper.selectByConversationId(conversationId);
        if (messages == null || messages.isEmpty()) return null;

        if ("inherit_full".equals(contextMode)) {
            StringBuilder sb = new StringBuilder("【父Agent对话历史】\n");
            for (ConversationMessage msg : messages) {
                String role = msg.getRole() != null ? msg.getRole().getValue() : "unknown";
                String content = msg.getContent();
                if (content == null) content = "";
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

    /**
     * 将批量收集结果格式化为文本，供父Agent消费
     * 每个子Agent展示状态 + 摘要 + 文件变更，末尾给出总体统计
     */
    private String formatBatchResults(List<SubAgentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 批量收集子Agent结果（共 ").append(results.size()).append(" 个）\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        int completed = 0, failed = 0, timeout = 0;
        List<String> allModified = new ArrayList<>();
        List<String> allCreated = new ArrayList<>();

        for (SubAgentResult r : results) {
            String icon;
            switch (r.getStatus()) {
                case "completed": icon = "✅"; completed++; break;
                case "failed":    icon = "❌"; failed++; break;
                case "timeout":   icon = "⏰"; timeout++; break;
                default:          icon = "❓";
            }
            sb.append("\n").append(icon).append(" 【").append(r.getAgentId()).append("】").append(r.getStatus());

            if (r.getSummary() != null && !r.getSummary().isEmpty()) {
                // 摘要截断前 120 字符（批量模式节省空间）
                String summary = r.getSummary();
                if (summary.length() > 120) summary = summary.substring(0, 120) + "...";
                sb.append(" — ").append(summary);
            }

            if (r.getModifiedFiles() != null) allModified.addAll(r.getModifiedFiles());
            if (r.getCreatedFiles() != null) allCreated.addAll(r.getCreatedFiles());
        }

        sb.append("\n\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("【总体统计】✅ 完成 ").append(completed)
                .append(" | ❌ 失败 ").append(failed)
                .append(" | ⏰ 超时 ").append(timeout).append("\n");

        if (!allModified.isEmpty()) {
            sb.append("【修改文件】").append(String.join(", ", allModified)).append("\n");
        }
        if (!allCreated.isEmpty()) {
            sb.append("【新建文件】").append(String.join(", ", allCreated)).append("\n");
        }

        if (failed > 0 || timeout > 0) {
            sb.append("\n⚠️ 存在失败/超时的子Agent，请检查上面标记 ❌/⏰ 的条目，"
                    + "必要时用 agent action=inspect 查看详情或重试。\n");
        }

        return sb.toString();
    }
}
