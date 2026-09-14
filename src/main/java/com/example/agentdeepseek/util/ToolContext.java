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
    }
}
