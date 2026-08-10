package com.example.agentdeepseek.mcp.client;

import com.example.agentdeepseek.mapper.McpServerMapper;
import com.example.agentdeepseek.model.entity.McpServerConfig;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.example.agentdeepseek.tool.permission.ToolPermissionLevel;
import com.example.agentdeepseek.tool.permission.ToolPermissionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 连接管理器
 * 负责 CodeCraft 作为 MCP Client 连接外部 MCP Server 的全生命周期：
 * 1. 应用启动时（晚于 ToolInitializer）自动连接所有 enabled=1 的服务器并注册其工具
 * 2. 提供动态 connect / disconnect / refreshTools 方法（供 P5 Controller 调用）
 * 3. 连接失败不阻塞应用启动，记录失败状态供前端展示
 *
 * 工具注册策略：外部工具包装为 McpToolAdapter 注册进 ToolRegistry，
 * 工具名强制加前缀（tool_prefix 或服务器名小写 + "_"），避免与内置工具冲突。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class McpClientManager implements ApplicationRunner {

    /** 外部 MCP 工具调用的请求超时时间 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final McpServerMapper mcpServerMapper;
    private final ToolRegistry toolRegistry;
    private final ToolPermissionRegistry permissionRegistry;
    private final ObjectMapper objectMapper;

    /** 连接状态快照：serverId → 连接 */
    private final Map<Long, McpConnection> connections = new ConcurrentHashMap<>();

    public McpClientManager(McpServerMapper mcpServerMapper, ToolRegistry toolRegistry,
                            @Lazy ToolPermissionRegistry permissionRegistry, ObjectMapper objectMapper) {
        this.mcpServerMapper = mcpServerMapper;
        this.toolRegistry = toolRegistry;
        this.permissionRegistry = permissionRegistry;
        this.objectMapper = objectMapper;
    }

    // ==================== 启动自动注册 ====================

    @Override
    public void run(ApplicationArguments args) {
        List<McpServerConfig> configs = mcpServerMapper.selectAllEnabled();
        if (configs.isEmpty()) {
            log.info("MCP Client：无启用的外部服务器配置，跳过连接");
            return;
        }
        log.info("MCP Client：发现 {} 个启用的外部服务器，开始连接...", configs.size());
        for (McpServerConfig config : configs) {
            connectAndRegister(config);
        }
    }

    // ==================== 连接管理 ====================

    /**
     * 连接服务器并注册其工具（启动与动态连接共用）
     */
    public synchronized McpConnection connectAndRegister(McpServerConfig config) {
        Long serverId = config.getId();
        McpConnection conn = connections.computeIfAbsent(serverId, id -> new McpConnection(id, config.getName()));

        // 已有连接先断开（幂等重连）
        if (conn.getClient() != null) {
            disconnect(serverId);
            conn = connections.computeIfAbsent(serverId, id -> new McpConnection(id, config.getName()));
        }

        McpSyncClient client = null;
        // 已注册工具名集合（try 外声明：异常回滚时需要访问）
        List<String> registeredNames = new ArrayList<>();
        try {
            // 1. 构建传输层
            McpClientTransport transport = buildTransport(config);

            // 2. 构建同步客户端并握手
            client = McpClient.sync(transport)
                    .requestTimeout(REQUEST_TIMEOUT)
                    .build();
            if (!client.isInitialized()) {
                client.initialize();
            }

            // 3. 拉取工具列表（循环处理分页 nextCursor，避免工具数超过单页上限时静默丢失）
            //    SDK 2.0.0 分页重载：listTools(String cursor)
            List<McpSchema.Tool> tools = new ArrayList<>();
            String cursor = null;
            do {
                McpSchema.ListToolsResult page = client.listTools(cursor);
                if (page.tools() != null) {
                    tools.addAll(page.tools());
                }
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isEmpty());

            // 4. 包装为内部 Tool 并注册（带前缀防冲突）
            String prefix = resolvePrefix(config);
            for (McpSchema.Tool tool : tools) {
                String fullName = prefix + tool.name();
                if (toolRegistry.containsTool(fullName)) {
                    log.warn("MCP 工具名冲突，跳过注册: {}（服务器={}）", fullName, config.getName());
                    continue;
                }
                McpToolAdapter adapter = new McpToolAdapter(serverId, config.getName(), fullName, tool.name(),
                        tool.description(), tool.inputSchema(), client, objectMapper);
                toolRegistry.register(adapter);
                // 同步注册权限元数据（档位来自 mcp_server.permission_level，默认 SAFE）
                permissionRegistry.register(fullName, ToolPermissionLevel.from(config.getPermissionLevel()).toMetadata());
                registeredNames.add(fullName);
                log.info("MCP 工具注册: {}（服务器={}，原始名={}）", fullName, config.getName(), tool.name());
            }

            conn.markConnected(client, tools, registeredNames);
            log.info("MCP Client 连接成功: 服务器={}, 拉取工具={} 个, 注册={} 个",
                    config.getName(), tools.size(), registeredNames.size());
            return conn;
        } catch (Exception e) {
            log.error("MCP Client 连接失败: 服务器={}, 错误={}", config.getName(), e.getMessage(), e);
            if (client != null) {
                closeQuietly(client);
            }
            // 回滚已注册的工具，避免异常后残留「幽灵工具」（client 已关闭，LLM 调用必失败）
            for (String name : registeredNames) {
                try {
                    toolRegistry.removeTool(name);
                    permissionRegistry.unregister(name);
                    log.info("MCP 工具注册回滚: {}（服务器={}）", name, config.getName());
                } catch (Exception ex) {
                    log.warn("MCP 工具注册回滚失败: {}，错误={}", name, ex.getMessage());
                }
            }
            conn.markFailed(e.getMessage() == null ? "未知错误" : e.getMessage());
            return conn;
        }
    }

    /**
     * 动态连接指定服务器（P5 API 调用）
     */
    public McpConnection connect(Long serverId) {
        McpServerConfig config = mcpServerMapper.selectById(serverId);
        if (config == null) {
            throw new IllegalArgumentException("MCP 服务器配置不存在: " + serverId);
        }
        return connectAndRegister(config);
    }

    /**
     * 断开连接并注销其注册的工具
     */
    public synchronized void disconnect(Long serverId) {
        McpConnection conn = connections.get(serverId);
        if (conn == null) {
            return;
        }
        // 注销已注册的工具
        for (String toolName : conn.getRegisteredToolNames()) {
            toolRegistry.removeTool(toolName);
            permissionRegistry.unregister(toolName);
            log.info("MCP 工具注销: {}（服务器={}）", toolName, conn.getServerName());
        }
        // 关闭客户端
        if (conn.getClient() != null) {
            closeQuietly(conn.getClient());
        }
        conn.markDisabled();
        log.info("MCP Client 已断开: 服务器={}", conn.getServerName());
    }

    /**
     * 彻底移除服务器连接状态（配置删除时调用）。
     * 与 disconnect 的区别：disconnect 保留 DISABLED 快照供前端展示；
     * removeConnection 在断开后同时清理 connections map，避免删除的配置残留内存。
     */
    public synchronized void removeConnection(Long serverId) {
        disconnect(serverId);
        McpConnection removed = connections.remove(serverId);
        if (removed != null) {
            log.info("MCP 连接状态已清理: serverId={}, name={}", serverId, removed.getServerName());
        }
    }

    /**
     * 重新拉取服务器工具列表（先断开再重连）
     */
    public synchronized void refreshTools(Long serverId) {
        McpServerConfig config = mcpServerMapper.selectById(serverId);
        if (config == null) {
            log.warn("刷新 MCP 工具失败: 服务器配置不存在 id={}", serverId);
            return;
        }
        disconnect(serverId);
        connectAndRegister(config);
    }

    // ==================== 状态查询 ====================

    /**
     * 获取所有服务器连接状态（P5 API 展示用）
     */
    public List<McpConnection> getAllConnections() {
        return Collections.unmodifiableList(new ArrayList<>(connections.values()));
    }

    /**
     * 获取单个服务器连接状态
     */
    public McpConnection getConnection(Long serverId) {
        return connections.get(serverId);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建客户端传输层（http / stdio）
     */
    private McpClientTransport buildTransport(McpServerConfig config) {
        String type = config.getType() == null ? "http" : config.getType().toLowerCase();
        switch (type) {
            case "http" -> {
                return buildHttpTransport(config);
            }
            case "stdio" -> {
                return buildStdioTransport(config);
            }
            default -> throw new IllegalArgumentException("不支持的 MCP 传输类型: " + config.getType());
        }
    }

    /**
     * 构建 Streamable HTTP 传输（支持自定义请求头）
     */
    private McpClientTransport buildHttpTransport(McpServerConfig config) {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new IllegalArgumentException("http 类型服务器必须配置 url");
        }
        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder(config.getUrl().trim());

        // 自定义请求头（如 Authorization / X-API-Key）
        Map<String, String> headers = parseHeaders(config.getHeaders());
        if (!headers.isEmpty()) {
            builder.httpRequestCustomizer((requestBuilder, method, uri, sessionId, ctx) -> {
                headers.forEach((k, v) -> {
                    try {
                        requestBuilder.header(k, v);
                    } catch (Exception e) {
                        log.warn("MCP 请求头非法，已忽略: {}={}", k, v);
                    }
                });
            });
        }
        return builder.build();
    }

    /**
     * 构建 stdio 传输（本地进程）
     */
    private McpClientTransport buildStdioTransport(McpServerConfig config) {
        if (config.getCommand() == null || config.getCommand().isBlank()) {
            throw new IllegalArgumentException("stdio 类型服务器必须配置 command");
        }
        String[] parts = config.getCommand().trim().split("\\s+");
        ServerParameters.Builder paramBuilder = ServerParameters.builder(parts[0]);
        if (parts.length > 1) {
            paramBuilder.args(Arrays.copyOfRange(parts, 1, parts.length));
        }
        return new StdioClientTransport(paramBuilder.build(), new JacksonMcpJsonMapper(objectMapper));
    }

    /**
     * 解析工具名前缀：tool_prefix 为空时用服务器名小写 + "_"
     */
    private String resolvePrefix(McpServerConfig config) {
        String prefix = config.getToolPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = config.getName().toLowerCase() + "_";
        }
        // 保证前缀以 "_" 结尾，与其他工具名风格一致
        if (!prefix.endsWith("_")) {
            prefix = prefix + "_";
        }
        return prefix;
    }

    /**
     * 解析请求头 JSON 字符串
     */
    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析 MCP 服务器请求头失败，已忽略: {}", headersJson);
            return Map.of();
        }
    }

    /**
     * 静默关闭客户端
     */
    private void closeQuietly(McpSyncClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }
}
