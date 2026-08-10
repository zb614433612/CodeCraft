package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.mapper.McpServerMapper;
import com.example.agentdeepseek.mcp.client.McpClientManager;
import com.example.agentdeepseek.mcp.client.McpConnection;
import com.example.agentdeepseek.model.entity.McpServerConfig;
import com.example.agentdeepseek.service.McpServerService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * MCP 服务器管理工具
 * LLM 通过此工具在 AI 聊天窗口直接完成 MCP 服务器的创建、删除、连接等管理操作，
 * 与系统中已有的 mcp_server 配置表及 McpClientManager 联动（复用 McpServerService 全部能力）。
 *
 * 支持操作：list / create / update / delete / connect / disconnect / refresh / tools
 * 返回格式：可读文本（含 emoji 状态标识，便于 LLM 向用户展示）
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = true,
        description = "创建和管理MCP服务器配置（增删改查、连接/断开/刷新工具）")
public class McpServerManagerTool implements Tool {

    private final ObjectMapper objectMapper;
    private final McpServerService mcpServerService;
    private final McpServerMapper mcpServerMapper;
    private final McpClientManager mcpClientManager;

    public McpServerManagerTool(ObjectMapper objectMapper, McpServerService mcpServerService,
                                McpServerMapper mcpServerMapper, McpClientManager mcpClientManager) {
        this.objectMapper = objectMapper;
        this.mcpServerService = mcpServerService;
        this.mcpServerMapper = mcpServerMapper;
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public String getName() {
        return "mcp_server_manager";
    }

    @Override
    public String getDescription() {
        return "管理 MCP 服务器，让用户通过自然语言指挥 LLM 在聊天窗口完成 MCP 服务器的创建、删除、连接等操作（无需打开配置页面）。\n"
                + "【适用场景】用户说「帮我创建一个 MCP 服务器连接 GitHub」「把 xxx MCP 服务器删掉」「查看当前的 MCP 服务器列表」「连接/断开 xx 服务器」等\n"
                + "【使用方式】通过 action 参数选择操作：\n"
                + "  - list=查看全部服务器（含连接状态、工具数）\n"
                + "  - create=新建服务器配置（http 类型需 url，stdio 类型需 command）\n"
                + "  - update=修改服务器配置（传入 id + 要改的字段）\n"
                + "  - delete=删除服务器配置（先断开连接并注销工具）\n"
                + "  - connect=连接服务器并注册其工具\n"
                + "  - disconnect=断开服务器并注销其工具\n"
                + "  - refresh=重新拉取服务器工具列表\n"
                + "  - tools=查看指定服务器拉取到的工具明细\n"
                + "【常用参数】id=服务器ID（update/delete/connect/disconnect/refresh/tools 时必填，从 list 结果获取）；\n"
                + "  name=服务器名称（create 必填，如 \"GitHub\"）；type=传输类型（create 必填：http/stdio）；\n"
                + "  url=http 类型的端点地址（如 http://localhost:3001/mcp）；command=stdio 类型的启动命令（如 npx -y @modelcontextprotocol/server-github）；\n"
                + "  headers=自定义请求头 JSON（如 {\"Authorization\":\"Bearer xxx\"}）；tool_prefix=工具名前缀（如 github_，默认用服务器名小写）；\n"
                + "  permission_level=权限档位（SAFE=只读/DATA=可写数据/HIGH_RISK=高危，默认 SAFE）；\n"
                + "  enabled=是否启用（1/0）；auto_register=启用时是否自动注册工具（1/0）\n"
                + "【安全提醒】delete 为破坏性操作，执行前必须先与用户确认；headers 中的密钥不会在列表中回显。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // ── action ──
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型。list=查看全部服务器；create=新建；update=修改；delete=删除；connect=连接；disconnect=断开；refresh=刷新工具；tools=查看工具明细");
        ArrayNode actionEnum = objectMapper.createArrayNode();
        actionEnum.add("list");
        actionEnum.add("create");
        actionEnum.add("update");
        actionEnum.add("delete");
        actionEnum.add("connect");
        actionEnum.add("disconnect");
        actionEnum.add("refresh");
        actionEnum.add("tools");
        action.set("enum", actionEnum);
        properties.set("action", action);

        // ── id ──
        ObjectNode id = objectMapper.createObjectNode();
        id.put("type", "number");
        id.put("description", "【update/delete/connect/disconnect/refresh/tools 时必填】服务器 ID。从 action=list 的结果中获取");
        properties.set("id", id);

        // ── name ──
        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "【create 必填，update 可选】服务器名称（展示用）。示例：\"GitHub\"、\"本地文件系统\"");
        properties.set("name", name);

        // ── type ──
        ObjectNode type = objectMapper.createObjectNode();
        type.put("type", "string");
        type.put("description", "【create 必填，update 可选】传输类型。http=Streamable HTTP（远程端点，需配 url）；stdio=本地进程（需配 command）");
        ArrayNode typeEnum = objectMapper.createArrayNode();
        typeEnum.add("http");
        typeEnum.add("stdio");
        type.set("enum", typeEnum);
        properties.set("type", type);

        // ── url ──
        ObjectNode url = objectMapper.createObjectNode();
        url.put("type", "string");
        url.put("description", "【type=http 时必填】MCP 端点 URL。示例：\"http://localhost:3001/mcp\"");
        properties.set("url", url);

        // ── command ──
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "【type=stdio 时必填】本地进程启动命令。示例：\"npx -y @modelcontextprotocol/server-github\"");
        properties.set("command", command);

