> 🌐 English Version：[🇬🇧 TOOL_SYSTEM_EN](./TOOL_SYSTEM_EN.md)
# 工具系统深描：如何新增一个 AI Tool

> 版本：v3.0.7 | 更新：2026-10-21 | 受众：开发者 / AI 协作伙伴
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
                      │  29 个工具实现类           │
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
| `READ` | 文件读取与搜索 | file_explorer |
| `WRITE` | 文件写入与修改 | file_writer (write/edit) |
| `DELETE` | 文件删除 | file_writer (delete) |
| `EXECUTE` | 命令执行 | command (exec/start) |
| `NETWORK` | 网络请求 | web_search、web_fetch、http_request、check_network |
| `DATABASE` | 数据库操作 | execute_sql |
| `GIT` | Git 操作 | git_query、git_submit、git_branch、git_stash、git_tag、git_remote |
| `SERVICE` | 服务管理 | command (list/logs/stop) |
| `SKILL` | 技能管理 | skill、lesson |
| `COMMUNICATION` | 用户交互 | ask_clarification |
| `ADMIN` | 管理操作 | agent、agent_invoke、task_manager、mcp_server_manager、schedule_task |
| `DESKTOP` | 桌面自动化（截图 / 键鼠模拟，高危） | screen_capture、desktop_control |

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
| `git_submit` | GIT | ✓ | ✗ | ✗ |
| `git_branch` | GIT | ✓ | ✗ | ✗ |
| `git_stash` | GIT | ✓ | ✗ | ✗ |
| `git_tag` | GIT | ✓ | ✗ | ✗ |
| `git_remote` | GIT | ✓ | ✗ | ✗ |
| `agent` | ADMIN | ✗ | ✗ | ✗ |
| `agent_invoke` | ADMIN | ✓ | ✗ | ✗ |
| `skill` | SKILL | ✓ | ✗ | ✗ |
| `lesson` | SKILL | ✓ | ✗ | ✗ |
| `project_info` | READ | ✗ | ✗ | ✗ |
| `task_manager` | ADMIN | ✓ | ✗ | ✗ |
| `ask_clarification` | COMMUNICATION | ✗ | ✗ | ✗ |
| `chat_attachment` | READ | ✗ | ✓ | ✗ |
| `mcp_server_manager` | ADMIN | ✓ | ✗ | ✗ |
| `schedule_task` | ADMIN | ✓ | ✗ | ✗ |
| `query_tool_history` | READ | ✗ | ✗ | ✗ |
| `screen_capture` | DESKTOP | ✗ | ✗ | ✓ |
| `desktop_control` | DESKTOP | ✓ | ✗ | ✓ |
| `browser` | BROWSER | ✓ | ✗ | ✓ |
| `project_map` | READ | ✗ | ✓ | ✗ |

> 📌 速查表按当前 29 个内置工具维护；MCP 外部工具（`mcp_server_manager` 动态注册）不在此表内。
> `browser` 同时实现 `SideEffectFreePreflight`：`snapshot` / `status` / `list` / `console` / `wait` / `screenshot` 六个只读动作免授权；此外 `tab` 动作只有 `op=list` 免授权（`new` / `switch` / `close` 会改窗口状态，仍需授权）。
> `project_map` 虽会写 `.codecraft/`，但作用域被 `PathSecurityChecker` 限制在项目根内、且不触碰源码，因此 `affectsData=false`（不打扰用户）；`isPathSensitive=true` 用于路径越界校验。

---

### 8.1 网络类工具双链路（v2.0.3）

`web_search` / `web_fetch` 均为「主链路 + 回退链路」结构，对 AI 完全透明（工具描述由实现动态生成）：

| 工具 | 主链路 | 启用条件 | 回退链路 |
|------|--------|----------|----------|
| `web_search` | DeepSeek 官方 `POST /v1/tools/search` | Provider `baseUrl` 主机为 `api.deepseek.com` 且已配置 API Key | Bing 全球 → Bing 中国 → 百度（按代理可用性选择）+ 结果缓存（TTL 300s）+ 低质量域名降权 |
| `web_fetch` | DeepSeek 官方 `POST /v1/tools/fetch` | 同上，且目标为公网地址（非私网 / 本机） | Jina Reader（有可用代理时优先）→ 本地抓取（UA 池 + 反爬重试） |

实现要点：

