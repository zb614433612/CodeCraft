# CodeCraft MCP 功能设计方案

> 版本：v1.0（正式版）| 日期：2026-08 | 状态：✅ 已实现（Phase 1-8 全部完成）
> 需求：CodeCraft 新增 MCP 功能，**双向支持**（Client 接入外部 MCP Server + Server 对外暴露内置工具）
> 说明：本文档为设计方案；实际实现与验证结果见文末「十一、实现记录与验证结果」

---

## 一、目标与背景

MCP（Model Context Protocol，模型上下文协议）是 Anthropic 提出的开放协议，用于标准化 LLM 应用与外部工具/数据源之间的连接。CodeCraft 已具备完整的自建工具系统（Tool 接口 + ToolRegistry + ToolExecutor + 三层权限管道），新增 MCP 功能的两个方向：

| 方向 | 含义 | 价值 |
|------|------|------|
| **MCP Client** | CodeCraft 作为客户端，连接外部 MCP Server（GitHub、数据库、浏览器自动化等），将外部工具拉取进现有 ToolRegistry | 让 DeepSeek/OpenAI 等模型直接调用海量 MCP 生态工具，无需逐个手写 |
| **MCP Server** | CodeCraft 作为服务端，把内置 24 个工具（文件/Git/命令/搜索等）暴露为 MCP 服务 | 让 Claude Desktop、Cursor 等 MCP 客户端连接 CodeCraft，复用其工具能力 |

---

## 二、技术选型

### 2.1 MCP Java SDK（官方）

使用官方 `io.modelcontextprotocol.sdk`（java-sdk）v2.0.0（2026-06-11 GA，跟踪 MCP 2025-11-25 规范）。

**⚠️ 关键兼容性决策（务必遵守）**：

1. **Jackson 版本冲突**：v2.0.0 的 `mcp` 便捷包默认捆绑 **Jackson 3**，而 CodeCraft（Spring Boot 3.4）使用 **Jackson 2**。直接引入 `mcp` 包会导致序列化冲突。
   - ✅ **正确做法**：只引入 `mcp-core` + `mcp-json-jackson2` 两个构件
2. **不引入 Spring AI**：java-sdk 的 Spring 集成（WebMVC/WebFlux/WebClient transport）已迁至 Spring AI 2.0+（`org.springframework.ai`），引入会带来大量依赖且与项目自建 LLM 层冲突。本项目不需要 Spring AI 集成——`mcp-core` 已内置：
   - **服务端传输**：Jakarta Servlet（Streamable HTTP）✅ 项目有 spring-boot-starter-web（Tomcat）
   - **客户端传输**：JDK HttpClient ✅ 零额外依赖
3. **传输协议**：SSE 传输已在 v2 废弃，统一使用 **Streamable HTTP**
4. **Java 17 兼容**：SDK 要求 Java 17+ ✅ 项目正好是 Java 17

### 2.2 依赖坐标（pom.xml 新增）

```xml
<!-- MCP Java SDK：core + Jackson2 绑定（勿用 mcp 聚合包，避免 Jackson3 冲突） -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-core</artifactId>
    <version>2.0.0</version>
</dependency>
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-json-jackson2</artifactId>
    <version>2.0.0</version>
</dependency>
```

> 兜底方案：若 v2.0.0 在 Maven Central 不可用或存在集成问题，回退 1.x 最新稳定版（1.1.x），API 差异以官方 MIGRATION-2.0.md 为准。

### 2.3 设计原则

- **不侵入现有工具系统**：MCP 工具通过适配器（Adapter）模式接入，现有 ToolExecutor / 权限管道 / 审计日志 全部复用
- **可插拔**：MCP Client 连接、MCP Server 暴露均可独立开关
- **配置驱动**：服务器列表、工具白名单均存数据库/配置文件，不硬编码

---

## 三、总体架构