        // ── headers ──
        ObjectNode headers = objectMapper.createObjectNode();
        headers.put("type", "string");
        headers.put("description", "【可选】自定义请求头 JSON 字符串。示例：\"{\\\"Authorization\\\":\\\"Bearer xxx\\\"}\"。列表查询时不回显明文（避免密钥泄露）");
        properties.set("headers", headers);

        // ── tool_prefix ──
        ObjectNode toolPrefix = objectMapper.createObjectNode();
        toolPrefix.put("type", "string");
        toolPrefix.put("description", "【可选】工具名前缀（防冲突）。示例：\"github_\"。为空时自动使用服务器名小写 + \"_\"");
        properties.set("tool_prefix", toolPrefix);

        // ── permission_level ──
        ObjectNode permissionLevel = objectMapper.createObjectNode();
        permissionLevel.put("type", "string");
        permissionLevel.put("description", "【可选】权限档位。SAFE=只读（默认）/ DATA=可写数据 / HIGH_RISK=高危（需授权）");
        ArrayNode permissionEnum = objectMapper.createArrayNode();
        permissionEnum.add("SAFE");
        permissionEnum.add("DATA");
        permissionEnum.add("HIGH_RISK");
        permissionLevel.set("enum", permissionEnum);
        properties.set("permission_level", permissionLevel);

        // ── enabled ──
        ObjectNode enabled = objectMapper.createObjectNode();
        enabled.put("type", "number");
        enabled.put("description", "【可选】是否启用：1=启用，0=停用（默认 0）。启用且 auto_register=1 时会立即尝试连接");
        properties.set("enabled", enabled);

        // ── auto_register ──
        ObjectNode autoRegister = objectMapper.createObjectNode();
        autoRegister.put("type", "number");
        autoRegister.put("description", "【可选】启用时是否自动注册其工具到 ToolRegistry：1=是，0=否（默认 0）");
        properties.set("auto_register", autoRegister);

        parameters.set("properties", properties);

        ArrayNode required = objectMapper.createArrayNode();
        required.add("action");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText();
        if (action.isEmpty()) {
            return buildError("【缺少参数】action 字段缺失。请设为 list / create / update / delete / connect / disconnect / refresh / tools 之一");
        }

