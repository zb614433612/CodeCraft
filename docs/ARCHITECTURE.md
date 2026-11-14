﻿﻿﻿> 🌐 English Version：[🇬🇧 ARCHITECTURE_EN](./ARCHITECTURE_EN.md)
# CodeCraft 架构全景图

> 版本：v3.1.3 | 更新：2026-11-14 | 受众：开发者 / AI 协作伙伴
> 本文档旨在让新加入的开发者（包括 AI Agent）在 5 分钟内建立对项目的完整认知地图。

---

## 一、一句话定义

**CodeCraft** 是一个基于 AI Agent 的桌面端智能编程助手。用户通过聊天界面向 AI 下达编程任务，AI 自动调用 27 种内置工具（读写文件、执行命令、操作 Git、搜索网络、桌面自动化等）完成任务，支持子 Agent 并行协作（最多 20 并发），支持多种 LLM 平台（DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax 等）动态切换，并可通过 MCP 双向接入外部工具生态。

---

## 二、系统拓扑图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Electron 桌面壳                                    │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │             Vue 3 前端 (frontend/)                                   │ │
│ │ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐           │ │
│ │ │ChatView  │ │AgentPanel│ │FileTree  │ │GitSidebar     │           │ │
│ │ │(SSE流式) │ │(子Agent) │ │(编辑器)  │ │(Diff/提交)    │           │ │
│ │ └────┬─────┘ └────┬─────┘ └────┬─────┘ └───────┬───────┘           │ │
│ │      └────────────┴────────────┴─────────────────┘                  │ │
│ │                       │ REST API + SSE                               │ │
│ └───────────────────────┼─────────────────────────────────────────────┘ │
│                         │                                               │
│ ┌───────────────────────┼─────────────────────────────────────────────┐ │
│ │          Spring Boot 3.4 后端 (src/main/java/)                      │ │
│ │                       │                                             │ │
│ │ ┌─────────────────────┴──────────────────────┐                      │ │
│ │ │        Controller 层（25 个）               │                      │ │
│ │ │ DeepSeekController / GitController /       │                      │ │
│ │ │ P2pController / SnapshotController / ...   │                      │ │
│ │ └─────────────────────┬──────────────────────┘                      │ │
│ │                       │                                             │ │
│ │ ┌─────────────────────┴──────────────────────┐                      │ │
│ │ │         Service 核心层                     │                      │ │
│ │ │                                            │                      │ │
│ │ │  DeepSeekServiceImpl (187KB, 核心引擎)     │                      │ │
│ │ │   ├─ ToolLoopManager (工具循环/死循环检测) │                      │ │
│ │ │   ├─ AgentForkManager (子Agent管理)        │                      │ │
│ │ │   ├─ CompactionService (上下文压缩)        │                      │ │
│ │ │   ├─ ContextBuilder (消息组装/Token估算)   │                      │ │
│ │ │   ├─ AgentEventBus (SSE事件推送)           │                      │ │
│ │ │   ├─ MessagePersister (消息持久化)         │                      │ │
│ │ │   └─ LLMClientManager (多Provider管理)     │                      │ │
│ │ └─────────────────────┬──────────────────────┘                      │ │
│ └───────────────────────┼─────────────────────────────────────────────┘ │
└─────────────────────────┼──────────────────────────────────────────────┘
                    LLM Provider API (DeepSeek/OpenAI/Anthropic/Ollama/MiMo/MiniMax)
```

---

## 三、核心数据流

### 3.1 一次用户对话的完整链路

```
用户输入消息
    ↓
┌─ DeepSeekController.chat() ─────────────────────────────────────────────┐
│  获取/创建 Conversation                                                  │
│  调用 DeepSeekService.sendMessage()                                     │
│  返回 SSE (Server-Sent Events) 流给前端                                  │
└────────────────────────────┬────────────────────────────────────────────┘
                             ↓
┌─ DeepSeekServiceImpl.sendMessage() ─────────────────────────────────────┐
│  ContextBuilder.buildMessages() → 组装完整消息上下文                    │
│    ├─ 系统提示词（system prompt）                                       │
│    ├─ 压缩后的历史摘要（CompactionService）                             │
│    ├─ 最近 N 轮完整对话                                                 │
│    ├─ 当前用户消息 + 附件/文件内容                                      │
│    └─ 技能目录注入（SkillCatalogBuilder）                                  │
│                                                                         │
│  发送 HTTP 请求到 DeepSeek API（WebFlux + SSE 流式读取）                │
│                                                                         │
│  AI 返回 → 如果有 tool_calls → ToolLoopManager 接管                     │
│    ┌──────────────────────────────────────────────────────────────┐    │
│    │ Tool Loop (工具调用循环，最多 50 轮)                          │    │
│    │ ┌─ ToolExecutor.execute()                                    │    │
│    │ │  ├─ ToolExecutionPipeline (三层权限检查)                   │    │
│    │ │  ├─ 执行工具逻辑                                           │    │
│    │ │  └─ PostEditPipeline (格式化 + 诊断)                       │    │
│    │ ├─ 工具结果追加到消息历史                                     │    │
│    │ ├─ 再次调用 DeepSeek API（带工具结果）                        │    │
│    │ └─ 重复直到 AI 不再请求工具调用                               │    │
│    │                                                               │    │
│    │ 安全机制：                                                    │    │
│    │ ├─ 死循环检测：连续 3 次相同工具+关键参数 → 标记并交由评委评估                   │    │
│    │ ├─ 评委机制：超最大迭代 → Judge 评估是否继续                  │    │
│    │ └─ 用户取消检测：每轮检查是否被中断                           │    │
│    └──────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 3.1.1 关键补充（v2.0.3）

