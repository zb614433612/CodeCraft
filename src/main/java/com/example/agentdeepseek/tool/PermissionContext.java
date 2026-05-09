package com.example.agentdeepseek.tool;

import com.example.agentdeepseek.service.PendingQuestionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 权限请求上下文
 * 为受限工具提供执行授权状态检查。
 * DeepSeekServiceImpl 在工具执行前统一向用户发起授权请求，
 * 工具内部通过此上下文检查授权结果。
 */
@Slf4j
@Component
public class PermissionContext {

    private static final ThreadLocal<PermissionRequestor> holder = new ThreadLocal<>();

    private PermissionContext() {}

    /**
     * 设置当前请求的上下文
     */
    public static void set(PendingQuestionStore store, ObjectMapper mapper) {
        holder.set(new PermissionRequestor(store, mapper));
    }

    /**
     * 标记当前批次的工具调用已获得用户批准
     */
    public static void setApproved() {
        PermissionRequestor ctx = holder.get();
        if (ctx != null) {
            ctx.approved = true;
        }
    }

    /**
     * 检查工具是否已获授权执行
     * @return null 表示已批准；非 null 表示拒绝信息
     */
    public static String requestPermission(String toolName, JsonNode arguments, Long conversationId) {
        PermissionRequestor ctx = holder.get();
        if (ctx == null) {
            log.error("权限请求上下文未设置");
            return "错误：权限请求上下文未设置";
        }
        if (ctx.approved) {
            return null; // 已批准
        }
        log.warn("工具 {} 未获得执行授权", toolName);
        return "操作未获批准，已取消";
    }

    /**
     * 清理当前请求的上下文
     */
    public static void clear() {
        holder.remove();
    }

    private static class PermissionRequestor {
        final PendingQuestionStore store;
        final ObjectMapper mapper;
        boolean approved = false;

        PermissionRequestor(PendingQuestionStore store, ObjectMapper mapper) {
            this.store = store;
            this.mapper = mapper;
        }
    }
}
