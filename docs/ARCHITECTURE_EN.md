> 🌐 中文版：[🇨🇳 ARCHITECTURE](./ARCHITECTURE.md)
# CodeCraft Architecture Panorama

> Version: v1.0.5 | Updated: 2026-05-28 | Audience: Developers / AI Collaborators
> This document aims to help new developers (including AI Agents) build a complete cognitive map of the project within 5 minutes.

---

## 1. One-Sentence Definition

**CodeCraft** is an AI Agent-based desktop intelligent programming assistant. Users communicate programming tasks to AI through a chat interface, and AI automatically invokes **19 tools** (file read/write, command execution, Git operations, web search, etc.) to complete tasks, supporting sub-agent parallel collaboration.

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
│ │ │        Controller Layer (16 Controllers)    │                      │ │
│ │ │ DeepSeekController / GitController /       │                      │ │
│ │ │ P2pController / SnapshotController / ...   │                      │ │
│ │ └─────────────────────┬──────────────────────┘                      │ │
│ │                       │                                             │ │
│ │ ┌─────────────────────┴──────────────────────┐                      │ │
│ │ │         Service Core Layer                  │                      │ │
│ │ │                                            │                      │ │
│ │ │  DeepSeekServiceImpl (129KB, Core Engine)  │                      │ │
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
│    └─ Matched skill injection (SkillMatcher)                           │
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
│    │ ├─ Infinite Loop Detection: 4 consecutive identical tool     │    │
│    │ │   calls → terminate                                         │    │
│    │ ├─ Judge Mechanism: exceeds max iterations → Judge evaluates │    │
│    │ │   whether to continue                                       │    │
│    │ └─ User Cancel Detection: check interruption each round       │    │
│    └──────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

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

---

