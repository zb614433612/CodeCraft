package com.example.agentdeepseek.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * MCP 工具适配器
 * 将外部 MCP Server 拉取到的工具包装为 CodeCraft 内部 Tool 接口，
 * 使 LLM 可以通过现有工具调用链路（ToolExecutor/权限管道/审计）调用外部 MCP 工具。
 *
 * 命名规则：fullName = 服务器工具前缀 + 外部原始工具名（如 github_create_issue），避免与内置工具冲突
 */
@Slf4j
public class McpToolAdapter implements com.example.agentdeepseek.tool.Tool {

    private final Long serverId;
    private final String serverName;
    /** 注册到 ToolRegistry 的工具全名（带前缀） */
    private final String fullName;
    /** 外部 MCP 服务器上的原始工具名 */
    private final String originalName;
    private final String description;
    /** 外部工具参数 Schema（inputSchema） */
    private final Map<String, Object> inputSchema;
    private final McpSyncClient client;
    private final ObjectMapper objectMapper;

    public McpToolAdapter(Long serverId, String serverName, String fullName, String originalName,
                          String description, Map<String, Object> inputSchema,
                          McpSyncClient client, ObjectMapper objectMapper) {
        this.serverId = serverId;
        this.serverName = serverName;
        this.fullName = fullName;
        this.originalName = originalName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public Long getServerId() {
        return serverId;
    }

    public String getOriginalName() {
        return originalName;
    }

    @Override
    public String getName() {
        return fullName;
    }

    @Override
    public String getDescription() {
        return "【MCP-" + serverName + "】" + (description == null || description.isEmpty() ? originalName : description);
    }

    @Override
    public JsonNode getParameters() {
        if (inputSchema == null || inputSchema.isEmpty()) {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            return empty;
        }
        return objectMapper.valueToTree(inputSchema);
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            // 参数转换：JsonNode → Map（MCP 协议参数格式）
            Map<String, Object> args = arguments == null || arguments.isNull()
                    ? Map.of()
                    : objectMapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {});

            // 调用外部 MCP 工具
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(originalName)
                    .arguments(args)
                    .build();
            McpSchema.CallToolResult result = client.callTool(request);

            // 提取结果文本
            StringBuilder sb = new StringBuilder();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent textContent) {
                        sb.append(textContent.text());
                    } else {
                        sb.append(content);
                    }
                    sb.append("\n");
                }
            }
            String output = sb.toString().trim();

            // 外部服务器标记的错误
            if (Boolean.TRUE.equals(result.isError())) {
                return "【MCP-" + serverName + " 工具执行失败】" + originalName + " 返回错误：" + output;
            }
            return output.isEmpty() ? "【MCP-" + serverName + "】工具 " + originalName + " 执行完成，无返回内容" : output;
        } catch (Exception e) {
            log.error("MCP 工具调用异常: 服务器={}, 工具={}, 错误={}", serverName, originalName, e.getMessage(), e);
            return "【MCP-" + serverName + "】工具 " + originalName + " 调用失败: " + e.getMessage()
                    + "。请检查 MCP 服务器是否可用";
        }
    }
}
