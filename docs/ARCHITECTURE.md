> 🌐 English Version：[🇬🇧 ARCHITECTURE_EN](./ARCHITECTURE_EN.md)
# CodeCraft 架构全景图

> 版本：v1.0.5 | 更新：2026-05-28 | 受众：开发者 / AI 协作伙伴
> 本文档旨在让新加入的开发者（包括 AI Agent）在 5 分钟内建立对项目的完整认知地图。

---

## 一、一句话定义

**CodeCraft** 是一个基于 AI Agent 的桌面端智能编程助手。用户通过聊天界面向 AI 下达编程任务，AI 自动调用 19 种工具（读写文件、执行命令、操作 Git、搜索网络等）完成任务，支持子 Agent 并行协作。

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
│ │ │        Controller 层（16 个）               │                      │ │
│ │ │ DeepSeekController / GitController /       │                      │ │
│ │ │ P2pController / SnapshotController / ...   │                      │ │
│ │ └─────────────────────┬──────────────────────┘                      │ │
│ │                       │                                             │ │
│ │ ┌─────────────────────┴──────────────────────┐                      │ │
│ │ │         Service 核心层                     │                      │ │
│ │ │                                            │                      │ │
│ │ │  DeepSeekServiceImpl (129KB, 核心引擎)     │                      │ │
│ │ │   ├─ ToolLoopManager (工具循环/死循环检测) │                      │ │
│ │ │   ├─ AgentForkManager (子Agent管理)        │                      │ │
│ │ │   ├─ CompactionService (上下文压缩)        │                      │ │
│ │ │   ├─ ContextBuilder (消息组装/Token估算)   │                      │ │
│ │ │   ├─ AgentEventBus (SSE事件推送)           │                      │ │
│ │ │   └─ MessagePersister (消息持久化)         │                      │ │
│ │ └─────────────────────┬──────────────────────┘                      │ │
│ └───────────────────────┼─────────────────────────────────────────────┘ │
└─────────────────────────┼──────────────────────────────────────────────┘
                          │
                    DeepSeek API
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
│    └─ 匹配到的技能注入（SkillMatcher）                                  │
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
│    │ ├─ 死循环检测：连续 4 轮相同工具调用 → 终止                   │    │
│    │ ├─ 评委机制：超最大迭代 → Judge 评估是否继续                  │    │
│    │ └─ 用户取消检测：每轮检查是否被中断                           │    │
│    └──────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

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

---

## 四、技术选型与关键设计决策

