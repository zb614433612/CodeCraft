# 工具系统深描：如何新增一个 AI Tool

> 版本：v1.0.5 | 更新：2026-05-28 | 受众：开发者 / AI 协作伙伴
> 本文档覆盖工具系统的完整架构、执行链路，以及新增一个 Tool 的 step-by-step checklist。

---

## 一、架构概览

```
                    ┌──────────────────────────┐
                    │   Tool.java (接口)        │
                    │   - getName()             │
                    │   - getDescription()      │
                    │   - getParameters()       │
                    │   - execute(arguments)    │
                    └────────────┬─────────────┘
                                 │ implements
                    ┌────────────┴─────────────┐
                    │   30+ 工具实现类           │
                    │   (@ToolPermission 注解)  │
                    └────────────┬─────────────┘
                                 │ 自动注入 List<Tool>
     ┌───────────────────────────┼───────────────────────────────┐
     │                           │                               │
     ▼                           ▼                               ▼
┌─────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│ToolInitializer│   │ToolPermissionRegistry│   │   ToolRegistry       │
│(Application  │    │  (InitializingBean)  │    │   (@Component)       │
│ Runner 阶段)  │    │                      │    │                      │
│              │    │  启动时扫描所有       │    │  ConcurrentHashMap   │
│ 遍历所有Tool │    │  @ToolPermission     │    │  <name → Tool>       │
│ → registry   │    │  构建权限元数据索引   │    │                      │
│   .register()│    │                      │    │  register/get/remove │
└─────────────┘    └──────────────────────┘    └──────────┬───────────┘
                                                          │
                                                          ▼
                                              ┌──────────────────────┐
                                              │   ToolExecutor       │
                                              │   (执行引擎)          │
                                              │                      │
                                              │ executeToolCalls()   │
                                              │   ├─ 解析tool_calls  │
                                              │   ├─ 快照创建         │
                                              │   ├─ 权限管道执行     │
                                              │   └─ diff统计        │
                                              └──────────┬───────────┘
                                                         │
                                                         ▼
                                              ┌──────────────────────┐
                                              │ ToolExecutionPipeline │
                                              │   (三层防护)          │
                                              │                      │
                                              │ 阶段0: 会话级自动批准  │
                                              │ 阶段1.1: manual+数据   │
                                              │ 阶段1.2: manual+路径   │
                                              │ 阶段1.3: auto+高危     │
                                              │ 阶段2: 审计日志        │
                                              │ 阶段3: tool.execute()  │
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
- `getName()` → 注册中心索引键
- `getDescription()` + `getParameters()` → 构建为 OpenAI Function Calling 的 `tools` 定义
- `execute()` → 核心业务逻辑，被 `ToolExecutor` 调度

---

## 三、注册机制：两阶段启动

```
Spring Boot 启动
    │
    ├─ 阶段 1: InitializingBean.afterPropertiesSet()
    │   └─ ToolPermissionRegistry  扫描所有 @ToolPermission 注解
    │       构建 metadataMap<toolName → 权限元数据>
    │
    └─ 阶段 2: ApplicationRunner.run()
        └─ ToolInitializer  遍历 List<Tool>（Spring 自动注入所有 Tool bean）
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

按 Agent 配置中指定的工具名列表，从 Registry 取出对应的 Tool，构建标准的 OpenAI Function Calling 定义数组。

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
    isPathSensitive = true,  // 涉及路径 → 越界检测
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

仅 `write_file` 和 `edit_file` 工具在执行后触发。

```
PostEditPipeline.execute(filePath)
    │
    ├─ 1. Formatter.format(filePath)
    │     按语言选择格式化器（Java→IDE formatter, Python→black, JS→prettier...）
    │     返回 FormatResult（含是否执行、是否成功、消息）
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
| 只读文件内容 | ❌ false | ❌ false | ❌ false |
| 写入/修改文件 | ✅ true | ✅ true | ❌ false |
| 删除文件 | ✅ true | ✅ true | ✅ true |
| 执行 shell 命令 | ✅ true | ❌ false | ✅ true |
| 数据库写操作 | ✅ true | ❌ false | ✅ true |
| Git push | ✅ true | ❌ false | ✅ true |
| 网络请求 | ❌ false | ❌ false | ❌ false |

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
| `read_file` | READ | ❌ | ❌ | ❌ |
| `write_file` | WRITE | ✅ | ✅ | ❌ |
| `edit_file` | WRITE | ✅ | ✅ | ❌ |
| `delete_file` | DELETE | ✅ | ✅ | ✅ |
| `glob_files` | READ | ❌ | ❌ | ❌ |
| `grep_search` | READ | ❌ | ❌ | ❌ |
| `run_command` | EXECUTE | ✅ | ❌ | ✅ |
| `run_server` | EXECUTE | ✅ | ❌ | ✅ |
| `service_control` | EXECUTE | ✅ | ❌ | ✅ |
| `web_search` | NETWORK | ❌ | ❌ | ❌ |
| `web_fetch` | NETWORK | ❌ | ❌ | ❌ |
| `http_request` | NETWORK | ❌ | ❌ | ❌ |
| `check_network` | NETWORK | ❌ | ❌ | ❌ |
| `execute_sql` | DATABASE | ✅ | ❌ | ✅ |
| `git_status` | GIT | ❌ | ❌ | ❌ |
| `git_diff` | GIT | ❌ | ❌ | ❌ |
| `git_log` | GIT | ❌ | ❌ | ❌ |
| `git_add` | GIT | ✅ | ❌ | ❌ |
| `git_commit` | GIT | ✅ | ❌ | ❌ |
| `git_push` | GIT | ✅ | ❌ | ✅ |
| `git_branch` | GIT | ✅ | ❌ | ❌ |
| `fork_agent` | SYSTEM | ✅ | ❌ | ❌ |
| `collect_agent` | SYSTEM | ❌ | ❌ | ❌ |
| `inspect_agent` | SYSTEM | ❌ | ❌ | ❌ |
| `manage_skill` | SYSTEM | ✅ | ❌ | ❌ |
| `report_skill_result` | SYSTEM | ✅ | ❌ | ❌ |
| `project_info` | READ | ❌ | ❌ | ❌ |
| `read_project_tree` | READ | ❌ | ❌ | ❌ |
| `task_manager` | SYSTEM | ✅ | ❌ | ❌ |
| `ask_clarification` | SYSTEM | ❌ | ❌ | ❌ |

---

## 九、与 DeepSeekServiceImpl 的协作接口

工具系统通过以下方式被 AI 核心引擎调用：

```
DeepSeekServiceImpl
    │
    ├─ toolExecutor.buildToolDefinitions(toolNames) → 构建 tools 数组，发给 AI
    │
    ├─ toolExecutor.executeToolCalls(toolCallsJson) → AI 请求工具时执行
    │   └─ ToolExecutionPipeline.execute()
    │
    └─ toolExecutor.buildToolMessages(results) → 工具结果转为消息格式，追加到对话
```

权限上下文（`PermissionContext`）由 `DeepSeekServiceImpl` 在每次用户新消息开始时通过 `PermissionContext.removeSessionApproved()` 重置。

---

> 📌 **文档维护约定**: 新增工具后请更新本文档的「全部工具速查表」。
