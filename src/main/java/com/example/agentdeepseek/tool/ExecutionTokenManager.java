package com.example.agentdeepseek.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行令牌管理器
 * 在 manual 模式下，每次 ask_execution 获得用户同意后发放一个令牌，
 * 受限工具（write_file/edit_file/run_command/run_background_command）执行时消耗该令牌。
 * 无令牌时受限工具直接返回"需要用户同意"消息，不执行实际操作。
 * 自动模式下不检查令牌。
 */
@Slf4j
@Component
public class ExecutionTokenManager {

    private final ConcurrentHashMap<Long, Boolean> tokens = new ConcurrentHashMap<>();

    /**
     * 为指定会话授予一个执行令牌
     */
    public void grant(Long conversationId) {
        if (conversationId != null) {
            tokens.put(conversationId, Boolean.TRUE);
            log.debug("已授予执行令牌: conversationId={}", conversationId);
        }
    }

    /**
     * 尝试消耗一个执行令牌
     * @return true 如果令牌存在且被消耗，false 表示无可用令牌
     */
    public boolean tryConsume(Long conversationId) {
        if (conversationId == null) return false;
        boolean consumed = tokens.remove(conversationId) != null;
        if (consumed) {
            log.debug("已消耗执行令牌: conversationId={}", conversationId);
        }
        return consumed;
    }

    /**
     * 清除指定会话的所有令牌
     */
    public void reset(Long conversationId) {
        if (conversationId != null) {
            tokens.remove(conversationId);
            log.debug("已清除执行令牌: conversationId={}", conversationId);
        }
    }
}
