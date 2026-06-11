> 🌐 中文版：[🇨🇳 CHANGELOG](./CHANGELOG.md)
# Changelog

This document records all important version changes of the CodeCraft project.

---

## [1.1.1] - 2026-06-10

### 🎉 Major Tool System Refactoring (Tool Unification)

- **Tool Consolidation (30+ → 19 tools)**: Consolidated original 30+ individual tools into 19 unified tools using `action` parameter to distinguish operation modes, greatly reducing LLM tool selection confusion
  - **File Operations**: `read_file`/`glob_files`/`grep_search`/`read_project_tree` → `file_explorer` (action: read/glob/grep/tree)
  - **File Editing**: `write_file`/`edit_file`/`delete_file` → `file_writer` (action: write/edit/delete)
  - **Command Execution**: `run_command`/`run_server`/`service_control` → `command` (action: exec/start/list/logs/stop)
  - **Git Operations**: `git_status`/`git_diff`/`git_log` → `git_query`; `git_add`/`git_commit`/`git_push` → `git_submit`
  - **Agent Collaboration**: `fork_agent`/`collect_agent`/`inspect_agent` → `agent` (action: fork/collect/inspect)
  - **Skill Management**: `manage_skill`/`report_skill_result` → `skill` (action: create/update/delete/list/report)
- **New Tools**: `chat_attachment` (on-demand PDF/Word/Excel attachment reading), `schedule_task` (scheduled task CRUD and toggle)
- **Action Parameter Iron Rule**: 12 tools with `action` parameter are mandatory, prominently displayed in system prompt header
- **Smart Action Fix**: `ToolExecutor` adds intelligent action completion engine — when LLM forgets to pass `action`, auto-infers from existing parameters (e.g., `file_path` → `read`, `content` → `write`), covering all 12 tools; returns precise fix guidance when inference fails

### 📎 Attachment System (ChatAttachment)

- **PDF/Word/Excel Parsing**: Added PDFBox (3.0.2) + POI (5.2.5) dependencies for text extraction from PDF, Word (.docx), Excel (.xlsx/.xls)
- **Attachment Upload Staging Mode**: Frontend no longer concatenates content to messages; returns `attachmentId` instead. LLM reads via `chat_attachment` tool on demand (saves Tokens)
- **Attachment Icons by Type**: PDF 📕, Word 📘, Excel 📊, Image 🖼️, Text 📄
- **Config Items**: `chat-attachment.store.temp-dir`, `chat-attachment.parse.pdf-max-pages`, etc.

### 🚀 Connection Pool & Performance Optimization

- **WebClient Connection Pool**: Added `ConnectionProvider("deepseek-pool")`, idle 40s timeout proactively closes connections (shorter than server ~60s, prevents dead connections), background 20s cleanup, max 5-minute lifetime forced rotation
- **HTTP/2 Preferred**: `HttpProtocol.H2, HTTP11` configuration, 401 does not close connection under HTTP/2
- **Connection Warmup**: Asynchronously warm up DeepSeek API HTTPS connections (DNS+TCP+TLS) after startup, eliminating 10-20s first-request delay. Prioritizes reading API Key from DB `sys_config`, falls back to config file
- **Performance Diagnostic Logs**: Records TTFB (Time To First Byte) and requestBodySize for each LLM request

### ⚡ Async Tool Execution + Loading Animation

- **Async Tool Calls**: In `DeepSeekServiceImpl`, tool execution switched from sync to `Mono.fromCallable` + `boundedElastic` thread pool, avoiding blocking of Netty event loop
- **SSE Event Stream Optimization**: Send `tool_call_start` event first (tool name + operation summary) → frontend renders purple loading card → replace with result card after tool completes
- **Frontend Pending Cards**: Purple pulsing border + progress bar slide + bouncing dot animation, dark theme adapted, pending cards always expanded (no collapsing)
- **Operation Summary Display**: `OperationDetailGenerator` adds action gateway dispatch, supporting all unified tools like `command` (exec/start/logs/stop), `file_writer` (write/edit/delete), `git_submit` (add/commit/push)

### 🖥️ Frontend Improvements

