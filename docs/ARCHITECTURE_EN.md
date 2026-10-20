﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿> 🌐 中文版：[🇨🇳 ARCHITECTURE](./ARCHITECTURE.md)
# CodeCraft Architecture Panorama

> Version: v3.0.4 | Updated: 2026-10-20 | Audience: Developers / AI Collaborators
> This document aims to help new developers (including AI Agents) build a complete cognitive map of the project within 5 minutes.

---

## 1. One-Sentence Definition

**CodeCraft** is an AI Agent-based desktop intelligent programming assistant. Users communicate programming tasks to AI through a chat interface, and AI automatically invokes **27 tools** (file read/write, command execution, Git operations, web search, desktop automation, etc.) to complete tasks, supporting sub-agent parallel collaboration and dynamic switching between multiple LLM platforms (DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax, etc.).

---

## 2. System Topology

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       Electron Desktop Shell                             │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │             Vue 3 Frontend (frontend/)                               │ │
│ │ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐           │ │
│ │ │ChatView  │ │AgentPanel│ │FileTree  │ │GitSidebar     │           │ │
│ │ │(SSE      │ │(SubAgent)│ │(Editor)  │ │(Diff/Commit)  │           │ │
│ │ │ Stream)  │ │          │ │          │ │               │           │ │
│ │ └────┬─────┘ └────┬─────┘ └────┬─────┘ └───────┬───────┘           │ │
│ │      └────────────┴────────────┴─────────────────┘                  │ │
│ │                       │ REST API + SSE                               │ │
│ └───────────────────────┼─────────────────────────────────────────────┘ │
│                         │                                               │
│ ┌───────────────────────┼─────────────────────────────────────────────┐ │
│ │          Spring Boot 3.4 Backend (src/main/java/)                   │ │
│ │                       │                                             │ │
│ │ ┌─────────────────────┴──────────────────────┐                      │ │
│ │ │        Controller Layer (25 Controllers)    │                      │ │
│ │ │ DeepSeekController / GitController /       │                      │ │
│ │ │ P2pController / SnapshotController / ...   │                      │ │
│ │ └─────────────────────┬──────────────────────┘                      │ │
│ │                       │                                             │ │
│ │ ┌─────────────────────┴──────────────────────┐                      │ │
│ │ │         Service Core Layer                  │                      │ │
│ │ │                                            │                      │ │
│ │ │  DeepSeekServiceImpl (187KB, Core Engine)  │                      │ │
│ │ │   ├─ ToolLoopManager (Tool Loop / Loop Detection)                 │ │
│ │ │   ├─ AgentForkManager (Sub-Agent Mgmt)     │                      │ │
│ │ │   ├─ CompactionService (Context Compaction)│                      │ │
│ │ │   ├─ ContextBuilder (Message Assembly / Token Estimation)         │ │
│ │ │   ├─ AgentEventBus (SSE Event Push)        │                      │ │
│ │ │   └─ MessagePersister (Message Persistence)│                      │ │
│ │ └─────────────────────┬──────────────────────┘                      │ │
│ └───────────────────────┼─────────────────────────────────────────────┘ │
└─────────────────────────┼──────────────────────────────────────────────┘
                          │
                    DeepSeek API
```

---

## 3. Core Data Flow

### 3.1 Complete Chain of a User Conversation

```
User Input Message
    ↓
┌─ DeepSeekController.chat() ─────────────────────────────────────────────┐
│  Get/Create Conversation                                                │
│  Call DeepSeekService.sendMessage()                                     │
│  Return SSE (Server-Sent Events) stream to frontend                     │
└────────────────────────────┬────────────────────────────────────────────┘
                             ↓
