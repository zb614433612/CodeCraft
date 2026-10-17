> 🌐 中文版：[🇨🇳 CHANGELOG](./CHANGELOG.md)
# Changelog

This document records all important version changes of the CodeCraft project.

---

## [2.0.3] - 2026-10-17

### 💬 Fixed: Chat Rendering Overlap / Misplacement / Slow Long Conversations

- **New backend "by round" cursor pagination endpoint** `GET /conversation/{conversationId}/messages/page?beforeId=&rounds=`:
  - Uses `role=USER` (excluding `desktop_inject` messages) as round boundaries, so boundaries always fall on user questions and `processMessageGroups` never aggregates across pages;
  - Returns the latest 5 rounds by default (`rounds` max 50); response carries `messages` (id ascending) + `hasMore` + `nextBeforeId`;
  - Cursor is `beforeId` (earliest message id of the previous page), avoiding offset drift when new messages are written.
- **Frontend switched to lazy loading**: first screen loads only the latest 5 rounds → scrolling to the top (`scrollTop ≤ 80`) loads 5 older rounds via `nextBeforeId`; pagination state is migrated when a local (`local-*`) conversation receives its real id.
- **Reading position no longer drifts after loading history**: `preserveScrollAfterPrepend` now compensates in both directions (`delta !== 0`) and skips compensation when the user is actively scrolling (offset > 48px).
- **Overlap / misplacement fixes**:
  - Virtual list is force-rebuilt per conversation (`:key="currentConversationId"`); message ids are globally unique (`user-` / `assistant-` / `reconnect-` + `localMsgSeq`) with a duplicate-id fallback;
  - Removed message enter animations and `transition: all` (animations during measurement cause wrong dynamic-list estimates); `min-item-size` 80 → 120;
  - New `v-track-msg-size` directive + `ResizeObserver`: real height changes (code block rendering / image loading / collapse) trigger a throttled (120ms, >1.5px delta) `forceUpdate()` self-heal, fixing scrollbar jitter and item misplacement;
  - Forced scroll-to-bottom after switching conversation / restoring an Agent snapshot (`scrollToBottomForce`: token cancellation + frame-by-frame stick-to-bottom + 3-frame height-stable check).
- **History loading no longer yanks the view to the bottom**: new `lastHistoryPrependAt` (500ms window) marker prevents "load history → message count changes → watcher treats it as a new message → force scroll".
- **`loadingMoreMessages` is now per-conversation** (`loadingMoreConvIds`): loading history in parallel conversations no longer blocks each other and the top hint no longer leaks across conversations.
- **Multi-agent parallel state fixes**:
  - `hasRunningTask` / `hasActiveStream` now cover four runtime sources (foreground stream `sendingStates` / background streams `backgroundStreams` / reconnecting `reconnectingConvIds` / active tasks `activeTaskMap`) — after switching Agents away and back, the "supplement request" and "stop task" buttons and the input loading state are restored;
  - `onStreamComplete` removes background-stream entries precisely by `abortController` (one conversation may host several background streams; the old removal by `convId` killed still-running streams and broke the stop button);
  - Background-task completion notifications now resolve the Agent name/avatar from the owning `agentConfigId` of that stream (previously the currently selected Agent, which showed wrong name/avatar under multi-agent parallelism).

### 🧠 Fixed: Isolated Tool-Call Sequences Causing HTTP 400 (Permanently Stuck Conversations)

- **New `ToolCallSequenceSanitizer` (idempotent / in-memory / copy-on-write / no DB writes)** sanitizes at the single request exit, replacing the historical "remove `assistant.tool_calls` only" patch (which turned persisted tool messages into orphan tool messages and still returned 400):
  - Drops orphan `tool` messages (no id-matching, unconsumed tool_call in the same round);
  - Removes `tool_call` entries without results one by one; clears `tool_calls` entirely if none has a result (the assistant degrades to plain text, content kept);
  - Drops assistant messages left with neither `content` nor `tool_calls`;
  - Removes illegal call entries (missing id / id `"null"` / duplicate id).
- **Wired into every request exit**: `ContextBuilder` (after conversion and again after compression/trimming), plus request-body building in `AbstractLLMClient` / `AnthropicClient` / `OllamaClient`; the old orphan-tool_calls patch in `ContextBuilder` was removed.
- **Write-side hardening**: `DeepSeekServiceImpl.appendToolResultMessages` appends a "[tool not executed]" placeholder tool message for every tool_call declared in the batch but missing a result (task cancelled / null result / result build failure), eliminating gaps at the source.
- **Regression tests**: new `ToolCallSequenceSanitizerTest` (36 assertions covering orphan tools, missing results, illegal ids, duplicate ids and idempotency).

### 🤖 Improved: Judge No Longer Hard-Terminates — The User Decides

- **Judge rejection / unparsable judge response / judge failure with no local progress / reaching the cumulative `MAX_JUDGE_GRANTED_ITERATIONS=100` cap** → all now ask the user through `ask_user` (`askType=iteration_confirm`) instead of terminating the task.
- **New "task iteration confirmation" panel**: shows the trigger, judge reason, current progress, current iteration (`n / max`) and cumulative extension (`x / 100`), with "continue +10 / +20 / +50 iterations" and "end task"; a 5-minute wait timeout ends the task and clears the pending question to avoid dangling state.
- After page refresh / task reconnect, the panel type is restored from the `【任务迭代确认】` question prefix instead of degrading into a normal follow-up question.

### 🔄 New: Desktop Auto Update (GitHub Releases)