- **工具调用序列净化（防 400 卡死）**：`ContextBuilder.buildMessages()` 在消息转换完成后、以及上下文压缩/裁剪之后，都会调用 `ToolCallSequenceSanitizer.sanitize()`（幂等）。净化器保证 `assistant.tool_calls` 与 `role=tool` 严格一一配对：丢弃孤立 tool 消息、剔除无结果的 tool_call（全无结果则清空 tool_calls）、丢弃空 assistant 消息、剔除非法/重复 id。同一净化器也接在 `AbstractLLMClient` / `AnthropicClient` / `OllamaClient` 的请求体构造出口。
- **写入侧占位**：`DeepSeekServiceImpl.appendToolResultMessages()` 为本批次「已声明但没拿到结果」的 tool_call 补一条「【工具未执行】」占位 tool 消息，从源头消除序列缺口。
- **评委机制（v2.0.3 语义）**：评委否决（reject）/ 响应解析失败 / 评委调用异常且本地判定无进展 / 累计扩容达 `MAX_JUDGE_GRANTED_ITERATIONS=100` 上限时，不再直接终止任务，而是发送 `ask_user`（`askType=iteration_confirm`）由用户选择「结束任务」或「继续执行 +10 / +20 / +50 次」；等待超时 5 分钟按结束处理。

### 3.2 子 Agent 协作

```
主 Agent (运行在用户会话中)
    ↓
    fork_agent("sub-1", "写UserService", [read_file,write_file,...])
    ┌─ AgentForkManager.forkAgent() ──────────────────────────────────────┐
    │  创建 sub_agent_log 表记录（status=running）                        │
    │  创建新的 ToolLoopManager 实例                                      │
    │  后台线程启动独立对话循环（独立的消息上下文）                       │
    │  返回 agent_id: "sub-1"                                             │
    └─────────────────────────────────────────────────────────────────────┘
    ↓
    (主 Agent 继续做其他事，子 Agent 后台运行)
    ↓
    collect_agent("sub-1", timeout=300)
    ┌─ AgentForkManager.collectAgent() ───────────────────────────────────┐
    │  阻塞等待子 Agent 完成（最多 300 秒）                               │
    │  收集结构化结果：状态、文件变更列表、编译结果、关键决策             │
    │  更新 sub_agent_log 记录                                            │
    │  返回摘要给主 Agent                                                 │
    └─────────────────────────────────────────────────────────────────────┘
    ↓
    (可选) inspect_agent("sub-1", "full_log")
        获取子 Agent 的完整思考过程、工具调用历史
```

### 3.3 聊天记录分页懒加载（v2.0.3）

超长会话不再全量拉取。链路如下：

```
前端 CodeAssistantView
  ├─ 打开会话 → GET /conversation/{id}/messages/page?rounds=5      （最近 5 轮）
  ├─ scrollTop ≤ 80 → 同接口 + beforeId=nextBeforeId&rounds=5      （更早 5 轮）
  ├─ 响应：messages（id 升序）/ hasMore / nextBeforeId
  └─ 渲染：DynamicScroller（:key=conversationId 强制重建）
       ├─ 消息 id 全局唯一（role-时间戳-自增序号）
       ├─ v-track-msg-size + ResizeObserver → 节流 forceUpdate 高度自愈
       └─ 插入历史后按高度差双向补偿（用户主动滚动则放弃补偿）
```

后端按「轮」分页：`role=USER`（排除 `desktop_inject` 注入消息）为轮边界，先用 `selectNthUserMessageIdFromTail` 定位末尾第 N 个用户消息 id，再按 id 区间取数——边界永远落在用户提问上，AI 消息聚合（`processMessageGroups`）不会跨页。游标用 `beforeId` 而非 offset，避免新消息写入导致分页漂移。

---

## 四、技术选型与关键设计决策

| 决策 | 选择 | 为什么 |
|------|------|--------|
| **数据库** | H2 (嵌入式) | 桌面应用需零配置部署，无需用户安装 MySQL |
| **缓存** | Caffeine | 替代 Redis，同样为了零依赖开箱即用 |
| **AI 通信** | WebFlux + SSE | 支持流式输出，用户可实时看到 AI 打字效果 |
| **多LLM支持** | LLMClient 抽象层 | 统一接口适配 DeepSeek/OpenAI/Anthropic/Ollama/MiMo/MiniMax，运行时动态切换 |
| **P2P 网络** | Netty + JSON | 高性能异步 IO，JSON 调试友好 |
| **P2P 信令** | 二维码 + ZXing | 免手动输入地址，扫码即可配对设备 |
| **P2P 安全** | TLS + BouncyCastle | 自签名证书 + AES 加密，端到端安全通道 |
| **Token 估算** | jtokkit (tiktoken) | 精确估算消耗，支撑上下文压缩决策 |
| **代码高亮** | highlight.js (自研编辑器) | 不依赖外部 IDE，桌面内即可浏览编辑代码 |
| **桌面打包** | Electron + 内置 JRE | 跨平台，用户双击即用，零环境配置 |

---

## 五、包结构速查表