## 4. Technology Choices & Key Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| **Database** | H2 (Embedded) | Desktop app needs zero-config deployment, no MySQL required |
| **Cache** | Caffeine | Replaces Redis, zero-dependency out of the box |
| **AI Communication** | WebFlux + SSE | Supports streaming output, users see AI typing in real-time |
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
│   ├── NetworkToolConfig      # Proxy/Network config
│   ├── OpenApiConfig          # Swagger/OpenAPI docs
│   └── SpaConfig              # SPA routing fallback
├── controller/                # REST API Controllers (16)
│   ├── DeepSeekController     # ★Core: Chat, Agent task status
│   ├── ConversationController # Conversation CRUD
│   ├── GitController          # Git operations
│   ├── P2pController          # P2P remote collaboration
│   ├── SnapshotController     # Snapshot management
│   ├── SkillController        # Skill CRUD
│   ├── AgentConfigController  # Agent config management
│   ├── ToolRegistryController # Tool registry query
│   ├── ProjectController      # Project file browsing
│   ├── ProjectBuildController # Project build/compile
│   ├── UserController         # User management
│   ├── MenuController         # Menu permissions
│   ├── RoleController         # Role management
│   ├── ConfigController       # System config (API Key, etc.)
│   ├── LogController          # Log query
│   └── ScheduleTaskController # Scheduled tasks
├── filter/                    # TokenAuthenticationFilter
├── initializer/               # UserInitializer (first-launch admin setup)
├── mapper/                    # MyBatis Mapper Interfaces (26)
│   ├── ConversationMapper / ConversationMessageMapper
│   ├── AgentConfigMapper / SkillMapper / SubAgentLogMapper
│   ├── UserMapper / MenuMapper / RoleMapper / RoleMenuMapper
│   ├── ScheduleTaskMapper / SysConfigMapper
│   └── P2p* (4: Auth/Session/Message/KnownPeer)
├── model/
│   ├── entity/                # Database Entities (14)
│   │   ├── Conversation / ConversationMessage / CompactionRecord
│   │   ├── User / Menu / Role / RoleMenu / SysConfig
│   │   ├── AgentConfig / AgentTask / Skill / SubAgentLog
│   │   ├── ScheduleTask / MessageRole
│   │   └── P2pAgentAuthorization / P2pAgentConversation /
│   │       P2pChatMessage / P2pKnownPeer
│   ├── dto/                   # Request/Response DTOs
│   └── vo/                    # View Objects
├── p2p/                       # ⚡P2P Remote Collaboration Subsystem
│   ├── controller/P2pController   # REST API (19.8KB)
│   ├── agent/P2pAgentService      # Agent remote invocation (33.4KB)
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
│   └── impl/
│       ├── DeepSeekServiceImpl    # ★★★Core Engine (129KB)
│       ├── ToolLoopManager        # Tool loop (dead loop / judge / cancel)
│       ├── AgentForkManager       # Sub-agent lifecycle
│       ├── CompactionService      # Context compaction (3-tier + async)
│       ├── ContextBuilder         # Message assembly + Token estimation
│       ├── AgentEventBus          # Agent event bus (SSE push)
│       ├── MessagePersister       # Async message persistence
│       ├── SnapshotService        # Code snapshot backup/rollback (19.8KB)
│       ├── ProjectBuildService    # Project build
│       ├── SkillMatcher           # BM25 + trigger word matching
│       ├── SkillIndexer           # Skill index builder
│       └── ...
├── tool/                      # ⚡AI Agent Tool System
│   ├── Tool.java              # Tool interface
│   ├── ToolRegistry           # Tool registry (singleton)
│   ├── ToolExecutor           # Tool execution engine
│   ├── ToolInitializer        # Auto-init all tools on startup
│   ├── impl/                  # 19 tool implementations
│   │   ├── File: file_explorer, file_writer
│   │   ├── Command: command
│   │   ├── Network: web_search, web_fetch, http_request, check_network
│   │   ├── Database: execute_sql
│   │   ├── Git: git_query, git_submit, git_branch
│   │   ├── Agent: agent
│   │   ├── Skill: skill
│   │   ├── Project: project_info
│   │   ├── Task: task_manager
│   │   ├── Interaction: ask_clarification
│   │   ├── Attachment: chat_attachment
│   │   └── Schedule: schedule_task
│   ├── permission/            # Permission control
│   └── postedit/              # Post-processing pipeline
├── log/LogService             # Application-level logging (10.8KB)
└── util/                      # Utility classes
    ├── CommandUtils           # Command execution (13.9KB)
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
```

---

## 7. Module Responsibility Quick Reference

| Module | One-Liner | Key Class | Est. Lines |
|--------|----------|-----------|------------|
| **AI Core Engine** | Conversation mgmt, API calls, tool loop | DeepSeekServiceImpl | ~3000 |
| **Tool Loop** | Dead loop detection, judge, SSE events | ToolLoopManager | ~450 |
| **Sub-Agent Mgmt** | fork/collect/inspect lifecycle | AgentForkManager | ~1400 |
| **Context Compaction** | LLM summary compression + async pre-compact | CompactionService | ~550 |
| **Message Assembly** | Token estimation + skill injection + language directives | ContextBuilder | ~500 |
| **Snapshot System** | File backup, LCS diff, quota management | SnapshotService | ~750 |
| **P2P Collaboration** | Peer network, agent remote invocation, signaling | P2pAgentService | ~2000 |
| **Tool System** | 19 tools registration/execution/permission/post-edit | tool/ package | ~6000 |
| **User Permissions** | RBAC, Token auth, menu control | UserServiceImpl + Filter | ~500 |
| **Scheduled Tasks** | Cron/one-time scheduling, execution tracking | ScheduleTaskScheduler | ~350 |
| **Skill System** | BM25 matching, Bayesian confidence | SkillMatcher + SkillIndexer | ~400 |

---

## 8. Key Conventions & Notes

1. **API Port**: Default `8084` (`server.port` in application.yml)
2. **H2 Console**: `http://localhost:8084/h2-console`, JDBC URL: `jdbc:h2:file:./data/codecraft`
3. **Default Admin**: Auto-created by `UserInitializer` on first launch
4. **Password Encryption**: MD5 (32-bit), not bcrypt — upgrade recommended for security-sensitive scenarios
5. **Tool Return Format**: Unified `ApiResponse<T>`, code referenced from `ResponseEnum`
6. **SSE Event Types**: `thinking` / `text` / `tool_start` / `tool_result` / `error` / `done`
7. **Sub-Agent Concurrency Limit**: 20 (hardcoded in AgentForkManager)
8. **Tool Loop Max Iterations**: 50
9. **Snapshot Quota**: 500MB limit, auto-clean to 300MB when exceeded
10. **Token Estimation Coefficients**: Chinese ~1.5, English ~1.0, Digits ~0.5, Emoji ~0.5

---

## 9. Known Technical Debt

| Issue | Severity | Recommendation |
|-------|----------|---------------|
| DeepSeekServiceImpl 129KB monolith | 🔴 High | Split into ChatOrchestrator / ToolLoopEngine / ResponseStreamer |
| MD5 password storage | 🔴 High | Upgrade to bcrypt or argon2 |
| Frontend lacks tests | 🟡 Medium | Add Vitest unit tests for core components |
| snapshots/ directory bloat | 🟡 Medium | Add periodic cleanup or Git-based snapshots |
| Some config hardcoded | 🟢 Low | Move DeepSeekConfig defaults to yml |

---

> 📌 **Doc Maintenance Convention**: When package structure changes, new core modules are added, or technical decisions change, please sync this document.