- 官方通道统一由 `DeepSeekToolsApiClient` 调用，复用 `LLMWebClientManager` 的认证头、连接池与重试策略；
- 官方通道异常 / 超时绝不暴露给 AI，直接回退原链路并记录日志，保证工具始终可用；
- 判定依据是 `baseUrl` 域名而非 `providerCode`，第三方中转 / 自建兼容服务不会启用（避免把请求发到非官方服务）；
- 子 Agent 通过 `ToolContext.setProviderCode(...)` 传递 Provider，保证子 Agent 内判定一致；
- 回归测试：`DeepSeekToolsApiClientTest`（26 断言）、`SearchToolLogicTest` / `WebFetchToolLogicTest` 已适配。

## 八·五、成长体系：Lesson 踩坑经验库

`lesson` 工具是成长体系（经验库）的入口，与 `skill`（目录常驻、按需加载的工作流模板）互补：**lesson 是「按需检索的失败经验」**，平时零上下文开销，出错时自动命中。

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

### 8.5.4 检索排序与召回（相关度优先）

`lesson action=search` 的结果按加权总分排序。旧实现把「环境兼容」当最终排序键（3 档离散分），会一票否决相关度——异环境的同一个坑被排到同环境的弱相关条目之后，模糊检索内部算好的词命中排序也被完全覆盖。现在的口径：

| 项 | 默认权重 | 说明 |
|---|---|---|
| 错误码精确命中 | 3.0 | 强信号，压过「纯靠相关度」的模糊命中 |
| 参数一致 | 2.0 | 部分一致按比例给分；无共现参数维度时不给分也不淘汰 |
| 相关度 | 2.5 | 字段权重（现象 3.0 / 标签 2.5 / 目标 2.0 / 根因 1.5 / 解法 1.0）× 候选集 IDF × 覆盖率 |
| 环境兼容 | 0.8 | 同环境 1.0 / 通用 0.85 / 异环境 0.2，只降权不淘汰 |
| 历史有效率 | 0.5 | 贝叶斯平滑成功率 + 状态加成（已验证 > 草稿） |
| 新鲜度 / 命中量 / 本项目沉淀 | 0.3 / 0.2 / 0.4 | 轻量修正项，用于打破平局与抑制老条目霸榜 |

- **查询词扩展**：拆词后经 `lesson.query-alias.entries` 别名表补充同义词，解决「中文描述查不到英文经验」（`端口被占用` ↔ `Address already in use`）。别名是人工白名单，保证同一查询每次扩展出同一批词（可复现、可对比）。
- **结果里的 `为何召回`**：每条最多 4 项理由，如 `错误码精确命中 CMD_NOT_FOUND | 参数一致(缺失依赖=starter-web) | 相关度 0.62（现象/标签）`。看到 `⚠异环境(linux)` 表示该经验记录时的 OS 与当前不同，可能不适用。
- **精确码不足时补模糊**：精确码只命中 1~2 条时会再补跑模糊检索并按 id 合并，保证 Top-3 有备选（旧实现只在精确命中为 0 才补）。
- **开关**：`lesson.ranker.enabled=false` 回退旧排序；`lesson.query-alias.enabled=false` 关闭扩展。两者关闭后检索行为与改造前等价。

### 8.5.5 前端管理

「踩坑经验」管理页（`/lesson-manage`，菜单「AI 能力」组）：分页浏览、详情查看、人工编辑补全、反馈验证、删除，以及成长看板（总数/草稿/有效/隐藏/命中次数/成功率）。

---

### 8.6 浏览器自动化工具（browser）

补齐网页能力的三层分工：`web_fetch`（文本层，只读）→ `browser`（语义层，DOM 操作）→ `screen_capture`/`desktop_control`（视觉层，像素兜底）。

#### 8.6.1 通道架构

```
Java (BrowserTool)
   ↓ HTTP 127.0.0.1:<随机端口>，header x-cc-token
Electron 主进程（electron/browser-bridge.js）
   ↓ webContents.debugger.sendCommand
Chromium CDP
```

- **零额外依赖**：Electron 的 `webContents.debugger` 本身就是 CDP 客户端，Java 侧只做 HTTP 转发，不需要 Playwright / Selenium（那些要额外下载浏览器，与 214MB 安装包冲突）。
- **握手文件**：Electron 启动时把 `{port, token, pid}` 写成 `runtime/browser-bridge.json` —— 打包运行在 `<userData>/runtime/`，开发运行在 `<项目根>/runtime/`。Java 侧按 `-Dapp.userdata` → 其父目录 → `user.dir` 依次探测（dev 下后端 cwd 是 `<项目根>/target`，少了「父目录」这一条会误判成「客户端没启动」）。用浏览器直接开 8084 时该文件不存在，工具会明确提示「需桌面客户端」。
- **必须 NO_PROXY**：很多机器配了全局代理（Clash / 公司代理），走系统代理会让发往 `127.0.0.1` 的请求直接失败，这类问题极难排查，所以在 `BrowserBridgeClient` 里硬性绕开。
- **窗口隔离**：窗口按 `conversationId` 独立（两个会话并行不抢页面）；登录态（session partition）按项目根哈希共享（同项目新会话仍是登录态），空闲 10 分钟回收但 cookie 留盘。