- **New `electron/update-manager.js`**: checks GitHub Releases (`/releases/latest`, 6-hour cache + 30-second minimum manual interval to respect the unauthenticated 60 req/h limit) → in-app download with progress (mirror prefix acceleration with automatic direct fallback) → per-platform installation (Windows launches the installer and quits; macOS opens the dmg for drag-and-drop; Linux reveals the AppImage in the file manager).
- **New `electron/update-utils.js`**: semantic version comparison (supports `v` prefix / prereleases) and release-asset matching by platform + architecture (independently testable).
- **No data loss on upgrade (three layers)**:
  - Backend working directory (cwd) moved from the install directory to `userData/runtime`, with automatic legacy data migration, `legacy-backup` restore and fallback on failure;
  - Backend process is stopped before upgrading and `userData/runtime/data` is backed up to `userData/backups`;
  - New `electron/installer.nsh` (NSIS `customInit`): backs up the legacy data directory before the first upgrade so an over-install cannot wipe the database.
- **Frontend**: new `frontend/src/utils/update.ts` (IPC wrapper + per-platform guidance); `SystemSettingView` gained a "Version & Updates" card (current version / latest version / check / download progress / one-click install / auto-check on startup switch / open release page, with a graceful degradation hint in browsers); `Layout.vue` auto-checks 15 seconds after startup and shows a notification that navigates to the card.
- **Local data reset integration**: `clearLocalData` also clears `backups` / `legacy-backup` / installers / update cache.
- **preload additions**: `update.*` (getState / check / download / cancelDownload / install / openReleasePage / getSettings / setSettings / onAvailable / onDownloadProgress) and `clearLocalData`.

### 🌐 New: DeepSeek Official web_search / web_fetch APIs

- **New `DeepSeekToolsApiClient`**: enabled automatically when the Provider `baseUrl` host is `api.deepseek.com` and an API key is configured (third-party proxies / self-hosted compatible services are excluded to avoid leaking requests); reuses `LLMWebClientManager` auth headers, connection pool and retries:
  - `POST /v1/tools/search`: query / count / need_content, parsing `results[]` (title / url / snippet / summary / content / site_name / date / authority / rerank_score);
  - `POST /v1/tools/fetch`: url → title + Markdown content.
- **`SearchTool` / `WebFetchTool` now "official first, fallback on failure"**: on official API errors / timeouts / non-official Providers they fall back to the original pipeline (Bing/Baidu multi-source scraping, Jina/local fetching) so the tools always work; the official `WebFetchTool` path is only used for public addresses.
- **`AgentForkManager`**: child agents now set `ToolContext.setProviderCode(...)` so provider detection also works inside sub-agents.
- **Regression tests**: new `DeepSeekToolsApiClientTest` (26 assertions, main-method style).

### 🏷️ Version Numbers

- Backend: `2.0.2` → `2.0.3` (pom.xml)
- Frontend: `2.0.2` → `2.0.3` (package.json + package-lock.json)
- Electron: `2.0.2` → `2.0.3` (package.json + package-lock.json, packaged jar path synced to `code-craft-2.0.3.jar`)
- MCP Server handshake version: `2.0.2` → `2.0.3`
- Build artifacts: `code-craft-2.0.2.jar` → `code-craft-2.0.3.jar`
- Docs: README(_EN) / BUILD_AND_RUN(_EN) / CHANGELOG(_EN) / docs (ARCHITECTURE, DEEPSEEK_SERVICE_IMPL, TOOL_SYSTEM, LLM_PROVIDER_SYSTEM, DEV_QUICKREF, RELEASE_CHECKLIST, etc.) synced to v2.0.3

---

## [2.0.2] - 2026-09-24

### 🎨 New: Appearance Settings (Global Background Image / Window Opacity)

- **New "Appearance" drawer at the sidebar bottom** (AppearanceSettingsDrawer): upload a global background image (≤10MB) and the whole app (sidebar / content area / title bar) shows it through; three sliders for "background opacity / background blur / window overall opacity" plus "Reset to defaults"
- **Backend**: new BackgroundStore (files under `data/backgrounds/`) and AppearanceController — `POST /api/appearance/background` (auth required) uploads and returns a URL; `GET /api/appearance/file/{name}` is token-free for CSS/img references
- **Frontend**: App.vue gains a fixed global background layer; style.css gains the `html.has-app-bg` translucent rule chain (root containers transparent + semi-transparent panels on pages); appearance settings persisted (bgImageUrl / bgOpacity / bgBlur / windowOpacity)
- **Electron**: preload exposes `setWindowOpacity` → main process `mainWindow.setOpacity()` (clamped 0.2~1, fails silently) — "window overall opacity" applies to the entire window including the title bar

### 🖥️ Desktop: Transparent Custom Title Bar + Splash Screen

- **Transparent title bar**: the title-bar overlay color is now fully transparent — the title-bar area directly reveals the web content (following the app background when a background image is set); window-control symbol color switches with light/dark theme
- **Splash screen**: new `electron/loading.html` and a Splash window (centered, borderless & transparent, mouse-through, non-focus-stealing, minimum display 600ms):
  - Dual progress channels: Spring Boot startup log anchors (Starting / Tomcat initialized / Tomcat started / Started) jump the progress + time-based smooth fallback
  - On ready the progress fills to 100% and fades out into the main window; if the backend process exits early, waiting fails immediately (error reason shown + native dialog, no more idle polling until timeout)
- **preload gains `openExternal`**: the "Open in browser" button of in-app web tabs launches the system default browser

### 🧩 Refactor: Skill System Now Uses "Progressive Disclosure"

- **New SkillCatalogBuilder (L1 catalog layer)**: condenses all active skills into a "skill catalog" (name / ID / confidence / trigger words / one-line description) dynamically injected into the system prompt, so the LLM always has a global view of all skills; the catalog is capped at a 3500-char budget, truncated by confidence descending when exceeded
- **Skill instructions loaded on demand (L2 layer)**: the LLM reads full usage instructions via `skill action=load` — changed from "BM25 auto-match injection" to "catalog resident + LLM active loading", removing the implicit matching step
- **Removed SkillIndexer / SkillMatcher** (BM25 + trigger-word matching) and the SSE event `skill_match`; the frontend "thinking process" now shows "hit skills" tags (dynamically appended when the LLM actually executes skill load)

### 🌐 Optimization: Network Tools (Search Sources / Cache / Anti-Bot / Jina / Adaptive Proxy)

