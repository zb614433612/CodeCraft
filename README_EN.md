> 🌐 中文版：[🇨🇳 README](./README.md)
# CodeCraft - AI-Powered Desktop Programming Assistant 🤖

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-brightgreen" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-blue" alt="Spring Boot 3.4">
  <img src="https://img.shields.io/badge/Vue-3.4-42b883" alt="Vue 3.4">
  <img src="https://img.shields.io/badge/Electron-34.5-47848f" alt="Electron 34.5">
  <img src="https://img.shields.io/badge/DeepSeek-API-4f46e5" alt="DeepSeek API">
</p>

## 📖 Project Overview

**CodeCraft** is a desktop intelligent programming assistant based on AI Agent. Users interact with AI through a chat interface, and AI automatically invokes **19 tools** (file operations, command execution, network requests, database queries, Git version control, agent collaboration, skill management, etc.) to complete programming tasks. It supports sub-agent parallel collaboration for complex multi-module development.

**Core features:**
- 🗣️ **Natural Language Programming**: Just describe what you need, AI plans and executes automatically
- 🧰 **19-Tool Ecosystem**: File operations, commands, network, database, Git, agents, skills — covering the full development workflow
- 🧩 **Task Decomposition & Parallel Sub-Agents**: Complex tasks are automatically decomposed, sub-agents work in parallel
- 🔄 **Auto Error Correction**: Auto-retries on failure, automatically switches alternatives
- 🎯 **Skill System**: Create reusable skills, learned patterns accumulate confidence
- 🖥️ **Desktop App**: Electron + built-in JRE, zero-installation ready to use
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

After the first launch, go to **Settings → System Config**, enter your DeepSeek API Key to start using AI features.

Default admin account: `admin` / `123456`

## 🏗️ Project Architecture

```
CodeCraft
├── src/main/java/.../              # Java Backend (Spring Boot 3.4)
│   ├── controller/                 # REST API Controllers (16)
│   ├── service/impl/               # Core Business Logic
│   │   ├── DeepSeekServiceImpl     # ★ AI Engine Core (129KB)
│   │   ├── ToolLoopManager         # Tool Call Loop Engine
│   │   ├── AgentForkManager        # Sub-Agent Lifecycle
│   │   └── CompactionService       # Context Compaction
│   ├── tool/                       # AI Agent Tools (19 tools)
│   ├── p2p/                        # ⚡ P2P Remote Collaboration
│   │   ├── agent/                  # P2pAgentService, Handlers
│   │   ├── connection/             # Netty Server/Client, ConnectionPool
│   │   ├── protocol/               # MessageFrame, MessageType
│   │   ├── security/               # TlsHelper, CryptoHelper
│   │   └── signaling/              # QR Code Signaling, Connection String
│   ├── model/entity/               # Database Entities (14)
│   ├── mapper/                     # MyBatis Mappers (26)
│   └── config/                     # Spring Configuration
├── frontend/                       # Vue 3 Frontend (TypeScript)
│   ├── src/
│   │   ├── views/                  # Page Views (10)
│   │   │   ├── CodeAssistantView   # ★ Main Chat + Coding Interface
│   │   │   ├── AgentConfigView     # Agent Configuration Management
│   │   │   └── ...
│   │   ├── components/             # Common Components (13)
│   │   │   ├── ChatView            # Chat Message Rendering (SSE)
│   │   │   ├── AgentPanel          # Sub-Agent Status Panel
│   │   │   ├── FileTree            # File Browser
│   │   │   ├── GitSidebar          # Git Diff/Commit Sidebar
│   │   │   └── ...
│   │   └── api/                    # API Call Modules (17)
│   └── ...
├── electron/                       # Electron Desktop Shell
├── docs/                           # Project Documentation
│   ├── ARCHITECTURE.md             # Architecture Panorama
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

## 🧰 Tool Ecosystem (19 Tools)

| Category | Tool | Typical Usage |
|----------|------|--------------|
| **File** | `file_explorer` | Read files, glob search, grep, directory tree |
| **File** | `file_writer` | Create, edit, delete files |
| **Command** | `command` | Execute commands, start/stop/list/logs services |
| **Network** | `web_search` / `web_fetch` / `http_request` / `check_network` | Web search, page fetch, API calls, connectivity check |
| **Database** | `execute_sql` | Database query and modification |
| **Git** | `git_query` / `git_submit` / `git_branch` | Git status, diff, log, add, commit, push, branch management |
| **Agent** | `agent` | Sub-agent fork, collect, inspect |
| **Skill** | `skill` | Skill CRUD and result reporting |
| **Project** | `project_info` | Maven/npm project structure and dependencies |
| **Task** | `task_manager` | Task lifecycle management |
| **Interaction** | `ask_clarification` | Clarify ambiguous requirements |
| **Attachment** | `chat_attachment` | Read PDF/Word/Excel attachments |
| **Schedule** | `schedule_task` | Scheduled task management |

## 🎯 Skill System

Skills are reusable instruction templates. Each skill includes:
- **Trigger Words**: Auto-match via BM25 + trigger word algorithm
- **Execution Instructions**: Detailed step-by-step procedures
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

## 📝 Tech Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| **Backend** | Spring Boot 3.4 + MyBatis-Plus | Java 17 |
| **AI Communication** | WebFlux + SSE | Streaming output |
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
