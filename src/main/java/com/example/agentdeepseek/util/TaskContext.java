package com.example.agentdeepseek.util;

import lombok.Data;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务上下文（Phase 18：多 Agent 并行任务）
 * <p>
 * 替代隐式 ThreadLocal 传递的新范式：任务执行链路上显式传递本对象，
 * 避免线程池复用导致 A 智能体上下文串到 B 智能体。
 * 每个调度任务对应一个 TaskContext，注册到 {@link TaskContextRegistry} 供取消时精确置位。
 * </p>
 */
@Data
public class TaskContext {

    /** 任务ID（agent_task.id） */
    private final Long taskId;

    /** 归属智能体配置ID */
    private final Long agentConfigId;

    /** 关联会话ID（可能为 null，纯派发任务时无会话） */
    private final Long conversationId;

    /** 工作目录快照（锁判定依据） */
    private final String workDir;

    /** 取消标志：取消时置位，工具循环每轮迭代检查 */
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    public TaskContext(Long taskId, Long agentConfigId, Long conversationId, String workDir) {
        this.taskId = taskId;
        this.agentConfigId = agentConfigId;
        this.conversationId = conversationId;
        this.workDir = workDir;
    }

    /** 请求取消（幂等） */
    public void cancel() {
        cancelFlag.set(true);
    }

    /** 是否已取消 */
    public boolean isCancelled() {
        return cancelFlag.get();
    }
}