```
┌──────────────────────────────────────────────────────────────────┐
│                        CodeCraft 应用                            │
│                                                                  │
│  ┌──────────────┐      ┌──────────────────────────────────────┐  │
│  │   LLM 层      │      │  工具系统（现有，零改动）              │  │
│  │ DeepSeek     │◄────►│  ToolRegistry ◄── ToolInitializer     │  │
│  │ OpenAI       │      │  ToolExecutor（解析/执行/智能补齐）     │  │
│  │ Anthropic    │      │  ToolExecutionPipeline（三层权限防护）  │  │
│  │ Ollama/MiMo  │      │  24 个内置工具（@Component 自动注册）  │  │
│  └──────────────┘      └──────────────┬───────────────────────┘  │
│                                       │                          │
│          ┌────────────────────────────┼───────────────────┐      │
│          │ 注册 McpToolAdapter         │                   │      │
│  ┌───────▼──────────┐      ┌──────────▼───────────┐  ┌────▼────┐ │
│  │ MCP Client 模块   │      │ MCP Server 模块      │  │ 配置层   │ │
│  │                  │      │                      │  │        │ │
│  │ McpClientManager │      │ CodeCraftMcpServer   │  │ mcp_    │ │
│  │ ├─ 连接管理/重连  │      │ ├─ Streamable HTTP   │  │ server  │ │
│  │ ├─ listTools     │      │ │  端点 /mcp          │  │ 表      │ │
│  │ └─ McpToolAdapter│      │ ├─ 工具白名单         │  │ mcp_    │ │
│  │    (Tool适配器)   │      │ └─ Token 认证(可选)   │  │ exposure│ │
│  └───────┬──────────┘      └──────────┬───────────┘  │ 表      │ │
│          │                            │              └─────────┘ │
└──────────┼────────────────────────────┼───────────────────────────┘
           │                            │
   ┌───────▼────────┐          ┌────────▼─────────┐
   │ 外部 MCP Server │          │ 外部 MCP 客户端   │
   │ 1..N           │          │ Claude Desktop   │
   │ (GitHub/DB/    │          │ Cursor           │
   │  Filesystem等) │          │ 其他 MCP Host    │
   └────────────────┘          └──────────────────┘
```

> 📌 **图示说明**：LLM 层现已包含 **MiniMax**（M3 / M2.x 系列，OpenAI 兼容 + adaptive thinking + reasoning_split）在内的 6 个 Provider 实现（DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax），详见 `LLM_PROVIDER_SYSTEM.md`。

**核心思路**：两个方向都通过「适配器」桥接到现有 Tool 接口，业务侧（LLM 调用、权限、审计、快照）完全复用，新增代码集中在 mcp 包内，与现有代码解耦。

---

## 四、MCP Client 模块设计（接入外部 Server）

### 4.1 数据库表：`mcp_server`（外部服务器配置）

沿用项目 MyBatis 注解 Mapper + 雪花 ID 风格（参考 `llm_provider` 表）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 雪花主键（ASSIGN_ID） |
| `name` | VARCHAR(100) | 服务器名称（展示用），如 "GitHub" |
| `type` | VARCHAR(20) | 传输类型：`http`（Streamable HTTP，推荐）/ `stdio`（本地进程） |
| `url` | VARCHAR(500) | http 类型：MCP 端点 URL，如 `http://localhost:3001/mcp` |
| `command` | VARCHAR(500) | stdio 类型：启动命令，如 `npx -y @modelcontextprotocol/server-github` |
| `headers` | TEXT | 自定义请求头 JSON（如 `{"Authorization": "Bearer xxx"}`） |
| `tool_prefix` | VARCHAR(50) | 工具名前缀（防冲突），如 `github_`，空则用服务器名小写 |
| `enabled` | TINYINT | 是否启用（1/0） |
| `auto_register` | TINYINT | 启用时是否自动注册其工具到 ToolRegistry（1/0） |
| `created_at` / `updated_at` | DATETIME | 时间戳 |

