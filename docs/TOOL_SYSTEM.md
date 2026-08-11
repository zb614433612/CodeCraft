> 🌐 English Version：[🇬🇧 TOOL_SYSTEM_EN](./TOOL_SYSTEM_EN.md)
# 工具系统深描：如何新增一个 AI Tool

> 版本：v1.1.5 | 更新：2026-07-08 | 受众：开发者 / AI 协作伙伴
> 本文档覆盖工具系统的完整架构、执行链路，以及新增一个 Tool 的 step-by-step checklist。

---

## 一、架构概览

```
                     ┌──────────────────────────┐
                     │  Tool.java (接口)         │
                     │  - getName()              │
                     │  - getDescription()       │
                     │  - getParameters()        │
                     │  - execute(arguments)     │
                     └────────────┬─────────────┘
                                  │ implements
                     ┌────────────┴─────────────┐
                      │  21 个工具实现类           │
                     │  (@ToolPermission 注解)   │
                     └────────────┬─────────────┘
                                  │ 自动注入 List<Tool>
      ┌───────────────────────────┼───────────────────────────────┐
      │                           │                               │
┌─────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
│ToolInitializer│  │ToolPermissionRegistry│   │  ToolRegistry        │
│(Application  │   │ (@Component)         │   │  (@Component)        │
│ Runner 阶段) │   │                      │   │                      │
│              │   │ 启动时扫描所有       │   │ ConcurrentHashMap     │
│ 遍历所有Tool │   │ @ToolPermission      │   │ <name → Tool>        │
│ → registry   │   │ 构建权限元数据索引   │   │                      │
│   .register()│   │                      │   │ register/get/remove  │
└─────────────┘   └──────────────────────┘   └──────────┬───────────┘
                                                          │
                                                          │
                                              ┌──────────────────────┐
                                              │  ToolExecutor        │
                                              │  (执行引擎)           │
                                              │                      │
                                              │ executeToolCalls()    │
                                              │   ├─ 解析tool_calls  │
                                              │   ├─ 快照创建         │
                                              │   ├─ 权限管道执行     │
                                              │   └─ diff统计        │
                                              └──────────┬───────────┘
                                                         │
                                              ┌──────────────────────┐
                                              │ ToolExecutionPipeline │
                                              │  (三层防护)           │
                                              │                      │
                                              │ ① 会话级自动批准     │
                                              │ ② 数据影响防护       │
                                              │ ③ 路径穿透防护       │
                                              │ ④ 高危操作防护       │
                                              │ ⑤ 审计日志           │
                                              │ ⑥ 执行               │
                                              └──────────────────────┘
```

---

## 二、核心接口：Tool.java

```java
public interface Tool {
    String getName();           // 工具名（唯一标识），如 "write_file"
    String getDescription();    // 工具描述，会发送给 AI 模型
    JsonNode getParameters();   // JSON Schema 格式的参数定义
    String execute(JsonNode arguments);  // 执行逻辑，返回字符串结果
}
```

四个方法各司其职：
- `getName()` → 注册中心索引
- `getDescription()` + `getParameters()` → 构建 OpenAI Function Calling 的 `tools` 定义
- `execute()` → 核心业务逻辑，被 `ToolExecutor` 调度

---

## 三、注册机制：两阶段启动

```
Spring Boot 启动
    ↓
    ├─ 阶段 1: InitializingBean.afterPropertiesSet()
    │   └─ ToolPermissionRegistry 扫描所有 @ToolPermission 注解
    │       构建 metadataMap<toolName → 权限元数据>
    │
    └─ 阶段 2: ApplicationRunner.run()
        └─ ToolInitializer 遍历 List<Tool>（Spring 自动注入所有 Tool bean）
            逐一调用 toolRegistry.register(tool)
```

**关键设计**：`ToolPermissionRegistry` 不依赖 `ToolRegistry`，而是直接注入 `List<Tool>`。这样权限元数据在工具注册之前就已就绪，避免时序问题。

---

## 四、执行引擎：ToolExecutor

### 4.1 主入口 `executeToolCalls(JsonNode toolCallsJson)`

AI 返回的 `tool_calls` 是数组，每个元素结构：
```json
{
  "id": "call_xxx",
  "function": {
    "name": "write_file",
    "arguments": "{\"file_path\":\"...\",\"content\":\"...\"}"
  }
}
```

