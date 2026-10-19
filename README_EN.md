> 🌐 中文版：[🇨🇳 README](./README.md)
# CodeCraft - AI-Powered Desktop Programming Assistant 🤖

<p align="center">
  <img src="https://img.shields.io/badge/version-3.0.1-7C5CFC" alt="v3.0.1">
  <img src="https://img.shields.io/badge/Java-17-brightgreen" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-blue" alt="Spring Boot 3.4">
  <img src="https://img.shields.io/badge/Vue-3.4-42b883" alt="Vue 3.4">
  <img src="https://img.shields.io/badge/Electron-34.5-47848f" alt="Electron 34.5">
  <img src="https://img.shields.io/badge/Multi--LLM-DeepSeek%20%7C%20GPT%20%7C%20Claude%20%7C%20Qwen%20%7C%20Ark%20%7C%20Ollama%20%7C%20MiMo%20%7C%20MiniMax-4f46e5" alt="Multi-LLM">
</p>

## 📖 Project Overview

**CodeCraft** is a desktop intelligent programming assistant powered by **multiple LLMs** (DeepSeek / GPT / OpenAI-compatible / Anthropic / Qwen / Volcengine Ark / Ollama / MiMo / MiniMax, etc.). Users interact with AI through a chat interface, and AI automatically invokes **27 tools** (file operations, command execution, network requests, database queries, Git version control, agent collaboration, skill & lesson management, desktop automation, etc.) to complete programming tasks. It supports sub-agent parallel collaboration, dynamic LLM Provider switching, and Agent-level Provider binding.

**Core features:**
- 🌐 **Multi-LLM Provider Support**: 10 provider templates — DeepSeek, GPT (OpenAI official), OpenAI (protocol contract), Anthropic (protocol contract), Qwen (Alibaba Model Studio), Volcengine Ark (Doubao), Ollama, MiMo, MiniMax, and Custom — with a unified LLMClient interface and runtime dynamic switching. **OpenAI / Anthropic are protocol-contract templates**: any service that follows the spec (Azure, OpenRouter, proxies, vLLM / LM Studio / One-API gateways, GLM / DeepSeek / MiniMax `/anthropic` layers) plugs in directly, with automatic protocol conversion (Anthropic `image_url → image/source`, tool schema translation). Dedicated templates (GPT / DeepSeek / Ark / Qwen) enable vendor-specific parameters (`reasoning_effort` / `thinking.type` / `enable_thinking`, `max_completion_tokens`, vision, web search). Enhanced capabilities are opt-in, skipped when credentials are missing, and always fall back: search (DeepSeek official, Ark, MiniMax) and balance/quota (DeepSeek, Volcengine AK/SK, Alibaba Cloud AK/SK, MiniMax quota, MiMo console cookie)
- 🗣️ **Natural Language Programming**: Just describe what you need, AI plans and executes automatically
- 🧰 **27-Tool Ecosystem**: File operations, commands, network, database, Git, agents, skills & lessons, desktop automation — covering the full development workflow
- 🧩 **Task Decomposition & Parallel Sub-Agents**: Complex tasks are automatically decomposed, sub-agents work in parallel
- 🤝 **Agent-to-Agent Invocation**: Agents can delegate tasks to other agent instances in independent sessions (create/continue); delegation = authorization, max depth 3
- 🔄 **Auto Error Correction**: Auto-retries on failure, automatically switches alternatives
- 🎯 **Skill System**: Create reusable skills; a lightweight catalog of all skills is injected into the system prompt (progressive disclosure) with full instructions loaded on demand, confidence evolves over use
- 📚 **Growth System (Lesson Knowledge Base)**: Failed tool calls are auto-captured as lessons (per-project, on-demand retrieval); solution hints are injected on recurring errors; a feedback loop promotes/hides experiences automatically
- 🗄️ **Data Management**: Connect external MySQL / PostgreSQL / H2 databases on the "DB Connections" page (with a built-in "Run SQL" tool); AI queries them via the `execute_sql` connection parameter; one-click config backup export/import (7 data categories) on the "Data Management" page
- 🖼️ **Vision & File Management**: Paste (Ctrl+V) or upload images (≤64MB) in chat for LLM understanding via the cloud Files API (file_id references; DeepSeek / Anthropic adapter); standalone File Manager page with full CRUD, session reference and permanent retention; documents (PDF/Word/Excel) stored locally in the same asset system
- 🪟 **Desktop Automation (Computer Use)**: `screen_capture` (grid-overlay screenshots, auto-upload + context injection) and `desktop_control` (mouse/keyboard simulation, 1-20 batch actions) — the "capture → locate → act → verify" loop, guarded by the DESKTOP high-risk permission and crosshair preflight
- 📎 **In-App Viewer**: File paths, images and links in AI replies are clickable — opened as in-app tabs (file / image / embedded web page) without leaving the app
- 💬 **Long-Conversation Rendering**: Chat history is lazy-loaded by round (latest 5 rounds first, 5 more when scrolling to the top) with a virtual list and ResizeObserver-based height self-heal — no overlapping bubbles, misplacement or scrollbar jumps even in thousand-message sessions
- 🖌️ **Appearance**: Global background image (≤10MB) with background opacity / blur / window overall opacity sliders; the title bar and whole window become translucent in sync
- 🖥️ **Desktop App**: Electron + built-in JRE, zero-installation ready to use
- 🔄 **Auto Update**: The desktop app checks GitHub Releases 15 seconds after startup (6-hour cache). v3 adds a dedicated **Version & Updates page** (`/about`): client version and backend version shown side by side, release notes rendered as markdown, progress-tracked download, one-click install (Windows launches the installer and restarts; macOS opens the dmg for drag-and-drop; Linux reveals the package), mirror acceleration with automatic direct fallback, and configurable auto-check / mirror prefix / backup retention; in a browser it degrades to a read-only version card. Upgrades keep all local data — the backend cwd lives in `userData/runtime` (untouched by the installer), the database is backed up before upgrading, and the installer backs up legacy data on the first upgrade
- 🌐 **P2P Remote Collaboration**: Peer-to-peer encrypted channels for remote agent invocation between devices
- 💾 **Snapshot System**: Auto-backup before code changes, support multi-granularity rollback
- 🔐 **Security**: Three-layer permission pipeline + path traversal check + audit logging