┌─ DeepSeekServiceImpl.sendMessage() ─────────────────────────────────────┐
│  ContextBuilder.buildMessages() → Assemble full message context         │
│    ├─ System prompt                                                     │
│    ├─ Compacted history summary (CompactionService)                    │
│    ├─ Last N complete conversation rounds                              │
│    ├─ Current user message + attachments/file content                  │
│    └─ Skill catalog injection (SkillCatalogBuilder)                           │
│                                                                         │
│  Send HTTP request to DeepSeek API (WebFlux + SSE streaming)           │
│                                                                         │
│  AI returns → If tool_calls present → ToolLoopManager takes over       │
│    ┌──────────────────────────────────────────────────────────────┐    │
│    │ Tool Loop (max 50 iterations)                                 │    │
│    │ ┌─ ToolExecutor.execute()                                    │    │
│    │ │  ├─ ToolExecutionPipeline (3-layer permission check)      │    │
│    │ │  ├─ Execute tool logic                                     │    │
│    │ │  └─ PostEditPipeline (format + diagnose)                   │    │
│    │ ├─ Append tool results to message history                     │    │
│    │ ├─ Call DeepSeek API again (with tool results)               │    │
│    │ └─ Repeat until AI no longer requests tool calls              │    │
│    │                                                               │    │
│    │ Safety Mechanisms:                                            │    │
│    │ ├─ Infinite Loop Detection: 3 consecutive identical tool+params → flagged for Judge     │    │
│    │ │   calls → flagged by Judge                                         │    │
│    │ ├─ Judge Mechanism: exceeds max iterations → Judge evaluates │    │
│    │ │   whether to continue                                       │    │
│    │ └─ User Cancel Detection: check interruption each round       │    │
│    └──────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 3.1.1 Key Additions (v2.0.3)

- **Tool-call sequence sanitization (anti-400)**: `ContextBuilder.buildMessages()` calls `ToolCallSequenceSanitizer.sanitize()` (idempotent) right after message conversion and again after context compaction/trimming. The sanitizer guarantees strict one-to-one pairing between `assistant.tool_calls` and `role=tool` messages: it drops orphan tool messages, removes tool_calls without results (clears `tool_calls` when none has a result), drops empty assistant messages, and removes illegal/duplicate ids. The same sanitizer is wired into the request-body exits of `AbstractLLMClient` / `AnthropicClient` / `OllamaClient`.
- **Write-side placeholders**: `DeepSeekServiceImpl.appendToolResultMessages()` appends a "[tool not executed]" placeholder tool message for every tool_call declared in the batch that produced no result, eliminating sequence gaps at the source.
- **Judge semantics (v2.0.3)**: on judge rejection / unparsable judge response / judge failure with no local progress / reaching the cumulative `MAX_JUDGE_GRANTED_ITERATIONS=100` cap, the task is no longer terminated directly. Instead an `ask_user` event (`askType=iteration_confirm`) lets the user choose "end task" or "continue +10 / +20 / +50 iterations"; a 5-minute wait timeout ends the task.

### 3.2 Sub-Agent Collaboration

```
Main Agent (running in user session)
    ↓
    fork_agent("sub-1", "Write UserService", [read_file,write_file,...])
    ┌─ AgentForkManager.forkAgent() ──────────────────────────────────────┐
    │  INSERT into sub_agent_log table (status=running)                   │
    │  Create new ToolLoopManager instance                                │
    │  Background thread starts independent conversation loop             │
    │  Return agent_id: "sub-1"                                           │
    └─────────────────────────────────────────────────────────────────────┘
    ↓
    (Main Agent continues other work, Sub-Agent runs in background)
    ↓
    collect_agent("sub-1", timeout=300)
    ┌─ AgentForkManager.collectAgent() ───────────────────────────────────┐
    │  Block and wait for sub-agent to complete (max 300s)               │
    │  Collect structured results: status, file changes, build result,   │
    │  decisions                                                          │
    │  Update sub_agent_log record                                        │
    │  Return summary to main agent                                       │
    └─────────────────────────────────────────────────────────────────────┘
    ↓
    (optional) inspect_agent("sub-1", "full_log")
        Get sub-agent's complete thinking process, tool call history
```

### 3.3 Lazy-Loaded Chat History Pagination (v2.0.3)

Long conversations are no longer fetched in full:

```
Frontend CodeAssistantView
  ├─ Open conversation → GET /conversation/{id}/messages/page?rounds=5   (latest 5 rounds)
  ├─ scrollTop ≤ 80 → same endpoint + beforeId=nextBeforeId&rounds=5     (5 older rounds)
  ├─ Response: messages (id ascending) / hasMore / nextBeforeId
  └─ Rendering: DynamicScroller (:key=conversationId forces rebuild)
       ├─ Globally unique message ids (role-timestamp-autoIncrement)
       ├─ v-track-msg-size + ResizeObserver → throttled forceUpdate self-heal
       └─ Bidirectional scroll compensation after prepending history
```