| 决策 | 选择 | 为什么 |
|------|------|--------|
| **数据库** | H2 (嵌入式) | 桌面应用需零配置部署，无需用户安装 MySQL |
| **缓存** | Caffeine | 替代 Redis，同样为了零依赖开箱即用 |
| **AI 通信** | WebFlux + SSE | 支持流式输出，用户可实时看到 AI 打字效果 |
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
│   ├── NetworkToolConfig      # 代理/网络配置
│   ├── OpenApiConfig          # Swagger/OpenAPI 文档
│   └── SpaConfig              # 单页应用路由回退
├── controller/                # REST API 控制器（16 个）
│   ├── DeepSeekController     # ★核心：聊天、Agent 任务状态
│   ├── ConversationController # 会话 CRUD
│   ├── GitController          # Git 操作（status/diff/commit...）
│   ├── P2pController          # P2P 远程协作
│   ├── SnapshotController     # 快照管理
│   ├── SkillController        # 技能 CRUD
│   ├── AgentConfigController  # Agent 配置管理
│   ├── ToolRegistryController # 工具注册表查询
│   ├── ProjectController      # 项目文件浏览
│   ├── ProjectBuildController # 项目构建/编译
│   ├── UserController         # 用户管理
│   ├── MenuController         # 菜单权限
│   ├── RoleController         # 角色管理
│   ├── ConfigController       # 系统配置（API Key等）
│   ├── LogController          # 日志查询
│   └── ScheduleTaskController # 定时任务
├── filter/                    # TokenAuthenticationFilter（Token 鉴权拦截器）
├── initializer/               # UserInitializer（首次启动初始化管理员）
├── mapper/                    # MyBatis Mapper 接口（26 个）
│   ├── ConversationMapper / ConversationMessageMapper
│   ├── AgentConfigMapper / SkillMapper / SubAgentLogMapper
│   ├── UserMapper / MenuMapper / RoleMapper / RoleMenuMapper
│   ├── ScheduleTaskMapper / SysConfigMapper
│   └── P2p* (4个：授权/会话/消息/已知节点)
├── model/
│   ├── entity/                # 数据库实体（14 个）
│   │   ├── Conversation / ConversationMessage / CompactionRecord
│   │   ├── User / Menu / Role / RoleMenu / SysConfig
│   │   ├── AgentConfig / AgentTask / Skill / SubAgentLog
│   │   ├── ScheduleTask / MessageRole
│   │   └── P2pAgentAuthorization / P2pAgentConversation / P2pChatMessage / P2pKnownPeer
│   ├── dto/                   # 请求/响应 DTO
│   │   ├── ChatRequest / ChatResponse
│   │   ├── ForkAgentRequest / CollectAgentRequest / InspectAgentRequest
│   │   ├── LoginRequest / LoginResponse / PendingQuestion
│   │   └── GetRandomCodeRequest
│   ├── vo/                    # 视图对象
│   │   ├── DirectoryEntry / ProjectTreeNode
│   └── SubAgentResult         # 子Agent结构化执行结果
├── p2p/                       # ⚡P2P 远程协作子系统
│   ├── controller/P2pController   # REST API（19.8KB，最大控制器）
│   ├── agent/P2pAgentService      # Agent 远程调用核心（33.4KB）
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
│   ├── impl/
│   │   ├── DeepSeekServiceImpl    # ★★★核心引擎（129KB，最复杂文件）
│   │   ├── ToolLoopManager        # 工具调用循环（死循环检测/评委/取消）
│   │   ├── AgentForkManager       # 子Agent生命周期（fork/collect/inspect）
│   │   ├── CompactionService      # 上下文压缩（三级决策 + 异步预压缩）
│   │   ├── ContextBuilder         # 消息上下文组装 + Token估算
│   │   ├── AgentEventBus          # Agent 事件总线（SSE 推送）
│   │   ├── MessagePersister       # 消息异步持久化
│   │   ├── SnapshotService        # 代码快照备份/回滚（19.8KB）
│   │   ├── ProjectBuildService    # 项目编译构建
│   │   ├── SkillMatcher           # BM25 + 触发词匹配
│   │   ├── SkillIndexer           # 技能索引构建
│   │   ├── CharacterPromptUtil    # 角色设定提示词工具
│   │   ├── AttachmentReaderService # 附件文件读取服务
│   │   └── ... (User/Config/Menu/Role 等 CRUD 服务)
│   └── SkillService / UserService / ConfigService / ...
├── tool/                      # ⚡AI Agent 工具系统
│   ├── Tool.java              # 工具接口定义
│   ├── ToolRegistry           # 工具注册中心（单例）
│   ├── ToolExecutor           # 工具执行引擎（参数解析/分发）
│   ├── ToolInitializer        # 启动时自动初始化所有工具
│   ├── ExecutionTokenManager  # 工具执行 Token 管理
│   ├── PermissionContext      # 会话级权限上下文
│   ├── impl/                  # 19 个工具实现
│   │   ├── 文件操作: ReadFileTool, WriteFileTool, EditFileTool,
│   │   │            DeleteFileTool, GlobFilesTool, GrepSearchTool
│   │   ├── 命令执行: RunCommandTool, RunServerTool, ServiceControlTool
│   │   ├── 网络请求: SearchTool, WebFetchTool, HttpRequestTool, NetworkCheckTool
│   │   ├── 数据库:   ExecuteSqlTool
│   │   ├── Git 操作: GitStatusTool, GitDiffTool, GitLogTool,
│   │   │            GitAddTool, GitCommitTool, GitPushTool, GitBranchTool
│   │   ├── Agent协作: ForkAgentTool, CollectAgentTool, InspectAgentTool
│   │   ├── 技能管理: ManageSkillTool, ReportSkillResultTool
│   │   ├── 项目管理: ProjectInfoTool, ReadProjectTreeTool
│   │   ├── 任务管理: TaskManagerTool
│   │   ├── 交互工具: AskClarificationTool
│   │   └── 分析工具: DeepSeekAnalyzer
│   ├── permission/            # 权限控制
│   │   ├── ToolPermission / ToolPermissionMetadata / ToolPermissionRegistry
│   │   ├── ToolExecutionPipeline (三层防护)
│   │   ├── PathSecurityChecker (路径越界检查)
│   │   ├── OperationCategory (操作分类)
│   │   └── ToolAuditLogger (审计日志)
│   └── postedit/              # 后处理管线
│       ├── PostEditPipeline   # 编排 Formatter + Diagnostic
│       ├── Formatter          # 代码格式化（Java/Python/JS/TS...）
│       └── Diagnostic         # 编译诊断（lint/语法检查）
├── log/LogService             # 应用级日志服务（10.8KB）
└── util/                      # 工具类
    ├── CommandUtils           # 命令执行工具（13.9KB）
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
```

---

## 七、模块职责速查

| 模块 | 一句话职责 | 关键类 | 行数估算 |
|------|-----------|--------|----------|
| **AI 核心引擎** | 对话管理、API 调用、工具循环 | DeepSeekServiceImpl | ~3000 |
| **工具循环** | 死循环检测、评委评估、SSE 事件 | ToolLoopManager | ~450 |
| **子Agent管理** | fork/collect/inspect 生命周期 | AgentForkManager | ~1400 |
| **上下文压缩** | LLM 摘要压缩 + 异步预压缩 | CompactionService | ~550 |
| **消息组装** | Token 估算 + 技能注入 + 语言指令 | ContextBuilder | ~500 |
| **快照系统** | 文件备份、LCS diff、配额管理 | SnapshotService | ~750 |
| **P2P 协作** | 对等网络、Agent 远程调用、信令 | P2pAgentService | ~2000 (整个p2p包) |
| **工具系统** | 19 个工具注册/执行/权限/后处理 | tool/ 整个包 | ~6000 |
| **用户权限** | RBAC、Token 认证、菜单控制 | UserServiceImpl + Filter | ~500 |
| **定时任务** | Cron/一次性调度、执行追踪 | ScheduleTaskScheduler | ~350 |
| **技能系统** | BM25 匹配、贝叶斯置信度 | SkillMatcher + SkillIndexer | ~400 |

---

## 八、关键约定与注意事项

1. **API 端口**: 默认 `8084`（application.yml 中 `server.port`）
2. **H2 控制台**: `http://localhost:8084/h2-console`，JDBC URL: `jdbc:h2:file:./data/codecraft`
3. **默认管理员**: 首次启动由 `UserInitializer` 自动创建
4. **密码加密**: MD5（32位），非 bcrypt——安全敏感场景需升级
5. **工具返回格式**: 统一使用 `ApiResponse<T>`，code 参考 `ResponseEnum`
6. **SSE 事件类型**: `thinking` / `text` / `tool_start` / `tool_result` / `error` / `done`
7. **子Agent 并发上限**: 5 个（在 AgentForkManager 中硬编码）
8. **工具循环最大迭代**: 50 轮
9. **快照配额**: 500MB 上限，超限自动清理至 300MB
10. **Token 估算系数**: 中文 ~1.5、英文 ~1.0、数字 ~0.5、emoji ~0.5（见 tokenCalculator.ts + TokenEstimator.java）

---

## 九、已知技术债务

| 问题 | 严重程度 | 建议 |
|------|---------|------|
| DeepSeekServiceImpl 129KB 单体 | 🔴 高 | 拆分为 ChatOrchestrator / ToolLoopEngine / ResponseStreamer |
| 密码 MD5 存储 | 🔴 高 | 升级 bcrypt 或 argon2 |
| 前端缺少测试 | 🟡 中 | 至少为核心组件补充 Vitest 单元测试 |
| snapshots/ 目录膨胀 | 🟡 中 | 增加定期清理机制或切换到 Git-based 快照 |
| 部分配置硬编码 | 🟢 低 | DeepSeekConfig 中有些默认值可移到 yml |

---

> 📌 **文档维护约定**: 当包结构变更、新增核心模块、或技术决策变化时，请同步更新本文档。