- **AgentPanel.vue**: Tool events now display `action` field (green tag), fixed empty state when no action and no filePath
- **CodeAssistantView.vue**: Complete attachment system rewrite (`attachmentId` mode, `type` field replacing `image`/`language`), tool call pending loading cards, enhanced tool result cards (operation type tags like "file_explorer · Search" + error detection only checks first line to prevent false positives), attachment icons by type, `attachmentIds` passed to backend
- **Layout.vue**: Theme toggle skips auto loop (light→dark→light direct switch), removed redundant `ThemeMode` type import
- **RightToolbar.vue**: Drawer panel `overflow: hidden` → `overflow-y: auto` to fix unscrollable content
- **sse-client.ts**: Added `tool_call_start` event type and `ToolCallStartData` interface
- **chat.ts**: `ChatRequest`/`StreamChatOptions` now include `attachmentIds` field, `FileUploadResult` returns `attachmentId` and `type`
- **Dark Theme**: Pending cards, action tags, tr-toggle and other components adapted for dark theme

### 👥 Sub-Agent Parallelism Boost

- **Max Concurrency 5 → 20**: `AgentForkManager.MAX_CONCURRENT_AGENTS` raised to 20
- **Thread Pool Refactoring**: `corePoolSize` from 2 to 20 (= MAX), `LinkedBlockingQueue(20)` → `SynchronousQueue` (unbuffered, CallerRuns policy when core threads full)
- **Thread Naming**: Sub-agent threads uniformly named `sub-agent-{id}` for easier log troubleshooting
- **Batch Collection**: New `batchCollectAgents()` method, collects all sub-agents in one call (parallel wait + overall timeout protection), recommended to use `action=batch_collect` in agent tool

### 🏠 API Key Database Storage

- **Config Storage Migration**: API Key set from frontend "Settings" page, stored in `sys_config` table (`deepseek_api_key`), no longer read from environment variables or `application-local.yml`
- **Backward Compatibility**: Warmup and runtime prioritize DB lookup, fall back to config file when DB has no record

### 📝 System Prompt Upgrade

- **Language Enforcement Directive**: Moved to top of prompt and upgraded to "Highest Priority Directive", 5 sub-rules covering thinking/replies/code/tool returns/priority
- **Tool Inventory Table**: Added "Available Tools" table with all 19 tools (tool name + typical usage + description)
- **Action Parameter Iron Rule**: Prominent warning box + mnemonic rhyme + 12-tool self-check checklist
- **Multi-Agent Collaboration**: Updated to action mode (fork → batch_collect/collect → inspect), recommends batch_collect for one-shot collection
- **All Tool Reference Names**: Old tool names in prompt uniformly replaced with new tool names

### 🐛 Fixes

- **DeepSeekAnalyzer Bracket Matching Fix**: `extractJsonFromText()` now uses `findMatchingBrace()` stack counter for precise `{}` matching, fixing nested JSON truncation across multiple JSON blocks
- **DeepSeekAnalyzer Non-Thinking Fallback**: Judge invocation explicitly sets `"non-thinking"` when no `thinking_mode` passed, preventing models like `deepseek-v4-pro` from defaulting to thinking mode causing empty `content=""` JSON extraction failure
- **ScheduleTaskServiceImpl**: Default `max_execute_count` changed from 0 (unlimited) to 100 (safety cap)

### 🗑️ Deleted Files