Backend paginates "by round": `role=USER` (excluding `desktop_inject`) marks round boundaries — `selectNthUserMessageIdFromTail` locates the N-th user message id from the tail, then the range is fetched by id. Boundaries therefore always fall on user questions and AI message grouping (`processMessageGroups`) never spans pages. The cursor is `beforeId` rather than offset, so newly written messages cannot shift pages.

---

## 4. Technology Choices & Key Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| **Database** | H2 (Embedded) | Desktop app needs zero-config deployment, no MySQL required |
| **Cache** | Caffeine | Replaces Redis, zero-dependency out of the box |
| **AI Communication** | WebFlux + SSE | Supports streaming output, users see AI typing in real-time |
| **Multi-LLM Support** | LLMClient Abstraction | Unified interface for DeepSeek/OpenAI/Anthropic/Ollama/MiMo/MiniMax, runtime dynamic switching |
| **P2P Network** | Netty + JSON | High-performance async IO, JSON debugging friendly |
| **P2P Signaling** | QR Code + ZXing | No manual address entry, scan to pair devices |
| **P2P Security** | TLS + BouncyCastle | Self-signed certificates + AES encryption, end-to-end secure channel |
| **Token Estimation** | jtokkit (tiktoken) | Accurate consumption estimation for compaction decisions |
| **Code Highlighting** | highlight.js (custom editor) | No external IDE dependency, browse and edit code within desktop |
| **Desktop Packaging** | Electron + Bundled JRE | Cross-platform, double-click to use, zero environment setup |

---

## 5. Package Structure Quick Reference

