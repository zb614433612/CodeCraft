package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MCP 外部服务器配置实体类
 * 存储 CodeCraft 作为 MCP Client 连接的外部 MCP Server 配置信息（http / stdio 两种传输）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig {
    private Long id;
    /** 服务器名称（展示用），如 GitHub */
    private String name;
    /** 传输类型：http=Streamable HTTP, stdio=本地进程 */
    private String type;
    /** http 类型：MCP 端点 URL，如 http://localhost:3001/mcp */
    private String url;
    /** stdio 类型：启动命令，如 npx -y @modelcontextprotocol/server-github */
    private String command;
    /** 自定义请求头 JSON，如 {"Authorization":"Bearer xxx"} */
    private String headers;
    /** 工具名前缀（防冲突），如 github_，空则用服务器名小写 */
    private String toolPrefix;
    /** 权限档位：SAFE=只读 / DATA=可写数据 / HIGH_RISK=高危 */
    private String permissionLevel;
    /** 是否启用：1=启用, 0=停用 */
    private Integer enabled;
    /** 启用时是否自动注册其工具到 ToolRegistry：1=是, 0=否 */
    private Integer autoRegister;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
