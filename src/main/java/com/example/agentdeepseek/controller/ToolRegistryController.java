package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
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

    public ToolRegistryController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/registry")
    @Operation(summary = "获取工具注册表（按分类分组，含描述）")
    public ApiResponse<Map<String, Object>> getToolRegistry() {
        Collection<Tool> allTools = toolRegistry.getAllTools();

        // 按分类分组（用 LinkedHashMap 保持枚举顺序）
        Map<OperationCategory, List<ToolInfo>> grouped = new LinkedHashMap<>();

        for (Tool tool : allTools) {
            ToolPermission permission = tool.getClass().getAnnotation(ToolPermission.class);
            OperationCategory category = permission != null ? permission.category() : OperationCategory.READ;
            String description = permission != null ? permission.description() : tool.getDescription();

            // 判断风险等级
            String risk = "low";
            if (permission != null) {
                if (permission.highRisk()) {
                    risk = "high";
                } else if (permission.affectsData()) {
                    risk = "medium";
                }
            }

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