```
src/main/java/com/example/agentdeepseek/
├── common/                    # Common infrastructure
│   ├── enums/ResponseEnum     # Unified response code enum
│   └── response/              # ApiResponse<T>, PageResult
├── config/                    # Spring Configuration
│   ├── DeepSeekConfig         # API Key, Model, URL config
│   ├── NetworkToolConfig      # Network tool config (auto proxy / search engines / UA pool)
│   ├── OpenApiConfig          # Swagger/OpenAPI docs
│   └── SpaConfig              # SPA routing fallback
├── controller/                # REST API Controllers (25) + GlobalExceptionHandler
│   ├── DeepSeekController     # ★Core: Chat, Agent task status
│   ├── ConversationController # Conversation CRUD + by-round pagination (messages/page)
│   ├── GitController          # Git operations
│   ├── P2pController          # P2P remote collaboration
│   ├── SnapshotController     # Snapshot management
│   ├── SkillController        # Skill CRUD
│   ├── AgentConfigController  # Agent config management
│   ├── LLMProviderController  # LLM Provider CRUD
│ │   ├── McpServerController    # MCP server management
│ │   ├── LessonController       # Lesson (experience) management
│ │   ├── PromptOptimizeController # Prompt optimization
│ │   ├── ToolRegistryController # Tool registry query
│   ├── ProjectController      # Project file browsing
│   ├── ProjectBuildController # Project build/compile
│   ├── UserController         # User management
│   ├── MenuController         # Menu permissions
│   ├── RoleController         # Role management
│   ├── ConfigController       # System config (API Key, etc.)
│   ├── LogController          # Log query
│   ├── SystemController       # System info (data stats / export-import / reset + GET /system/version)
│   ├── DbConnectionController # External DB connections (Phase 20)
│   ├── FileAssetController    # File assets (upload/list/reference, vision-files-api)
│   ├── ProviderBalanceController # Provider balance query
│   ├── AppearanceController    # Appearance settings (background image upload/serve)
│   ├── AvatarController        # Avatar upload/serve
│   ├── SubAgentController      # Sub-agent run records (GET /api/sub-agents, reads sub_agent_log)
│   └── ScheduleTaskController # Scheduled tasks
├── filter/                    # TokenAuthenticationFilter
├── initializer/               # UserInitializer (first-launch admin setup)
├── mapper/                    # MyBatis Mapper Interfaces (24)
│   ├── ConversationMapper / ConversationMessageMapper / CompactionMapper
│   ├── AgentConfigMapper / SkillMapper / SubAgentLogMapper
│   ├── UserMapper / MenuMapper / RoleMapper / RoleMenuMapper
│   ├── ScheduleTaskMapper / SysConfigMapper
│   └── ProviderConfigMapper (LLM Provider) / McpServerMapper (MCP)
│ │   ├── LessonMapper / LessonNormCacheMapper / LessonRuleMapper (Growth)
│ │   ├── DbConnectionMapper / FileAssetMapper / FileReferenceMapper
│ │   └── P2p* (4: Auth/Session/Message/KnownPeer)
├── model/
│   ├── entity/                # Database Entities (26)
│   │   ├── Conversation / ConversationMessage / CompactionRecord
│   │   ├── User / Menu / Role / RoleMenu / SysConfig
│   │   ├── AgentConfig / AgentTask / Skill / SubAgentLog
│   │   ├── ScheduleTask / MessageRole / ProviderConfig / McpServerConfig
│ │   │   ├── Lesson / LessonNormCache / LessonRule（Growth System）
│ │   │   ├── DbConnection / FileAsset / FileReference
│ │   │   └── P2pAgentAuthorization / P2pAgentConversation /
│ │   │       P2pChatMessage / P2pKnownPeer
│   ├── dto/                   # Request/Response DTOs
│   └── vo/                    # View Objects
├── p2p/                       # ⚡P2P Remote Collaboration Subsystem
│   ├── controller/P2pController   # REST API (39.7KB)
│   ├── agent/P2pAgentService      # Agent remote invocation (34.9KB)
│   ├── connection/                # P2pServer / P2pClient / ConnectionPool
│   ├── message/                   # HandshakeHandler / MessageRouter
│   ├── protocol/                  # MessageType / P2pConstants / Codecs
│   ├── security/                  # TlsHelper / CryptoHelper
│   ├── signaling/                 # QrCodeSignaling / ConnectionStringHelper
│   └── service/P2pChatService
├── scheduler/                 # Scheduled Tasks
│   ├── ScheduleTaskScheduler  # Cron/One-time task scheduling
│   └── RunningProcessManager  # Subprocess lifecycle management (8KB)
├── service/                   # Business Interfaces + Implementations
│   ├── DeepSeekService        # Core chat service interface
│   ├── ShellDiscoveryService  # Smart Shell discovery (Win/Mac/Linux multi-shell)
│   ├── files/                 # ★File assets & Files API adapter (upload/vision/reference)
│   ├── DataTransferService    # Backup export/import (data management)
│   ├── AvatarStore / BackgroundStore # Avatar & background image local storage
│   ├── llm/                   # LLM client layer (LLMClient + 6 providers + ProviderBalanceService + LLMErrorFormatter + ToolCallSequenceSanitizer + DeepSeekToolsApiClient)
│   └── impl/
│       ├── DeepSeekServiceImpl    # ★★★Core Engine (187KB)
│       ├── ToolLoopManager        # Tool loop (dead loop / judge / cancel)
│       ├── AgentForkManager       # Sub-agent lifecycle
│       ├── CompactionService      # Context compaction (3-tier + async)
│       ├── ContextBuilder         # Message assembly + Token estimation
│       ├── AgentEventBus          # Agent event bus (SSE push)
│       ├── MessagePersister       # Async message persistence
│       ├── SnapshotService        # Code snapshot backup/rollback (32.2KB)
│       ├── ProjectBuildService    # Project build
│       ├── SkillCatalogBuilder    # Skill catalog builder (progressive disclosure L1)
│       ├── lesson/                 # ★Growth System: Lesson Knowledge Base (per-project, zero context cost)
│       │   ├── LessonService       # CRUD + on-demand retrieval + state machine (48KB)
│       │   ├── LessonRecorder      # Auto-capture drafts / LLM proactive record
│       │   ├── LessonReviewService # Turn-level async review (root cause/solution, C2)
│       │   └── FailureNormalizer   # Error-code normalization + signature dedup (error_signature)
│ │       │   ├── LessonNormalizerService # P0 normalization pipeline (rules + LLM fallback + cache)
│ │       │   ├── LessonDetourService # P1 detour extraction & persistence (C3)
│ │       │   ├── DetourSignalDetector # Detour signal scanning
│ │       │   ├── DetourBlockParser  # 【方案取舍】block parser
│ │       │   └── ErrorCodeDictionary # Error-code dictionary (yml config)
│       └── ...
├── tool/                      # ⚡AI Agent Tool System
│   ├── Tool.java              # Tool interface
│   ├── ToolRegistry           # Tool registry (singleton)
│   ├── ToolExecutor           # Tool execution engine
│   ├── ToolInitializer        # Auto-init all tools on startup
│   ├── impl/                  # 27 tool implementations (new naming, one tool many actions)
│   │   ├── File: file_explorer, file_writer
│   │   ├── Command: command
│   │   ├── Network: web_search, web_fetch, http_request, check_network
│   │   ├── Database: execute_sql
│   │   ├── Git: git_query, git_submit, git_branch, git_stash, git_tag, git_remote
│   │   ├── Agent: agent, agent_invoke (Phase 19: invoke/poll/cancel)
│   │   ├── Skill: skill
│   │   ├── Lesson: lesson  ★Growth System (search/record/complete/feedback/list)
│   │   ├── Project: project_info
│   │   ├── Task: task_manager
│   │   ├── Interaction: ask_clarification, chat_attachment, query_tool_history
│   │   ├── Schedule: schedule_task
│   │   ├── MCP Manager: mcp_server_manager
│   │   ├── Desktop: screen_capture, desktop_control (Computer Use)
│   │   └── History: query_tool_history
│   ├── desktop/               # Desktop automation (platform adapter/CaptureContext/verify)
│   ├── permission/            # Permission control
│   └── postedit/              # Post-processing pipeline
├── log/LogService             # Application-level logging (11KB)
└── util/                      # Utility classes
    ├── CommandUtils           # Smart Shell discovery + Command execution (22.2KB)
    ├── DiffUtil               # LCS diff calculation
    ├── FileEncodingDetector   # Encoding detection (10.2KB)
    └── ...
```