#### 8.6.2 动作清单（P0 + P1 + P2）

| 动作 | 参数 | 说明 | 免授权 |
|------|------|------|:------:|
| `open` | url / show / width / height / newTab | 开或复用本会话窗口（不传 url 开空白页）；`newTab=true` 强制新建标签 | ✗ |
| `navigate` | url | 当前标签跳转，自动等 `readyState=complete` | ✗ |
| `snapshot` | — | 读标题/地址/正文摘要/可见可交互元素（带 ref） | ✓ |
| `click` | ref / rx / ry | 滚动到位 + 真实鼠标事件（失败回退 `el.click()`）；`rx`/`ry`（0~1）用于点跨域 iframe 内的相对位置 | ✗ |
| `fill` | ref / value | 原生 setter + input/change 事件（React/Vue 受控组件可响应） | ✗ |
| `select` | ref / label 或 value | 下拉框：文案精确 → 文案部分 → value，失败回传候选清单 | ✗ |
| `press` | key / ref? | `Input.dispatchKeyEvent`，支持 Enter/Esc/Tab/方向键/`Control+a` 等组合键 | ✗ |
| `hover` | ref | 先移近再移中心（一次性跳过去部分站点不触发 mouseenter） | ✗ |
| `scroll` | direction / amount / ref? | 页面级或元素内滚动；元素不可滚动时向上找容器，再退化滚页面 | ✗ |
| `wait` | waitFor / selector / text / url / state / timeoutMs | 轮询 `Runtime.evaluate`，超时返回「仍未满足」而非报错 | ✓ |
| `screenshot` | fullPage / format | 截图上传为文件资产并注入上下文（走 `desktop_inject` 通道） | ✓ |
| `console` | sinceSeq? | 走 bridge 的 `/events` 拉事件，导航中/崩溃后也能取 | ✓ |
| `eval` | script | 逃生舱，需 `allow-script-eval=true`，默认关闭 | ✗ |
| `tab` | op = list / new / switch / close；tabId | 多标签管理。仅 `op=list` 免授权（new / switch / close 改窗口状态仍需授权） | ✓/✗ |
| `show` / `hide` | tabId? | 显隐单个标签窗口 | ✗ |
| `close` | tabId? / all | 关闭标签；`all=true` 关掉本会话全部标签 | ✗ |
| `status` / `list` | — | 通道可用性 / 标签清单（带 tabId、`[当前]` 标记、本会话/其他会话区分） | ✓ |

#### 8.6.3 关键设计

- **ref 机制**：`snapshot` 每次全量重建 `window.__ccRefs` 并分配 `e1..eN`，旧 ref 立即失效。这是刻意选择——页面结构变了还让旧 ref 生效，点错位置的代价远高于「重新 snapshot」。
- **snapshot 输出紧凑文本而非 HTML**：整页 `outerHTML` 动辄几万 token，而「可见可交互元素清单」通常几百 token。模型需要的是「能点什么」，不是「DOM 长什么样」。
- **穿透范围**：递归遍历 `document` → open `shadowRoot` → 同源 `iframe.contentDocument`。iframe 内元素标 `[iframe内]`，坐标按**执行时动态累加** `frameElement` 位置（用 snapshot 时算的旧值会点偏，因为点之前会先滚动宿主）。
- **跨域 iframe 降级可点**：`contentDocument` 为 null 的跨域 iframe 不整块跳过，而是把它本身作为占位元素输出（标 `[跨域iframe]`，带 bbox）。`click ref=<它> rx=0.7 ry=0.3` 用 0~1 相对比例把点击送进 iframe 内部对应位置——比「让模型看截图猜绝对坐标」稳得多。配合 `screenshot` 估比例后先 click 聚焦，再 `press` 键入文字即可操作内嵌表单。closed 模式 Shadow DOM 与 canvas 绘制内容仍拿不到，回执明确提示改用截图。
- **多标签 = 同会话多窗口**：每个标签是一个独立 `BrowserWindow`（共享同一 session partition 登录态），不是单窗口多 tab。这样每个标签天然保留自己的页面状态，改动也局限在窗口池，不需要碰 `WebContentsView` 的复杂布局；代价是任务栏会多出几个窗口。
- **多标签的事件游标必须按 `conversationId::tabId` 隔离**：如果只按会话记游标，t1 消费到 seq=12 后，新建的 t2 从 seq=1 开始的事件会被整段当成「已读」吞掉——表现就是新标签页里的报错完全看不到。
- **事件增量**：bridge 每窗口有 200 条环形缓冲（JS 异常 / console.error·warn / 网络失败 / 自动处理的弹窗），每次 `/cdp` 响应附带 `sinceSeq` 之后的增量；`/events` 端点可独立拉取（导航中/崩溃后 `evaluate` 会失败，恰是最需要看报错的时刻）。
- **密码与下载**：`input[type=password]` 的值永不输出（只显示「密码已隐藏」）；下载默认被 bridge 拦截（AI 误点「下载全部附件」能把磁盘写满，而下载的文件模型也读不到）。
- **弹窗自动处理**：`Page.javascriptDialogOpening` 一律 accept 并记一条事件，否则 alert/confirm 会阻塞渲染与 CDP 命令。
- **截图注入来源区分**：网页截图与桌面截图共用 `desktop_inject` 通道，但 `ToolContext.setInjectKind("browser")` 会让注入文案走「网页截图」版本 —— 不提归一化坐标协议，否则会诱导模型去数并不存在的网格刻度。
- **安全边界**：只放行 http/https（`file:` 能绕过后端路径白名单）；bridge 只绑 `127.0.0.1` + token 鉴权；窗口不注入 preload（远程页面拿到 Node API 就是攻击面）。

