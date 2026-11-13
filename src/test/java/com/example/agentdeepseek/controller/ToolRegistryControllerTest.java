package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.tool.permission.ToolPermissionLevel;
import com.example.agentdeepseek.tool.permission.ToolPermissionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具注册表展示层 — 权限档位一致性回归测试（纯逻辑，无需 Spring）。
 *
 * <p><b>回归目标</b>：{@code GET /api/tools/registry} 曾直接读
 * {@code tool.getClass().getAnnotation(ToolPermission.class)} 来判定分类与风险等级。
 * 内置工具有注解，一切正常；但 MCP 外部工具由 {@code McpToolAdapter} 包装，<b>没有类注解</b>，
 * 其真实档位来自 {@code mcp_server.permission_level} 经
 * {@code ToolPermissionRegistry.register(...)} 的动态注册。
 * 于是 HIGH_RISK 的 MCP 工具在智能体「工具集」界面被展示成「只读 + 低风险」，
 * 用户会按无风险勾选一个高危工具 —— 属安全观感误导。</p>
 *
 * <p><b>修复方式</b>：展示层统一以 {@code ToolPermissionRegistry.getMetadata(toolName)} 为准。
 * 本测试锁住两件事：</p>
 * <ol>
 *   <li><b>零回归</b>：带注解的内置工具，展示结果与改动前逐字段一致；</li>
 *   <li><b>修正生效</b>：无注解的动态工具，SAFE / DATA / HIGH_RISK 三档能正确映射为
 *       READ/low、WRITE/medium、EXECUTE/high。</li>
 * </ol>
 *
 * <p>运行方式（main 方法，非 JUnit）：先 {@code mvn -DskipFrontend=true test-compile}，
 * 再用 target/classes + target/test-classes + 依赖 classpath 运行本类。</p>
 */
public class ToolRegistryControllerTest {

    static int passed = 0;
    static int failed = 0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    interface Assertion {
        boolean check() throws Exception;
    }

    // ==================== 测试替身 ====================

    /** 模拟内置工具：权限来自类注解（真实实现如 CommandTool） */
    @ToolPermission(
            category = OperationCategory.EXECUTE,
            affectsData = true,
            highRisk = true,
            description = "执行 shell 命令"
    )
    static class FakeBuiltinTool implements Tool {
        @Override
        public String getName() {
            return "command";
        }

        @Override
        public String getDescription() {
            // 注解描述存在时，展示层应优先用注解里的短描述，而非这段长描述
            return "执行命令并返回输出（长描述，不应出现在工具集界面）";
        }

        @Override
        public JsonNode getParameters() {
            return MAPPER.createObjectNode();
        }

        @Override
        public String execute(JsonNode arguments) {
            return "ok";
        }
    }

    /** 模拟 MCP 外部工具：无任何类注解（真实实现 McpToolAdapter 即如此） */
    static class FakeMcpTool implements Tool {
        private final String name;
        private final String description;

        FakeMcpTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public JsonNode getParameters() {
            return MAPPER.createObjectNode();
        }

        @Override
        public String execute(JsonNode arguments) {
            return "ok";
        }
    }

    // ==================== 装配 ====================

    private final ToolRegistry toolRegistry;
    private final ToolPermissionRegistry permissionRegistry;
    private final ToolRegistryController controller;

    ToolRegistryControllerTest() {
        FakeBuiltinTool builtin = new FakeBuiltinTool();

        this.toolRegistry = new ToolRegistry();
        // 内置工具在 Spring 启动时由 ToolPermissionRegistry 的 afterPropertiesSet() 扫描注解入册
        this.permissionRegistry = new ToolPermissionRegistry(List.of(builtin));
        this.permissionRegistry.afterPropertiesSet();
        this.toolRegistry.register(builtin);

        this.controller = new ToolRegistryController(toolRegistry, permissionRegistry);
    }

    /** 注册一个动态工具：加进 ToolRegistry，并按档位写入权限元数据（模拟 McpClientManager 的注册链路） */
    private void registerDynamicTool(String fullName, String description, ToolPermissionLevel level) {
        toolRegistry.register(new FakeMcpTool(fullName, description));
        if (level != null) {
            permissionRegistry.register(fullName, level.toMetadata());
        }
    }

    // ==================== 主流程 ====================