---

## 6. Database ER Summary

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
         │                │ confidence (Bayesian)
         │                └──────────────────────┘
         │
┌────────┴──────────────────────┐   ┌──────────────────────┐
│ conversation_message          │   │ conversation_compaction│
│ ─────────────────────        │   │ ───────────────────── │
│ id (PK)                       │   │ id (PK)              │
│ conversation_id (FK) ─────────┼───│ conversation_id (FK)  │
│ role (system/user/            │   │ summary               │
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
│ status            │      │   cancelled/...)     │
│ instructions     │      │ iteration, event_cnt │
│ full_messages    │      │ pending_question      │
│ file_changes     │      └──────────────────────┘
│ compile_result   │
└──────────────────┘

┌─────────────────────────────── P2P Related ───────────────────────┐
│ p2p_chat_message          p2p_agent_authorization                  │
│ ─────────────────         ──────────────────────                   │
│ peer_id, content          peer_id, agent_config_id                 │
│ direction (sent/recv)     direction (sent/received)                │
│ message_type              status (active/cancelled)                 │
│                                                                     │
│ p2p_agent_conversation    p2p_known_peer                            │
│ ────────────────────      ──────────────                            │
│ peer_id + agent_id        peer_id, name, address                    │
│ + conversation_id         last_seen                                 │
└─────────────────────────────────────────────────────────────────────┘

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
                          │
                          │ ┌──────────────────────────────────┐
                          │ │ lesson (Growth System)            │
                          │ │ ──────────────────────────────    │
                          │ │ id (PK)                            │
                          │ │ project_key (isolation)
│                           │ │ type (FAILURE/DETOUR)               │
│                           │ │ goal (detour task goal)             │
│                           │ │ env_params (env-aware retrieval)    │           │
                          │ │ tool_name + error_category         │
                          │ │ error_code + error_signature (UQ) │
                          │ │ symptom/root_cause/solution        │
                          │ │ status (0draft/1active/2hidden)    │
                          │ │ source (auto/llm/manual/review)           │
                          │ │ hit/success/fail_count

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
│ file_id (remote ID)      │   │ file_asset_id (FK)       │
│ provider_code / user_id  │   │ conversation_id          │
│ filename / display_name  │   │ message_id (backfilled)  │
│ mime_type / size         │   │ created_at               │
│ local_path / storage_type│   └──────────────────────────┘
│ source / status / orphan │
│ expires_at / created_at  │
└──────────────────────────┘

┌────────────────────────┐   ┌──────────────────────────┐
│ lesson_norm_cache      │   │ lesson_rule              │
│ ────────────────────── │   │ ───────────────────────  │
│ id (PK)                │   │ id (PK)                  │
│ cache_key (UQ, md5)    │   │ pattern (error text)     │
│ category / error_code  │   │ category / error_code    │
│ expires_at             │   │ status (candidate/active)│
└────────────────────────┘   │ match_hit_count (usage)  │
                             └──────────────────────────┘             │
                          │ └────────────────────────────────────┘