        return switch (action) {
            case "list" -> handleList();
            case "create" -> handleCreate(arguments);
            case "update" -> handleUpdate(arguments);
            case "delete" -> handleDelete(arguments);
            case "connect" -> handleConnect(arguments);
            case "disconnect" -> handleDisconnect(arguments);
            case "refresh" -> handleRefresh(arguments);
            case "tools" -> handleTools(arguments);
            default -> buildError("【不支持的操作】action='" + action + "' 无效。支持：list / create / update / delete / connect / disconnect / refresh / tools");
        };
    }

    // ======================== handleList ========================

    /**
     * 列出全部 MCP 服务器（含连接状态、工具数），LLM 据此获取服务器 ID。
     */
    private String handleList() {
        try {
            List<McpServerService.McpServerVO> servers = mcpServerService.listAll();
            if (servers.isEmpty()) {
                return "📭 MCP 服务器列表为空。\n\n试试对我说「帮我创建一个 MCP 服务器」吧！\n"
                        + "示例：创建一个 http 类型的服务器，名称 GitHub，地址 http://localhost:3001/mcp";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📊 MCP 服务器列表：共 ").append(servers.size()).append(" 个\n\n");

            for (McpServerService.McpServerVO vo : servers) {
                appendServerSummary(sb, vo);
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.error("查看 MCP 服务器列表失败", e);
            return buildError("【查询失败】" + e.getMessage());
        }
    }

    /**
     * 追加单个服务器的概要信息（list 与 create/update 结果共用）
     */
    private void appendServerSummary(StringBuilder sb, McpServerService.McpServerVO vo) {
        String statusIcon = switch (vo.getStatus()) {
            case "CONNECTED" -> "🟢";
            case "FAILED" -> "🔴";
            default -> "⚪";
        };
        sb.append("  ").append(statusIcon).append(" [").append(vo.getId()).append("] ").append(vo.getName()).append("\n");
        sb.append("      📡 类型：").append(vo.getType());
        if ("http".equalsIgnoreCase(vo.getType())) {
            sb.append("  🔗 URL：").append(vo.getUrl());
        } else if ("stdio".equalsIgnoreCase(vo.getType())) {
            sb.append("  ⌨️ 命令：").append(truncate(vo.getCommand(), 60));
        }
        sb.append("\n");
        sb.append("      🔌 状态：").append(vo.getStatus());
        if ("FAILED".equals(vo.getStatus()) && StringUtils.hasText(vo.getErrorMessage())) {
            sb.append("（").append(truncate(vo.getErrorMessage(), 80)).append("）");
        }
        if ("CONNECTED".equals(vo.getStatus())) {
            sb.append("  🧰 工具：拉取 ").append(vo.getToolCount()).append(" 个，注册 ").append(vo.getRegisteredToolCount()).append(" 个");
        }
        sb.append("\n");
        sb.append("      🛡 权限：").append(vo.getPermissionLevel() == null ? "SAFE" : vo.getPermissionLevel());
        sb.append("  ✅ 启用：").append(Integer.valueOf(1).equals(vo.getEnabled()) ? "是" : "否");
        sb.append("  🔁 自动注册：").append(Integer.valueOf(1).equals(vo.getAutoRegister()) ? "是" : "否");
        sb.append("\n");
    }

    // ======================== handleCreate ========================

    /**
     * 新建 MCP 服务器配置；enabled=1 且 auto_register=1 时立即尝试连接。
     */
    private String handleCreate(JsonNode args) {
        String name = args.path("name").asText();
        String type = args.path("type").asText();

        if (name.isEmpty()) {
            return buildError("【缺少参数】create 需要 name。示例：{\"action\": \"create\", \"name\": \"GitHub\", \"type\": \"http\", \"url\": \"http://localhost:3001/mcp\"}");
        }
        if (type.isEmpty()) {
            return buildError("【缺少参数】create 需要 type（http / stdio）。示例：{\"type\": \"http\", \"url\": \"...\"} 或 {\"type\": \"stdio\", \"command\": \"npx ...\"}");
        }
        if ("http".equalsIgnoreCase(type) && args.path("url").asText().isEmpty()) {
            return buildError("【缺少参数】type=http 时必须提供 url（MCP 端点地址）。示例：\"url\": \"http://localhost:3001/mcp\"");
        }
        if ("stdio".equalsIgnoreCase(type) && args.path("command").asText().isEmpty()) {
            return buildError("【缺少参数】type=stdio 时必须提供 command（本地进程启动命令）。示例：\"command\": \"npx -y @modelcontextprotocol/server-github\"");
        }

        McpServerConfig config = buildConfigFromArgs(args, null);
        try {
            McpServerService.McpServerVO vo = mcpServerService.create(config);
            log.info("工具创建 MCP 服务器: id={}, name={}, type={}", vo.getId(), vo.getName(), vo.getType());

            StringBuilder sb = new StringBuilder();
            sb.append("✅ MCP 服务器创建成功！\n\n");
            appendServerSummary(sb, vo);
            if ("CONNECTED".equals(vo.getStatus())) {
                sb.append("      💡 已自动连接并注册 ").append(vo.getRegisteredToolCount()).append(" 个工具，现在就可以在对话中调用它们了\n");
            } else if ("FAILED".equals(vo.getStatus())) {
                sb.append("      ⚠️ 配置已保存，但连接失败：").append(vo.getErrorMessage())
                        .append("\n      可用 action=connect 重试，或检查地址/命令是否正确\n");
            } else {
                sb.append("      💡 当前未启用（enabled=0 或 auto_register=0），可用 action=connect 手动连接\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            log.warn("创建 MCP 服务器失败: {}", e.getMessage());
            return buildError("【创建失败】" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("创建 MCP 服务器异常", e);
            return buildError("【创建失败】" + e.getMessage());
        }
    }

    // ======================== handleUpdate ========================

    /**
     * 修改服务器配置（只覆盖传入的字段，未传字段保留原值）；启用状态变化时自动重连/断开。
     */
    private String handleUpdate(JsonNode args) {
        Long id = parseId(args);
        if (id == null) {
            return buildError("【缺少参数】update 需要 id。示例：{\"action\": \"update\", \"id\": 1, \"name\": \"新名称\"}");
        }

        McpServerConfig existing = mcpServerMapper.selectById(id);
        if (existing == null) {
            return buildError("【服务器不存在】未找到 ID=" + id + " 的 MCP 服务器。请用 action=list 查看服务器列表确认正确 ID");
        }

        McpServerConfig config = new McpServerConfig();
        config.setId(id);

        // 名称：传了才覆盖
        String name = args.path("name").asText();
        config.setName(name.isEmpty() ? existing.getName() : name);
        // 类型：传了才覆盖
        String type = args.path("type").asText();
        config.setType(type.isEmpty() ? existing.getType() : type);
        // URL / 命令：传了才覆盖
        String url = args.path("url").asText();
        config.setUrl(url.isEmpty() ? existing.getUrl() : url);
        String command = args.path("command").asText();
        config.setCommand(command.isEmpty() ? existing.getCommand() : command);
        // 请求头：显式传入才覆盖（传空字符串表示清空）
        if (args.has("headers")) {
            config.setHeaders(args.path("headers").asText());
        } else {
            config.setHeaders(existing.getHeaders());
        }
        // 工具前缀：传了才覆盖
        String toolPrefix = args.path("tool_prefix").asText();
        config.setToolPrefix(toolPrefix.isEmpty() ? existing.getToolPrefix() : toolPrefix);
        // 权限档位：传了才覆盖
        String permissionLevel = args.path("permission_level").asText();
        config.setPermissionLevel(permissionLevel.isEmpty() ? existing.getPermissionLevel() : permissionLevel);
        // 启用 / 自动注册：显式传入才覆盖（兼容 0/1 数字与 true/false 布尔）
        if (args.has("enabled")) {
            config.setEnabled(parseIntOrBool(args.get("enabled")));
        } else {
            config.setEnabled(existing.getEnabled());
        }
        if (args.has("auto_register")) {
            config.setAutoRegister(parseIntOrBool(args.get("auto_register")));
        } else {
            config.setAutoRegister(existing.getAutoRegister());
        }

        try {
            McpServerService.McpServerVO vo = mcpServerService.update(id, config);
            log.info("工具更新 MCP 服务器: id={}, name={}", id, vo.getName());

            StringBuilder sb = new StringBuilder();
            sb.append("✅ MCP 服务器 [").append(id).append("] 已更新！\n\n");
            appendServerSummary(sb, vo);
            if ("CONNECTED".equals(vo.getStatus())) {
                sb.append("      💡 已重新连接并注册 ").append(vo.getRegisteredToolCount()).append(" 个工具\n");
            } else if ("FAILED".equals(vo.getStatus())) {
                sb.append("      ⚠️ 配置已保存，但连接失败：").append(vo.getErrorMessage()).append("\n");
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            log.warn("更新 MCP 服务器失败: {}", e.getMessage());
            return buildError("【更新失败】" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("更新 MCP 服务器异常: id={}", id, e);
            return buildError("【更新失败】" + e.getMessage());
        }
    }

    // ======================== handleDelete ========================

    /**
     * 删除服务器配置（先断开连接并注销工具）。破坏性操作，调用前应已获用户确认。
     */
    private String handleDelete(JsonNode args) {
        Long id = parseId(args);
        if (id == null) {
            return buildError("【缺少参数】delete 需要 id。示例：{\"action\": \"delete\", \"id\": 1}");
        }

        McpServerConfig existing = mcpServerMapper.selectById(id);
        if (existing == null) {
            return buildError("【服务器不存在】未找到 ID=" + id + " 的 MCP 服务器");
        }

        String name = existing.getName();
        try {
            mcpServerService.delete(id);
            log.info("工具删除 MCP 服务器: id={}, name={}", id, name);
            return "✅ 已删除 MCP 服务器「" + name + "」（ID=" + id + "），其连接与已注册工具均已清理";
        } catch (RuntimeException e) {
            log.error("删除 MCP 服务器异常: id={}", id, e);
            return buildError("【删除失败】" + e.getMessage());
        }
    }

    // ======================== handleConnect ========================

    /**
     * 连接指定服务器并注册其工具（会发起真实网络连接 / 启动本地进程，可能耗时数秒）。
     */
    private String handleConnect(JsonNode args) {
        Long id = parseId(args);
        if (id == null) {
            return buildError("【缺少参数】connect 需要 id。示例：{\"action\": \"connect\", \"id\": 1}");
        }

        try {
            McpServerService.McpServerVO vo = mcpServerService.connect(id);
            log.info("工具连接 MCP 服务器: id={}, name={}, status={}", id, vo.getName(), vo.getStatus());

            if ("CONNECTED".equals(vo.getStatus())) {
                return "✅ 已连接 MCP 服务器「" + vo.getName() + "」（ID=" + id + "）\n"
                        + "   🧰 拉取工具 " + vo.getToolCount() + " 个，注册 " + vo.getRegisteredToolCount() + " 个，现在就可以在对话中调用它们了";
            }
            return "❌ 连接失败：MCP 服务器「" + vo.getName() + "」（ID=" + id + "）\n"
                    + "   ⚠️ 原因：" + (StringUtils.hasText(vo.getErrorMessage()) ? vo.getErrorMessage() : "未知错误")
                    + "\n   可检查 url/command 是否正确、目标服务是否可达，然后重试";
        } catch (IllegalArgumentException e) {
            return buildError("【连接失败】" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("连接 MCP 服务器异常: id={}", id, e);
            return buildError("【连接失败】" + e.getMessage());
        }
    }

    // ======================== handleDisconnect ========================

    /**
     * 断开服务器连接并注销其注册的工具。
     */
    private String handleDisconnect(JsonNode args) {
        Long id = parseId(args);
        if (id == null) {
            return buildError("【缺少参数】disconnect 需要 id。示例：{\"action\": \"disconnect\", \"id\": 1}");
        }

        try {
            McpServerService.McpServerVO vo = mcpServerService.disconnect(id);
            log.info("工具断开 MCP 服务器: id={}, name={}", id, vo.getName());
            return "✅ 已断开 MCP 服务器「" + vo.getName() + "」（ID=" + id + "），其工具已从工具列表中注销";
        } catch (IllegalArgumentException e) {
            return buildError("【断开失败】" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("断开 MCP 服务器异常: id={}", id, e);
            return buildError("【断开失败】" + e.getMessage());
        }
    }

    // ======================== handleRefresh ========================

    /**
     * 重新拉取指定服务器的工具列表（先断开再重连）。
     */
    private String handleRefresh(JsonNode args) {
        Long id = parseId(args);
        if (id == null) {
            return buildError("【缺少参数】refresh 需要 id。示例：{\"action\": \"refresh\", \"id\": 1}");
        }

        try {
            McpServerService.McpServerVO vo = mcpServerService.refresh(id);
            log.info("工具刷新 MCP 服务器工具: id={}, name={}, status={}", id, vo.getName(), vo.getStatus());

            if ("CONNECTED".equals(vo.getStatus())) {
                return "✅ 已刷新 MCP 服务器「" + vo.getName() + "」（ID=" + id + "）的工具列表\n"
                        + "   🧰 拉取工具 " + vo.getToolCount() + " 个，注册 " + vo.getRegisteredToolCount() + " 个";
            }
            return "❌ 刷新失败：MCP 服务器「" + vo.getName() + "」（ID=" + id + "）\n"
                    + "   ⚠️ 原因：" + (StringUtils.hasText(vo.getErrorMessage()) ? vo.getErrorMessage() : "未知错误");
        } catch (IllegalArgumentException e) {
            return buildError("【刷新失败】" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("刷新 MCP 服务器工具异常: id={}", id, e);
            return buildError("【刷新失败】" + e.getMessage());
        }
    }

    // ======================== handleTools ========================

    /**
     * 查看指定服务器当前拉取到的工具明细（含名称、描述）。
     */
    private String handleTools(JsonNode args) {
        Long id = parseId(args);
        if (id == null) {
            return buildError("【缺少参数】tools 需要 id。示例：{\"action\": \"tools\", \"id\": 1}");
        }

        McpConnection conn = mcpClientManager.getConnection(id);
        if (conn == null || conn.getClient() == null) {
            return buildError("【未连接】服务器 ID=" + id + " 当前未连接，无法查看工具。请先用 action=connect 连接");
        }

        List<McpSchema.Tool> tools = conn.getTools();
        if (tools.isEmpty()) {
            return "🧰 MCP 服务器「" + conn.getServerName() + "」（ID=" + id + "）已连接，但未拉取到任何工具";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🧰 MCP 服务器「").append(conn.getServerName()).append("」（ID=").append(id)
                .append("）工具列表：共 ").append(tools.size()).append(" 个\n\n");
        for (McpSchema.Tool tool : tools) {
            sb.append("  - ").append(tool.name());
            if (tool.description() != null && !tool.description().isBlank()) {
                sb.append("：").append(truncate(tool.description(), 80));
            }
            sb.append("\n");
        }
        sb.append("\n💡 注册后的工具名为：前缀 + 工具名（如 github_create_issue），可直接在对话中调用");
        return sb.toString();
    }

    // ======================== 辅助方法 ========================

    /**
     * 从参数构建配置实体（create 用；必填校验由 McpServerService.validate 兜底）
     */
    private McpServerConfig buildConfigFromArgs(JsonNode args, Long id) {
        McpServerConfig config = new McpServerConfig();
        config.setId(id);
        config.setName(args.path("name").asText());
        config.setType(args.path("type").asText());
        config.setUrl(args.path("url").asText());
        config.setCommand(args.path("command").asText());
        if (args.has("headers")) {
            config.setHeaders(args.path("headers").asText());
        }
        config.setToolPrefix(args.path("tool_prefix").asText());
        config.setPermissionLevel(args.path("permission_level").asText());
        if (args.has("enabled")) {
            config.setEnabled(parseIntOrBool(args.get("enabled")));
        }
        if (args.has("auto_register")) {
            config.setAutoRegister(parseIntOrBool(args.get("auto_register")));
        }
        return config;
    }

    /**
     * 解析 id 参数（要求为正整数）
     */
    private Long parseId(JsonNode args) {
        if (!args.hasNonNull("id")) {
            return null;
        }
        long id = args.path("id").asLong(-1);
        return id > 0 ? id : null;
    }

    /**
     * 兼容数字（0/1）、布尔（true/false）与字符串（"1"/"0"/"true"/"false"/"yes"/"no"/"on"/"off"）三种传参方式
     */
    private Integer parseIntOrBool(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? 1 : 0;
        }
        if (node.isNumber()) {
            return node.asInt() != 0 ? 1 : 0;
        }
        String text = node.asText().trim().toLowerCase();
        if (text.isEmpty()) {
            return null;
        }
        return switch (text) {
            case "1", "true", "yes", "on", "y", "t" -> 1;
            default -> 0;
        };
    }

    /**
     * 按 Unicode 码点截断，避免在代理对（emoji）中间切断产生乱码
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        int codePoints = s.codePointCount(0, s.length());
        int end = s.offsetByCodePoints(0, Math.min(maxLen, codePoints));
        return s.substring(0, end) + "...";
    }

    private String buildError(String message) {
        return message;
    }
}