    public static void main(String[] args) {
        System.out.println("=== ToolRegistryController 权限档位展示回归测试 ===\n");
        test("内置工具(有注解): 分类=EXECUTE / 风险=high / 描述用注解短描述（零回归）", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            ApiResponse<Map<String, Object>> resp = c.controller.getToolRegistry();

            String cat = categoryOf(resp, "command");
            String risk = riskOf(resp, "command");
            String desc = descriptionOf(resp, "command");
            boolean ok = "EXECUTE".equals(cat) && "high".equals(risk) && "执行 shell 命令".equals(desc);
            if (!ok) {
                System.out.println("      category=" + cat + ", risk=" + risk + ", description=" + desc);
            }
            return ok;
        });

        test("MCP工具 HIGH_RISK: 分类=EXECUTE / 风险=high（改动前错误的 READ/low）", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            c.registerDynamicTool("mcp_danger_exec", "执行危险操作 【MCP-demo】", ToolPermissionLevel.HIGH_RISK);

            ApiResponse<Map<String, Object>> resp = c.controller.getToolRegistry();
            String cat = categoryOf(resp, "mcp_danger_exec");
            String risk = riskOf(resp, "mcp_danger_exec");
            boolean ok = "EXECUTE".equals(cat) && "high".equals(risk);
            if (!ok) {
                System.out.println("      category=" + cat + ", risk=" + risk
                        + "（期望 EXECUTE / high；若为 READ / low 说明仍在读类注解）");
            }
            return ok;
        });

        test("MCP工具 HIGH_RISK: 描述回退工具自身文本，保留【MCP-服务器名】标记", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            c.registerDynamicTool("mcp_danger_exec", "执行危险操作 【MCP-demo】", ToolPermissionLevel.HIGH_RISK);

            String desc = descriptionOf(c.controller.getToolRegistry(), "mcp_danger_exec");
            boolean ok = "执行危险操作 【MCP-demo】".equals(desc);
            if (!ok) {
                System.out.println("      description=" + desc);
            }
            return ok;
        });

        test("MCP工具 DATA: 分类=WRITE / 风险=medium", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            c.registerDynamicTool("mcp_write_data", "写入外部数据 【MCP-demo】", ToolPermissionLevel.DATA);

            ApiResponse<Map<String, Object>> resp = c.controller.getToolRegistry();
            String cat = categoryOf(resp, "mcp_write_data");
            String risk = riskOf(resp, "mcp_write_data");
            boolean ok = "WRITE".equals(cat) && "medium".equals(risk);
            if (!ok) {
                System.out.println("      category=" + cat + ", risk=" + risk);
            }
            return ok;
        });

        test("MCP工具 SAFE: 分类=READ / 风险=low（不得误报风险）", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            c.registerDynamicTool("mcp_safe_read", "只读查询 【MCP-demo】", ToolPermissionLevel.SAFE);

            ApiResponse<Map<String, Object>> resp = c.controller.getToolRegistry();
            String cat = categoryOf(resp, "mcp_safe_read");
            String risk = riskOf(resp, "mcp_safe_read");
            boolean ok = "READ".equals(cat) && "low".equals(risk);
            if (!ok) {
                System.out.println("      category=" + cat + ", risk=" + risk);
            }
            return ok;
        });

        test("既无注解也无动态元数据: 回退 DEFAULT(READ/low)，且不从列表消失", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            // level=null 表示不写权限元数据（例如注册链路异常残留的工具）
            c.registerDynamicTool("mcp_orphan", "无元数据工具 【MCP-demo】", null);

            ApiResponse<Map<String, Object>> resp = c.controller.getToolRegistry();
            String cat = categoryOf(resp, "mcp_orphan");
            String risk = riskOf(resp, "mcp_orphan");
            boolean ok = "READ".equals(cat) && "low".equals(risk);
            if (!ok) {
                System.out.println("      category=" + cat + ", risk=" + risk);
            }
            return ok;
        });

        test("注销后重新查询: 工具从分组中消失（不残留幽灵工具）", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            c.registerDynamicTool("mcp_temp", "临时工具 【MCP-demo】", ToolPermissionLevel.SAFE);
            if (categoryOf(c.controller.getToolRegistry(), "mcp_temp") == null) {
                System.out.println("      前置条件失败：注册后未出现在注册表");
                return false;
            }

            // 模拟 McpClientManager.disconnect() 的清理顺序：先 ToolRegistry，后 ToolPermissionRegistry
            c.toolRegistry.removeTool("mcp_temp");
            c.permissionRegistry.unregister("mcp_temp");

            String cat = categoryOf(c.controller.getToolRegistry(), "mcp_temp");
            if (cat != null) {
                System.out.println("      注销后仍残留，category=" + cat);
                return false;
            }
            return true;
        });

        test("分组顺序与分类标签: EXECUTE 分组标签包含中文说明", () -> {
            ToolRegistryControllerTest c = new ToolRegistryControllerTest();
            c.registerDynamicTool("mcp_danger_exec", "执行危险操作 【MCP-demo】", ToolPermissionLevel.HIGH_RISK);

            ApiResponse<Map<String, Object>> resp = c.controller.getToolRegistry();
            Object categoriesRaw = resp.getData().get("categories");
            if (!(categoriesRaw instanceof List<?> categories)) {
                System.out.println("      categories 缺失或类型异常");
                return false;
            }
            for (Object item : categories) {
                if (!(item instanceof Map<?, ?> cat)) {
                    continue;
                }
                if (!"EXECUTE".equals(cat.get("code"))) {
                    continue;
                }
                Object label = cat.get("label");
                System.out.println("      EXECUTE 分组标签=" + label);
                return label != null && label.toString().contains("命令执行");
            }
            System.out.println("      未找到 EXECUTE 分组");
            return false;
        });

        System.out.println("\n=== 结果: " + passed + " 通过 / " + failed + " 失败 ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 断言辅助 ====================

    static void test(String name, Assertion assertion) {
        try {
            if (assertion.check()) {
                passed++;
                System.out.println("  [PASS] " + name);
            } else {
                failed++;
                System.out.println("  [FAIL] " + name);
            }
        } catch (Exception e) {
            failed++;
            System.out.println("  [ERROR] " + name + " -> " + e);
        }
    }

    /** 在响应里定位某个工具所在的分类 code；找不到返回 null */
    private static String categoryOf(ApiResponse<Map<String, Object>> response, String toolName) {
        Map<String, Object> data = response.getData();
        if (data == null) {
            return null;
        }
        Object categoriesRaw = data.get("categories");
        if (!(categoriesRaw instanceof List<?> categories)) {
            return null;
        }
        for (Object item : categories) {
            if (!(item instanceof Map<?, ?> category)) {
                continue;
            }
            Object toolsRaw = category.get("tools");
            if (!(toolsRaw instanceof List<?> tools)) {
                continue;
            }
            for (Object toolItem : tools) {
                if (toolItem instanceof ToolRegistryController.ToolInfo info
                        && Objects.equals(toolName, info.getName())) {
                    Object code = category.get("code");
                    return code == null ? null : code.toString();
                }
            }
        }
        return null;
    }

    /** 取某工具展示的风险等级；找不到返回 null */
    private static String riskOf(ApiResponse<Map<String, Object>> response, String toolName) {
        ToolRegistryController.ToolInfo info = findToolInfo(response, toolName);
        return info == null ? null : info.getRisk();
    }

    /** 取某工具展示的描述；找不到返回 null */
    private static String descriptionOf(ApiResponse<Map<String, Object>> response, String toolName) {
        ToolRegistryController.ToolInfo info = findToolInfo(response, toolName);
        return info == null ? null : info.getDescription();
    }

    private static ToolRegistryController.ToolInfo findToolInfo(ApiResponse<Map<String, Object>> response,
                                                                String toolName) {
        Map<String, Object> data = response.getData();
        if (data == null) {
            return null;
        }
        Object categoriesRaw = data.get("categories");
        if (!(categoriesRaw instanceof List<?> categories)) {
            return null;
        }
        for (Object item : categories) {
            if (!(item instanceof Map<?, ?> category)) {
                continue;
            }
            Object toolsRaw = category.get("tools");
            if (!(toolsRaw instanceof List<?> tools)) {
                continue;
            }
            for (Object toolItem : tools) {
                if (toolItem instanceof ToolRegistryController.ToolInfo info
                        && Objects.equals(toolName, info.getName())) {
                    return info;
                }
            }
        }
        return null;
    }
}