执行流程：
1. 遍历每个 tool_call
2. 解析 `function.name` → 从 ToolRegistry 获取 Tool 实例
3. 解析 `function.arguments` → JSON 字符串 → JsonNode
4. **文件修改类工具**（write_file/edit_file/delete_file）→ 先创建快照
5. 调用 `ToolExecutionPipeline.execute()`（替代直接 `tool.execute()`）
6. **文件修改类工具** → 计算 diff 统计
7. 返回 `ToolCallResult`（含 toolCallId、toolName、content、restricted、operationSummary）

### 4.2 结果消息构建 `buildToolMessages()`

将执行结果转换为 OpenAI API 格式的 tool 消息：
```json
{
  "role": "tool",
  "content": "写入成功...",
  "tool_call_id": "call_xxx",
  "tool_name": "write_file"
}
```

### 4.3 工具定义构建 `buildToolDefinitions(List<String> toolNames)`

按 Agent 配置中指定的工具名列表，从 Registry 取出对应 Tool，构建标准的 OpenAI Function Calling 定义数组。

---

## 五、权限系统：三层防护管道

### 5.1 @ToolPermission 注解

```java
@Retention(RUNTIME)
@Target(TYPE)
public @interface ToolPermission {
    OperationCategory category() default READ;  // READ/WRITE/DELETE/EXECUTE/GIT/NETWORK
    boolean affectsData() default false;         // 层面一：是否影响数据
    boolean isPathSensitive() default false;     // 层面二：是否涉及文件路径
    boolean highRisk() default false;            // 层面三：是否高危操作
    String description() default "";
}
```

示例（WriteFileTool）：
```java
@ToolPermission(
    category = OperationCategory.WRITE,
    affectsData = true,      // 写入数据 → manual 模式弹窗
    isPathSensitive = true,  // 涉及路径 → 越界检查
    description = "写入/覆盖文件"
)
```

### 5.2 ToolPermissionRegistry

启动时自动扫描所有 `@ToolPermission` 注解，构建 `metadataMap`。提供查询方法：
- `requiresDataApproval(toolName)` → 层面一
- `isPathSensitive(toolName)` → 层面二
- `isHighRisk(toolName)` → 层面三

### 5.3 ToolExecutionPipeline 执行流程

```
阶段 0：会话级自动批准检查
  └─ PermissionContext.isSessionApproved(conversationId)?
     └─ YES → 跳过所有权限检查，直接审计+执行

阶段 1.1：层面一 — 数据影响防护（仅 manual 模式）
  └─ executionMode == "manual" && affectsData()
     └─ PermissionContext.requestPermission() → 前端弹窗
        └─ 用户拒绝 → 返回拒绝信息，不执行

阶段 1.2：层面二 — 路径穿透防护（仅 manual 模式）
  └─ executionMode == "manual" && isPathSensitive()
     └─ PathSecurityChecker.checkAndRequest() → 检测路径越界
        └─ 越界 → 前端弹窗确认

阶段 1.3：层面三 — 高危操作防护（auto 模式也需授权）
  └─ executionMode == "auto" && highRisk()
     └─ PermissionContext.requestHighRiskPermission() → 前端弹窗

阶段 2：审计日志
  └─ ToolAuditLogger.log(toolName, arguments, userId, executionMode)

阶段 3：执行
  └─ tool.execute(arguments)
```

---

## 六、后处理管线：PostEditPipeline

由 `write_file` 和 `edit_file` 工具在执行后触发：

```
PostEditPipeline.execute(filePath)
    ↓
    ├─ 1. Formatter.format(filePath)
    │   按语言选择格式化器（Java→IDE formatter, Python→black, JS→prettier...）
    │   返回 FormatResult（含是否执行、是否成功、消息）
    │
    └─ 2. Diagnostic.diagnose(filePath)
        编译检查 / lint 检查
        返回 DiagnosticResult（含错误列表、警告列表）
```

两个阶段独立运行，单个失败不影响另一个，不阻塞主流程。结果合并后追加到工具返回值末尾。

---

## 七、如何新增一个 Tool：Step-by-Step Checklist

### Step 1：创建实现类

在 `tool/impl/` 下创建类，实现 `Tool` 接口：

```java
@Slf4j
@Component
@ToolPermission(
    category = OperationCategory.XXX,  // 选择合适的分类
    affectsData = true/false,
    isPathSensitive = true/false,
    highRisk = true/false,
    description = "简短描述"
)
public class MyNewTool implements Tool {

    private final ObjectMapper objectMapper;

    public MyNewTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "my_new_tool";  // 唯一标识，snake_case
    }

    @Override
    public String getDescription() {
        return "【适用场景】... 【使用方式】... 【注意事项】...";
    }

    @Override
    public JsonNode getParameters() {
        // 构建 JSON Schema
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        // ... 定义每个参数的类型和描述
        parameters.set("properties", properties);
        parameters.putArray("required").add("param1").add("param2");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 1. 解析参数
        // 2. 参数校验
        // 3. 执行业务逻辑
        // 4. 返回结果字符串
    }
}
```