#### 8.6.4 前端状态可视化

模型开的浏览器窗口在 Electron 主进程里，用户看不到，只能翻任务栏猜「AI 是不是在操作什么」。所以两套前端都加了状态条 + 面板，数据来自后端 REST（窗口是进程级资源，不落库，每次实时问 bridge）：

| 接口 | 说明 |
|------|------|
| `GET /api/browser/sessions?conversationId=` | 返回 `{available, conversationId, tabs[], activeTabId, totalWindows, maxTabs}`；不传 `conversationId` 时返回全部窗口 |
| `POST /api/browser/sessions/show` | body `{conversationId, tabId?, visible}`，显隐某个标签窗口 |
| `POST /api/browser/sessions/close` | body `{conversationId, tabId?, all?}`，关闭标签 |

- **bridge 不可用时 `available=false`，不报错**：浏览器用 8084 直连时没有 electron bridge，属于正常情况，不该在聊天区弹红字。
- **入口只在有标签时出现**：常驻空按钮会让人以为功能坏了。
- **轮询策略**：4 秒间隔，仅本会话有标签时运行，标签归零立即停；非 `/chat` 路由跳过；`tool_call_start` 出现 `browser` 时插队刷新一次（`refreshBrowserOnToolCall`），不等轮询——「模型刚开了一个网页」正是最想马上看到状态的时刻。子 Agent 的 `agent_tool_call` 同样插队刷新。
- **切会话必须立刻换状态**：标签按 `conversationId` 隔离，`selectConversation` 内同步调 `loadBrowserSessions()`，不能沿用上一个会话的列表。
- **两套前端实现一致**：v3 用 header 下拉面板（`ChatView` 的 `.cc-browser`），经典版用 `chat-messages-area` 顶部的状态条 + 展开面板（`.ai-browser-bar`），均为普通流布局不遮挡消息。

---

### 8.7 项目知识库工具（project_map）

把「项目结构」写进文件让模型读，而不是靠每轮 grep/glob 摸索。产物落在项目根 `.codecraft/`（默认 gitignore）。

| action | 作用 |
|--------|------|
| `status` | 报告库是否存在、模块数、stale 模块 |
| `bootstrap` | 建库/增量重建（只重写有文件变化的模块，无变化时零写入） |
| `read` | 读 INDEX 摘要或某模块详情 |
| `annotate` | 给模块补「一句话职责」（模型产出，重建时保留） |
| `rule` | 规则条目增删改查（`op=add/update/remove/list`） |

- **双模式**：代码文件 ≥10% 走代码模式（模块表 + 代表符号）；否则走文档模式（文件名 + 标题 + 章节），适配「全是需求文档」的工作目录。
- **注入**：每轮注入 `.state.json` 摘要（模块 → 一句话职责），预算代码模式 ≤1100 字符、文档模式 ≤1500 字符；写文件成功后追加模块提示；加载技能时追加该项目专属的 `skill.*` 规则（预算 800 字符）。
- **不兜底建库**：只有模型显式调用 `bootstrap` 才落盘，避免在临时目录里制造噪音。
- **规则 key 前缀白名单**：`build./style./arch./avoid./flow./domain./link./know./risk./test./deploy./ops./req./doc./skill.`，非白名单拒写。

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