- **web_search source upgrade**: multi-source priority — with a proxy: Bing Global → Bing CN → Baidu; without a proxy (China direct fallback): only CN-reachable sources; new search-result cache (TTL 300s / max 100 entries); low-quality domains (e.g. Baijiahao) down-ranked
- **web_fetch upgraded to "Jina first + local fallback"**: with a working local proxy, fetch prefers Jina Reader rendering (friendly to anti-bot / JS-rendered pages); when Jina is unavailable (429 quota cooldown) or no proxy is present, it automatically falls back to local fetching
- **Local fetching hardened**: a UA pool picked per-domain, retries with rotating UAs (403/429/503/5xx/timeouts/anti-bot interception pages detected; 429 honors Retry-After)
- **Proxy strategy rebuilt as "auto detection"**: `network-tool.proxy.mode` = auto (default) / always / never; AUTO probes candidate ports in order (Clash 7890 / Clash Verge 7897 / socks 7891 / v2rayN 10809) + egress verification (detects "port listening but proxy dead") + directional caching (300s when proxied / 30s when direct, quickly adapting when a proxy is started later)
- **Deprecated `web-fetch.proxy`** (WebFetchConfig removed): all network-tool proxies are unified under `network-tool.proxy`; new ProxyResolver component with unit tests

### 💾 New: Data Management (Backup Export / Import)

- **"Data Management" page gains "Export Backup / Import Backup"**: table-level snapshot JSON export of 7 categories (LLM configs / agents / skills / chat history / lessons / MCP servers / DB connections, 10 tables); import merges via primary-key UPSERT (same id overwritten, new ids appended, existing data never cleared) and repairs auto-increment sequences
- **Backend**: new DataTransferService; SystemController gains `/data-stats`, `/export-data`, `/import-data` endpoints (admin); LLM Provider cache is refreshed after import (newly imported configs take effect immediately); single-row failures do not abort the import (inserted/updated/failed stats returned)
- **Security note**: backup files contain sensitive data such as API keys and encrypted DB passwords (so re-install / cross-device migration needs no reconfiguration); the UI warns users to keep them safe

### 🗄️ Enhancement: Database Connections (Run SQL in Management Page)

- **"Database Connections" page gains a "Run SQL" drawer** (SqlExecutorDrawer): pick a connection → type SQL (Ctrl+Enter to run) → results shown as tables — multiple statements run in order, per-statement error isolation, max-row selection (100~1000), statement count / total elapsed / affected rows / truncation hints
- **New SqlExecutor core component** (shared by the management page and the `execute_sql` AI tool): two-tier checking — strict mode (AI tool: single statement + whitelist + 5000 chars) and relaxed mode (management page: multi-statement + 20000 chars); a single SELECT without LIMIT gets one appended; 30s per-statement timeout
- **API**: new `POST /api/db-connections/{id}/execute`; the shared frontend request helper now supports custom timeouts (long-running SQL)

### 🖼️ New: Avatar Upload (Users / Agents)

- **Local avatar image upload supported** (≤5MB, png/jpg/jpeg/gif/webp/bmp): new AvatarController + AvatarStore (files under `data/avatars/`); `GET /api/avatar/file/{name}` is token-free for direct img references
- **Frontend**: new AvatarUploader component; wired into the profile page, agent config page, and chat avatar rendering (image avatar / default emoji dual form)
- **Database**: `agent_config.avatar` column enlarged VARCHAR(20) → VARCHAR(255) (to store local URLs; existing databases migrate automatically on startup)

### 📎 New: In-App Viewer (File / Image / Link Tabs)

- **Project file paths, images and external links in AI replies are clickable**, opening in in-app tabs (closable anytime):
  - Markdown pipeline extension: local paths in image syntax / link syntax / inline code / plain text are auto-converted into clickable links (file chips / image chips)
  - Local file → file tab; local image → image tab (blob-loaded); http(s) link → embedded `<webview>` web tab (with reload and "Open in browser")
- **Backend**: ProjectService gains `resolveFilePath` (partial path → in-project candidate resolution, exact matches first, max 50, blacklist dirs excluded) and `readFileBytes`; ProjectController gains `GET /api/project/resolve` and `GET /api/project/raw` (Content-Type inferred by extension)
- **Prompt**: the core agent prompt gains "content citation rules" and "in-app viewing" instructions (relative path style / image syntax / link format)

### ⚠️ Enhancement: Friendly LLM API Error Messages (task_error)

- **New LLMErrorFormatter**: converts low-level LLM API exceptions into "title + cause + suggestion" Chinese messages following the official DeepSeek error-code table (400 / 401 / 402 / 422 / 429 / 500 / 503 + context-length special case + network errors)
- **Unified task-level error exit** (tool-loop exceptions / direct no-tool path) emits the `task_error` SSE event; the message is also written to `agent_task.error_message`
- **Task interruption recovery**: the task-status API returns a "last failed task" summary → after page refresh, the chat shows error details and the input area restores the "Task interrupted" banner with a "Continue task" button; AgentEventBus gains identity-checked emission (error events go only to the task's own channel, never to a new task's stream)
- **P2P Agent** handles `task_error` events as well (remote tasks carry the friendly failure reason)

### 🗂️ Optimization: Sidebar Menu Restructure (4 Groups)