```

---

## 7. Module Responsibility Quick Reference

| Module | One-Liner | Key Class | Est. Lines |
|--------|----------|-----------|------------|
| **AI Core Engine** | Conversation mgmt, API calls, tool loop | DeepSeekServiceImpl | ~4600 |
| **Tool Loop** | Dead loop detection, judge, SSE events | ToolLoopManager | ~560 |
| **Sub-Agent Mgmt** | fork/collect/inspect lifecycle | AgentForkManager | ~1600 |
| **Shell Discovery** | Smart shell detection (Win/Mac/Linux) | ShellDiscoveryService | ~260 |
| **Context Compaction** | LLM summary compression + async pre-compact | CompactionService | ~640 |
| **Message Assembly** | Token estimation + skill catalog injection + language directives | ContextBuilder | ~900 |
| **Snapshot System** | File backup, LCS diff, quota management | SnapshotService | ~800 |
| **P2P Collaboration** | Peer network, agent remote invocation, signaling | P2pAgentService | ~5000 |
| **Tool System** | 27 tools registration/execution/permission/post-edit | tool/ package | ~10000 |
| **File Assets** | Upload/local storage/Files API adapter/vision understanding | FileAssetService + FilesApiClientManager | ~900 |
| **Desktop Automation** | Screenshot (grid/scale ticks)/mouse & keyboard/coordinate preflight | ScreenCaptureTool + DesktopControlTool | ~1500 |
| **Growth System** | Retrieval/capture/review/normalization/detour | LessonService + LessonNormalizerService + LessonDetourService | ~4100 |
| **User Permissions** | RBAC, Token auth, menu control | UserServiceImpl + Filter | ~500 |
| **Scheduled Tasks** | Cron/one-time scheduling, execution tracking | ScheduleTaskScheduler | ~350 |
| **Skill System** | Skill catalog injection (progressive disclosure), Bayesian confidence | SkillCatalogBuilder + SkillService | ~300 |
| **Multi-LLM Provider** | Provider CRUD, client routing, hot refresh, friendly error formatting | LLMClientManager + LLMClient + 6 implementations + LLMErrorFormatter | ~1900 |
| **External DB Connections** | Connection mgmt / credential encryption / run SQL | DbConnectionService + SqlExecutor | ~600 |
| **Data Management (Backup)** | Config data export/import (UPSERT merge) | DataTransferService | ~400 |
| **Appearance & Avatars** | Background image & avatar local storage/serving | BackgroundStore + AvatarStore | ~300 |
| **Chat History Pagination** | By-round cursor pagination (5 rounds default / 50 max) + frontend lazy loading | ConversationController + ConversationServiceImpl + ConversationMessageMapper | ~250 |
| **Tool-Call Sequence Sanitizer** | Idempotently repairs tool_calls / tool message pairing to avoid HTTP 400 | ToolCallSequenceSanitizer | ~300 |
| **Auto Update (Desktop)** | Releases check / download / install / pre-upgrade backup | electron/update-manager.js + update-utils.js | ~700 |
| **DeepSeek Official Tool APIs** | Official search/fetch first, fallback to legacy pipeline | DeepSeekToolsApiClient + SearchTool + WebFetchTool | ~500 |

---

## 8. Key Conventions & Notes

1. **API Port**: Default `8084` (`server.port` in application.yml)
2. **H2 Console**: `http://localhost:8084/h2-console`, JDBC URL: `jdbc:h2:file:./data/codecraft;MODE=MySQL;DB_CLOSE_DELAY=-1` (user `sa`, empty password)
3. **Default Admin**: Auto-created by `UserInitializer` on first launch
4. **Password Encryption**: MD5 (32-bit), not bcrypt — upgrade recommended for security-sensitive scenarios
5. **Tool Return Format**: Unified `ApiResponse<T>`, code referenced from `ResponseEnum`
6. **SSE Events**: OpenAI-compatible streaming format (`choices[].delta.content` / `reasoning_content`) + custom events `tool_call_start` / `ask_user` / `delegate_progress` / `task_error`
7. **Sub-Agent Concurrency Limit**: 20 (hardcoded in AgentForkManager)
8. **Tool Loop Max Iterations**: 50 (the judge may extend; cumulative extension capped at +100, and hitting the cap asks the user instead of terminating)
9. **Snapshot Quota**: 500MB limit, auto-clean to 300MB when exceeded
10. **Token Estimation Coefficients**: Chinese ~1.5, English ~1.0, Digits ~0.5, Emoji ~0.5
11. **Tool-Call Sequence Pairing (hard constraint)**: `assistant.tool_calls` and `role=tool` messages must be strictly one-to-one; a single mismatch returns 400 and permanently poisons the conversation. Every LLM request exit must pass through `ToolCallSequenceSanitizer` (idempotent, copy-on-write) — any "remove one side only" patch is wrong.
12. **Chat Message API**: always use `GET /conversation/{id}/messages/page` (by-round cursor) instead of the full `GET /conversation/{id}/messages` for long conversations.
13. **Auto Update**: `pom.xml` is the single source of truth for the version (`scripts/sync-version.*` syncs to electron); GitHub Releases asset names must match `CodeCraft-Setup-{version}.exe` / `CodeCraft-{version}.dmg` / `CodeCraft-{version}.AppImage`.