### 4.2 核心类设计（包：`mcp/client`）

```
mcp/client/
├── McpClientManager.java        // 连接生命周期管理（单例 @Component）
├── McpToolAdapter.java          // Tool 适配器：外部 MCP 工具 → 内部 Tool 接口
├── McpToolDefinition.java       // 拉取到的外部工具元数据（name/desc/schema）
├── McpConnectionState.java      // 连接状态枚举（CONNECTED/FAILED/DISABLED）
└── McpClientProperties.java     // 配置绑定（可选，默认读 DB）
```

**McpClientManager 职责**：
1. 启动时（`ApplicationRunner`，在 ToolInitializer 之后）读取 `enabled=1` 的服务器配置
2. 逐个建立 MCP 连接：`McpClient`（sync）→ `initialize()` → `listTools()`
3. 将每个外部工具包装为 `McpToolAdapter`，调用 `toolRegistry.register(adapter)`
4. 连接失败：记录失败状态与原因，不阻塞应用启动（降级为 DISABLED）
5. 提供动态方法：`connect(serverId)` / `disconnect(serverId)` / `refreshTools(serverId)`（供 Controller 调用）
6. 定时健康检查（可选，Caffeine + @Scheduled，默认 60s 一次，失败自动重连）

**McpToolAdapter 实现要点**（实现现有 `Tool` 接口）：

```java
public class McpToolAdapter implements Tool {
    private final Long serverId;
    private final String prefix;          // 如 "github_"
    private final String originalName;    // 外部工具名，如 "create_issue"
    private final String description;
    private final JsonNode inputSchema;   // MCP inputSchema → 现有 Tool.getParameters() 格式
    private final McpClient mcpClient;    // 同步客户端

    public String getName() { return prefix + originalName; }  // "github_create_issue"
    public JsonNode getParameters() { return inputSchema; }
    public String execute(JsonNode arguments) {
        // mcpClient.callTool(new CallToolRequest(originalName, arguments));
        // 返回 CallToolResult 的文本内容（content[0].text，或 isError 时返回错误信息）
    }
}
```

### 4.3 工具注册流程

```
应用启动
  → ToolInitializer 注册 24 个内置工具
  → McpClientManager（ApplicationRunner，order 靠后）
      → 读 mcp_server 表 enabled=1
      → for each server:
          connect → initialize → listTools
          → 每个工具 new McpToolAdapter → toolRegistry.register()
      → 日志：MCP 工具注册完成，共 N 个（来源 server 明细）
```

- **工具名冲突防护**：强制使用 `tool_prefix`（默认 server 名小写 + `_`），与内置工具名天然隔离
- **动态增删**：用户新增/启用服务器 → `connect()` 注册工具；删除/停用 → `disconnect()` 注销（`toolRegistry.removeTool()`）
- **Agent 工具选择**：Agent 配置的工具列表（toolNames）由前端多选，MCP 工具以 `mcp://serverName/toolName` 或前缀形式出现在选择器中（见前端设计）

### 4.4 与现有权限系统的集成（关键设计点）

现有权限系统基于 **静态注解** `@ToolPermission`（编译期扫描），而 MCP 工具是**运行时动态创建**的，需要扩展：

1. `ToolPermissionRegistry` 增加动态注册 API：
   ```java
   public void registerDynamicPermission(String toolName, ToolPermission permission);
   public void removeDynamicPermission(String toolName);
   ```
2. MCP 服务器配置增加「权限档位」字段（`permission_level`）：`SAFE`（默认）/ `DATA` / `HIGH_RISK`
   - SAFE：affectsData=false, isPathSensitive=false, highRisk=false（外部只读工具）
   - DATA：affectsData=true（写操作类外部工具）
   - HIGH_RISK：三项全 true（需 manual 授权或 auto 高危弹窗）
