package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.tool.permission.ToolPermissionMetadata;
import com.example.agentdeepseek.tool.permission.ToolPermissionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 工具注册表控制器
 * 返回所有已注册工具的按分类分组信息（含描述），供前端展示
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
@Tag(name = "工具注册表", description = "查询系统所有可用工具的按分类分组信息")
public class ToolRegistryController {

    private final ToolRegistry toolRegistry;
    private final ToolPermissionRegistry toolPermissionRegistry;

    public ToolRegistryController(ToolRegistry toolRegistry, ToolPermissionRegistry toolPermissionRegistry) {
        this.toolRegistry = toolRegistry;
        this.toolPermissionRegistry = toolPermissionRegistry;
    }

    @GetMapping("/registry")
    @Operation(summary = "获取工具注册表（按分类分组，含描述）")
    public ApiResponse<Map<String, Object>> getToolRegistry() {
        Collection<Tool> allTools = toolRegistry.getAllTools();

        // 按分类分组（用 LinkedHashMap 保持枚举顺序）
        Map<OperationCategory, List<ToolInfo>> grouped = new LinkedHashMap<>();

        for (Tool tool : allTools) {
            // ★ 权限元数据统一以 ToolPermissionRegistry 为准，而不是直接读类注解：
            //   ① 内置工具：注解已在 afterPropertiesSet() 阶段由 ToolPermissionMetadata.from(ann) 转入注册中心，
            //      两者内容完全等价，因此切换数据源对内置工具的展示零影响；
            //   ② 运行时动态注册的工具（如 McpToolAdapter 包装的 MCP 外部工具）没有类注解，
            //      其权限档位来自 mcp_server.permission_level 的动态注册。若仍读注解，
            //      category 会固定回落为 READ、risk 固定回落为 low——即使实际档位是 HIGH_RISK，
            //      界面也会显示为「只读 + 低风险」，误导用户按无风险勾选高危工具。
            ToolPermissionMetadata meta = toolPermissionRegistry.getMetadata(tool.getName());
            OperationCategory category = meta.getCategory();

            // 描述保持原有回退链：注解短描述（内置工具不变）→ 工具自身描述（动态工具带【MCP-服务器名】标记）
            ToolPermission permission = tool.getClass().getAnnotation(ToolPermission.class);
            String description = (permission != null && !permission.description().isBlank())
                    ? permission.description()
                    : tool.getDescription();

            // 风险等级同样取自注册中心，使动态注册的 DATA / HIGH_RISK 档位能正确呈现
            String risk = meta.highRisk() ? "high" : (meta.affectsData() ? "medium" : "low");

            grouped.computeIfAbsent(category, k -> new ArrayList<>())
                    .add(new ToolInfo(tool.getName(), description, risk));
        }

        // 构建分类列表（按枚举顺序，过滤空分类）
        List<Map<String, Object>> categories = new ArrayList<>();
        for (OperationCategory category : OperationCategory.values()) {
            List<ToolInfo> tools = grouped.get(category);
            if (tools == null || tools.isEmpty()) {
                continue;
            }
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("code", category.name());
            cat.put("label", getCategoryLabel(category));
            cat.put("tools", tools);
            categories.add(cat);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", categories);

        return ApiResponse.success(result);
    }

    /**
     * 获取分类的中文显示标签
     */
    private String getCategoryLabel(OperationCategory category) {
        switch (category) {
            case READ:          return "📖 文件读取";
            case WRITE:         return "✏️ 文件写入";
            case DELETE:        return "🗑️ 删除操作";
            case EXECUTE:       return "⚡ 命令执行";
            case NETWORK:       return "🌐 网络请求";
            case DATABASE:      return "🗄️ 数据库";
            case GIT:           return "🔀 Git 操作";
            case SERVICE:       return "🔄 服务管理";
            case SKILL:         return "🧠 技能管理";
            case COMMUNICATION: return "💬 用户交互";
            case ADMIN:         return "⚙️ 管理操作";
            case DESKTOP:       return "🖥️ 桌面自动化";
            case BROWSER:       return "🧭 浏览器自动化";
            default:            return category.name();
        }
    }

    /**
     * 工具信息内部类
     */
    static class ToolInfo {
        private String name;
        private String description;
        private String risk;

        public ToolInfo() {}

        public ToolInfo(String name, String description, String risk) {
            this.name = name;
            this.description = description;
            this.risk = risk;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getRisk() { return risk; }
        public void setRisk(String risk) { this.risk = risk; }
    }
}