---

## 9. Known Technical Debt

| Issue | Severity | Recommendation |
|-------|----------|---------------|
| DeepSeekServiceImpl 187KB monolith | 🔴 High | Split into ChatOrchestrator / ToolLoopEngine / ResponseStreamer |
| MD5 password storage | 🔴 High | Upgrade to bcrypt or argon2 |
| Frontend lacks tests | 🟡 Medium | Add Vitest unit tests for core components |
| snapshots/ directory bloat | 🟡 Medium | Add periodic cleanup or Git-based snapshots |
| Some config hardcoded | 🟢 Low | Move DeepSeekConfig defaults to yml |

---

## 10. Multi LLM Provider Support

> Detailed documentation: [LLM_PROVIDER_SYSTEM_EN.md](./LLM_PROVIDER_SYSTEM_EN.md)

CodeCraft supports multiple LLM platforms. Core components:

- **llm_provider table**: Stores Provider config (code/name/baseUrl/apiKey/defaultModel/requestTemplate, etc.)
- **LLMClient Interface**: Unified abstraction layer; all Providers must implement it (buildRequestBody/streamChat/extractContent, etc.)
- **LLMClientManager**: Core manager for Provider registration, routing (resolveClientByCode/resolveClientByProviderId), hot refresh
- **10 Provider Templates (v3.0.0)**: `DeepSeekClient` / `GptClient` (OpenAI official) / `OpenAIClient` (protocol contract) / `AnthropicClient` (protocol contract) / `QwenClient` (Alibaba Model Studio) / `VolcengineArkClient` (Volcengine Ark) / `OllamaClient` / `MiMoClient` / `MiniMaxClient` / `custom` (OpenAIClient minimal mode), all extending `AbstractLLMClient`; each class carries a "适用范围（选型指引）" Javadoc block
- **Agent Binding**: agent_config table gains provider_id/provider_code fields; each Agent can be bound to a specific Provider
- **Frontend Dynamic Switching**: CodeAssistantView supports runtime Provider switching with automatic model list refresh
- **Provider Routing Priority**: Frontend dynamic providerCode > Agent config providerId > First available Provider
- **DeepSeek Official Tool APIs (v2.0.3)**: `DeepSeekToolsApiClient` is enabled automatically when the Provider `baseUrl` host is `api.deepseek.com` and an API key is configured. `web_search` / `web_fetch` then prefer the official `/v1/tools/search` and `/v1/tools/fetch` endpoints and fall back to the legacy pipeline (Bing/Baidu, Jina/local) on failure. Detection uses the baseUrl host rather than providerCode, so third-party proxies / self-hosted services are never enabled
- **Platform enhancement capabilities (v3.0.0)**: `llm_provider.extra_config` carries platform credentials (Volcengine AK/SK + search key, Alibaba Cloud AK/SK, MiniMax Coding Plan key, MiMo console cookie); capabilities work on an "opt-in + skip when credentials are missing + always fall back" basis. Balance/quota queries support DeepSeek / Volcengine / Alibaba Cloud (Qwen) / MiniMax / MiMo

---

> 📌 **Doc Maintenance Convention**: When package structure changes, new core modules are added, or technical decisions change, please sync this document.
