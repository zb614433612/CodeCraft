package com.example.agentdeepseek.util;

/**
 * 工具执行上下文（ThreadLocal）
 * 用于在工具执行期间传递当前会话的执行模式与会话ID，
 * 供受限工具（write_file/edit_file/run_command等）检查执行权限。
 * 工具执行是同步的且在单一线程中完成，ThreadLocal 安全可用。
 */
public class ToolContext {

    private static final ThreadLocal<String> currentExecutionMode = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentConversationId = new ThreadLocal<>();

    public static void set(String mode, Long conversationId) {
        currentExecutionMode.set(mode);
        currentConversationId.set(conversationId);
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

    public static void clear() {
        currentExecutionMode.remove();
        currentConversationId.remove();
    }
}
