package com.example.agentdeepseek.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 外部服务器连接状态
 * 记录 CodeCraft 作为 MCP Client 与外部 MCP Server 的连接快照，
 * 供 McpClientManager 管理连接生命周期，并支持状态查询（P5 API 使用）。
 */
@Getter
public class McpConnection {

    /** 连接状态枚举 */
    public enum Status {
        /** 已连接：握手成功，工具已注册 */
        CONNECTED,
        /** 连接失败：记录失败原因，不阻塞应用启动 */
        FAILED,
        /** 未启用或已断开 */
        DISABLED
    }

    private final Long serverId;
    private final String serverName;
    private volatile Status status;
    private volatile McpSyncClient client;
    /** 该服务器拉取到的原始 MCP 工具定义 */
    private final List<McpSchema.Tool> tools = new ArrayList<>();
    /** 实际注册进 ToolRegistry 的工具全名（带前缀），用于断开时清理 */
    private final List<String> registeredToolNames = new ArrayList<>();
    /** 最近一次失败原因 */
    private volatile String errorMessage;
    private volatile LocalDateTime connectedAt;

    public McpConnection(Long serverId, String serverName) {
        this.serverId = serverId;
        this.serverName = serverName;
        this.status = Status.DISABLED;
    }

    public synchronized void markConnected(McpSyncClient client, List<McpSchema.Tool> tools,
                                           List<String> registeredToolNames) {
        this.client = client;
        this.tools.clear();
        this.tools.addAll(tools);
        this.registeredToolNames.clear();
        this.registeredToolNames.addAll(registeredToolNames);
        this.errorMessage = null;
        this.connectedAt = LocalDateTime.now();
        this.status = Status.CONNECTED;
    }

    public synchronized void markFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = Status.FAILED;
        this.client = null;
        this.tools.clear();
        this.registeredToolNames.clear();
        this.connectedAt = null;
    }

    public synchronized void markDisabled() {
        this.status = Status.DISABLED;
        this.client = null;
        this.tools.clear();
        this.registeredToolNames.clear();
        this.errorMessage = null;
        this.connectedAt = null;
    }
}
