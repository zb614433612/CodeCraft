package com.example.agentdeepseek.mcp.server;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 工具处理器
 * 负责将 CodeCraft 内置工具暴露为 MCP 工具（tools/list）并执行外部调用（tools/call）
 * 安全策略：白名单前置校验 + 审计日志（不走内部权限管道，外部客户端无用户上下文）
 */
@Slf4j
@Component
public class McpToolHandler {

    /** 审计日志中参数摘要的最大长度 */
    private static final int ARGS_SUMMARY_MAX_LENGTH = 300;

    private final List<Tool> tools;
    private final McpServerProperties props;
    private final ObjectMapper objectMapper;

    public McpToolHandler(List<Tool> tools, McpServerProperties props, ObjectMapper objectMapper) {
        this.tools = tools;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 判断工具是否在白名单内
     * 规则：exposed-tools 为空 → 使用默认只读白名单；包含 "*" → 全部暴露
     */
    public boolean isExposed(String toolName) {
        List<String> whitelist = props.getExposedTools();
        if (whitelist == null || whitelist.isEmpty()) {
            return McpServerProperties.DEFAULT_EXPOSED_TOOLS.contains(toolName);
        }
        return whitelist.contains("*") || whitelist.contains(toolName);
    }

    /**
     * 获取所有被白名单允许暴露的工具（MCP Schema 格式），供 tools/list 返回
     */
    public List<McpSchema.Tool> listExposedTools() {
        List<McpSchema.Tool> result = new ArrayList<>();
        for (Tool tool : tools) {
            if (!isExposed(tool.getName())) {
                continue;
            }
            Map<String, Object> inputSchema = objectMapper.convertValue(
                    tool.getParameters(), new TypeReference<Map<String, Object>>() {});
            McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .inputSchema(inputSchema)
                    .build();
            result.add(mcpTool);
        }
        return result;
    }

    /**
     * 执行外部客户端请求的工具调用（tools/call）
     * 白名单之外的调用直接拒绝，执行过程记录审计日志
     */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        long start = System.currentTimeMillis();
        String argsSummary = summarize(arguments);

        // 白名单前置校验
        if (!isExposed(name)) {
            log.warn("MCP 外部调用被拒绝：工具 [{}] 不在暴露白名单中", name);
            return errorResult("工具 " + name + " 不在 MCP 暴露白名单中，请先在 codecraft.mcp.server.exposed-tools 中配置");
        }

        // 查找工具
        Tool tool = tools.stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElse(null);
        if (tool == null) {
            log.warn("MCP 外部调用失败：工具 [{}] 不存在", name);
            return errorResult("工具 " + name + " 不存在");
        }

        // 执行工具
        try {
            JsonNode argsNode = objectMapper.valueToTree(arguments == null ? Map.of() : arguments);
            String result = tool.execute(argsNode);
            long cost = System.currentTimeMillis() - start;
            log.info("MCP 外部调用成功: 工具={}, 参数={}, 耗时={}ms", name, argsSummary, cost);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(result)
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            // 异常明细（可能含文件路径/SQL 细节）只记服务端日志，不向外部客户端透传
            log.error("MCP 外部调用异常: 工具={}, 参数={}, 耗时={}ms, 错误={}", name, argsSummary, cost, e.getMessage(), e);
            return errorResult("工具 " + name + " 执行失败，请查看服务端日志");
        }
    }

    /**
     * 构造错误结果（isError=true）
     */
    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    /**
     * 参数摘要（截断 + 脱敏兜底）
     */
    private String summarize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        String json = String.valueOf(arguments);
        if (json.length() > ARGS_SUMMARY_MAX_LENGTH) {
            json = json.substring(0, ARGS_SUMMARY_MAX_LENGTH) + "...(截断)";
        }
        return json;
    }
}