```
src/main/java/com/example/agentdeepseek/
├── common/                    # 通用基础
│   ├── enums/ResponseEnum     # 统一响应码枚举
│   └── response/              # ApiResponse<T>、PageResult
├── config/                    # Spring 配置
│   ├── DeepSeekConfig         # API Key、模型、URL 等配置
│   ├── NetworkToolConfig      # 网络工具配置（代理自动探测 / 搜索源 / UA 池）
│   ├── OpenApiConfig          # Swagger/OpenAPI 文档
│   └── SpaConfig              # 单页应用路由回退
├── controller/                # REST API 控制器（25 个）+ GlobalExceptionHandler（全局异常处理）
│   ├── DeepSeekController     # ★核心：聊天、Agent 任务状态
│   ├── ConversationController # 会话 CRUD + 按轮分页（messages/page）
│   ├── GitController          # Git 操作（status/diff/commit...）
│   ├── P2pController          # P2P 远程协作（39.7KB，最大控制器）
│   ├── SnapshotController     # 快照管理
│   ├── SkillController        # 技能 CRUD
│   ├── AgentConfigController  # Agent 配置管理
│   ├── LLMProviderController  # LLM Provider CRUD
│ │   ├── McpServerController    # MCP 服务器管理
│ │   ├── LessonController       # 踩坑经验管理
│ │   ├── PromptOptimizeController # 提示词优化
│ │   ├── ToolRegistryController # 工具注册表查询
│   ├── ProjectController      # 项目文件浏览
│   ├── ProjectBuildController # 项目构建/编译
│   ├── UserController         # 用户管理
│   ├── MenuController         # 菜单权限
│   ├── RoleController         # 角色管理
│   ├── ConfigController       # 系统配置（API Key等）
│   ├── LogController          # 日志查询
│   ├── SystemController       # 系统信息（数据统计/导出导入/重置 + GET /system/version 版本与构建时间）
│   ├── DbConnectionController # 外部数据库连接（Phase 20）
│   ├── FileAssetController    # 文件资产（上传/列表/引用，vision-files-api）
│   ├── ProviderBalanceController # Provider 余额查询
│   ├── AppearanceController    # 外观设置（背景图上传/读取）
│   ├── AvatarController        # 头像上传/读取
│   ├── SubAgentController      # 子 Agent 运行记录（GET /api/sub-agents，读 sub_agent_log）
│   └── ScheduleTaskController # 定时任务
├── filter/                    # TokenAuthenticationFilter（Token 鉴权拦截器）
├── initializer/               # UserInitializer（首次启动初始化管理员）
├── mapper/                    # MyBatis Mapper 接口（24 个）
│   ├── ConversationMapper / ConversationMessageMapper / CompactionMapper
│   ├── AgentConfigMapper / SkillMapper / SubAgentLogMapper
│   ├── UserMapper / MenuMapper / RoleMapper / RoleMenuMapper
│   ├── ScheduleTaskMapper / SysConfigMapper
│   └── ProviderConfigMapper（LLM Provider）/ McpServerMapper（MCP）
│ │   ├── LessonMapper / LessonNormCacheMapper / LessonRuleMapper（成长体系）
│ │   ├── DbConnectionMapper / FileAssetMapper / FileReferenceMapper
│ │   │   └── P2p* (4个：授权/会话/消息/已知节点)
├── model/
│   ├── entity/                # 数据库实体（26 个）
│   │   ├── Conversation / ConversationMessage / CompactionRecord
│   │   ├── User / Menu / Role / RoleMenu / SysConfig
│   │   ├── AgentConfig / AgentTask / Skill / SubAgentLog
│   │   ├── ScheduleTask / MessageRole / ProviderConfig / McpServerConfig
│ │   │   ├── Lesson / LessonNormCache / LessonRule（成长体系）
│ │   │   ├── DbConnection / FileAsset / FileReference
│ │   │   └── P2pAgentAuthorization / P2pAgentConversation / P2pChatMessage / P2pKnownPeer

│   ├── dto/                   # 请求/响应 DTO
│   │   ├── ChatRequest / ChatResponse
│   │   ├── ForkAgentRequest / CollectAgentRequest / InspectAgentRequest
│   │   ├── LoginRequest / LoginResponse / PendingQuestion
│   │   └── GetRandomCodeRequest
│   ├── vo/                    # 视图对象
│   │   ├── DirectoryEntry / ProjectTreeNode / ConversationMessagePageVO（按轮分页响应）
│   └── SubAgentResult         # 子Agent结构化执行结果
├── p2p/                       # ⚡P2P 远程协作子系统
│   ├── controller/P2pController   # REST API（39.7KB，最大控制器）
│   ├── agent/P2pAgentService      # Agent 远程调用核心（34.9KB）
│   ├── connection/                # P2pServer / P2pClient / ConnectionPool / PeerInfo
│   ├── message/                   # HandshakeHandler / MessageRouter / MessageFrame
│   ├── protocol/                  # MessageType / P2pConstants / 编解码器
│   ├── security/                  # TlsHelper / CryptoHelper（证书生成 + 加密）
│   ├── signaling/                 # QrCodeSignaling / ConnectionStringHelper
│   ├── service/P2pChatService     # P2P 聊天消息
│   └── config/P2pConfig           # P2P 配置（端口、证书路径等）
├── scheduler/                 # 定时任务
│   ├── ScheduleTaskScheduler  # Cron/一次性任务调度
│   └── RunningProcessManager  # 子进程生命周期管理（8KB）
├── service/                   # 业务接口 + 实现
│   ├── DeepSeekService        # 核心聊天服务接口
│   ├── ShellDiscoveryService  # Shell 自动发现（Windows/macOS/Linux 多 Shell 支持）
│   ├── impl/
│   │   ├── DeepSeekServiceImpl    # ★★★核心引擎（187KB，最复杂文件）
│   │   ├── ToolLoopManager        # 工具调用循环（死循环检测/评委/取消）
│   │   ├── AgentForkManager       # 子Agent生命周期（fork/collect/inspect）
│   │   ├── CompactionService      # 上下文压缩（三级决策 + 异步预压缩）
│   │   ├── ContextBuilder         # 消息上下文组装 + Token估算
│   │   ├── AgentEventBus          # Agent 事件总线（SSE 推送）
│   │   ├── MessagePersister       # 消息异步持久化
│   │   ├── SnapshotService        # 代码快照备份/回滚（32.2KB）
│   │   ├── ProjectBuildService    # 项目编译构建
│   │   ├── SkillCatalogBuilder    # 技能目录构建（渐进式披露 L1 层）
│   │   ├── CharacterPromptUtil    # 角色设定提示词工具
│   │   ├── AttachmentReaderService # 附件文件读取服务
│   │   └── ... (User/Config/Menu/Role 等 CRUD 服务)
│   ├── lesson/                 # ★成长体系：踩坑经验库（按项目隔离，零上下文开销）
│   │   ├── LessonService       # 经验 CRUD + 按需检索 + 状态机（48KB）
│ │   │   ├── LessonRecorder      # 失败自动捕获草稿 / LLM 主动记录
│ │   │   ├── LessonReviewService # 对话级异步复盘（提炼根因/解法，C2）
│ │   │   ├── FailureNormalizer   # 错误码归一化 + 指纹去重（error_signature）
│ │   │   ├── LessonNormalizerService # P0 归一化管线（规则+LLM 兜底+缓存）
│ │   │   ├── LessonDetourService # P1 弯路经验提炼入库（C3）
│ │   │   ├── DetourSignalDetector # 弯路信号扫描（用户否定/换方案/工具序列）
│ │   │   ├── DetourBlockParser  # 【方案取舍】块解析
│ │   │   └── ErrorCodeDictionary # 错误码字典（yml 配置加载）
│   ├── agentinvoke/            # ★智能体互调：委托执行/信任链/环检测（Phase 19）
│   ├── dbconnection/           # ★外部数据库连接：配置/加密/连接管理（Phase 20）
│   ├── files/                  # ★文件资产与 Files API 适配（上传/图像理解/引用管理，vision-files-api）
│   ├── llm/                    # LLM 客户端层：LLMClient 抽象 + 6 个 Provider 实现 + ProviderBalanceService + LLMErrorFormatter（错误友好格式化）+ ToolCallSequenceSanitizer（工具调用序列净化）+ DeepSeekToolsApiClient（DeepSeek 官方搜索/抓取）
│   └── SkillService / UserService / ConfigService / DataTransferService（备份导出/导入）/ AvatarStore / BackgroundStore / ...
├── projectcontext/          # 项目知识库（模块划分/符号扫描/地图服务/上下文注入）
├── tool/                      # ⚡AI Agent 工具系统
│   ├── Tool.java              # 工具接口定义
│   ├── ToolRegistry           # 工具注册中心（单例）
│   ├── ToolExecutor           # 工具执行引擎（参数解析/分发）
│   ├── ToolInitializer        # 启动时自动初始化所有工具
│   ├── ExecutionTokenManager  # 工具执行 Token 管理
│   ├── PermissionContext      # 会话级权限上下文
│   ├── impl/                  # 29 个工具实现（新命名，一工具多 action）
│   │   ├── 文件操作: FileExplorerTool (read/glob/grep/tree),
│   │   │            FileWriterTool (write/edit/delete)
│   │   ├── 命令执行: CommandTool (exec/start/list/logs/stop)
│   │   ├── 网络请求: SearchTool, WebFetchTool, HttpRequestTool, NetworkCheckTool
│   │   ├── 数据库:   ExecuteSqlTool
│   │   ├── Git 操作: GitQueryTool, GitSubmitTool, GitBranchTool, GitStashTool, GitTagTool, GitRemoteTool
│   │   ├── Agent协作: AgentTool (fork/collect/batch_collect/inspect)
│   │   ├── 技能管理: SkillTool (create/update/delete/list/report)
│   │   ├── 经验库:   LessonTool (search/record/complete/feedback/list) ★成长体系
│   │   ├── 项目管理: ProjectInfoTool, ProjectMapTool (status/bootstrap/read/annotate/rule)
│   │   ├── 任务管理: TaskManagerTool
│   │   ├── 交互工具: AskClarificationTool, ChatAttachmentTool, QueryToolHistoryTool
│   │   ├── 定时任务: ScheduleTaskTool
│   │   ├── MCP 管理: McpServerManagerTool
│   │   │   │   ├── 桌面自动化: ScreenCaptureTool (截图/网格刻度/准星标记/放大镜),
│   │   │            DesktopControlTool (键鼠模拟/批量动作/坐标预检)
│   │   │   │   ├── 浏览器自动化: BrowserTool (open/navigate/snapshot/click/fill/select/press/hover/
│   │   │   │   │              scroll/wait/screenshot/console/eval/tab/close/status/list)
│   │   └── 辅助组件: DeepSeekAnalyzer（非流式 LLM 分析器，评委/子Agent 调用）
│   ├── desktop/                 # 桌面自动化支持（平台适配/CaptureContext/坐标校验/预检）
│   ├── browser/                 # 浏览器工具支持（bridge 客户端/快照解析/CDP 转发）
│   ├── permission/            # 权限控制
│   │   ├── ToolPermission / ToolPermissionMetadata / ToolPermissionRegistry
│   │   ├── ToolPermissionLevel (权限档位：SAFE/DATA/HIGH_RISK)
│   │   ├── ToolExecutionPipeline (三层防护) / SideEffectFreePreflight (预检豁免)
│   │   ├── PathSecurityChecker (路径越界检查)
│   │   ├── OperationCategory (操作分类，含 DESKTOP / BROWSER)
│   │   └── ToolAuditLogger (审计日志)
│   └── postedit/              # 后处理管线
│       ├── PostEditPipeline   # 编排 Formatter + Diagnostic
│       ├── Formatter          # 代码格式化（Java/Python/JS/TS...）
│       └── Diagnostic         # 编译诊断（lint/语法检查）
├── log/LogService             # 应用级日志服务（11KB）
└── util/                      # 工具类
    ├── CommandUtils           # 智能 Shell 发现 + 命令执行（22.2KB）
    ├── DiffUtil               # LCS 差异计算
    ├── FileEncodingDetector   # 编码检测（10.2KB）
    ├── OperationDetailGenerator # 操作详情生成
    ├── Md5Util                # MD5 哈希
    └── ProjectRootContext     # 项目根路径上下文
```