3. 执行链路零改动：`ToolExecutor` → `executionPipeline.execute()` 按既有逻辑处理 MCP 工具
4. 审计日志自动覆盖：MCP 工具调用同样进入 ToolAuditLogger

### 4.5 异常与超时策略

- 调用超时：默认 60s（可配置），超时返回友好错误信息给 LLM
- 外部 Server 宕机：`callTool` 异常 → 返回「MCP 服务器 xxx 不可用」而非中断对话
- stdio 类型：进程崩溃后自动重启（最多 3 次/10 分钟，防循环）

---

## 五、MCP Server 模块设计（对外暴露工具）

### 5.1 暴露方式

- 传输：Streamable HTTP（mcp-core 内置 Servlet transport，适配现有 Tomcat）
- 端点：`POST /mcp`（可配置），同时暴露 `GET /mcp`（SSE 流，2025-11-25 规范）
- 无需新增端口，复用 Spring Boot 内嵌 Tomcat

### 5.2 核心类设计（包：`mcp/server`）

```
mcp/server/
├── CodeCraftMcpServer.java        // 构建并启动 McpServer（@Component，@PostConstruct 初始化）
├── McpToolHandler.java            // 实现 tools/list + tools/call 的处理器
├── McpExposureConfig.java         // 暴露配置（白名单列表、端点、Token）
├── McpAuthFilter.java             // 可选 Token 认证（Servlet Filter）
└── McpAuditLogger.java            // 外部调用审计（复用 LogService）
```

**McpToolHandler 职责**：
- `tools/list`：遍历白名单中的内置 Tool，转换 MCP 格式：
  ```
  { name: tool.getName(), description: tool.getDescription(), inputSchema: tool.getParameters() }
  ```
- `tools/call`：解析工具名与参数 → 直接调用 `tool.execute(arguments)`（同步、阻塞式，适配 Servlet）
  - 结果包装为 `CallToolResult`（`content[0].text` = 工具返回字符串；异常 → `isError=true`）
  - ⚠️ 注意：不走 ToolExecutor 的权限管道（外部客户端无 CodeCraft 用户上下文），改为**白名单前置校验 + 审计记录**

### 5.3 工具白名单（`mcp_exposure` 表 / application.yml）

| 默认状态 | 工具 |
|---------|------|
| ✅ 默认暴露（只读） | `file_explorer`(read/glob/grep/tree)、`git_query`、`web_search`、`web_fetch`、`http_request`、`check_network`、`project_info` |
| 🔒 需显式开启（写/执行） | `file_writer`、`command`、`git_submit`、`git_branch`、`execute_sql`、`agent`、`agent_invoke`、`skill`、`task_manager`、`chat_attachment`、`schedule_task` |

配置项（application.yml 段）：
```yaml
codecraft:
  mcp:
    server:
      enabled: true          # 总开关
      path: /mcp             # 端点路径
      auth-token: ""         # 空=不认证；非空=客户端需带 X-API-Key 头
      exposed-tools:         # 白名单；"*" 表示全部
        - file_explorer
        - git_query
        - web_search
```

> 也可存 `mcp_exposure` 表支持运行时热更新（前端页面勾选），两种方式二选一，推荐先做 yml 静态配置，后续再升级 DB 动态化。

### 5.4 安全设计

| 风险 | 对策 |
|------|------|
| 未授权调用 | 可选 `X-API-Key` Token 认证（McpAuthFilter，路径拦截 /mcp） |
| 危险工具被外部调用 | 白名单机制：写操作工具默认不暴露 |
| 参数注入 | 复用工具内部校验逻辑（各 Tool 自带参数校验） |
| 无审计 | McpAuditLogger 记录：调用时间、客户端 IP、工具名、参数摘要、结果状态 |
| 路径越界 | 暴露的 file_explorer 仍受 ProjectRootContext 约束（工具内部实现自带） |

### 5.5 外部客户端接入示例