## 🚀 Quick Start

### Method 1: Download Installer (Recommended)

Go to [Releases](https://github.com/zb614433612/CodeCraft/releases) page to download the latest installer for your platform.

| Platform | Installer |
|----------|----------|
| Windows | `CodeCraft-Setup-{version}.exe` |
| macOS | `CodeCraft-{version}.dmg` |
| Linux | `CodeCraft-{version}.AppImage` / `.deb` |

> Already installed? No need to reinstall — the app checks for updates automatically after startup, or use "Data Management → Version & Updates" for a one-click upgrade that backs up and keeps all local data.

### Method 2: Run from Source

```bash
# 1. Clone repository
git clone https://github.com/zb614433612/CodeCraft.git
cd CodeCraft

# 2. Build & Run (requires JDK 17+)
mvn clean package -DskipTests && mvn spring-boot:run

# 3. Open browser
#    http://localhost:8084
```

> 📌 See [BUILD_AND_RUN.md](BUILD_AND_RUN.md) for detailed setup instructions.

### First Launch Configuration

After the first launch, go to the **Model Config** page, add an **LLM Provider** (supports DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax, etc.) to start using AI features.

Default admin account: `admin` / `123456`

## 🏗️ Project Architecture

```
CodeCraft
├── src/main/java/.../              # Java Backend (Spring Boot 3.4)
│   ├── controller/                 # REST API Controllers (23)
│   ├── service/impl/               # Core Business Logic
│   │   ├── DeepSeekServiceImpl     # ★ AI Engine Core (187KB)
│   │   ├── ToolLoopManager         # Tool Call Loop Engine
│   │   ├── AgentForkManager        # Sub-Agent Lifecycle
│   │   └── CompactionService       # Context Compaction
│   ├── service/llm/                # ★ LLM Client Layer (LLMClient + 6 Provider impls)
│   ├── service/lesson/             # Growth System (retrieval/record/review/normalizer)
│   ├── service/agentinvoke/        # Agent-to-Agent Invocation (delegation/trust chain)
│   ├── service/dbconnection/       # External DB connections (config/AES-GCM/manager)
│   └── service/files/          # File Assets & Files API adapter (vision pipeline)
│   ├── tool/                       # AI Agent Tools (27 tools)
│   ├── p2p/                        # ⚡ P2P Remote Collaboration
│   │   ├── agent/                  # P2pAgentService, Handlers
│   │   ├── connection/             # Netty Server/Client, ConnectionPool
│   │   ├── protocol/               # MessageFrame, MessageType
│   │   ├── security/               # TlsHelper, CryptoHelper
│   │   └── signaling/              # QR Code Signaling, Connection String
│   ├── model/entity/               # Database Entities (26, incl. ProviderConfig/McpServerConfig/Lesson/FileAsset)
│   ├── mapper/                     # MyBatis Mappers (24)
│   └── config/                     # Spring Configuration
├── frontend/                       # Vue 3 Frontend (TypeScript)
│   ├── src/
│   │   ├── views/                  # Page Views (16)
│   │   │   ├── CodeAssistantView   # ★ Main Chat + Coding Interface
│   │   │   ├── AgentConfigView     # Agent Configuration Management
│   │   │   └── ...
│   │   ├── components/             # Common Components (16)
│   │   │   ├── ChatView            # Chat Message Rendering (SSE)
│   │   │   ├── AgentPanel          # Sub-Agent Status Panel
│   │   │   ├── FileTree            # File Browser
│   │   │   ├── GitSidebar          # Git Diff/Commit Sidebar
│   │   │   └── ...
│   │   └── api/                    # API Call Modules (23, incl. llm-provider / file-asset API)
│   └── ...
├── electron/                       # Electron Desktop Shell (splash screen / transparent title bar / window opacity / auto update)
│   ├── main.js                     # Main process (splash / window opacity / backend lifecycle)
│   ├── update-manager.js           # Auto update (GitHub Releases check / download / install / pre-upgrade backup)
│   ├── update-utils.js             # Version comparison & release asset matching
│   ├── installer.nsh               # NSIS hook (back up legacy data before first upgrade)
│   └── loading.html                # Splash screen
├── docs/                           # Project Documentation
│   ├── ARCHITECTURE.md             # Architecture Panorama
│   ├── LLM_PROVIDER_SYSTEM.md     # ★ Multi-LLM Provider System
│   ├── DEEPSEEK_SERVICE_IMPL.md    # Core Engine Deep Dive
│   ├── P2P_SYSTEM.md               # P2P System Deep Dive
│   ├── SNAPSHOT_SYSTEM.md          # Snapshot System Deep Dive
│   ├── TOOL_SYSTEM.md              # Tool System Deep Dive
│   ├── DEV_QUICKREF.md             # Dev Quick Reference
│   ├── RELEASE_CHECKLIST.md        # Release Checklist
│   └── FAQ.md                      # Frequently Asked Questions
└── scripts/                        # Build Scripts
    ├── build.bat                   # Windows One-Click Build
    └── build.sh                    # macOS/Linux One-Click Build
```

## 🧰 Tool Ecosystem (27 Tools)

| Category | Tool | Typical Usage |
|----------|------|--------------|
| **File** | `file_explorer` | Read files, glob search, grep, directory tree |
| **File** | `file_writer` | Create, edit, delete files |
| **Command** | `command` | Execute commands, start/stop/list/logs services |
| **Network** | `web_search` / `web_fetch` / `http_request` / `check_network` | Web search, page fetch, API calls, connectivity check |
| **Database** | `execute_sql` | Database query and modification (system DB or configured external connection) |
| **Git** | `git_query` / `git_submit` / `git_branch` / `git_stash` / `git_tag` / `git_remote` | Git status, diff, log, show, add, commit, push/pull/fetch, branch/merge, stash, tag & remote management |
| **Agent** | `agent` | Sub-agent fork, collect, inspect |
| **Agent** | `agent_invoke` | Agent-to-agent delegation: invoke/poll/cancel, independent session |
| **Skill** | `skill` | Skill CRUD and result reporting |
| **Project** | `project_info` | Maven/npm project structure and dependencies |
| **Task** | `task_manager` | Task lifecycle management |
| **Interaction** | `ask_clarification` | Clarify ambiguous requirements |
| **Attachment** | `chat_attachment` | Read PDF/Word/Excel attachments |
| **Schedule** | `schedule_task` | Scheduled task management |
| **History** | `query_tool_history` | Query tool call history in current session |
| **Lesson** | `lesson` | Pitfall experience knowledge base (search/record/complete/feedback/list) |
| **MCP** | `mcp_server_manager` | Manage MCP servers (create/delete/connect/disconnect/refresh) |
| **Desktop** | `screen_capture` / `desktop_control` | Screen capture (grid overlay) and mouse/keyboard automation (Computer Use, batch actions) |

## 🗄️ Data Management (External DB Connections / Backup & Restore)

Configure external MySQL / PostgreSQL / H2 business databases on the "DB Connections" page (passwords AES-256-GCM encrypted; test-connection and a built-in "Run SQL" tool supported). AI then targets them via the `execute_sql` `connection` parameter — omit it to use the built-in CodeCraft system DB (backward compatible). The "Data Management" page also offers one-click config backup export / import (7 data categories), so re-install and cross-device migration need no reconfiguration.

## 🖼️ Vision & File Management

Paste (Ctrl+V) or upload images directly in chat — images go to the cloud Files API (file_id references) for LLM vision understanding, with DeepSeek / Anthropic adapter support. A standalone **File Manager** page provides full CRUD, "reference to session", and permanent retention. Documents (PDF / Word / Excel / text) are stored locally and unified under the same asset system (`storage_type=cloud|local`).

## 🪟 Desktop Automation (Computer Use)

`screen_capture` captures the screen (resolution-normalized, auto-uploaded, injected into context as a user message) with a 10×10 grid + 0–1000 normalized coordinate scale; `desktop_control` then simulates mouse/keyboard actions (move/click/drag/scroll/key/type, 1-20 batch actions per call). Click/drag coordinates pass a crosshair preflight check ("verify before execute"). Guarded by the DESKTOP high-risk permission category.

## 🎯 Skill System

Skills are reusable instruction templates. A lightweight catalog of all active skills (name / ID / confidence / trigger words / one-line description) is injected into the system prompt — the LLM keeps a global view of every skill (progressive disclosure) — while full execution instructions are loaded on demand via `skill action=load`. Each skill includes:
- **Trigger Words**: Guide the LLM to pick the right skill from the catalog
- **Execution Instructions**: Detailed step-by-step procedures, loaded only when needed
- **Confidence Score**: Bayesian smoothing formula, auto-adjusts based on usage feedback

The system ships with 12+ built-in skills covering code submission, bug fixing, documentation, testing, and more.

## 👥 Sub-Agent Parallel Collaboration

Main agent can `fork` up to 20 sub-agents to work in parallel on independent modules, then `batch_collect` all results:

```
Main Agent (user conversation)
    ├── fork → Sub-Agent 1: "Write UserService"
    ├── fork → Sub-Agent 2: "Write OrderService"
    ├── fork → Sub-Agent 3: "Write PaymentService"
    │         ... (up to 20 concurrent)
    └── batch_collect → Summarize all results
```

## 🤝 Agent-to-Agent Invocation

Agents can also **delegate** tasks: Agent A calls another agent instance B (agent_config) through the `agent_invoke` tool — B works in an **independent session** as itself (own role / model / work dir / toolset). Supports `create_session` / `continue_session` / `poll` / `cancel`. **Delegation = authorization**: a single approval covers B's whole run (trust propagates along the chain, cycle detection + depth ≤ 3); stopping A cascades cancellation to B. B's delegated sessions appear in its conversation list (prefixed with "[A 委托]"). See `docs/AGENT_INVOKE_DESIGN.md`.

## 📝 Tech Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| **Backend** | Spring Boot 3.4 + MyBatis | Java 17 |
| **AI Communication** | WebFlux + SSE | Streaming output |
| **Multi-LLM Support** | LLMClient Abstraction Layer | DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax |
| **Database** | H2 (Embedded) | Zero-config deployment |
| **Cache** | Caffeine | In-memory, zero-dependency |
| **Frontend** | Vue 3 + TypeScript + Vite | Composition API |
| **Desktop** | Electron 34 | Built-in JRE, cross-platform |
| **P2P Network** | Netty + BouncyCastle | TLS encrypted P2P channel |
| **Build** | Maven + npm | Unified build pipeline |

## 📄 Documentation

| Document | Reader | Description |
|----------|--------|-------------|
| [ARCHITECTURE.md](./docs/ARCHITECTURE.md) | Developers / AI | Architecture panorama: topology, data flow, package structure, ER diagram |
| [LLM_PROVIDER_SYSTEM.md](./docs/LLM_PROVIDER_SYSTEM.md) | Developers / AI | Multi-LLM Provider system: architecture, data model, routing, extension guide |
| [DEEPSEEK_SERVICE_IMPL.md](./docs/DEEPSEEK_SERVICE_IMPL.md) | Developers / AI | Core engine: method call topology, Tool Loop state machine, SSE events |
| [P2P_SYSTEM.md](./docs/P2P_SYSTEM.md) | Developers / AI | P2P: protocol stack, connection lifecycle, agent remote invocation |
| [TOOL_SYSTEM.md](./docs/TOOL_SYSTEM.md) | Developers / AI | Tool system: architecture, permission pipeline, how to add a tool |
| [SNAPSHOT_SYSTEM.md](./docs/SNAPSHOT_SYSTEM.md) | Developers / AI | Snapshot: storage layout, backup/rollback/diff, quota management |
| [DEV_QUICKREF.md](./docs/DEV_QUICKREF.md) | Developers | Quick reference: ports, commands, config, database |
| [RELEASE_CHECKLIST.md](./docs/RELEASE_CHECKLIST.md) | Maintainers | Release checklist: 7-step process, versioning, FAQs |
| [FAQ.md](./docs/FAQ.md) | All users | Common issues and solutions |

## 📦 Release Versions

See [CHANGELOG.md](./CHANGELOG.md) for detailed release notes.

## 🤝 Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).

## 📜 License

MIT License

---

**Made with ❤️ by CodeCraft Team**