---

## 六、数据库 ER 概要

```
┌──────────────────┐      ┌──────────────────────┐
│  sys_user        │      │  agent_config        │
│ ───────────────  │      │ ──────────────────   │
│ id (PK)          │      │ id (PK)              │
│ username (UQ)    │      │ name, description    │
│ password (MD5)   │      │ avatar, system_prompt│
│ role, status     │      │ tool_names, model    │
└──────┬───────────┘      │ thinking_mode        │
       │                  │ temperature          │
       │                  │ execution_mode       │
       │                  │ work_dir, is_builtin │
       │                  └──────────┬───────────┘
       │                             │
┌──────┴──────────┐      ┌──────────┴───────────┐
│ conversation   │      │     skill            │
│────────────────│      │ ──────────────────   │
│ id (PK)        │      │ id (PK)              │
│ user_id (FK) ──┘      │ user_id (FK) ────────┘
│ agent_config_id │─────│ agent_config_id
│ name, work_dir  │      │ name, description
└────────┬─────────┘      │ trigger_words
         │                │ confidence (贝叶斯)
         │                └──────────────────────┘
         │
┌────────┴──────────────────────┐   ┌──────────────────────┐
│ conversation_message          │   │ conversation_compaction│
│ ─────────────────────        │   │ ───────────────────── │
│ id (PK)                       │   │ id (PK)              │
│ conversation_id (FK) ─────────┼───│ conversation_id (FK)  │
│ role (system/user/            │   │ summary (压缩摘要)    │
│   assistant/tool)             │   │ start/end_message_id  │
│ content, reasoning            │   │ token_savings         │
│ tool_calls (JSON)             │   │ superseded            │
└───────────────────────────────┘   └──────────────────────┘

┌──────────────────┐      ┌──────────────────────┐
│  sub_agent_log   │      │   agent_task         │
│ ───────────────  │      │ ──────────────────   │
│ id (PK)          │      │ id (PK)              │
│ agent_id         │      │ conversation_id (FK)  │
│ parent_conv_id   │      │ status (running/     │
│ status (running/ │      │   cancelled/...)     │
│   completed/...) │      │ iteration, event_cnt │
│ instructions     │      │ pending_question      │
│ full_messages    │      └──────────────────────┘
│ file_changes     │
│ compile_result   │
└──────────────────┘

┌─────────────────────────── P2P 相关 ───────────────────────────┐
│ p2p_chat_message          p2p_agent_authorization               │
│ ─────────────────         ──────────────────────                │
│ peer_id, content          peer_id, agent_config_id              │
│ direction (sent/recv)     direction (sent/received)              │
│ message_type              status (active/cancelled)              │
│                                                                  │
│ p2p_agent_conversation    p2p_known_peer                         │
│ ────────────────────      ──────────────                         │
│ peer_id + agent_id        peer_id, name, address                 │
│ + conversation_id         last_seen                              │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────┐      ┌──────────────────────┐
│  sys_role        │      │    sys_menu          │
│ ───────────────  │      │ ──────────────────   │
│ id, name, code   │      │ id, name, path       │
│ description      │      │ icon, menu_type      │
└──────┬───────────┘      │ parent_id, sort      │
       │                  └──────────┬───────────┘
       │       sys_role_menu         │
       └──────────┬──────────────────┘
                  │ (role_id, menu_id) UNIQUE
                  │
┌──────────────────┐      ┌──────────────────────┐
│ sys_config       │      │ schedule_task        │
│────────────────  │      │───────────────────   │
│ config_key (UQ)  │      │ name, instruction    │
│ config_value     │      │ cron_expression      │
└──────────────────┘      │ execute_time         │
                          │ status (ENABLED/...) │
                          │ max_execute_count    │
                          └──────────────────────┘
                          ┌──────────────────────────────────┐
                          │ lesson（成长体系：踩坑经验）      │
                          │ ──────────────────────────────  │
                          │ id (PK)                          │
                          │ project_key (隔离维度)
│                           │ type (FAILURE/DETOUR 分型)
│                           │ goal (弯路经验任务目标)
│                           │ env_params (记录环境，检索降权)           │
                          │ tool_name + error_category       │
                          │ error_code + error_signature(UQ) │
                          │ symptom/root_cause/solution      │
                          │ status (0草稿/1有效/2隐藏)       │
                          │ source (auto/llm/manual/review)         │
                          │ hit/success/fail_count           │
                          └──────────────────────────────────┘

┌──────────────────┐      ┌──────────────────────┐
│  llm_provider    │      │  mcp_server          │
│ ──────────────── │      │ ───────────────────  │
│ id (PK)          │      │ id (PK)              │
│ code (UQ), name  │      │ name (UQ)            │
│ base_url, api_key│      │ type (http/stdio)    │
│ default_model    │      │ command/url          │
│ request_template │      │ headers, tool_prefix │
│ enabled          │      │ permission_level     │
└──────────────────┘      │ enabled/auto_register │
                          └──────────────────────┘

┌──────────────────────────┐
│ db_connection (Phase 20) │
│ ──────────────────────── │
│ id (PK)                  │
│ name / db_type           │
│ host / port / db_name    │
│ username / password_enc  │
│ extra_params / enabled   │
│ user_id                  │
└──────────────────────────┘

┌──────────────────────────┐   ┌──────────────────────────┐
│ file_asset (vision)      │   │ file_reference           │
│ ──────────────────────── │   │ ───────────────────────  │
│ id (PK)                  │   │ id (PK)                  │
│ file_id (远端ID)         │   │ file_asset_id (FK)       │
│ provider_code / user_id  │   │ conversation_id          │
│ filename / display_name  │   │ message_id (回填)        │
│ mime_type / size         │   │ created_at               │
│ local_path / storage_type│   └──────────────────────────┘
│ source / status / orphan │
│ expires_at / created_at  │
└──────────────────────────┘

┌────────────────────────┐   ┌──────────────────────────┐
│ lesson_norm_cache      │   │ lesson_rule              │
│ ────────────────────── │   │ ───────────────────────  │
│ id (PK)                │   │ id (PK)                  │
│ cache_key (UQ, md5)    │   │ pattern (报错文本片段)    │
│ category / error_code  │   │ category / error_code    │
│ expires_at             │   │ status (候选/转正)       │
└────────────────────────┘   │ match_hit_count (使用度) │
└──────────────────────────┘
```

