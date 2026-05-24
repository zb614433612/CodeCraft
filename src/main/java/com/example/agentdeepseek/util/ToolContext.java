package com.example.agentdeepseek.util;

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

    public static void clear() {
        currentExecutionMode.remove();
        currentConversationId.remove();
        currentUserId.remove();
        currentAgentType.remove();
        currentAgentConfigId.remove();
        currentTurnId.remove();
    }
}
