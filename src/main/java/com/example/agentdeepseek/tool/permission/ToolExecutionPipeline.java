package com.example.agentdeepseek.tool.permission;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 三层防护执行管道 — 取代工具类内部的零散权限检查。
 *
 * <pre>
 * 阶段 1: 三层权限检查
 *   1.1 层面一: manual + affectsData → 弹窗授权
 *   1.2 层面二: manual + isPathSensitive + 路径越界 → 弹窗授权（auto 模式放行）
 *   1.3 层面三: auto + highRisk → 弹窗授权
 * 阶段 2: 审计日志
 * 阶段 3: 执行工具
 * </pre>
 */
@Slf4j
@Component
public class ToolExecutionPipeline {

    private final ToolPermissionRegistry permissionRegistry;
    private final PathSecurityChecker pathSecurityChecker;
    private final ToolAuditLogger auditLogger;

    public ToolExecutionPipeline(ToolPermissionRegistry permissionRegistry,
                                  PathSecurityChecker pathSecurityChecker,
                                  ToolAuditLogger auditLogger) {
        this.permissionRegistry = permissionRegistry;
        this.pathSecurityChecker = pathSecurityChecker;
        this.auditLogger = auditLogger;
    }

    /**
     * 管道执行入口
     *
     * @return 执行结果字符串；若被权限拦截则返回拒绝信息
     */
    public String execute(Tool tool, String toolName, JsonNode arguments,
                          String executionMode, Long conversationId, Long userId) {

        ToolPermissionMetadata meta = permissionRegistry.getMetadata(toolName);

        // ===== 阶段 0：会话级别自动批准 =====
        // 如果用户选择了「本轮对话全部同意」，跳过所有权限检查（包括高危操作）
        if (PermissionContext.isSessionApproved(conversationId)) {
            log.debug("会话 {} 已获「本轮对话全部同意」，跳过工具 {} 的权限检查", conversationId, toolName);
            // 直接跳到阶段 2：审计日志
            auditLogger.log(toolName, arguments, userId, executionMode);
            return tool.execute(arguments);
        }

        // ===== 阶段 1.1：层面一 — 数据影响防护（仅 manual 模式）=====
        if ("manual".equals(executionMode) && meta.affectsData()) {
            String denial = PermissionContext.requestPermission(toolName, arguments, conversationId);
            if (denial != null) return denial;
        }

        // ===== 阶段 1.2：层面二 — 路径穿透防护（仅 manual 模式）=====
        if ("manual".equals(executionMode) && meta.isPathSensitive()) {
            String pathViolation = pathSecurityChecker.checkAndRequest(
                    toolName, arguments, conversationId);
            if (pathViolation != null) return pathViolation;
        }

        // ===== 阶段 1.3：层面三 — 高危操作防护（auto 模式也需授权）=====
        if ("auto".equals(executionMode) && meta.highRisk()) {
            String denial = PermissionContext.requestHighRiskPermission(toolName, arguments, conversationId);
            if (denial != null) return denial;
        }

        // ===== 阶段 2：审计日志 =====
        auditLogger.log(toolName, arguments, userId, executionMode);

        // ===== 阶段 3：执行工具 =====
        return tool.execute(arguments);
    }
}