---

## 七、模块职责速查

| 模块 | 一句话职责 | 关键类 | 行数估算 |
|------|-----------|--------|----------|
| **AI 核心引擎** | 对话管理、API 调用、工具循环 | DeepSeekServiceImpl | ~4600 |
| **工具循环** | 死循环检测、评委评估、SSE 事件 | ToolLoopManager | ~560 |
| **子Agent管理** | fork/collect/inspect 生命周期 | AgentForkManager | ~1600 |
| **Shell 发现** | 智能检测最佳 Shell（Win/Mac/Linux） | ShellDiscoveryService | ~260 |
| **上下文压缩** | LLM 摘要压缩 + 异步预压缩 | CompactionService | ~640 |
| **消息组装** | Token 估算 + 技能目录注入 + 语言指令 | ContextBuilder | ~900 |
| **快照系统** | 文件备份、LCS diff、配额管理 | SnapshotService | ~800 |
| **P2P 协作** | 对等网络、Agent 远程调用、信令 | P2pAgentService | ~5000 (整个p2p包) |
| **工具系统** | 29 个工具注册/执行/权限/后处理 | tool/ 整个包 | ~10000 |
| **文件资产** | 文件上传/本地存储/Files API 适配/图像理解 | FileAssetService + FilesApiClientManager | ~900 |
| **桌面自动化** | 截图（网格刻度）/键鼠模拟/坐标预检 | ScreenCaptureTool + DesktopControlTool | ~1500 |
| **浏览器自动化** | DOM 级网页操作（快照/点击/输入/按键/滚动/截图/控制台/多标签），走 Electron CDP bridge | BrowserTool + BrowserBridgeClient | ~2600 |
| **项目知识库** | 模块地图 / 符号扫描 / 规则沉淀 / 上下文注入 | ProjectMapService + ProjectContextProvider | ~2400 |
| **成长体系** | 经验检索/自动捕获/复盘/归一化/弯路提炼 | LessonService + LessonNormalizerService + LessonDetourService | ~4100 |
| **用户权限** | RBAC、Token 认证、菜单控制 | UserServiceImpl + Filter | ~500 |
| **定时任务** | Cron/一次性调度、执行追踪 | ScheduleTaskScheduler | ~350 |
| **技能系统** | 技能目录注入（渐进式披露）、贝叶斯置信度 | SkillCatalogBuilder + SkillService | ~300 |
| **多LLM Provider** | Provider CRUD、客户端路由、热刷新、错误友好格式化 | LLMClientManager + LLMClient + 6个实现 + LLMErrorFormatter | ~1900 |
| **外部数据库连接** | 连接管理/凭据加密/执行 SQL | DbConnectionService + SqlExecutor | ~600 |
| **数据管理（备份）** | 配置数据导出/导入（UPSERT 合并） | DataTransferService | ~400 |
| **外观与头像** | 背景图与头像的本地存储/读取 | BackgroundStore + AvatarStore | ~300 |
| **聊天记录分页** | 按「轮」游标分页（默认 5 轮 / 上限 50）+ 前端懒加载 | ConversationController + ConversationServiceImpl + ConversationMessageMapper | ~250 |
| **工具调用序列净化** | 幂等修复 tool_calls / tool 消息配对，防接口 400 | ToolCallSequenceSanitizer | ~300 |
| **自动更新（桌面端）** | Releases 检测 / 下载 / 安装 / 升级前备份 | electron/update-manager.js + update-utils.js | ~700 |
| **DeepSeek 官方工具 API** | 官方搜索/抓取接口优先，失败回退原链路 | DeepSeekToolsApiClient + SearchTool + WebFetchTool | ~500 |