```json
// Claude Desktop 配置（claude_desktop_config.json）
{
  "mcpServers": {
    "codecraft": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": { "X-API-Key": "your-token" }
    }
  }
}
```

---

## 六、后端 API 设计

新增 `McpController`（管理外部服务器配置）+ `McpExposureController`（暴露白名单）：

### 6.1 外部 MCP Server 管理（`/api/mcp/servers`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mcp/servers` | 服务器列表（含连接状态、工具数） |
| POST | `/api/mcp/servers` | 新增服务器配置 |
| PUT | `/api/mcp/servers/{id}` | 修改配置（改后自动重连） |
| DELETE | `/api/mcp/servers/{id}` | 删除配置（自动注销其工具） |
| POST | `/api/mcp/servers/{id}/connect` | 测试连接（initialize + listTools，返回工具清单） |
| POST | `/api/mcp/servers/{id}/disconnect` | 断开连接并注销工具 |
| POST | `/api/mcp/servers/{id}/tools/refresh` | 重新拉取工具列表 |
| GET | `/api/mcp/servers/{id}/tools` | 查看该服务器当前工具（含已注册状态） |

### 6.2 本服务暴露配置（`/api/mcp/exposure`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/mcp/exposure` | 查看暴露配置（端点、Token 是否开启、白名单） |
| PUT | `/api/mcp/exposure` | 更新白名单（若支持 DB 动态化） |

响应统一使用现有 `R<T>` 包装；权限走现有 Sa-Token/Token 认证体系。

---

## 七、前端页面设计

### 7.1 新页面 `McpConfigView.vue`（Vue3 + ant-design-vue 4.x）

接入现有路由与菜单（`/mcp`），参照 `ProviderConfigView.vue` 风格。页面分两个 Tab：

**Tab 1：MCP 服务器（Client 方向）**
- 服务器列表表格：名称 / 类型 / 地址(URL 或命令) / 状态标签（已连接·连接失败·未启用）/ 工具数 / 操作
- 操作列：测试连接、刷新工具、编辑、删除、启停
- 新增/编辑弹窗（a-form）：
  - 名称、类型（select: http/stdio）
  - URL（http 时）或命令（stdio 时）
  - 请求头（key-value 动态列表，存 JSON）
  - 工具名前缀、权限档位（SAFE/DATA/HIGH_RISK）、自动注册开关
- 连接详情抽屉（a-drawer）：该服务器工具列表（名称/描述/参数摘要）+ 注册状态

**Tab 2：对外暴露（Server 方向）**
- 顶部信息卡：端点地址（`http://host:port/mcp`）、认证状态（Token 未开启/已开启）
- 工具白名单：全量内置工具表格 + 勾选暴露（默认只读类勾选，写操作类警示色提示）
- 接入指引（折叠面板）：展示 Claude Desktop / Cursor 的配置 JSON 示例，一键复制

### 7.2 工具选择联动

- `AgentConfigView.vue` 的工具多选组件：MCP 工具显示为「服务器名/工具名」分组（如 `GitHub / create_issue`），与内置工具分组并列，便于配置 Agent 时勾选
- 工具被 LLM 调用时，现有前端工具卡片/日志展示自动生效（无额外改动）

---

## 八、实施步骤规划（Phase）

| Phase | 内容 | 验收标准 |
|-------|------|---------|
| P1 | pom.xml 引入 mcp-core + mcp-json-jackson2 | `mvn compile` 通过，无 Jackson 冲突 |
| P2 | 数据库表 mcp_server + Entity + Mapper + 基础 CRUD Service | 单测/接口可增删改查 |
| P3 | **MCP Server**：CodeCraftMcpServer + McpToolHandler + 白名单 + Token | 本机用 npx @modelcontextprotocol/inspector 连接 /mcp 能列出并调用工具 |
| P4 | **MCP Client**：McpClientManager + McpToolAdapter + 动态注册/注销 | 本机起一个测试 MCP Server（如 filesystem），CodeCraft 能拉到工具并让 DeepSeek 调用 |
| P5 | Controller（server CRUD + exposure）+ Service | API 冒烟测试通过 |
| P6 | 前端 McpConfigView.vue + 路由 + Agent 工具选择联动 | 页面可完成服务器增删改查、测试连接、白名单配置 |
| P7 | 权限档位接入 ToolPermissionRegistry 动态注册 + 审计 | 外部工具按档位走对应权限流程；调用有审计日志 |
| P8 | 端到端验证 + 文档更新（TOOL_SYSTEM.md 工具速查表、README、MCP_SYSTEM.md 转正式版） | 双向联通验证通过 |