### Step 2：选择正确的 OperationCategory

| 分类 | 含义 | 示例工具 |
|------|------|---------|
| `READ` | 只读操作 | read_file, glob_files, grep_search |
| `WRITE` | 写入/创建 | write_file, edit_file |
| `DELETE` | 删除操作 | delete_file |
| `EXECUTE` | 命令执行 | run_command, run_server |
| `GIT` | Git 操作 | git_add, git_commit, git_push |
| `NETWORK` | 网络请求 | web_search, web_fetch, http_request |
| `DATABASE` | 数据库操作 | execute_sql |
| `SYSTEM` | 系统级操作 | fork_agent, task_manager |

### Step 3：配置三层权限

| 你的工具行为 | affectsData | isPathSensitive | highRisk |
|-------------|:-----------:|:---------------:|:--------:|
| 只读文件内容 | ✓ false | ✓ false | ✓ false |
| 写入/修改文件 | ✓ true | ✓ true | ✓ false |
| 删除文件 | ✓ true | ✓ true | ✓ true |
| 执行 shell 命令 | ✓ true | ✓ false | ✓ true |
| 数据库写操作 | ✓ true | ✓ false | ✓ true |
| Git push | ✓ true | ✓ false | ✓ true |
| 网络请求 | ✓ false | ✓ false | ✓ false |

### Step 4：无需额外注册

Spring 自动发现 `@Component` → `ToolInitializer` 自动注册到 `ToolRegistry` → `ToolPermissionRegistry` 自动解析 `@ToolPermission`。

### Step 5：如果是文件修改类工具

参考 `WriteFileTool`，在 `execute()` 完成后调用后处理管线。注意：ToolExecutor 已统一处理了快照创建和 diff 统计，工具内部无需再处理。

### Step 6：更新前端（如需要）

如果新工具需要特殊的前端展示（如命令终端卡片、文件清单卡片），在 `frontend/src/` 中：
- `utils/markdown.ts`：添加渲染逻辑
- 相关组件：添加交互处理

---

## 八、全部工具速查表

| 工具名 | 分类 | affectsData | pathSensitive | highRisk |
|--------|------|:-----------:|:-------------:|:--------:|
| `file_explorer` | READ | ✗ | ✓ | ✗ |
| `file_writer` | WRITE | ✓ | ✓ | ✗ |
| `command` | EXECUTE | ✓ | ✗ | ✓ |
| `web_search` | NETWORK | ✗ | ✗ | ✗ |
| `web_fetch` | NETWORK | ✗ | ✗ | ✗ |
| `http_request` | NETWORK | ✗ | ✗ | ✗ |
| `check_network` | NETWORK | ✗ | ✗ | ✗ |
| `execute_sql` | DATABASE | ✓ | ✗ | ✓ |
| `git_query` | GIT | ✗ | ✗ | ✗ |
| `git_submit` | GIT | ✓ | ✗ | ✓ |
| `git_branch` | GIT | ✓ | ✗ | ✗ |
| `agent` | SYSTEM | ✗ | ✗ | ✗ |
| `skill` | SYSTEM | ✓ | ✗ | ✗ |
| `lesson` | SYSTEM | ✓ | ✗ | ✗ |
| `project_info` | READ | ✗ | ✗ | ✗ |
| `task_manager` | SYSTEM | ✓ | ✗ | ✗ |
| `ask_clarification` | SYSTEM | ✗ | ✗ | ✗ |
| `chat_attachment` | READ | ✗ | ✗ | ✗ |
| `mcp_server_manager` | SYSTEM | ✓ | ✗ | ✗ |
| `schedule_task` | SYSTEM | ✓ | ✗ | ✗ |
| `query_tool_history` | READ | ✗ | ✗ | ✗ |

> 📌 速查表按当前 21 个内置工具维护；MCP 外部工具（`mcp_server_manager` 动态注册）不在此表内。

---

## 八·五、成长体系：Lesson 踩坑经验库

`lesson` 工具是成长体系（经验库）的入口，与 `skill`（常驻匹配的工作流模板）互补：**lesson 是「按需检索的失败经验」**，平时零上下文开销，出错时自动命中。

### 8.5.1 数据流