---

## 八、关键约定与注意事项

1. **API 端口**: 默认 `8084`（application.yml 中 `server.port`）
2. **H2 控制台**: `http://localhost:8084/h2-console`，JDBC URL: `jdbc:h2:file:./data/codecraft;MODE=MySQL;DB_CLOSE_DELAY=-1`（用户名 `sa`，密码空）
3. **默认管理员**: 首次启动由 `UserInitializer` 自动创建
4. **密码加密**: MD5（32位），非 bcrypt——安全敏感场景需升级
5. **工具返回格式**: 统一使用 `ApiResponse<T>`，code 参考 `ResponseEnum`
6. **SSE 事件**: OpenAI 兼容流式格式（`choices[].delta.content` / `reasoning_content`）+ 自定义事件 `tool_call_start` / `ask_user` / `delegate_progress` / `task_error`
7. **子Agent 并发上限**: 20 个（AgentForkManager.MAX_CONCURRENT_AGENTS）
8. **工具循环最大迭代**: 主循环 50 轮（评委可扩展，累计上限 +100 次）；子Agent 默认 30 轮
9. **快照配额**: 500MB 上限，超限自动清理至 300MB
10. **Token 估算系数**: 中文 ~1.5、英文 ~1.0、数字 ~0.5、emoji ~0.5（见 tokenCalculator.ts + TokenEstimator.java）
11. **工具调用序列配对（硬约束）**: `assistant.tool_calls` 与 `role=tool` 必须严格一一配对，缺一即 400 且会永久污染会话。所有 LLM 请求出口必须经过 `ToolCallSequenceSanitizer`（幂等、copy-on-write），任何"只删一侧"的局部修复都是错的。
12. **聊天消息接口**: 一律走 `GET /conversation/{id}/messages/page`（按轮游标），不要再用全量 `GET /conversation/{id}/messages` 渲染长会话。
13. **自动更新**: 版本号以 `pom.xml` 为唯一源（`scripts/sync-version.*` 同步到 electron），发布时 GitHub Releases 的资产名需匹配 `CodeCraft-Setup-{version}.exe` / `CodeCraft-{version}.dmg` / `CodeCraft-{version}.AppImage`。
14. **上下文模式三档（v3.0.9）**: `deepseek.context-mode` = `full` / `balanced`（默认）/ `compact`。`balanced` **在构建阶段就不写 reasoning**（而非事后裁剪），保证前缀天然稳定、每轮零额外失效；`compact` 才摘要工具结果（会触发一次缓存重建）。旧配置 `stripHistoryReasoning` 由 `DeepSeekConfig.resolveContextMode()` 自动映射，无需迁移。改动上下文构建逻辑前先读这条——**任何 system / tools 变化都会让前缀缓存全量失效**，回本周期在 98% 命中率下长达 100~150 轮。
15. **Token 成本采集口径（v3.0.9 / v3.1.1 扩展）**: SSE 采集点必须放在 `doOnNext`（**不能放在 `takeWhile` 之后**，否则末尾 usage 帧被截断丢弃）；落库用 `doFinally`（正常/异常/取消都能落账）。计价走 `llm.pricing` 配置（含缓存命中价），不在代码里写单价。**usage 有三个协议**，由 `LLMClient#getUsageProtocol()` 声明、`TokenUsageParser#parseFrame` 分派：`openai`（单帧 usage）、`anthropic`（输入侧只在 `message_start`、输出侧在 `message_delta`，**必须跨帧合并**）、`ollama`（NDJSON 的 `done=true` 收尾行）。`stream_options.include_usage` 由 `requiresStreamUsageOption()` **按 Provider 精确开启**（官方 GPT / 千问 / 方舟 / MiniMax / MiMo 需要，DeepSeek 默认就返回，第三方兼容服务刻意不加以免 400）。**未在 `llm.pricing.models` 配单价的模型一律不估算**（`cost_estimate` 落 NULL，汇总带 `pricedRequestCount`，前端显示「—」）—— 绝不回退默认价，错误的成本比没有成本更危险。
16. **前缀缓存指纹（v3.0.9）**: `ContextFingerprint` 每轮请求前比对 `system + tools` 哈希，变化即 WARN。缓存失效不报错、只涨价，这是唯一能把「静默变贵」变成「日志可见」的抓手——新增/修改工具 schema 时留意这条日志。
17. **工具定义合并（v3.0.9）**: `git_*`（6 个）→ `git`、`screen_capture` + `desktop_control` → `desktop`。**纯委托**实现（按 action 路由到原子工具实例，参数透传），旧工具类仍注册在 `ToolRegistry` 供历史消息引用，`normalizeToolNames()` 负责旧名兼容。合并的目的是压缩每轮下发的 schema 与减少模型的选择空间，不是减少注册项。
18. **Electron 终端 PTY（v3.0.9）**: 终端在 Electron 内走 `node-pty` 真伪终端（`electron/pty-manager.js`，IPC 通道，不监听本地端口）；浏览器降级走 `/project/exec`。`node-pty` 是原生模块，**必须 `asarUnpack`**，且 `before-quit` 必须调 `disposeAllSessions()`（否则残留 shell 拖住退出）。自带 win32/darwin prebuilds、**无 linux prebuild**，Linux 打包机需 node-gyp 工具链。
19. **长耗时命令后台化（v3.0.9）**: `command(action=exec)` 超时**不杀进程**，转后台任务（返回任务 ID），配合 `wait` / `logs` / `stop`。`timeout` 参数语义已改为**仅对 `wait` 生效**。同 cwd + 同命令有去重拦截；一次性任务（上限 10）与长期服务（上限 5）分池计数；结束输出保留 30 分钟。应急回退：`command.exec.background-after-seconds: 0`。
20. **按轮成本归并（v3.1.0）**: 一次用户提问在工具循环里会拆成多次 API 请求，成本只有按 `turn_id` 归并才能对应「这一轮花了多少」。`turn_id` 由前端生成、随 `ChatRequest.turnId` 下发（与代码快照、重新生成共用同一 id），存入 `llm_token_usage.turn_id`，按轮查询走 `GET /api/token-usage/turns`。**存量数据没有 `turn_id`，只计入会话 / 时间维度汇总** —— 不要事后回填到某一轮，否则该轮成本虚高。带 `turn_id` 的 insert 失败必须降级为不带该列写入：宁可丢「按轮展示」，不能让整条成本记账静默中断。删除会话会级联删除该会话的成本明细（`ConversationServiceImpl` 显式调用，不靠外键）。
21. **上下文压缩的估算口径（v3.1.2）**: 异步预压缩的 token 估算**必须基于「应用压缩记录之后」的有效消息**（`applyCompactionRecords` → `toApiMessages` → `TokenEstimator.estimateMessages`），绝不能直接累加 DB 全量原文。历史被压缩后 DB 原文仍然存在，用全量原文估算会让 ratio 只增不减、永久超过阈值，导致「每次请求后都压缩 → 每次压缩都改写前缀 → 缓存 100% 失效」（实测 15 次压缩中 9 次全量未命中，约 130 万 miss token）。另外三条硬约束：① TOOL 消息的 API `content` 取自 `reasoning` 字段（工具结果存在这里），**不受 `stripHistoryReasoning` 影响**，把它当思考链剥掉会严重低估（工具结果是上下文大头）；② 传给 `compact()` 的列表必须与它内部「重新查库 + 应用压缩记录」的列表**逐条对齐**，否则 `compressStart/compressEnd` 会压错区间；③ 压缩收益为负（摘要 ≥ 原文）必须跳过 —— 白白改变前缀只会损失一次全量缓存。改动 `CompactionService` 前先读这条。
22. **工具结果体积预算（v3.1.2）**: 单条工具结果的裁剪规则**只在 `util/ToolResultBudget` 一处定义**（配置 `tool.result-budget.*`，按工具分级：`file_explorer`/`web_fetch`/`browser` 16000 字符，`command`/`web_search`/`execute_sql`/`http_request` 10000，其余 12000），由 `ToolExecutor#buildToolMessages` 在**写入前**执行一次 —— 落库（`saveToolMessage`）与发送 LLM 用的是**同一份**裁剪后内容。四条硬约束：① **禁止在别处新增私有阈值常量**（历史上 `ToolExecutor.MAX_TOOL_RESULT_CHARS` 与 `ContextBuilder.MAX_TOOL_CONTENT_CHARS` 各写一套 30000，规则一旦漂移就变成「落库 A 形态、发给 LLM B 形态」）；② **必须幂等**：截断文本带 `…[CC-TRUNC]` 标记，已带标记的内容原样返回，否则每轮重建历史时截断位置漂移 → 前缀缓存全量失效；③ `ContextBuilder.trimToolLoopToBudget` 的超限兜底**复用同一实例**，只对「存量超预算数据」生效一次，不做二次改写；④ 截断处必须附带「如何取回被省略部分」的指引（`file_explorer` 的 `start_line`/`end_line`、`command` 的 `action=logs`、`execute_sql` 的 `WHERE`/`LIMIT`）—— 静默砍掉一半内容会让模型基于残缺信息下结论或盲目重跑同一工具，那比不截断更贵。
23. **grep 输出必须合并重叠上下文窗口（v3.1.2）**: `FileExplorerTool.renderGrepHits` 按文件分组后用 `TreeMap<行号, 行内容>` 去重再输出，相邻/重叠的 `context_lines` 窗口只保留一份，行号不连续处插 `...`。旧实现对 `contextBefore`/`contextAfter` 的**每一行都写同一个行号**（`lineNum - size` / `lineNum + 1`），既让模型按错行号去读文件，又让输出看起来像「大量重复内容」，掩盖了真实原因。实测命中密集的 grep 结果里超过一半是窗口重叠造成的重复，纯烧 cache-miss token（命中/未命中单价相差约 50 倍）。

