package com.example.agentdeepseek.mcp.server;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * CodeCraft MCP Server 启动器
 * 将内置工具（白名单过滤后）通过 Streamable HTTP 协议暴露为 MCP 服务，
 * 供 Claude Desktop / Cursor 等 MCP 客户端连接调用。
 *
 * 实现方式：构建 McpSyncServer + HttpServletStreamableServerTransportProvider（本身是 HttpServlet），
 * 在 ServletContext 初始化阶段动态注册到内嵌 Tomcat，复用现有端口无需新增监听。
 */
@Slf4j
@Component
public class CodeCraftMcpServer implements ServletContextInitializer {

    /** MCP Server 名称与版本（用于 initialize 握手） */
    private static final String SERVER_NAME = "codecraft";
    private static final String SERVER_VERSION = "1.1.5";

    private final McpServerProperties props;
    private final McpToolHandler toolHandler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public CodeCraftMcpServer(McpServerProperties props, McpToolHandler toolHandler) {
        this.props = props;
        this.toolHandler = toolHandler;
    }

    @Override
    public void onStartup(ServletContext servletContext) {
        // 未启用时直接跳过
        if (!props.isEnabled()) {
            log.info("MCP Server 未启用（codecraft.mcp.server.enabled=false），跳过启动");
            return;
        }
        // 防止重复初始化（多 ServletContext 场景）
        if (!started.compareAndSet(false, true)) {
            return;
        }

        try {
            // 1. 构建 Streamable HTTP 传输提供者（本身是 HttpServlet）
            HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint(props.getPath())
                            .build();

            // 2. 从白名单生成 MCP 工具规范列表
            List<McpServerFeatures.SyncToolSpecification> toolSpecs =
                    toolHandler.listExposedTools().stream()
                            .map(tool -> McpServerFeatures.SyncToolSpecification.builder()
                                    .tool(tool)
                                    .callHandler((exchange, request) ->
                                            toolHandler.callTool(request.name(), request.arguments()))
                                    .build())
                            .collect(Collectors.toList());

            // 3. 构建并启动同步 MCP Server
            McpServer.sync(transportProvider)
                    .serverInfo(SERVER_NAME, SERVER_VERSION)
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(toolSpecs)
                    .build();

            // 4. 将传输 Servlet 注册到内嵌 Tomcat（复用现有端口）
            // ⚠️ 必须开启 asyncSupported：MCP Streamable HTTP 依赖 SSE 异步流，否则 tools/list 等会报
            //    "A filter or servlet of the current chain does not support asynchronous operations"
            ServletRegistration.Dynamic registration =
                    servletContext.addServlet("mcpServer", transportProvider);
            registration.addMapping(props.getPath());
            registration.setLoadOnStartup(1);
            registration.setAsyncSupported(true);

            log.info("MCP Server 启动成功: 端点={}, 暴露工具数={}, 认证={}",
                    props.getPath(), toolSpecs.size(),
                    (props.getAuthToken() == null || props.getAuthToken().isEmpty()) ? "未开启" : "已开启(X-API-Key)");
        } catch (Exception e) {
            log.error("MCP Server 启动失败: {}", e.getMessage(), e);
        }
    }
}