- Old tool implementations all removed (21 files): `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `DeleteFileTool`, `GlobFilesTool`, `GrepSearchTool`, `ReadProjectTreeTool`, `RunCommandTool`, `RunServerTool`, `ServiceControlTool`, `GitStatusTool`, `GitDiffTool`, `GitLogTool`, `GitAddTool`, `GitCommitTool`, `GitPushTool`, `ForkAgentTool`, `CollectAgentTool`, `InspectAgentTool`, `ManageSkillTool`, `ReportSkillResultTool`
- Config file: `DeleteFileConfig` → renamed to `EditFileConfig` (adapting to `file_writer` system)
- `OperationCategory` enum comments globally updated (old tool names → new tool names)

### 🏷️ Version

- Backend: `1.1.0` → `1.1.1`
- Frontend: `1.1.0` → `1.1.1`
- Electron: `1.1.0` → `1.1.1`
- Artifact: `CodeCraft-Setup-1.1.0.exe` → `CodeCraft-Setup-1.1.1.exe`

### 📝 Documentation

- `README.md`: Tool name updates (`delete_file` → `file_writer action=delete`), sub-agent concurrency updates (5→20)
- `BUILD_AND_RUN.md` version sync to `1.1.1`
- `CHANGELOG.md` added `1.1.1` entry (this document)

---

## [1.1.0] - 2026-06-24

### 🎉 New Features

- **Right Toolbar**: New right-side icon toolbar integrating File Tree, Git Panel, Agent Running Panel; vertical button bar one-click switching; panel width draggable
- **Split Layout**: Universal layout component supporting drag-to-split, files/Git panels can be dragged to four directions (up/down/left/right) for split-screen display; drag files to hot zones for auto split mode
- **HEAD Version File View**: New `git show HEAD:file` API (`/api/git/show`), supports viewing original content of any file in HEAD commit for comparison/reference
- **Hunk Restore**: New `git apply --reverse` API (`/api/git/restore-hunks`), supports selective hunk-by-hunk file restoration instead of full `git restore`
- **Snapshot File Content Diff**: New snapshot file content read API (`/api/snapshots/file-content`), supports diff comparison between workspace files and historical snapshot versions

### 🔧 Fixes & Optimizations

- **Git Status Query Refactoring**: Two-step method to precisely distinguish tracked/untracked files — `git diff --name-only HEAD` (tracked) + `ls-files --others --exclude-standard` (untracked), completely solving false "modified" reports caused by autocrlf
- **Git stderr/stdout Separation**: `GitCommandExecutor` no longer merges error stream (`redirectErrorStream=false`), preventing CRLF warnings from mixing into normal output and causing diff parse failures, also avoids stderr pipe blocking
- **HOME Environment Variable Injection**: Git subprocesses inherit `HOME`/`USERPROFILE` environment variables, ensuring global `.gitconfig` settings (e.g., `core.autocrlf`) take effect in subprocesses
- **Git Diff Baseline Fix**: `git diff` always uses `HEAD` as baseline, showing true differences between working/staging area vs latest commit
- **Permission Approval Enhancement**: Manual mode approval events (`ask_user`) now carry tool details (`toolName`/`filePath`/`fullDetail`), users can see specific operations in the confirmation dialog
- **Changed File Filtering**: Git status query auto-excludes build artifacts/snapshots/logs directories (`target/`, `node_modules/`, `snapshots/`, etc.)
- **Agent Panel Dark Mode**: `AgentPanel.vue` fully adapted for dark theme: event list, skill tags, scrollbar
- **Multi-Component Dark Mode**: `AgentSelector`, `DiffView`, `FileEditor`, `FileTree`, `GitSidebar`, `SkillList`, `TreeNode` dark theme improvements

### 🛠️ Technical Architecture

- **GitCommandExecutor Refactoring**: New `executeWithStdin()` method (supports commands requiring stdin like `git apply --reverse`), stdout/stderr independent thread consumption to prevent pipe deadlocks
- **GitController Refactoring**: Status query logic extracted into `appendTrackedChanges()` / `appendUntrackedFiles()` / `isExcludedPath()` private methods; new `show` and `restore-hunks` endpoints
- **SnapshotService Extension**: New `getSnapshotFileContent()` method, supports retrieving snapshot file content by sessionId + filePath
- **ToolLoopManager Extension**: `createAskUserEvent()` now accepts `toolName`/`filePath`/`fullDetail` parameters
- **DeepSeekServiceImpl Enhancement**: Permission approval flow integrated with tool detail passing, approval dialogs show richer operation info

### 🏷️ Version

- Backend: `1.0.9` → `1.1.0`
- Frontend: `1.0.9` → `1.1.0`
- Electron: `1.0.9` → `1.1.0`
- Artifact: `CodeCraft-Setup-1.0.9.exe` → `CodeCraft-Setup-1.1.0.exe`

### 📝 Documentation

- `BUILD_AND_RUN.md` version sync to `1.1.0`
- `CHANGELOG.md` added `1.1.0` entry (this document)

---

## [1.0.9] - 2026-06-03

### 🎉 New Features

- **Context Mode**: New dual-strategy context injection system supporting full/compact modes, drastically optimizing Token consumption
  - **Full Mode**: All history messages injected intact, LLM has complete context
  - **Compact Mode**: Non-current-turn tool call results only retain success/failure summary, reasoning process (reasoning_content) removed, Token consumption expected to drop 90%+
  - In compact mode, LLM can use `query_tool_history` tool to query full details of historical tool calls on demand
  - Configuration persisted to server-side `sys_config` table, quick toggle at chat input bottom
- **QueryToolHistory Tool**: `query_tool_history` supports querying complete original results of historical tool calls by tool_name, limit, message_id; on-demand retrieval in compact mode, also useful for deep troubleshooting in full mode
- **Context Token Visualization**: Real-time display of current context Token count at chat input bottom; purple "⚡ Compact" marker with precise discount algorithm in compact mode
- **Settings Page Context Mode Management**: New "Context Mode" config card with full/compact dual radio buttons + detailed usage instructions

### 🔧 Fixes & Optimizations

- **Input Not Clearing After Send**: Fixed residual content in input box after AI assistant message sent (root cause: Ant Design Vue `a-textarea` in `disabled` state doesn't respond to `v-model` changes in same render cycle; fixed with `await nextTick()` before disable + fallback clear logic)
- **Server Port Correction**: Corrected service port to 8084 (alignment)
- **application.yml Config Sync**: v1.0.9 config items completed

### 🛠️ Technical Architecture

- **ContextBuilder Refactoring**: New `buildMessagesFromHistory(conversationId, contextMode)` dual-mode message building method; `compactHistoryMessages()` core compact logic (tool result summarization + reasoning removal + token estimation); `buildCompactModeInstruction()` injects intelligent prompt directives
- **DeepSeekAnalyzer Enhancement**: Integrated compact context mode support, optimized analyzer collaboration with ContextBuilder
- **AgentForkManager Adaptation**: Sub-agent creation correctly propagates context mode configuration
- **DeepSeekServiceImpl Refactoring**: Message building pipeline integrated with ContextBuilder, unified context management
- **TokenEstimator**: New Token estimation utility class supporting message-list-level Token estimation
- **Config Layer Extension**: `DeepSeekConfig` adds `contextMode` field, `ChatRequest` adds `contextMode` parameter

### 🗄️ Database Changes

- `sys_config` table added `context_mode` config item (full/compact, default full)

### 🏷️ Version

- Backend: `1.0.8` → `1.0.9`
- Frontend: `1.0.5` → `1.0.9`
- Electron: `1.0.8` → `1.0.9`
- Artifact: `CodeCraft-Setup-1.0.8.exe` → `CodeCraft-Setup-1.0.9.exe`

### 📝 Documentation

- `README.md` updated project feature descriptions
- `BUILD_AND_RUN.md` version sync

---

## [1.0.8] - 2026-06-02

### 🎉 New Features

- **P2P Image/File Transfer**: Support sending images and files in chat
  - Images ≤20MB thumbnail preview + click to enlarge, >20MB auto-degrade to file transfer
  - Files max 2GB, chunked transfer (1MB/chunk), TLS encrypted channel
  - Files stored on disk (`data/p2p/received/{peerId}/{transferId}/`), database stores only metadata
  - Auto-generate thumbnails for received images, click files to open local directory
  - `p2p_chat_message` table adds 7 file metadata fields (`file_name`/`file_size`/`mime_type`/`file_category`/`transfer_id`/`file_status`/`local_path`)
  - New `FileTransferHandler` + `FileTransferManager` for independent file transfer protocol management
- **Input Area Enhancement**: Drag-and-drop send, Ctrl+V paste images, 📎 button file selection
- **P2P Node Management**: Offline nodes support reconnect/delete, auto-disable remote agent when peer offline

### 🔧 Fixes & Optimizations

- **P2P Panel Fixes**: reactive not imported, Token loss (FormData request + img tag), Desktop HeadlessException, filename loss (String.format % conflict), sender image flicker
- **http-client Fix**: FormData requests auto-skip Content-Type setting, headers explicitly merged to prevent token loss
- **TokenAuthenticationFilter**: Fixed authentication filter logic

### 📝 Documentation

- **README.md Streamlined**: Removed redundant descriptions, restructured as core feature overview + key feature summary

### 🗄️ Database Changes

- `p2p_chat_message` table added `file_name`/`file_size`/`mime_type`/`file_category`/`transfer_id`/`file_status`/`local_path` fields
- `P2pChatService` added `saveFileMessage()` and `findByTransferId()` methods

---

## [1.0.7] - 2026-05-31

### 🎉 New Features

- **Prompt Optimization**: Input box adds ✨ Optimize button, uses LLM to auto-rewrite user messages for clarity
  - Backend `PromptOptimizeService` + `PromptOptimizeController`, non-streaming calls via DeepSeek Flash
  - Frontend locks input + loading animation on click, optimized result auto-replaces original text
  - Optimization template focuses on "identifying vague references, clarifying operation intent, preserving original meaning"
- **Supplement Request (Mid-stream Injection)**: Can add supplementary requirements during AI execution, message injected into running ToolLoop
  - New `SupplementStore` message queue
  - Frontend `supplementRequest()` API + backend `/api/deepseek/supplement` endpoint

### 🎨 UI Optimization

- **Send Panel Redesign**: Unified button group style (attachment/optimize/send buttons no longer mismatched), dropdown selectors use emoji icons (🛡️Execute ⚡Model 💡Thinking), rounded corners aligned with Bento design language, full dark theme adaptation

### 🔧 Fixes & Optimizations

- **Optimize Button Loading Fix**: Manually toggle LoadingOutlined to resolve overflow issue with Ant Design `:loading`
- **Prompt Template Correction**: Added "Do not answer user's question" hard constraint to prevent optimizer from acting as dialogue role

---

## [1.0.6] - 2026-05-30

### 🎉 New Features

- **P2P Message Direction Awareness**: Frontend auto-distinguishes "sent" vs "received" messages based on `direction` field
- **P2P Agent Call Loading Animation**: Caller shows "AI is thinking..." loading state after initiating agent call
- **P2P Markdown Rendering**: Agent response content supports full Markdown rendering (code highlighting/tables/lists, etc.)
- **Session Auto-Rebuild**: When user clears chat history, P2P mapping auto-detects and rebuilds new session, preventing empty AI responses

### 🔧 Fixes & Optimizations

- **JSON Parsing Improvement**: `P2pController` switched to Jackson ObjectMapper for complete parsing, replacing string lookup approach
- **P2P Dark Mode**: Loading animation, message bubbles, send button dark theme adapted
- **Message Bubble Spacing Fix**: Fixed inconsistent chat message bubble spacing

### 📝 Documentation System

- **New `docs/` Directory**: Architecture panorama, tool system, core engine, P2P system, snapshot system, dev quickref, release checklist, FAQ (8 documents)
- **New `README` Doc Navigation Table**: Recommended docs by reader role
- **New `BUILD_AND_RUN` Lazy Channel**: One-click script build instructions

### 🤖 CI / Automation

- **New `.github/workflows/release.yml`**: Push `v*` tag auto-triggers Win/Mac/Linux 3-platform build + Release publish
- **New `scripts/` Directory**: One-click build scripts (`build.bat` / `build.sh`) + version sync script

---

## [1.0.5] - 2026-05-28

### 🎉 New Features

- **P2P Remote Collaboration**: New peer-to-peer remote Agent invocation system supporting secure device-to-device direct communication
  - Netty TCP Server/Client for high-performance async network communication
  - Protobuf efficient binary serialization (message protocol)
  - TLS self-signed certificate encrypted communication (BouncyCastle)
  - QR code scan pairing (ZXing), no manual address entry needed
  - Agent authorization sharing (bidirectional authorization management panel)
  - P2P chat messages fully persisted and queryable
- **P2P Frontend Panel**: `P2pPanel.vue` component, supports connection status display, message send/receive, authorization management
- **P2P Menu Item**: Left settings menu adds "P2P Connection" entry

### 🎨 UI Optimization

- **Sidebar Redesign**: Layout.vue upgraded to Bento card style, CSS variable system + micro-interaction animations
- **Theme Toggle**: Supports light/dark/auto three modes, one-click toggle at sidebar bottom
- **Menu Item Overflow Tooltip**: Menu items add title attribute, collapsed state hover shows full name
- **Collapse Animation**: Sidebar collapse/expand transition smoother (cubic-bezier easing)

### 🔧 Fixes & Optimizations

- **Electron Cache Cleanup**: Auto-clear HTTP cache after overwrite install to prevent white screen from old cache
- **Electron Firewall Management**: Auto-register P2P port firewall rule on startup, auto-clean on exit
- **Message Bubble Spacing Fix**: Virtual scroller message items use `padding-bottom` instead of `margin-bottom` to fix bubble sticking
- **Dark Mode Send Button**: disabled state background color adapted for dark theme
- **Thinking Process Header Layout**: Skill tag overflow handling improved, `flex-wrap: nowrap` + reduced max width
- **Skill List Dark Mode**: SkillList component fully adapted for dark mode (cards, text, tags, etc.)
- **Skill Management TransitionGroup**: Added `tag="div"` and `min-height: 0` to fix transition animation layout issues

### 🗄️ Database Changes

- New `p2p_chat_message` table: P2P chat record storage
- New `p2p_agent_authorization` table: P2P Agent authorization records
- New `p2p_agent_conversation` table: P2P Agent session mapping
- New menu item: P2P Connection (menu ID=13, SETTING type)

### 🏷️ Version

- Backend: `1.0.4` → `1.0.5`
- Frontend: `1.0.4` → `1.0.5`
- Electron: `1.0.4` → `1.0.5`
- Artifact: `CodeCraft-Setup-1.0.4.exe` → `CodeCraft-Setup-1.0.5.exe`

### 📄 Documentation

- `README.md` added P2P remote collaboration feature description
- `BUILD_AND_RUN.md` version sync
- All `.md` docs version synced to `1.0.5`

---

## [1.0.4] - 2026-05-26

### 🏷️ Version

- Backend: `1.0.3` → `1.0.4`
- Frontend: `1.0.1` → `1.0.4`
- Electron: `1.0.3` → `1.0.4`
- Artifact: `CodeCraft-Setup-1.0.3.exe` → `CodeCraft-Setup-1.0.4.exe`

### 📄 Documentation

- All `.md` docs version synced to `1.0.4`

---

## [1.0.3] - 2026-05-25

### 🎉 New Features

- **Agent Selector Expand/Collapse**: AgentSelector defaults to showing first 3 agents, "Show All" button when more than 3, preventing crowding
- **Skill List by Confidence**: SkillList sorted by confidence descending, same confidence by creation time ascending, most effective skills shown first
- **Schedule Task by Agent Filter**: ScheduleTaskView adds Agent filter dropdown for quick filtering by specified agent
- **Skill Tag Overflow**: CodeAssistantView skill match tags add title attribute and overflow ellipsis style, long skill names won't break layout

### 🔧 Fixes & Optimizations

- **Main Content Scrolling Fix**: Layout.vue main-content `overflow` from `hidden` to `overflow-y: auto`, fixing unscrollable content in some scenarios
- **Skill Match Forced Truncation**: SkillMatcher.java adds forced truncation logic ensuring returned skills don't exceed TOP_K limit, preventing fallback logic from exceeding skill count

### 🏷️ Version

- Backend: `1.0.2` → `1.0.3`
- Electron: `1.0.2` → `1.0.3`
- Artifact: `CodeCraft-Setup-1.0.2.exe` → `CodeCraft-Setup-1.0.3.exe`

---

## [1.0.2] - 2026-05-25

### 🎉 New Features

- **Tool Selection Grouped by Category**: Agent management and skill management tool selection area changed to collapsible panels grouped by category, each tool with function description and high-risk marker ⚠️
- **Backend Tool Registry API**: New `GET /api/tools/registry` endpoint returning all tools grouped by category (with descriptions and risk levels)
- **Multi-Agent Background Streaming Switching**: Switching agents keeps background streams running, returning to agent auto-restores stream state and stop button, supporting multiple agents streaming in parallel

### 🔧 Fixes & Optimizations

- **Work Directory Check**: Sending message without work directory set prompts user, won't default to project root
- **File Tab Loading State**: Fixed "Loading..." text not hiding after load completes
- **File Tree Default Display**: Fixed file tree defaulting to project root directory when no work directory selected
- **Stream Stop Button**: Fixed stop button not canceling backend task (cancelTask not called)
- **Message Blank After Stream Stop**: Fixed half-output message lost becoming blank after clicking stop
- **Stop Button Needs Double Click**: Fixed first stop ineffective due to `checkAndReconnect` reconnection
- **agent_task Schema SQL**: Fixed H2 incompatible `ENGINE=InnoDB` syntax causing task record creation failure
- **Tool Selection Style Sync**: Agent management and skill management tool selection area styles fully unified

### 🗄️ Database Changes

- `schema.sql` added `agent_task` table creation statement (with `pending_question_uuid` and `pending_question_text` fields and indexes)
- Fixed `agent_task` CREATE TABLE dynamic SQL H2 compatibility

### 🏷️ Version

- Backend: `1.0.1` → `1.0.2`
- Frontend: `1.0.1` → `1.0.2`
- Electron: `1.0.1` → `1.0.2`
- Artifact: `CodeCraft-Setup-1.0.1.exe` → `CodeCraft-Setup-1.0.2.exe`

---

## [1.0.1] - 2026-05-24

### 🎉 New Features

- **Agent Configuration Management**: Support creating and managing multiple custom AI Agents, each independently configurable with system prompt, tool set, AI model (e.g., deepseek-v4-flash), thinking mode (non-thinking/thinking/thinking_max), and execution mode (auto/manual)
- **Agent Selector**: New AgentSelector component at top of chat interface for quick agent switching
- **Skill Management Standalone Page**: New `/skill-manage` route and SkillManageView, supporting visual CRUD for skills, skills can bind to specific Agent configuration
- **Session-Level Work Directory**: Conversation entity adds `workDir` field, sessions can bind independent work directories
- **Agent Config Association**: Conversations, skills, scheduled tasks all add `agentConfigId` field, supporting binding to specified Agent configuration

### 🔧 Improvements

- **Frontend Menu Reorganization**: Left menu bar restructured as grouped layout ("Chat" group + "Settings" group); LINK type menus in chat group, SETTING type menus in settings group
- **Menu Item Name Optimization**: "Code Assistant" → "AI Assistant"
- **Frontend API Unification**: Skill API migrated from native `fetch` to unified `request` utility, interface more standardized
- **Chat API Extension**: `ChatRequest` and `StreamChatOptions` add `agentConfigId` optional parameter
- **Conversation API Extension**: `getConversationList` supports filtering by `agentConfigId`
- **ScheduleTask API Extension**: `ScheduleTaskItem` and `CreateTaskParams` add `agentConfigId` field
- **Frontend Build Script Optimization**: `build` command removes `vue-tsc -b` pre-check, new standalone `typecheck` command

### 🗄️ Database Changes

- New `agent_config` table: stores custom Agent configurations, includes built-in "Code Assistant" Agent (ID=1, immutable/undeletable)
- `conversation` table adds `agent_config_id` and `work_dir` fields
- `skill` table adds `agent_config_id` field (null=global skill)
- `schedule_task` table adds `agent_config_id` field
- New menu items: Agent Management (`/agent-config`), Skill Management (`/skill-manage`)

### 🏷️ Version

- Backend: `0.0.1-SNAPSHOT` → `1.0.1`
- Frontend: `0.0.0` → `1.0.1`
- Electron: `1.0.0` → `1.0.1`
- Artifact: `CodeCraft-Setup-1.0.0.exe` → `CodeCraft-Setup-1.0.1.exe`

### 📄 Documentation

- `README.md` updated feature descriptions and project structure
- `BUILD_AND_RUN.md` updated version references
- New `CHANGELOG.md` (this document)

---

## [1.0.0] - 2026-05-19

### First Official Release

- 🤖 AI Agent multi-tool invocation (30+ tools: file read/write, code search, command execution, Git operations, API calls, etc.)
- 🧩 Task decomposition and parallel sub-agent execution
- 🔄 Auto error correction and retry mechanism
- 🎯 Skill system: supports creating and managing reusable skills
- 👨‍💻 Coding assistance: code generation, file browsing/editing, code search, diff comparison, file snapshots
- 🔧 Project management: Maven build, Git integration, project structure analysis
- 🗄️ H2 embedded database (zero-config) + Caffeine in-memory cache
- 🖥️ Electron desktop application (Windows/macOS/Linux), built-in JRE no Java installation needed
- 🔐 User authentication and permission management (Token auth, role permissions, menu permissions)
- ⏰ Scheduled task scheduling (Cron expressions + one-time tasks)
- 📝 System log viewer
- 🌐 Built-in network proxy support
