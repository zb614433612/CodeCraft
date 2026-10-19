package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.SubAgentLogMapper;
import com.example.agentdeepseek.model.entity.SubAgentLog;
import com.example.agentdeepseek.model.vo.SubAgentVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 子Agent执行记录查询（只读）。
 *
 * <p><b>为什么是「读库」而不是「收事件」</b>：后端 {@code AgentEventBus} 只发
 * {@code ask_user} / {@code tool_call_start} / {@code task_error}，
 * {@code AgentInvokeServiceImpl} 另发 {@code delegate_progress}（跨智能体委托），
 * 而 {@code AgentForkManager}（fork 子Agent）<b>从不发任何 SSE 事件</b> ——
 * 它的产出是以纯文本写进主Agent思考流的。所以原先前端解析器里那条
 * {@code agent_event} 分支永远是死代码（旧版 {@code AgentPanel.vue} 因此一直空着）。</p>
 *
 * <p>本接口直接读 {@code sub_agent_log}：fork 时 INSERT（status=running），
 * 执行结束 UPDATE（completed/failed/timeout + 摘要 + 文件变更）。
 * 前端在「流式期间轮询 + 会话切回时拉一次」即可同时覆盖实时与历史回放，且不虚构任何数据。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/sub-agents")
@Tag(name = "子Agent", description = "子Agent执行记录查询（只读）")
public class SubAgentController {

    /** 服务端统一格式化时间，避免前端各自处理 LocalDateTime 的序列化差异 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单个子Agent的文件清单最多返回多少条（面板只做概览，全量在 inspect / diff 里看） */
    private static final int FILE_LIMIT = 60;

    private final SubAgentLogMapper subAgentLogMapper;
    private final ObjectMapper objectMapper;

    public SubAgentController(SubAgentLogMapper subAgentLogMapper, ObjectMapper objectMapper) {
        this.subAgentLogMapper = subAgentLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询某个会话下创建过的全部子Agent（按创建时间升序）。
     *
     * @param conversationId 主Agent会话 ID（必填）
     * @param turnId         可选：只返回某一轮（用户消息 turnId）创建的子Agent
     */
    @GetMapping
    @Operation(summary = "查询会话的子Agent列表", description = "读 sub_agent_log，按创建时间升序返回；running 记录即为后台仍在执行的子Agent")
    public ApiResponse<List<SubAgentVO>> list(
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String turnId) {

        if (conversationId == null || conversationId <= 0) {
            return ApiResponse.success(new ArrayList<>());
        }

        List<SubAgentLog> rows;
        try {
            // 轻量查询：不取 full_messages（单条可达数十 KB，面板用不上）
            rows = subAgentLogMapper.selectMetaByConversationId(conversationId);
        } catch (Exception e) {
            // 列表查询失败属可降级场景（面板显示空态），不把整页聊天带崩
            log.warn("[SubAgent] 查询子Agent列表失败: conversationId={}, err={}", conversationId, e.getMessage());
            return ApiResponse.success(new ArrayList<>());
        }

        List<SubAgentVO> result = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return ApiResponse.success(result);
        }
        boolean filterByTurn = turnId != null && !turnId.isBlank();
        for (SubAgentLog row : rows) {
            if (row == null) continue;
            if (filterByTurn && !turnId.equals(row.getParentTurnId())) continue;
            result.add(toVO(row));
        }
        return ApiResponse.success(result);
    }

    // ==================== 内部转换 ====================

    private SubAgentVO toVO(SubAgentLog row) {
        SubAgentVO vo = new SubAgentVO();
        vo.setId(row.getId());
        vo.setAgentId(row.getAgentId());
        vo.setName(row.getName());
        vo.setStatus(normalizeStatus(row.getStatus()));
        vo.setInstructions(row.getInstructions());
        vo.setContextMode(row.getContextMode());
        vo.setTools(parseJsonArray(row.getTools()));
        vo.setSkills(parseJsonArray(row.getSkills()));
        vo.setSummary(row.getSummary());
        vo.setCompileResult(row.getCompileResult());
        vo.setIterationsUsed(row.getIterationsUsed() != null ? row.getIterationsUsed() : 0);
        vo.setMaxIterations(row.getMaxIterations() != null ? row.getMaxIterations() : 0);
        vo.setErrorMessage(row.getErrorMessage());
        vo.setParentTurnId(row.getParentTurnId());
        fillFileChanges(vo, row.getFileChanges());
        vo.setCreatedAt(format(row.getCreatedAt()));
        vo.setCompletedAt(format(row.getCompletedAt()));
        // 兜底：老记录可能没有 completedAt 但状态已终态，用 updatedAt 近似「结束时间」
        if (vo.getCompletedAt() == null && !"running".equals(vo.getStatus())) {
            vo.setCompletedAt(format(row.getUpdatedAt()));
        }
        return vo;
    }

    /** 状态归一：库里的值来自 SubAgentResult，未知值统一落 running（面板据此显示进行中） */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "running";
        String s = status.trim().toLowerCase();
        if (s.equals("completed") || s.equals("failed") || s.equals("timeout") || s.equals("running")) {
            return s;
        }
        return s;
    }

    /**
     * 解析工具/技能列（库中为 JSON 数组字符串，如 {@code ["file_writer","command"]}）。
     * 解析不出就当空列表 —— 面板少展示一行，但不会因此报错。
     */
    private List<String> parseJsonArray(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.isBlank() || "null".equals(json)) return list;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    String text = item.asText("");
                    if (!text.isBlank()) list.add(text);
                }
            }
        } catch (Exception e) {
            log.debug("[SubAgent] 解析 JSON 数组失败（忽略）: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 解析文件变更列：{@code {"created":[],"modified":[],"deleted":[]}}（见 AgentForkManager#updateLogResult）。
     * running 期间该列为空 —— 保持三个空列表，前端据此不渲染「文件变更」区块。
     */
    private void fillFileChanges(SubAgentVO vo, String json) {
        vo.setCreatedFiles(new ArrayList<>());
        vo.setModifiedFiles(new ArrayList<>());
        vo.setDeletedFiles(new ArrayList<>());
        if (json == null || json.isBlank() || "{}".equals(json)) return;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) return;
            vo.setCreatedFiles(textList(root.path("created")));
            vo.setModifiedFiles(textList(root.path("modified")));
            vo.setDeletedFiles(textList(root.path("deleted")));
        } catch (Exception e) {
            log.debug("[SubAgent] 解析文件变更失败（忽略）: {}", e.getMessage());
        }
    }

    private List<String> textList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) return list;
        for (JsonNode item : node) {
            if (list.size() >= FILE_LIMIT) break;
            String text = item.asText("");
            if (!text.isBlank()) list.add(text);
        }
        return list;
    }

    private String format(LocalDateTime time) {
        return time == null ? null : TS.format(time);
    }
}
