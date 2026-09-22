package com.example.agentdeepseek.util;

import java.util.List;

/**
 * 工具执行上下文（ThreadLocal）
 * 用于在工具执行期间传递当前会话的执行模式、会话ID、用户ID和助手类型，
 * 供受限工具（write_file/edit_file/run_command等）检查执行权限，
 * 以及供技能工具获取当前用户和助手上下文。
 * 工具执行是同步的且在单一线程中完成，ThreadLocal 安全可用。
 */
public class ToolContext {

    private static final ThreadLocal<String> currentExecutionMode = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentConversationId = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentAgentType = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentAgentConfigId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentTurnId = new ThreadLocal<>();
    private static final ThreadLocal<Double> currentTemperature = new ThreadLocal<>();
    private static final ThreadLocal<String> currentProviderCode = new ThreadLocal<>();
    /** Phase 19：智能体互调信任链（途经 agentConfigId，源自 apiRequest._trustChain，供 agent_invoke 多级委托传递） */
    private static final ThreadLocal<List<Long>> currentTrustChain = new ThreadLocal<>();
    /**
     * 2026-09-21：当前执行任务 id（agent_task.id，源自 apiRequest._taskId）。
     * 供 agent_invoke 记录「委托发起自哪个任务」（callerTaskId）：任务被取消时按任务级精确级联取消其委托，
     * 避免「A 的普通新消息/后续任务被打断」误杀更早回合发起的独立后台委托。
     */
    private static final ThreadLocal<Long> currentTaskId = new ThreadLocal<>();
    /**
     * M5：工具执行期间登记的"注入 user 消息"文件资产ID列表（桌面截图等工具写入）。
     * ToolExecutor 在单个工具执行完成后读取并清空；由 DeepSeekServiceImpl 转换为 user 图像消息。
     */
    private static final ThreadLocal<List<Long>> currentInjectUserFileAssetIds = new ThreadLocal<>();
    /**
     * ★ 修复（P0 工具授权）：当前请求允许的工具集（= Agent 授权列表 tool_names；技能不再扩展授权）。
     * 由 DeepSeekServiceImpl 在工具执行前从 apiRequest._allowedToolNames 同步，
     * 供 ToolExecutor 在执行每个工具调用前做「授权白名单」校验。
     * 未设置（null）时保持旧行为（不校验），兼容尚未接入白名单的执行入口。
     */
    private static final ThreadLocal<List<String>> currentAllowedTools = new ThreadLocal<>();

    public static void set(String mode, Long conversationId) {
        currentExecutionMode.set(mode);
        currentConversationId.set(conversationId);
    }

    public static void set(String mode, Long conversationId, String agentType, Long userId) {
        currentExecutionMode.set(mode);
        currentConversationId.set(conversationId);
        currentAgentType.set(agentType);
        currentUserId.set(userId);
    }

    public static void setTurnId(String turnId) {
        currentTurnId.set(turnId);
    }

    public static String getTurnId() {
        return currentTurnId.get();
    }

    /**
     * @return "manual" 或 "auto"，未设置时返回 "auto"
     */
    public static String getMode() {
        String mode = currentExecutionMode.get();
        return mode != null ? mode : "auto";
    }

    public static Long getConversationId() {
        return currentConversationId.get();
    }

    public static Long getUserId() {
        return currentUserId.get();
    }

    public static String getAgentType() {
        return currentAgentType.get();
    }

    public static Long getAgentConfigId() {
        return currentAgentConfigId.get();
    }

    public static void setAgentConfigId(Long agentConfigId) {
        currentAgentConfigId.set(agentConfigId);
    }

    public static Double getTemperature() {
        return currentTemperature.get();
    }

    public static void setTemperature(Double temperature) {
        currentTemperature.set(temperature);
    }

    public static String getProviderCode() {
        return currentProviderCode.get();
    }

    public static void setProviderCode(String providerCode) {
        currentProviderCode.set(providerCode);
    }

    /** 获取当前信任链（智能体互调；无委托上下文时返回空列表） */
    public static List<Long> getTrustChain() {
        return currentTrustChain.get();
    }

    /** 设置当前信任链（DeepSeekServiceImpl 工具执行前从 apiRequest._trustChain 同步） */
    public static void setTrustChain(List<Long> trustChain) {
        if (trustChain == null || trustChain.isEmpty()) {
            currentTrustChain.remove();
        } else {
            currentTrustChain.set(trustChain);
        }
    }

    /** 获取当前执行任务 id（无任务上下文时返回 null） */
    public static Long getTaskId() {
        return currentTaskId.get();
    }

    /** 设置当前执行任务 id（DeepSeekServiceImpl 工具执行前从 apiRequest._taskId 同步；null=清除） */
    public static void setTaskId(Long taskId) {
        if (taskId == null) {
            currentTaskId.remove();
        } else {
            currentTaskId.set(taskId);
        }
    }

    /**
     * ★ 修复（P0 工具授权）：设置当前请求允许的工具集（授权白名单）。
     * DeepSeekServiceImpl 在工具执行前从 apiRequest._allowedToolNames 同步；
     * 传 null 表示清除（关闭校验）。
     */
    public static void setAllowedTools(List<String> allowedTools) {
        if (allowedTools == null) {
            currentAllowedTools.remove();
        } else {
            currentAllowedTools.set(allowedTools);
        }
    }

    /**
     * ★ 修复（P0 工具授权）：获取当前请求允许的工具集。
     *
     * @return 允许的工具名列表；未设置时返回 null（表示不启用白名单校验，保持旧行为兼容）
     */
    public static List<String> getAllowedTools() {
        return currentAllowedTools.get();
    }

    /**
     * M5：登记一条"注入 user 消息"的文件资产ID（桌面截图等工具在执行成功后调用）。
     */
    public static void addInjectUserFileAssetId(Long fileAssetId) {
        if (fileAssetId == null) {
            return;
        }
        List<Long> list = currentInjectUserFileAssetIds.get();
        if (list == null) {
            list = new java.util.ArrayList<>();
            currentInjectUserFileAssetIds.set(list);
        }
        list.add(fileAssetId);
    }

    /**
     * M5：读取并清空注入文件资产ID列表（ToolExecutor 在单个工具执行完成后调用；读取即清空）。
     *
     * @return 待注入资产ID列表；无登记时为空列表（不为 null）
     */
    public static List<Long> consumeInjectUserFileAssetIds() {
        List<Long> list = currentInjectUserFileAssetIds.get();
        currentInjectUserFileAssetIds.remove();
        return list == null ? List.of() : list;
    }

    /**
     * M5：清空注入文件资产ID列表（防御性清理，不取值；工具异常/早退分支调用）。
     */
    public static void clearInjectUserFileAssetIds() {
        currentInjectUserFileAssetIds.remove();
    }

    public static void clear() {
        currentExecutionMode.remove();
        currentConversationId.remove();
        currentUserId.remove();
        currentAgentType.remove();
        currentAgentConfigId.remove();
        currentTurnId.remove();
        currentTemperature.remove();
        currentProviderCode.remove();
        currentTrustChain.remove();
        currentTaskId.remove();
        currentInjectUserFileAssetIds.remove();
        currentAllowedTools.remove();
    }
}