> 建议 P3（Server）先行：独立性强、便于用官方 Inspector 工具自测；P4（Client）其次。

---

## 九、风险与注意事项

| 风险 | 等级 | 对策 |
|------|------|------|
| Jackson 3/2 冲突（mcp 聚合包） | 🔴 高 | 只引入 mcp-core + mcp-json-jackson2，禁止引入 `mcp` 聚合包 |
| v2.0.0 较新，资料少 | 🟡 中 | 预留 1.1.x 回退方案；以官方 conformance 测试结果（Server 40/40）为信心依据 |
| stdio 进程管理（Windows/Electron 环境） | 🟡 中 | 进程超时、崩溃重启策略；打包环境注意 npx 可用性 |
| MCP 工具绕过权限管道 | 🟡 中 | 权限档位 + 动态注册 API + 白名单前置校验 |
| 工具名冲突 | 🟢 低 | 强制前缀机制 |
| 外部 Server 可用性影响主流程 | 🟢 低 | 连接失败不阻塞启动；调用异常返回友好错误 |
| 大模型上下文膨胀（工具定义过多） | 🟢 低 | 按 Agent 配置选工具，MCP 工具同样受 toolNames 白名单控制 |

---

## 十、交付物清单

1. `docs/MCP_SYSTEM.md`（本文档）
2. 后端：`mcp/client/*`、`mcp/server/*`、`McpController`、`McpExposureController`、`McpServerService`、`mcp_server` 表及 Mapper/Entity
3. 前端：`McpConfigView.vue`、路由与菜单、Agent 工具选择联动
4. 测试：MCP Server 端用官方 inspector 验证；Client 端用本地 filesystem MCP 验证
5. 文档：TOOL_SYSTEM.md 更新、README 更新

---

## 十一、实现记录与验证结果（v1.0 转正补充）

### 11.1 实际交付清单（与设计差异说明）

| 设计项 | 实际实现 | 差异说明 |
|--------|----------|----------|
| `mcp/client` 包 | ✅ McpConnection / McpToolAdapter / McpClientManager | 与设计一致 |
| `mcp/server` 包 | ✅ McpServerProperties / McpToolHandler / CodeCraftMcpServer / McpAuthFilter | 与设计一致 |
| `McpController` | ✅ McpServerController（`/api/mcp/servers` CRUD + connect/disconnect/refresh） | 合并了 exposure 管理；`mcp_exposure` 表未建，Server 白名单用 application.yml 配置（6 个只读工具） |
| `McpServerService` | ✅ 含状态合并 VO（status/errorMessage/toolCount/registeredToolCount） | 新增：前端免二次查询 |
| 权限集成 | ✅ ToolPermissionLevel 枚举 + ToolPermissionRegistry 动态注册 API | 与设计一致（P7） |
| 前端 | ✅ McpConfigView.vue + api/mcp.ts + 路由 /mcp-config + 菜单 id=15 | 与设计一致（P6） |
| Agent 工具选择联动 | ✅ MCP 工具注册进 ToolRegistry 后自动纳入 toolNames 白名单机制 | 无需额外开发 |

### 11.2 各 Phase 验证结果