---

## 九、已知技术债务

| 问题 | 严重程度 | 建议 |
|------|---------|------|
| DeepSeekServiceImpl 187KB 单体 | 🔴 高 | 拆分为 ChatOrchestrator / ToolLoopEngine / ResponseStreamer |
| 密码 MD5 存储 | 🔴 高 | 升级 bcrypt 或 argon2 |
| 前端缺少测试 | 🟡 中 | 至少为核心组件补充 Vitest 单元测试 |
| snapshots/ 目录膨胀 | 🟡 中 | 增加定期清理机制或切换到 Git-based 快照 |
| 部分配置硬编码 | 🟢 低 | DeepSeekConfig 中有些默认值可移到 yml |

---

## 十、多 LLM Provider 支持

> 详细文档请参阅 [LLM_PROVIDER_SYSTEM.md](./LLM_PROVIDER_SYSTEM.md)

CodeCraft 支持多种 LLM 平台，核心组件：

- **llm_provider 表**：存储 Provider 配置（code/name/baseUrl/apiKey/defaultModel/requestTemplate 等）
- **LLMClient 接口**：统一抽象层，所有 Provider 必须实现（buildRequestBody/streamChat/extractContent 等）
- **LLMClientManager**：核心管理器，负责 Provider 注册、路由（resolveClientByCode/resolveClientByProviderId）、热刷新
- **10 类 Provider 模板（v3.0.0）**：`DeepSeekClient` / `GptClient`（OpenAI 官方专精）/ `OpenAIClient`（公共契约）/ `AnthropicClient`（公共契约）/ `QwenClient`（阿里云百炼）/ `VolcengineArkClient`（火山方舟）/ `OllamaClient` / `MiMoClient` / `MiniMaxClient` / `custom`（复用 OpenAIClient 最小兼容模式），统一继承 `AbstractLLMClient`；每个类的 Javadoc 顶部均有「适用范围（选型指引）」
- **Agent 绑定**：agent_config 表新增 provider_id/provider_code 字段，每个 Agent 可绑定特定 Provider
- **前端动态切换**：CodeAssistantView 支持运行时切换 Provider，自动刷新模型列表
- **Provider 路由优先级（v3.0.8）**：前端动态 providerCode > Agent 配置 providerCode（须已启用）> Agent 配置 providerId > 第一个可用 Provider；`updateRuntime()` 写入时以 providerCode 为语义源同步 providerId，杜绝两字段错位
- **会话列表排序（v3.0.8）**：`ConversationMapper` 四个列表查询按 `updated_at DESC`（最近活跃在前，每轮对话开始由 `ContextBuilder` 刷新）；前端每轮对话结束后都刷新侧栏列表，最新活跃会话实时置顶
- **项目知识库自动维护（v3.0.8）**：本轮对话出现写类工具（file_writer / execute_sql）时，任务结束后异步增量 bootstrap——`.codecraft/` 不存在自动创建、已存在只更新变化模块（幂等保留 annotate）；`codecraft.project-context.auto-maintain` 开关默认开
- **DeepSeek 官方工具 API（v2.0.3）**：`DeepSeekToolsApiClient` 在 Provider 的 `baseUrl` 主机为 `api.deepseek.com` 且配置了 API Key 时自动启用，`web_search` / `web_fetch` 优先调用官方 `/v1/tools/search` 与 `/v1/tools/fetch`，异常时自动回退原链路（Bing/百度、Jina/本地）。判定依据是 baseUrl 域名而非 providerCode，第三方中转/自建服务不会启用
- **平台增强能力（v3.0.0）**：`llm_provider.extra_config` 承载平台凭证（火山 AK/SK + 联网搜索 Key、阿里云 AK/SK、MiniMax Coding Plan Key、MiMo 控制台 Cookie），能力按「按需启用 + 缺凭证跳过 + 失败回退」工作；余额/额度查询支持 DeepSeek / 火山引擎 / 阿里云（通义千问）/ MiniMax / MiMo

---

> 📌 **文档维护约定**: 当包结构变更、新增核心模块、或技术决策变化时，请同步更新本文档。