- **Menu consolidated into 4 groups + bottom management**: "Chat" / "AI Capabilities" (agents, skills, MCP servers, scheduled tasks, lessons) / "Data & Tools" (database connections, file management, device interconnect) / "System" (model config, run logs, data management) + bottom (profile, user management, menu permissions)
- **Renames**: "Collaboration" → "Device Interconnect", "Config" → "Model Config"
- **Frontend**: Layout.vue switched to MENU_GROUPS config-driven rendering (empty groups auto-hidden) + parallel group loading; MenuPermissionView and api/menu.ts synced to the 5-type menu enum
- **Database**: schema.sql gains idempotent migrations (grouping / ordering / renaming conditional UPDATEs + fixing the user role's broken "AI Assistant" link); new SchemaMenuMigrationTest regression test

### 🛠️ Fixes & Adjustments

- **Fresh-database init fix**: `agent_config` inline indexes replaced with H2-compatible `CREATE INDEX IF NOT EXISTS` (the original index names clashed with the conversation table, causing agent_config creation to fail on a fresh database)
- **Prompt**: core agent prompt slimmed (redundant language-mandate block removed); chat streaming status bar background made transparent (blends into the background image)
- **In-App Viewer fixes** (clicking file / image links in LLM output):
  - Fixed "tab opens but content stays blank / image stuck loading": tab objects now wrapped with `reactive` — the previous implementation mutated raw objects asynchronously after insertion, which does not trigger Vue reactivity
  - Path joining normalized to forward slashes: the old `workDir + '/' + path` produced mixed forms like `C:\dir/sub/file` (poor display, unreliable cross-entry dedup); the same pattern in file-tree double-click / Git actions / permission panel was unified too, with normalized tab-dedup comparison
  - Fixed bare file names (e.g. `README.md` / `main.py`) being linkified into http links (`.md` / `.py` happen to be valid TLDs) — they are now restored as local file links (handled by the "click to open file" flow; real domains like `example.com` are unaffected)
  - File editor / image / web tabs now adapt to the background image: the dark editor becomes translucent + frosted glass (header / gutter / highlight layer backgrounds removed), image & web tab toolbars translucent

### 🏷️ Version Numbers

- Backend: `2.0.1` → `2.0.2` (pom.xml)
- Frontend: `2.0.1` → `2.0.2` (package.json + package-lock.json)
- Electron: `2.0.1` → `2.0.2` (packaged jar path synced to `code-craft-2.0.2.jar`)
- MCP Server handshake version: `2.0.1` → `2.0.2`
- Build artifacts: `code-craft-2.0.1.jar` → `code-craft-2.0.2.jar`
- Docs: README(_EN) / BUILD_AND_RUN(_EN) / docs (ARCHITECTURE, DEEPSEEK_SERVICE_IMPL, TOOL_SYSTEM, LLM_PROVIDER_SYSTEM, DEV_QUICKREF, RELEASE_CHECKLIST, etc.) synced to v2.0.2

---

## [2.0.1] - 2026-09-22

### 🔧 Enhancement: Git Tool Family Completion (AI Tools / REST / Frontend UI)

- **3 new Git tools added (built-in tools 24 → 27)**: `git_stash` (save/list/pop/drop), `git_tag` (list/create/delete/push), `git_remote` (list/add/set_url/remove)
- **`git_submit` extended with 6 actions**: pull, fetch, unstage, restore, reset (--hard requires confirm=true), revert; fixed the dead-end message "run git pull first" when push is rejected (no pull tool existed before)
- **`git_query` new `show` action**: view a commit's details or a file's changes in a commit (ref/file/stat params, output auto-truncated)
- **`git_branch` new `merge` action**: merge a branch into the current one (no_ff/message); `create` supports switch=true; fixed the "stash if necessary" hint that pointed to a non-existent stash tool
- **Permission annotations unified**: `@ToolPermission` added to `git_query` / `git_submit` (write tools have affectsData=true → manual-mode approval; git_submit was previously unapproved)
- **REST layer**: 22 new GitController endpoints (pull / push / fetch / stash×4 / merge / reset / revert / branches / branch×3 / tags / tag×3 / remotes / remote×2); /restore supports `all`
- **Frontend**: GitSidebar gains sync buttons (pull/push/fetch), stash/branch/tag collapsible sections, file restore button; fixed the "unstage" bug that actually called /add; `api/git.ts` +18 functions; tool label mapping updated
- **Config & docs**: tool-groups & prompt tool list updated to 6 Git tools; README(_EN) / BUILD_AND_RUN(_EN) / docs (TOOL_SYSTEM(_EN), ARCHITECTURE(_EN), MCP_SYSTEM) synced (built-in tools 24 → 27)

### 🐛 Fix: Sub-agent repeated-call detection wrongly killing normal batch operations (F3)

- **Root cause**: the fork loop (AgentForkManager) compared only the FIRST tool call's name per round; reading different files / searching different keywords in consecutive rounds (normal batch behavior) was misdetected as an infinite loop. The termination path was a silent `break` — zero logging, status left at its initial value (completed) — surfacing as "sub-agent stopped early with no trace at all"
- **Fix**: ① criteria aligned with the main loop ToolLoopManager ("same tool + same key args" only; all tool calls are iterated), the local incomplete implementation removed; ② termination now logs a warning + sets `completionSuspicious=true` (reusing the ⚠️ verification channel); ③ judge-termination / user-rejection / API parse-failure exits now also set the suspicious flag (exit-path consolidation); ④ `ToolLoopManager.extractToolKey` now includes `pattern` (grep/glob keyword) in the file_explorer/file_writer signature — consecutive searches with different keywords are no longer misdetected
- **Verification**: new regression test SubAgentRepeatDetectTest (8 cases covering both "different files not killed" and "true loop still detected"), 8/8 passed

### 🐛 Fix: file_explorer grep cannot search .log and other non-whitelisted text files

- **Root cause**: grep filtered files by the `TEXT_EXTENSIONS` whitelist; `.log` (plus .csv/.diff/.mjs etc.) was not listed and got silently skipped. Even an explicit `include` could not bypass it — log contents were unsearchable (unrelated to file size: a 372KB log was equally affected; read has no such limit, and the inconsistent behavior easily misled "the file doesn't contain it" conclusions)
- **Fix**: ① whitelist expanded (.mjs/.cjs/.log/.csv/.tsv/.diff/.patch/.rst/.adoc/.jsonl/.ndjson/.lock); ② new include escape hatch (explicitly specified types bypass the default whitelist; binary blocking preserved); ③ zero-hit hint now suggests using include for special suffixes
- **Verification**: new regression test FileExplorerGrepLogTest (6 cases, red-green: 2/6 before fix → 6/6 after)

### 🏷️ Version Numbers

- Backend: `2.0.0` → `2.0.1` (pom.xml)
- Frontend: `2.0.0` → `2.0.1` (package.json + package-lock.json)
- Electron: `2.0.0` → `2.0.1` (packaged jar path synced to `code-craft-2.0.1.jar`)
- MCP Server handshake version: `2.0.0` → `2.0.1`
- Build artifacts: `code-craft-2.0.0.jar` → `code-craft-2.0.1.jar`
- Docs: BUILD_AND_RUN(_EN) / docs (ARCHITECTURE, DEEPSEEK_SERVICE_IMPL, LLM_PROVIDER_SYSTEM, TOOL_SYSTEM, DEV_QUICKREF, RELEASE_CHECKLIST, etc.) synced to v2.0.1

---

## [2.0.0] - 2026-09-16

### 🖼️ New: Vision & File Management (Files API)

- **Paste/upload images straight to LLMs**: Ctrl+V paste or upload images in chat (≤64MB), referenced into user messages via the cloud Files API (file_id) with automatic base64 fallback for Providers without Files support; multi-image and historical message image playback supported
- **Files API adapter layer**: new FilesApiClient abstraction (interface + AbstractFilesApiClient + DeepSeek/Anthropic implementations + FilesApiClientManager), routing per Provider following the LLMClient adapter pattern
- **File asset module**: new file_asset / file_reference tables with FileAssetController / FileAssetMapper / FileReferenceMapper / service/files (FileAssetService / FileAssetStore / FileVisionService); files retained permanently (no more expires_after); cascade deletion when references drop to zero (with orphan compensation retry)
- **Standalone File Manager page** (SETTING menu): upload / list / rename / delete / preview, "reference to session" dual entry; automatic compression for large pasted images
- **Unified document attachments (M7)**: storage_type distinguishes cloud (images via cloud Files API) / local (PDF/Word/Excel/text stored locally); document reads go through the chat_attachment read_by_file_asset channel
- **Frontend**: chat image message component with privacy notice, FileManageView page, file-asset API module

### 🪟 New: Desktop Automation (Computer Use)

- **New `screen_capture` tool (the 23rd)**: screenshot → resolution normalization → auto-upload to Files API → injected into context as a user message; overlay with 10×10 grid + three-level edge rulers (25/50/100) for coordinate reading; region zoom (magnifier) and mark crosshair support (estimate → marked screenshot → visual correction → act)
- **New `desktop_control` tool (the 24th)**: desktop mouse/keyboard simulation (mouse_move / mouse_click / mouse_drag / mouse_scroll / key / type / screen_size), 1–20 batch actions per call (single-action mode also supported); 0–1000 normalized coordinate protocol (based on the latest screenshot)
- **Safety mechanisms**: DESKTOP high-risk permission category (approval dialog shows action summary) + crosshair preflight gate (unverified click/drag coordinates return a crosshair check image first; preflight rounds exempt via SideEffectFreePreflight) + red lines (no password/payment input)
- **Cross-platform**: DesktopPlatformAdapter (Windows / macOS / Linux); new `desktop` config block in application.yml (capture / control)

### ⚡ Optimization: Streaming Render Performance (stream-render-perf M1–M5)

- **Frontend rendering**: segmented stable rendering, update pipeline de-duplication, giant-message slimming, streaming degradation (very large messages fall back to plain text while streaming, Markdown restored after)
- **Backend SSE batch push (M5)**: SseBatchUtil merges pushes by output boundaries (50ms / 32 items), zero frontend changes

### 💰 New: Provider Balance Query

- New ProviderBalanceController / ProviderBalanceService (DeepSeek `/user/balance`); chat page refreshes the balance on entry and after each turn

### 🛠️ Fixes & Enhancements

- **LLM network retry enhancement**: LLMWebClientManager auto-retry on network errors + auth attribution fix
- **Global exception handling**: new GlobalExceptionHandler as unified fallback
- **Permission pipeline**: OperationCategory gains DESKTOP; new SideEffectFreePreflight (no-side-effect preflight exemption) and ToolPermissionLevel
- **DB connections**: connection test fix
- **Agent-to-agent invocation enhancement**: the `agent_invoke` tool description now dynamically injects an "available agents" roster (delegating agents can discover targets on their own; calling by ID is recommended)

### 🏷️ Version Numbers

- Backend: `1.1.8` → `2.0.0` (pom.xml)
- Frontend: `1.1.8` → `2.0.0`
- Electron: `1.1.8` → `2.0.0` (packaged jar path synced to `code-craft-2.0.0.jar`)
- MCP Server handshake version: `1.1.8` → `2.0.0`
- Build artifacts: `code-craft-1.1.8.jar` → `code-craft-2.0.0.jar`
- Docs: README(_EN) / BUILD_AND_RUN(_EN) / docs (ARCHITECTURE, TOOL_SYSTEM, MCP_SYSTEM, etc.) synced to v2.0.0 (built-in tools 22 → 24)

---

## [1.1.8] - 2026-09-14

### 🤝 New: Agent-to-Agent Invocation

- **New `agent_invoke` built-in tool (the 22nd)**: Agent A delegates a task to Agent B (an agent_config instance) mid-execution — B works in an independent session as itself (own role/model/work dir/toolset); supports `create_session` / `continue_session` / `poll` / `cancel`
- **Delegation as authorization**: one approval of `agent_invoke` in manual mode counts as explicit delegation consent; B then runs without further prompts; trust propagates along the call chain (cycle detection + max depth 3); stopping A cascades cancellation to B's delegated task
- **Transparent & auditable**: B's delegated session appears in its conversation list (name carries the "[A 委托]" prefix); agent_task gains caller provenance; B's real result and tool-trace summary are returned as-is
- **Integration**: `agent_invoke` added to the MCP exposure whitelist; core agent prompt gains A2A usage rules; new design doc `docs/AGENT_INVOKE_DESIGN.md`

### 🗄️ New: External Database Connections (Data Management)

- **"DB Connections" management page** (SETTING menu): configure external MySQL / PostgreSQL / H2 business databases; passwords stored AES-256-GCM encrypted (reuses P2P CryptoHelper), never echoed via API; test-connection and enable/disable supported
- **`execute_sql` gains a `connection` parameter**: omitted = CodeCraft system DB (backward compatible); otherwise connects to the named external business DB, and results label the target DB
- **Backend**: new `db_connection` table, DbConnectionController / Mapper / Entity, service/dbconnection (DbConnectionService / DbConnectionManager / CredentialCipher); pom adds mysql-connector-j & postgresql drivers
- **Frontend**: new DbConnectionView page and db-connection API

### 🚦 Optimization: LLM Rate Limiting & Retry (P4)

- **Global concurrency limit**: rebuilt around DeepSeek's official concurrency model (v4-pro 500 / v4-flash 2500 per account) as a single global semaphore (QPS token bucket & per-provider buckets removed); queued wait when full, "Provider busy" on timeout
- **429/5xx exponential backoff retry**: up to 3 attempts (1s → 2s → 4s, capped at 10s); stream-safe — only header-stage errors are retried, no duplicated stream data

### 📚 Optimization: Lesson Retrieval (P1)

- **Error-code alias normalization**: new canonical-aliases mapping (e.g. CMD_ENCODING_MISMATCH → CMD_ENCODING) unifies synonymous codes to avoid same-pit fragmentation
- **Retrieval improvements**: `lesson` keyword supports space-separated multi-word fuzzy matching; `error_code` accepts raw error text and is auto-normalized

### 🖥️ Optimization: Runtime Log Page

- Supports **select-to-copy** and **log export**

### 🛠️ Fixes & Adjustments

- **Desktop launchers**: start.bat / start.sh now dynamically scan the latest `code-craft-*.jar` in `target` (removes hardcoded JAR-name drift, aligned with main.js logic)
- **Frontend**: input-box lock fix after switching back to an agent; session-restore and menu-icon (DB connections) adjustments
- **MCP Server handshake version fix**: `1.1.6` (missed in the 1.1.7 bump) → `1.1.8`

### 🏷️ Version

- Backend: `1.1.7` → `1.1.8`
- Frontend: `1.1.7` → `1.1.8`
- Electron: `1.1.7` → `1.1.8`
- MCP Server handshake version: `1.1.6` → `1.1.8` (fixes the 1.1.7 miss)
- Build artifact: `code-craft-1.1.7.jar` → `code-craft-1.1.8.jar`
- Documentation: README(_EN) / BUILD_AND_RUN(_EN) / docs (TOOL_SYSTEM, ARCHITECTURE, MCP_SYSTEM, etc.) synced to v1.1.8

---

## [1.1.7] - 2026-08-18

### 🌐 New MiniMax LLM Provider

- **New MiniMaxClient**: Supports MiniMax API (OpenAI-compatible protocol), covering M3 flagship / M2.7 / M2.5 / M2.1 / M2 model family
- **Thinking Mode Mapping**: `non-thinking` → `thinking.type=disabled`, `thinking` → `enabled`, `thinking_max` → `adaptive` (M3 adaptive deep thinking; M2.x server ignores disabled)
- **Output Parameter Compatibility**: `max_tokens` automatically converted to `max_completion_tokens` (MiniMax deprecated max_tokens), default 65536
- **reasoning_split Switch**: Splits thinking content into `reasoning_content` field, avoiding `<think>` tag mixing into body; natively compatible with AbstractLLMClient's default delta.reasoning_content parsing
- **Authentication**: Authorization: Bearer $MINIMAX_API_KEY (same as DeepSeek / OpenAI, reusing getAuthHeader / getAuthHeaderPrefix routing)
- **Routing Integration**: LLMClientManager adds `case "minimax"` → instantiates MiniMaxClient; LLMWebClientManager auth header comment updated for minimax (shares Authorization + Bearer with deepseek/openai)
- **Frontend Option**: ConfigView / ProviderConfigView Provider dropdown adds `minimax` (purple theme #4a36e0, emoji 🔮); templateLabel / providerEmoji / templateStyle all synchronized
- **Documentation Sync**: README.md / README_EN.md / docs/ARCHITECTURE(_EN).md / BUILD_AND_RUN(_EN).md / docs/LLM_PROVIDER_SYSTEM(_EN).md / docs/MCP_SYSTEM.md — 7 documents synchronized to add MiniMax, Provider impls count 5 → 6
- **Keywords**: Project root README keyword list adds `minimax`

### 🏷️ Version

- Backend: `1.1.6` → `1.1.7`
- Frontend: `1.1.6` → `1.1.7`
- Electron: `1.1.6` → `1.1.7`
- MCP Server handshake version: `1.1.6` → `1.1.7`
- Build artifact: `code-craft-1.1.6.jar` → `code-craft-1.1.7.jar`

---

## [1.1.6] - 2026-08-12

### 📚 Growth System: Lesson Knowledge Base Evolution (P0 Normalization + P1 Detour Channel + P2 Environment Awareness)

- **P0 Error Normalization Pipeline**: new "Error Code Dictionary" (configured in application.yml, regex-matches error text → stable short code + error category); when no rule matches, LLM semantic normalization is the fallback (`LessonNormalizerService`, async queue + `lesson_norm_cache` cache, cache hits skip the LLM call)
- **P1 Detour Channel (DETOUR)**: experience type split (FAILURE failure experiences / DETOUR detour experiences); detour experiences are fingerprinted by task goal dimension; at task start the system auto-searches detour experiences and injects a "detour warning" system hint (conversation-level dedup); the 【方案取舍】 (Plan Trade-off) block in the LLM's final reply is auto-parsed and saved; signal scanning (user negation/correction, LLM self-reported plan change, tool-sequence signals) + async LLM judgment & distillation (C3)
- **P2 Environment-Aware Retrieval**: the OS environment is auto-attached when recording (env_params); retrieval ranks "generic > same-OS > different-OS" without killing generic experiences
- **P2 Rule Self-Learning Table**: new `lesson_rule` table distills "error text fragment → category + short code" rules (auto-saved when LLM normalization succeeds, unique index prevents concurrent duplicates); rule table takes priority at extraction; dashboard enforces capacity cap by evicting the oldest "candidate & never-extracted" rules (replacement over time)
- **Management UI**: experience list gains a "Type" column and filter (FAILURE/DETOUR), detail shows type and goal, dashboard gains a "Detour Experiences" card; dashboard adds normalization metrics (rule match / LLM call / cache hit / backfill) and rule stats (total/candidate/active/usage)
- **lesson tool**: `search` gains a `type` parameter (FAILURE / DETOUR filter); examples include detour experience lookup
- **Core Agent Prompt Slimming**: heavy redundancy removed (tool lists now defer to function definitions), new P1 planning convention — "search detour experiences before planning a new task" and the 【方案取舍】 block output spec (auto-saved as type=detour)
- **Database**: `lesson` table gains type / goal / env_params columns (H2-compatible ALTER TABLE ADD COLUMN IF NOT EXISTS); new tables `lesson_norm_cache` and `lesson_rule` with indexes

### 🏷️ Version Bump

- Backend: `1.1.5` → `1.1.6`
- Frontend: `1.1.5` → `1.1.6`
- Electron: `1.1.5` → `1.1.6`
- MCP Server handshake version: `1.1.5` → `1.1.6`
- Build artifact: `code-craft-1.1.5.jar` → `code-craft-1.1.6.jar`

---

## [1.1.5] - 2026-08-11

### 📚 Growth System: Lesson Knowledge Base

- **New `lesson` built-in tool**: five-in-one — search (retrieve experiences) / record (save experiences) / complete (fill in drafts) / feedback (validate results) / list (inspect the library), with per-project isolation
- **Auto Capture**: ToolExecutor automatically saves a draft (source=auto) when a tool call fails; LLM proactive records (source=llm), manual curation (source=manual), and turn-level review (source=review) all converge into the same library
- **Passive Injection**: on tool failure, the knowledge base is auto-searched and the LLM receives a "solution hint + completion guide" (conversation-level dedup, zero standing cost, never blocks the main flow)
- **Validation Loop**: F3 tracking auto-feedback — if the same error does not recur by the end of the session after a solution was injected, it counts as effective; if it recurs, it counts as ineffective. Experiences promoted to ACTIVE after 2 effective reuses, hidden after 5 consecutive failures
- **Turn-Level Review (C2)**: after the tool loop ends, an async LLM pass distills root cause / solution, so even friendly non-exception failures get captured
- **Management UI**: new "踩坑经验" (Lesson) page (/lesson-manage, SETTING menu group) with pagination, details, manual editing, feedback, delete, and a growth dashboard (total/draft/active/hidden/hits/success rate)
- **Core Agent Prompt**: built-in tool list 20 → 21, added the highest-priority "growth loop" rule (search → apply → record → feedback)

### 🔧 Build Script Fix

- **sync-version.bat**: `setlocal DisableDelayedExpansion` fixes the "Expected ')', got '='" error caused by delayed-expansion swallowing `!` characters (e.g. `!j.build`) in the build.bat call chain

### 🏷️ Version Bump

- Backend: `1.1.4` → `1.1.5`
- Frontend: `1.1.4` → `1.1.5`
- Electron: `1.1.4` → `1.1.5`
- MCP Server handshake version: `1.1.4` → `1.1.5`
- Build artifact: `code-craft-1.1.4.jar` → `code-craft-1.1.5.jar`

---

## [1.1.4] - 2026-08-10

### 🛰️ MCP Bidirectional Support (Model Context Protocol)

- **MCP Server**: Exposes built-in tools as MCP services for external clients like Claude Desktop / Cursor (Streamable HTTP transport + optional X-API-Key auth + whitelist control)
- **MCP Client**: Connects to external MCP Servers and dynamically registers their tools into ToolRegistry for LLM invocation (http/stdio dual transport, custom headers, non-blocking startup on failure)
- **MCP Server Management Tool**: New built-in `mcp_server_manager` tool — create/delete/connect/disconnect/refresh MCP servers directly in the chat window, no config page needed
- **Dynamic Permission Levels**: MCP external tools map to SAFE/DATA/HIGH_RISK permission metadata; tools & permissions auto-unregistered on disconnect
- **Management UI**: New MCP server config page (/mcp-config) with CRUD, 3-state connection status, and tool count

### ✨ Prompt System Optimization

- **Refactored core agent system prompt** (code_agent_prompt.txt): tool list corrected to 20 built-in tools, added MCP external tool section, working directory / execution mode / context compaction / snapshot protection behaviors
- **Refactored judge prompt** (judge_prompt.txt): dynamic iteration limit (no longer hard-coded 50), refined extend/reject criteria, explicit JSON output format with Chinese fields
- **Enhanced prompt optimizer** (prompt_optimize.txt): code-request rewrite rules (keep identifiers as-is), 500-char length limit, explicit no-optimization scenarios

### 🔧 Fixes & Optimizations

- **application.yml** tool list now includes `mcp_server_manager`, aligned with the 20 registered built-in tools
- **.gitignore** excludes planning-with-files workspace files (task_plan.md / findings.md / progress.md / .planning/)

### 🏷️ Version Bump

- Backend: `1.1.3` → `1.1.4`
- Frontend: `1.1.3` → `1.1.4`
- Electron: `1.1.3` → `1.1.4`
- MCP Server handshake version: `1.1.3` → `1.1.4`
- Build artifact: `code-craft-1.1.3.jar` → `code-craft-1.1.4.jar`

---

## [1.1.3] - 2026-07-02

### 🌐 Multi LLM Provider Support System

- **Multi-Provider Support**: Supports mainstream LLM platforms including DeepSeek, OpenAI, Anthropic, Ollama, MiMo
- **Dynamic Switching**: Frontend can switch Providers at runtime without restarting the service
- **Agent Binding**: Each Agent can be bound to a specific Provider and model
- **Unified Interface**: All Providers are called through a unified `LLMClient` interface, shielding platform differences
- **Hot Refresh**: Client cache is automatically refreshed after Provider configuration changes

### 🏗️ Core Architecture Changes

- **New LLMClient Abstraction Layer**: Unified interface for DeepSeek/OpenAI/Anthropic/Ollama/MiMo
- **New LLMClientManager**: Core manager for Provider registration, routing, and caching
- **6 New Provider Implementations**: DeepSeekClient, OpenAIClient, AnthropicClient, OllamaClient, MiMoClient, AbstractLLMClient
- **New LLMProviderController**: REST API controller for Provider CRUD operations
- **New llm_provider Database Table**: Stores Provider configuration information

### 🎨 Frontend UI Enhancements

- **New Provider Switching UI**: CodeAssistantView supports runtime Provider switching
- **Dynamic Model List**: Automatically refreshes model list based on current Provider
- **No Provider Guide**: Displays guide interface when no Provider is configured
- **Agent Config Enhancement**: AgentConfigView adds Provider binding configuration

### 🔧 Fixes & Optimizations

- **Tool call parameter accumulation saves partial content on interruption**
- **Judge extension counter memory leak prevention**
- **Sub-agent timeout statistics**
- **NullNode handling fix**

### 🏷️ Version Bump

- Backend: `1.1.2` → `1.1.3`
- Frontend: `1.1.2` → `1.1.3`
- Electron: `1.1.2` → `1.1.3`
- Build artifact: `code-craft-1.1.2.jar` → `code-craft-1.1.3.jar`

### 📦 New Dependencies

- Backend: `LLMClientManager`, `LLMClient` interface and 6 implementation classes
- Frontend: `llm-provider.ts` API interface, `ProviderConfigView.vue` page

---

## [1.1.2] - 2026-07-01

### 🌡️ Agent Temperature Support (Full-Stack)

- **Database**: `agent_config` table adds `temperature` column (DOUBLE, default 0.3), `AgentConfigServiceImpl` auto-creates table / ALTER TABLE for backward compatibility
- **Entity**: `AgentConfig` adds `temperature` field, MyBatis Mapper all SQLs synced
- **API**: `ChatRequest` / `ForkAgentRequest` add `temperature` parameter
- **Service**: `DeepSeekServiceImpl` supports 3-tier priority (frontend > Agent config > default 0.3), `ToolContext` adds `temperature` ThreadLocal
- **Sub-Agent**: `AgentForkManager` sub-agent execution uses configured temperature, `AgentTool` passes it on fork
- **Frontend UI**: `AgentConfigView` adds temperature slider (0~2, step 0.1) with marks and real-time value display

### 🐚 Smart Shell Discovery & Multi-Shell Support

- **Intelligent Shell Discovery**: `CommandUtils` refactored from hardcoded `cmd`/`sh` to auto-detect best available shell
  - Windows: pwsh → powershell → Git Bash → cmd (priority order)
  - Unix/Linux: `$SHELL` env → zsh → bash → sh
- **New `ShellDiscoveryService`**: Unified shell discovery and command wrapping, used by `ProjectBuildService` for all build/run/exec commands
- **Working Directory Switching**: Shell wrapper commands support `cd` prefix (cmd: `cd /d`, pwsh: `Set-Location`, bash: `cd &&`)
- **Git Bash Detection**: Auto-search Program Files / LocalAppData for Git Bash installation paths

### 🖥️ Frontend Terminal Panel with xterm.js

- **New `TerminalPanel.vue`**: Standalone terminal component, integrated into right toolbar "Terminal" tab
- **xterm.js Integration**: `@xterm/xterm` (6.0.0) + `@xterm/addon-fit` (0.11.0), replacing the old hand-written HTML terminal
- **CodeAssistantView Refactor**: Removed old terminal tab (shell-style terminal + run log hybrid), simplified to "Run Log" panel only
- **Log xterm Terminal**: Run log rendered via xterm.js with light/dark theme auto-adaptation (Pinia settingsStore + system `prefers-color-scheme` dual listener)
- **Log Panel Enhancements**: Independent drag resize, `xterm.clear()` for clearing, incremental `writeln` sync

### 📂 Directory Browser Enhancement

- **`DirectoryEntry` adds `directory` property**: Distinguish directories from files for proper icon display
- **`listChildren` adds `includeFiles` parameter**: On-demand file listing (default directories only), used for terminal tab completion
- **Sorting optimization**: Directories first, then alphabetical by name
- **Frontend API**: `project.ts` adds `includeFiles` query param, `DirectoryEntry` interface adds `directory` field

### 🔧 Fixes & Optimizations

- **artifactId fix**: `pom.xml` `artifactId` corrected from `codecraft` to `code-craft` (matching jar filename `code-craft-{version}.jar`)
- **Default Agent name**: Renamed from "编码助手" to "AI 助手"
- **Frontend text alignment**: Multiple CSS fixes adding `text-align: left` to messages/code/logs/tool results
- **Dark theme console cleanup**: Removed deprecated `.console-panel` / `.console-tab-bar` dark theme styles

### 🏷️ Version Bump

- Backend: `1.1.1` → `1.1.2`
- Frontend: `1.1.1` → `1.1.2`
- Electron: `1.1.1` → `1.1.2`
- Build artifact: `code-craft-1.1.1.jar` → `code-craft-1.1.2.jar`

### 📦 New Dependencies

- Frontend: `@xterm/xterm` ^6.0.0, `@xterm/addon-fit` ^0.11.0
- Backend: `ShellDiscoveryService` new service class

### 📄 New Files

- `frontend/src/components/TerminalPanel.vue` — Standalone terminal panel (xterm.js)
- `src/main/java/com/example/agentdeepseek/service/ShellDiscoveryService.java` — Shell auto-discovery service

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

## [1.1.0] - 2026-06-04

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