```
工具执行失败 (ToolExecutor)
    ↓ 自动捕获草稿 (source=auto)
LessonDraft 入库（仅现象 + 错误码 + 工具名）
    ↓ 被动注入（同会话去重，最多 1 次）
「解法提示 + 补全引导」注入 LLM 上下文
    ↓
LLM 应用解法 → 会话结束未再犯 → F3 自动判「有效」→ 经验转正 (ACTIVE)
LLM 再次失败        → F3 自动判「无效」→ 经验降权/隐藏 (HIDDEN)
```

### 8.5.2 经验来源（source 四通道）

| source | 触发方式 | 说明 |
|--------|---------|------|
| `auto` | 工具执行抛异常 | ToolExecutor 自动捕获草稿（只有现象，无解法） |
| `llm` | LLM 主动 `lesson action=record` | 一步到位带解法，质量最高 |
| `manual` | 人工沉淀（前端管理页） | 人工编辑补全 |
| `review` | 对话级异步复盘 | 工具循环结束后调 LLM 提炼根因/解法（C2） |

### 8.5.3 验证闭环（F3 追踪）

- **有效复用 2 次** → 经验自动转正（草稿 → 有效）
- **连续失败 5 次** → 经验自动隐藏，不再参与检索
- **feedback 反馈**：LLM 应用解法后主动 `lesson action=feedback`，加速经验置信度收敛
- 经验按 `project_key` 隔离，跨项目检索仅命中 `global=true` 的通用经验

### 8.5.4 前端管理

「踩坑经验」管理页（`/lesson-manage`，菜单 SETTING 组）：分页浏览、详情查看、人工编辑补全、反馈验证、删除，以及成长看板（总数/草稿/有效/隐藏/命中次数/成功率）。

---

## 九、与 DeepSeekServiceImpl 的协作接口

工具系统通过以下方式被 AI 核心引擎调用：

```
DeepSeekServiceImpl
    ↓
    ├─ toolExecutor.buildToolDefinitions(toolNames) → 构建 tools 数组，发送给 AI
    │
    ├─ toolExecutor.executeToolCalls(toolCallsJson) → AI 请求工具时执行
    │   └─ ToolExecutionPipeline.execute()
    │
    └─ toolExecutor.buildToolMessages(results) → 工具结果转为消息格式，追加到对话
```

权限上下文（`PermissionContext`）由 `DeepSeekServiceImpl` 在每次用户新消息开始时通过 `PermissionContext.removeSessionApproved()` 重置。

---

## 十、MCP 外部工具（动态注册）

除静态注解工具外，工具系统还支持 **MCP（Model Context Protocol）外部工具**——由 MCP Client 模块从外部 MCP Server 拉取、运行时动态注册进 ToolRegistry，与内置工具走同一套执行与权限管道。

### 10.1 注册链路

```
McpClientManager（启动时自动连接 mcp_server 表 enabled=1 的配置）
    ↓ listTools() 拉取外部工具列表
McpToolAdapter（包装器：工具名前缀 + 描述【MCP-服务器名】标记）
    ↓ toolRegistry.register(adapter)          ← 与内置工具同池
ToolPermissionRegistry.register(fullName, 档位.toMetadata())   ← 权限联动（P7）
```

- **命名**：`工具前缀 + 原始名`（前缀取自 `mcp_server.tool_prefix`，缺省用服务器名小写），强制避免与内置工具冲突；同名冲突时跳过注册
- **描述**：自动追加 `【MCP-服务器名】` 标记，前端可辨识
- **注销**：断开连接时 `toolRegistry.removeTool()` + `permissionRegistry.unregister()` 同步清理

### 10.2 权限档位（mcp_server.permission_level → ToolPermissionMetadata）

| 档位 | 映射 | 效果 |
|------|------|------|
| `SAFE`（默认） | READ / affectsData=false / highRisk=false | 无授权要求 |
| `DATA` | WRITE / affectsData=true / highRisk=false | manual 模式需前置授权 |
| `HIGH_RISK` | EXECUTE / affectsData=true / pathSensitive=true / highRisk=true | 所有模式需授权 |

动态注册与静态 `@ToolPermission` 注解扫描共存：动态注册覆盖同名 key，注销后回退默认元数据。

### 10.3 配置方式

外部服务器配置存于 `mcp_server` 表（name / type[http|stdio] / url 或 command / headers / tool_prefix / permission_level / enabled / auto_register），通过前端「MCP 服务器」管理页或 `/api/mcp/servers` API 维护。详细设计见 `docs/MCP_SYSTEM.md`。

> 📌 **文档维护约定**: 新增工具后请更新本文档的「全部工具速查表」。
