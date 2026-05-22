package com.example.agentdeepseek.tool;

import com.example.agentdeepseek.service.PendingQuestionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限请求上下文（三层统一防护模型 + 会话级别自动批准）
 *
 * <pre>
 * 层面一：manual 模式下 affectsData=true 的工具 → requestPermission()
 * 层面二：manual 模式下路径越界 → requestCrossPathPermission()
 * 层面三：auto 模式下 highRisk=true 的工具 → requestHighRiskPermission()
 *
 * 会话级别自动批准：用户选择「本轮对话全部同意」后，当前会话后续所有工具调用
 * 自动跳过三层权限检查（包括高危操作），直到会话结束。
 * </pre>
 *
 * DeepSeekServiceImpl 在工具批次执行前调用 set()/setApproved() 建立上下文，
 * ToolExecutionPipeline 在执行各工具时查询授权状态。
 */
@Slf4j
@Component
public class PermissionContext {

    private static final ThreadLocal<PermissionRequestor> holder = new ThreadLocal<>();

    /** 已获得「本轮对话全部同意」的会话ID集合 */
    private static final Set<Long> sessionApprovedConversations = ConcurrentHashMap.newKeySet();

    private PermissionContext() {}

    /**
     * 设置当前批次的权限请求上下文
     */
    public static void set(PendingQuestionStore store, ObjectMapper mapper) {
        holder.set(new PermissionRequestor(store, mapper));
    }

    /**
     * 标记当前批次的工具调用已获得用户批准（层面一覆盖）
     */
    public static void setApproved() {
        PermissionRequestor ctx = holder.get();
        if (ctx != null) {
            ctx.approved = true;
            ctx.crossPathApproved = true;
            ctx.highRiskApproved = true;
        }
    }

    // ======================== 层面一：数据影响授权 ========================

    /**
     * 检查工具是否已获用户授权（层面一：数据影响防护）
     *
     * @return null 表示已批准；非 null 表示拒绝信息
     */
    public static String requestPermission(String toolName, JsonNode arguments, Long conversationId) {
        PermissionRequestor ctx = holder.get();
        if (ctx == null) {
            log.error("权限请求上下文未设置");
            return "错误：权限请求上下文未设置";
        }

        if (ctx.approved) {
            return null;
        }

        log.warn("工具 {} 未获得执行授权", toolName);
        return "操作未获批准，已取消";
    }

    // ======================== 层面二：路径穿透授权 ========================

    /**
     * 检查跨目录访问是否已获授权（层面二：路径穿透防护）
     *
     * @param toolName  工具名称
     * @param violations 越界的路径列表
     * @return null 表示已批准；非 null 表示拒绝信息
     */
    public static String requestCrossPathPermission(String toolName, List<String> violations,
                                                     Long conversationId) {
        PermissionRequestor ctx = holder.get();
        if (ctx == null) {
            log.error("权限请求上下文未设置");
            return "错误：权限请求上下文未设置";
        }

        if (ctx.crossPathApproved) {
            return null;
        }

        log.warn("工具 {} 的路径穿透未获授权: {}", toolName, violations);
        return "路径越权访问未获批准，已取消：\n" + String.join("\n", violations);
    }

    // ======================== 层面三：高危操作授权 ========================

    /**
     * 检查高危操作是否已获授权（层面三：高危操作防护，auto 模式也生效）
     *
     * @return null 表示已批准；非 null 表示拒绝信息
     */
    public static String requestHighRiskPermission(String toolName, JsonNode arguments,
                                                    Long conversationId) {
        PermissionRequestor ctx = holder.get();
        if (ctx == null) {
            log.error("权限请求上下文未设置");
            return "错误：权限请求上下文未设置";
        }

        if (ctx.highRiskApproved) {
            return null;
        }

        log.warn("高危工具 {} 在 auto 模式下未获授权", toolName);
        return "高危操作未获批准，已取消。工具 " + toolName + " 即使在自动模式下也需要用户授权。";
    }

    /**
     * 清理当前请求的上下文
     */
    public static void clear() {
        holder.remove();
    }

    // ======================== 会话级别自动批准 ========================

    /**
     * 检查当前会话是否已获得「本轮对话全部同意」
     *
     * @param conversationId 会话ID
     * @return true 表示已获得本轮批准，所有权限检查可跳过
     */
    public static boolean isSessionApproved(Long conversationId) {
        if (conversationId == null) return false;
        return sessionApprovedConversations.contains(conversationId);
    }

    /**
     * 设置当前会话为「本轮对话全部同意」
     * 调用后该会话后续所有工具调用自动跳过权限检查（包括高危操作）
     *
     * @param conversationId 会话ID
     */
    public static void setSessionApproved(Long conversationId) {
        if (conversationId != null) {
            sessionApprovedConversations.add(conversationId);
            log.info("会话 {} 已获得「本轮对话全部同意」，后续工具调用自动放行", conversationId);
        }
    }

    /**
     * 移除会话的自动批准状态（新消息开始时调用）
     *
     * @param conversationId 会话ID
     */
    public static void removeSessionApproved(Long conversationId) {
        if (conversationId != null) {
            sessionApprovedConversations.remove(conversationId);
        }
    }

    // ======================== 内部类 ========================

    private static class PermissionRequestor {
        final PendingQuestionStore store;
        final ObjectMapper mapper;
        boolean approved = false;
        boolean crossPathApproved = false;
        boolean highRiskApproved = false;

        PermissionRequestor(PendingQuestionStore store, ObjectMapper mapper) {
            this.store = store;
            this.mapper = mapper;
        }
    }
}