- **P3（Server）**：initialize 握手 ✅；tools/list 返回 6 个默认白名单工具 ✅；tools/call 执行 check_network 成功 ✅；白名单外工具拒绝 ✅
- **P4（Client）**：自连自身，19 内置 + 7 selftest_ 外部 = 26 个工具注册 ✅（v1.1.4 当时；当前为 24 个内置工具）；/api/tools/registry 可见带【MCP-前缀】描述 ✅
- **P5/P6（API+前端）**：列表 API 三态（FAILED/CONNECTED/DISABLED）✅；重复前缀冲突跳过（registeredToolCount=0）✅；disconnect 后工具清零 ✅；mvn compile 全通过（含前端 vite build）✅
- **P7（权限）**：SAFE 档位 → category=READ/affectsData=false ✅；HIGH_RISK 档位 → category=EXECUTE/affectsData=true/highRisk=true ✅；disconnect 同步注销权限元数据 ✅
- **P8（端到端）**：完整调用链路（Client → Server → 内置工具 → 返回）往返执行成功 ✅（临时 main 类验证，结果含「网络连通性检测结果」报告）

### 11.3 已知限制与后续优化

1. **调用链路验证场景**：P8 采用自连自身完成端到端验证；真实外部 Server（如 npx filesystem MCP）验证可留待有真实 LLM key 时通过对话触发
2. **测试残留配置**：H2 库中残留 4 条 SelfTest 自连配置（id=1/33/65/97），前端 MCP 管理页「删除」按钮可清理
3. **mcp_exposure 表未建**：Server 白名单目前走 application.yml 静态配置（`codecraft.mcp.server.tools`），如需页面化管理可后续补
4. **stdio 传输**：代码已支持（ServerParameters + JacksonMcpJsonMapper），未做真实外部进程联测；Windows 打包环境需注意 npx 可用性

### 11.4 AI 聊天窗口管理工具（mcp_server_manager，v1.1 增强）

除前端配置页面外，MCP 服务器管理能力已通过内置工具 `mcp_server_manager` 暴露给 LLM，用户可直接在 AI 聊天窗口用自然语言指挥模型完成服务器管理，无需打开配置页面。

**实现**：`tool/impl/McpServerManagerTool.java`（实现现有 `Tool` 接口，`@Component` 自动注册，复用 `McpServerService` + `McpClientManager` 全部能力）。

**权限**：`@ToolPermission(category = ADMIN, affectsData = true)` —— 手动模式弹窗授权，审计日志自动覆盖。

**支持操作**（action 参数）：

| action | 说明 | 关键参数 |
|--------|------|----------|
| `list` | 查看全部服务器（含连接状态/工具数/权限档位） | 无 |
| `create` | 新建服务器配置（http/stdio） | name、type、url 或 command、headers、tool_prefix、permission_level、enabled、auto_register |
| `update` | 修改配置（未传字段保留原值，自动重连/断开） | id + 需修改字段 |
| `delete` | 删除配置（先断开连接并注销工具）⚠️ 破坏性操作，执行前需用户确认 | id |
| `connect` | 连接服务器并注册其工具 | id |
| `disconnect` | 断开服务器并注销其工具 | id |
| `refresh` | 重新拉取工具列表（断开后重连） | id |
| `tools` | 查看指定服务器当前拉取到的工具明细 | id |

**安全设计**：
- `headers`（含 Authorization 等密钥）在 list/查询结果中不回显明文，仅显示是否已配置
- 输出采用 emoji 状态标识（🟢 已连接 / 🔴 连接失败 / ⚪ 未启用），便于 LLM 向用户直观展示
- delete 为破坏性操作，工具描述中强制要求 LLM 先与用户确认再执行
- 校验逻辑完全复用 `McpServerService.validate`（名称唯一、type 合法性、必填字段），不重复实现

**验证**：`mvn compile` 通过；工具注册进 ToolRegistry 后自动纳入 Agent 工具选择（toolNames 白名单机制），无需额外前端改动。
