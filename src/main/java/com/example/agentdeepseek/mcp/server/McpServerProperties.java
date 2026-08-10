package com.example.agentdeepseek.mcp.server;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP Server 暴露配置属性
 * 对应 application.yml 中 codecraft.mcp.server 配置段
 */
@Data
@Component
@ConfigurationProperties(prefix = "codecraft.mcp.server")
public class McpServerProperties {

    /** 是否启用 MCP Server 对外暴露功能 */
    private boolean enabled = false;

    /** MCP 端点路径（Streamable HTTP） */
    private String path = "/mcp";

    /** 认证 Token（可选）：空=不认证；非空=客户端需带 X-API-Key 头 */
    private String authToken = "";

    /** 工具白名单：空=默认只读工具集；"*"=暴露全部工具 */
    private List<String> exposedTools = new ArrayList<>();

    /** 默认白名单（未配置 exposed-tools 时的兜底），仅暴露只读类工具 */
    public static final List<String> DEFAULT_EXPOSED_TOOLS = List.of(
            "file_explorer",
            "git_query",
            "web_search",
            "web_fetch",
            "check_network",
            "project_info"
    );
}
