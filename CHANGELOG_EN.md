> 🌐 中文版：[🇨🇳 CHANGELOG](./CHANGELOG.md)
# Changelog

This document records all important version changes of the CodeCraft project.

---

## [3.0.4] - 2026-10-20

> Experience-fix release: we chased two long-standing annoyances down to their roots —
> "a new version is detected but the app still offers to install the old package" and
> "file links in replies do nothing when clicked". Prompt optimization gained loading feedback
> and undo, approval / clarification cards are now docked so long conversations can't push them
> out of view, clarification questions can be answered by clicking 1./2./3. options, skill tags
> show the real skill name, and the release workflow now embeds the actual change list.
> Version numbers are unified at 3.0.4; no database schema changes.

### 🔄 Auto-update: no more installing the wrong package

- **Main-process guard**: new `invalidateStaleDownload(targetVersion)` — after `checkForUpdates()` finds the latest version (and before `publicState()` is emitted) `downloadState.version` is compared with the latest version; a mismatch drops the download state and broadcasts via `emitDownloadState()`. Previously `downloadState` survived across checks, so after downloading 3.0.1 and then releasing 3.0.2 the "Install" button still pointed at the old package.
- **Restore gate on startup**: `initUpdateManager()` used to restore "downloaded" merely because the file existed; it now also requires `compareVersion(latestInfo.version, app.getVersion()) > 0` and a matching file size.
- **Check before install**: `installDownloaded()` gained a version gate — if the local package version differs from the latest, it throws "please download again" instead of installing a stale build.
- **Frontend second line of defence**: `VersionCard` adds `versionFromFileName()` (regex `/(\d+\.\d+\.\d+)/`, so `.exe` is not mistaken for a prerelease suffix), `downloadVersion` and `staleDownload`; the button reads "Re-download vX" and a hint explains the version mismatch.

### 🔗 File links could not be opened (fixed)

- **Root cause**: workspace tabs passed the **relative path** from the message straight to `GET /api/project/read`; the backend resolves it against `user.dir` (the jar directory under Electron), so it read something else entirely. The legacy `openLocalFileTab` used to build an absolute path first and fall back to `/api/project/resolve` suffix matching — that layer was lost during the v3 migration.
- **New `utils/file-path.ts`**: `normalizeFsPath` / `isAbsoluteFsPath` / `joinFsPath` / `absolutizePath` / `resolveFileTarget` / `describeMissingFile`. `resolveFileTarget()` returns `{ raw, path, found, candidates, viaResolve }`: join with the working directory first, then fall back to the backend resolve endpoint and surface the candidate list.
- **Resolve on click**: `ChatView.onStreamClick` is now async and resolves the target before handing it to `workspace.openTab` / `openFilePreview`; `openFilePreview` accepts `(target: string | ResolvedFileTarget, kind)`. Resolving at click time (instead of inside the tag) prevents the same file opening as two tabs when referenced once relatively and once absolutely (`tabKeyOf` keys on `payload.path`).
- **Workspace views**: `WorkspaceFileView` gained a `root` prop plus `resolvedPath` and uses the resolved path for both reads and writes; `WorkspaceTabView` forwards `:root="root"` and resolves in `loadImage` too.
- **Bare file names**: `markdown.ts` `isLocalFilePath(p, allowBareName = false)` now recognises plain file names (via `FILE_EXT_STRICT_RE`) only for explicit links / images (`allowBareName=true`); inline text scanning still requires a path separator so ordinary prose is not mistaken for a path.
- **Readable errors**: failures now report connection of "working directory + candidate paths + backend message"; `.cc-wsv__state-text` / `.cc-fileview__state.is-error` in `app.css` use `white-space: pre-line; word-break: break-all` so long paths are no longer truncated.

### ✨ Prompt optimization: loading feedback and undo

- **Request layer**: `optimizePrompt` in `api/chat.ts` now returns `OptimizePromptResult { ok, optimized, error, elapsedMs, model }` instead of silently falling back to the original text; a 30 s `AbortController` backstop (backend timeout 15 s plus transport margin); 401 goes through `notifyUnauthorized`; errors distinguish "non-JSON response (backend too old)", "network down" and "timeout".
- **Loading feedback**: the ✨ button becomes a spinning ring (`aria-busy`) and is disabled against double clicks; a status bar above the textarea shows "AI is optimizing your prompt · Ns elapsed" (ticks every second, stops at 60 s); the textarea is `readonly` and sending is blocked while optimizing (`readonly` rather than `disabled`, so colours stay normal).
- **Undo**: `ChatView` keeps an `optimizeUndo` snapshot; `optimizeUndoAvailable` is true only while the draft still equals the optimized result; `applyOptimizedDraft()` / `undoOptimize()` are exposed via `defineExpose`; sending or switching conversations clears the snapshot so undo cannot leak across sessions.
- **Outcome granularity**: `App.vue` `onOptimize` was rewritten with re-entry protection and four toast states (`toast.loading` → success / error / info / warning), reporting elapsed time and character count; it skips overwriting when the conversation changed or the draft was edited; drafts over `OPTIMIZE_MAX_INPUT = 6000` are rejected with a hint to split the text (the model's `max_tokens` cannot hold it, and waiting 10 s only yields a truncated, worse result).
- **Backend**: `PromptOptimizeService` adds an `OptimizeResult` record with granular failure reasons (missing template / no provider / timeout / empty result / call exception); `MAX_TOKENS` raised 500 → 800 (the default model may be a reasoning model, where 500 tokens are easily consumed by thinking and content comes back empty); `PromptOptimizeController` now emits a `LinkedHashMap` (`Map.of` rejects nulls) while keeping the bare JSON shape compatible with the classic frontend.

### 📌 Approval / clarification cards are docked in place

- The card used to be inline at the end of the message list — in long conversations you had to scroll all the way down to notice that "the AI stopped and is waiting for you". It now lives outside the message stream in a fixed `.cc-approval-dock` above the composer: `flex: 0 1 auto` + `min-height: 0` + `overflow: hidden` so it can shrink on small windows without pushing the composer off screen.
- The dock header shows "waiting for authorization / waiting for your answer / waiting for your confirmation" depending on `askType`, with a pulse indicator; the body scrolls internally at `max-height: min(46vh, 460px)` so long diffs remain readable.
- A new `watch(() => props.approval)` scrolls to the latest message only when the user was already at the bottom (never interrupting someone reading history), plus a "back to latest" button.

### 🗂 Answer clarification questions by clicking a direction

- New `utils/ask-options.ts`: `parseAskOptions(markdown)` parses `1. / 2. / 1、/ (1)`, `A.` and `-` style list items from the question, stripping fenced code blocks, normalising literal `\n` and removing markdown decorations.
- **Mis-detection guards**: at least 2 items, at most 6, average length ≤ 90 characters and raw block ≤ 400 characters — otherwise it falls back to the plain textarea.
- The clarification branch of `ChatApproval` renders option buttons; one click answers with the original numbering (`${mark}. ${plain}`), with a `picking` flag preventing duplicate submissions.
- The backend `AskClarificationTool` parameter description now insists that multiple directions be listed as `1./2./3.` lines — zero protocol change on the frontend, just better hit rate.

### 🎯 Skill hits show the real skill name

- Tag text changed from `🎯 {name}` to `🎯 命中技能：{name}`; `hitSkills` are cleaned before display (strip `**` and leading markers, turn bare IDs like `#12` into "技能 #12", de-duplicate).
- `utils/skill-hit.ts`: new `cleanSkillName()`; the `parseSkillHitsFromSummaries` regex changed from `(.+)$` to `/🎯\s*命中技能\s*[:：]?\s*\*{0,2}\s*([^\n]+)/` — summaries ending with `\n\n` previously made the whole match fail, which is exactly why the tag showed `XXX`.
- `App.vue` adds `skillNameMap` / `ensureSkillNameMap()`: lazily fetches the skill list once to build a `#id → name` map (no retry on failure), `setConvMessages()` applies `withSkillNames()`, and `watch(skillNameMap)` backfills already-rendered history. Unresolved IDs render as "技能 #12" instead of a bare number.

### 📝 Release workflow: Release notes now include the change list

- The body in `.github/workflows/release.yml` used to be a hard-coded template with download instructions and the macOS note only — **it never contained the actual changes** (that is why the Release page looked like it had "no release notes"). The create-release job now checks out the repo and extracts the current version's section from `CHANGELOG.md` with `awk`, appending it to the Release body; if extraction fails it falls back to the CHANGELOG link.
- Also fixed the Windows asset name in the body: it said `CodeCraft-Setup-x.y.z.exe` while the real asset is `CodeCraft.Setup.x.y.z.exe`.

### 🧾 Version bump

- Backend: `3.0.3` → `3.0.4` (`pom.xml`, artifact `code-craft-3.0.4.jar`)
- Classic frontend: `3.0.3` → `3.0.4` (`frontend/package.json` + lock)
- v3 frontend: `3.0.3` → `3.0.4` (`frontend-v3/package.json` + lock)
- Electron: version and `extraResources` jar path → `3.0.4` / `code-craft-3.0.4.jar`
- MCP Server handshake version: `3.0.3` → `3.0.4` (`CodeCraftMcpServer.SERVER_VERSION`)
- Docs: README(_EN) badge, ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) headers, DEV_QUICKREF / RELEASE_CHECKLIST(_EN) build commands and the FAQ version example are unified at v3.0.4

### ⚠️ Upgrade notes

- **No database schema changes**, but there are backend changes (prompt-optimization response shape, clarification prompt) — **install the new package over the old one**; replacing only the frontend static files is not enough.
- The auto-update fix in the main process only takes effect with a new package: the main process of 3.0.3 and earlier still has the old logic and may still offer a stale installer if one is lying around — installing 3.0.4 restores correct behaviour.
- File-link resolution relies on `GET /api/project/read` and `/api/project/resolve`; the working directory follows the current settings (conversation override > agent work dir > global setting). If a link still fails, please report the candidate paths shown in the error.

---

## [3.0.3] - 2026-10-20

> Stability release: finds and fixes the real culprit behind "expanding long reasoning freezes the app"
> (O(n²) backtracking in the markdown local-path regex), adds proper loading / error / scroll-anchor
> feedback to paginated chat history, redirects to the login page when the session expires, renders
> attachments on user messages, and fixes the auto-updater installing a stale package.
> Version numbers are unified at 3.0.3; no backend API or schema changes.

### ⚡ Long reasoning freeze (root cause: regex backtracking)

The previous release blamed "too frequent rendering" and only added throttling plus length caps — a band-aid. This release pins down the real culprit and removes it:

- **Root cause**: the local-path detector in `utils/markdown.ts` used `PATH_RE` with `[^...]*[\\/]` — "any-length prefix + a slash". On a **long single run of text with no slash at all** (typical for long Chinese reasoning, which rarely contains paths) it backtracks at every position and degrades to O(n²). Measured on plain Chinese text: 2k chars 51ms, 8k chars 774ms, 16k chars 2721ms, 32k chars **10697ms**, and ~40s for 60k chars — exactly the "click to expand and it dies" feeling.
- **Why only expansion froze**: historical reasoning is lazily rendered (`p.html` stays empty), so the markdown + path pass ran synchronously inside `processHtml` the moment you expanded, concentrating all the cost in that single click.
- **Fix**: two-stage linear matching — `PATH_CANDIDATE_RE` splits on whitespace (O(n)), then a `^`-anchored `PATH_WORD_RE` validates each token; `splitTextByFilePath` also short-circuits when the text contains neither `\` nor `/`. After the fix: 32k chars **1ms**, 160k chars 5ms, 76k chars with 6000 paths 46ms. All 10 behaviour-parity cases pass (Windows absolute paths, relative paths, multiple paths per line, URL exclusion, bare filenames not misdetected, trailing punctuation).
- **Highlight fallback**: `highlight.js` is synchronous CPU work (328ms for 3000 lines of Java), so a 20k-char threshold was added — `PROC_HIGHLIGHT_LIMIT` for reasoning, `HISTORY_HIGHLIGHT_LIMIT` for historical message bodies. The streaming render cap `FLUSH_RENDER_LIMIT` dropped from 120k to 20k chars.
- Previous optimisations are kept: lazy rendering of historical reasoning, zero DOM when collapsed (`v-if`), 15k streaming / 60k per-message chunking, and token estimation cached per message fingerprint.

### 🐞 Paginated chat history

- **Loading and error states**: `ChatView` gains `loading` / `loadingMore` / `loadError` props. Switching conversations no longer flashes the "start a new chat" empty state while the page request is still in flight; a failed load no longer pretends to be an empty conversation — it shows an error icon, the reason, and a **Retry** button (`reload` event).
- **Top-of-list feedback**: the placeholder shown when scrolling to the top now switches to "Loading earlier messages…" while the request is running, instead of appearing unresponsive.
- **Scroll-anchor compensation**: `requestMore()` records `scrollHeight` / `scrollTop`; when data arrives, `nextTick` recomputes `scrollTop` from the height delta to pin the viewport to the message the user was reading, then `requestAnimationFrame` corrects once more (markdown and highlighting keep growing the content on the next frame). Local window expansion (`showEarlier()`) also corrects a second frame, and only when the offset exceeds 2px, to avoid jitter. `prependAnchor` was moved above the watcher to avoid a TDZ error.
- Pagination cursors are now per-conversation (`cursorMap` / `hasMoreMap`), so a slow request from a previous conversation can no longer overwrite the new one; `chatLoadError` is separate from `chatError` (pagination failure vs. send failure / interrupted task).

### 🔐 Session expiry redirects to login

- `utils/http-client.ts` gains `notifyUnauthorized(reason)`: clears the token and fires the router-registered unauthorized callback so the app navigates to the login page.
- The 401 branch in `utils/sse-client.ts` used to only call `clearAuthToken()` (UI stayed put, subsequent requests kept failing); it now calls `notifyUnauthorized('Login expired, please sign in again')`.
- `App.vue`'s stream-consumer `catch` returns early on `HTTP 401` / "login expired" instead of also showing a "send failed" toast, so no red error text lingers on the login page.

### 📎 Attachments on user messages

- The user bubble now renders attachments: images as 48×48 thumbnails (click to open the lightbox), documents as file chips (click to download; legacy attachments with `id=0` show a "cannot download" hint).
- `ChatMessage` exports a `MessageFileAsset` type and `ChatMessageModel` gains `fileAssets`; `App.vue` adds `loadMessageFileRefs()` — it calls `GET /api/files?conversationId=X&withRefs=true`, groups files by `file_reference.messageId`, attaches them to the matching messages, and lazily fetches `preview` blobs for image thumbnails. Attachments just sent are attached immediately without waiting for a refresh.

### 🔄 Auto-update: installer version check

- `VersionCard`'s `downloadReady` no longer relies on `status === 'done'` alone; it compares the downloaded package version with the detected latest version (leading `v` stripped).
- If a package for **another version** is already on disk when a new release is detected, the download state is invalidated and the UI asks the user to download again. This fixes "downloaded 3.0.1 earlier, 3.0.2 is now out, but the button still offers Install 3.0.1".
- When an older main process does not report `download.version`, the state is treated as mismatched — better to re-download than to install the wrong package.

### 🧾 Version sync

- Backend: `3.0.2` → `3.0.3` (`pom.xml`, artifact `code-craft-3.0.3.jar`)
- Classic frontend: `3.0.2` → `3.0.3` (`frontend/package.json` + lock)
- v3 frontend: `3.0.2` → `3.0.3` (`frontend-v3/package.json` + lock)
- Electron: version and `extraResources` jar path → `3.0.3` / `code-craft-3.0.3.jar`
- MCP Server handshake version: `3.0.2` → `3.0.3` (`CodeCraftMcpServer.SERVER_VERSION`)
- Docs: README(_EN) badge, ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) headers, DEV_QUICKREF / RELEASE_CHECKLIST(_EN) build commands, and the FAQ version example are unified at v3.0.3

### ⚠️ Upgrade notes

- **No backend API or database schema changes**; just replace the installer. Users on 3.0.2 can install over it directly.
- The freeze fix and attachment rendering are frontend-only and must ship with the package: the desktop app loads `v3-static/` from inside the jar, so a page refresh alone may not pick them up.

---

## [3.0.2] - 2026-10-20

> Fix release: a batch of v3 chat-page defects (disjointed top-bar colours, stale balance,
> working-directory switch having no effect, page freeze on very long reasoning) plus two
> features that only the classic UI had — "skill hit" badges and the Git commit-history panel.
> The Provider-switch semantics are also corrected. Version numbers are unified at 3.0.2;
> no backend API or schema changes.

### 🔧 v3 chat page fixes

- **Balance refreshes after every turn**: it used to be queried only when entering the chat page or switching Provider, so the number never moved after a reply. `onSend`'s `finally` now forces a refresh (including background sessions and manual stops); a failed query keeps the last successful value instead of blanking it.
- **Working-directory switch now really applies**: switching folders in the file tree used to change only that panel's `localRoot`, while chat requests kept the old `workDir`. It now asks:
  - "Set as working directory" → writes a session-level override + `settingsStore.projectRoot`, then `PUT /agent-configs/{id}/runtime` and reloads the agent list — AI file operations, Git, terminal and relative-path resolution all follow;
  - "Browse only" → only the file-tree scope changes.
  - `chatProjectRoot` priority is now "session override > agent config > persisted `settingsStore.projectRoot`", matching the classic UI.
- **Top bar and main area share one colour**: under a wallpaper, `.cc-header` (the row holding Files / Git / Terminal) drops its 62% card tint and simply reuses the main column's paper surface; the Electron 32px title-bar right segment mirrors the page's two layers (`--bg-solid` 50% + paper 40%). Previously the 62% card layer composited ~30% darker than the main area in dark mode, leaving a disjointed dark band at the top.
- **Settings that never took effect now do**:
  - window opacity is wired to `electronAPI.setWindowOpacity` (it used to change a store number only);
  - UI density is written to `<html data-density>` and overrides `--sp-*` / `--header-h` in `tokens.css` (it used to be a demo-only local ref);
  - switching the theme now also calls `electronAPI.setTheme`, so the native window-button colours follow.
- **Provider-switch semantics corrected**: `providerCode` is written back only when the user actually changed the binding. Previously even an "execution mode only" apply pinned "follow default Provider" to a concrete code.

### ⚡ Chat-history loading (no more freezes on long reasoning)

- **Lazy rendering of historical reasoning**: `chat-mapper` no longer runs `renderMarkdown(thinking)` for every message (tens of thousands of characters × a dozen messages = hundreds of thousands parsed synchronously — the main cause of "opening an old session freezes"). Only the raw text is kept and `ChatMessage` renders it on demand with a per-text cache.
- **Zero DOM while collapsed**: the reasoning block switched from "content always in the DOM + CSS `display:none`" to `v-if="procOpen"`, matching the classic `getThinkingVisible` behaviour, so thousands of hidden nodes are no longer attached.
- **Segmented rendering for very long reasoning**: a single block renders only the last 60k characters by default; earlier content is loaded explicitly via a "Render full reasoning (N chars)" button. During streaming, blocks over 30k characters skip per-frame rendering; on completion, blocks over 120k skip the full render (avoiding the multi-second highlight.js cost).
- **Context-token estimate cache**: per-message estimates are cached by "message id + content-length fingerprint", so paging / switching sessions only recomputes changed messages instead of walking every loaded message again.

### 🎯 New: skill hit badges

- The reasoning row of an assistant message shows `🎯 skill name` pills for skills that were **actually executed** in this turn (only `skill action=load` counts, not "matched in the skill catalogue"); the badge still shows when there is no reasoning text at all.
- Two data channels: the `🎯 命中技能: xxx` summary in `tool_call_start.summaries` (primary) and `【技能详情】xxx (ID:n)` inside the tool output (fallback).
- **History replay**: after a refresh or session switch the same information is re-derived from the raw tool output, so older sessions keep showing their skills.
- Parsing lives in the new `frontend-v3/src/utils/skill-hit.ts`, shared by the live stream and history replay.

### 📜 New: Git commit-history panel

- A new "Commit history" section in the Git panel's collapsible area parses the `git log --oneline` text returned by `GET /api/git/log` into "short hash (monospace, accent) + subject".
- Clicking a row copies the full 40-character hash; "Load more" adds 20 entries per click; after a successful commit the section **auto-expands and refreshes** so the new commit is immediately visible; panel refreshes (commit / pull / rollback) also update it while expanded.

### 🧾 Version bumps

- Backend: `3.0.1` → `3.0.2` (`pom.xml`, artifact `code-craft-3.0.2.jar`)
- Classic frontend: `3.0.1` → `3.0.2` (`frontend/package.json` + lock)
- v3 frontend: `3.0.1` → `3.0.2` (`frontend-v3/package.json` + lock)
- Electron: version and `extraResources` jar path → `3.0.2` / `code-craft-3.0.2.jar`
- MCP Server handshake version: `3.0.1` → `3.0.2` (`CodeCraftMcpServer.SERVER_VERSION`)
- Docs: README(_EN) badge, ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) headers, DEV_QUICKREF / RELEASE_CHECKLIST(_EN) build commands, and the FAQ version example are unified at v3.0.2

### ⚠️ Upgrade notes

- **No backend API or database changes** — replacing the installer is enough; users on 3.0.1 can install over it directly.
- Frontend changes ship with the package: the desktop app loads the `v3-static/` build inside the jar, so a plain page refresh may not pick them up.

---

## [3.0.1] - 2026-10-19

> Patch release: the v3 frontend's wallpaper now actually shows through **across the whole UI**
> (L21) instead of only the three outer shell containers; the desktop 32px title-bar strip is
> now painted by the frontend in the page's own colours (L22); version numbers are unified at
> 3.0.1 and `scripts/sync-version.sh` is fixed. No backend API or schema changes.

### 🖼 Wallpaper transparency across the whole UI (v3)

- **Problem**: with a wallpaper set, only the sidebar / top bar / main content area were translucent. Message bubbles, the composer, cards, list rows and drawers stayed fully opaque, so stacking them hid the wallpaper completely — users saw "I set a wallpaper but can't see it".
- **Approach (retarget tokens, not individual components)**: `tokens.css` now exposes 5 opaque base colours (`--bg-solid` / `--bg-sub-solid` / `--card-solid` / `--card-2-solid` / `--accent-soft-solid`) and the semantic variables reference them, so **behaviour is pixel-identical when no wallpaper is set**; `app.css` §37 rewrites those 5 surface colours to translucent values via `color-mix()` under `.cc-shell.has-bg`, covering all 283 background references at once — components added later can't be missed.
- **Four layers** (wallpaper → content sheet → card → overlay): shell 62%–74% + backdrop blur; content sheet 40% (30% in light theme); cards 64% (58% in light theme); drawers 94%, popovers/dropdowns 90%, and code / log / diff / editor reading surfaces 96%.
- **Wallpaper luminance correction**: `.cc-app-bg::after` darkens the wallpaper by 60% in dark theme and lightens it by 32% in light theme, so light text on a bright photo remains readable; the correction fades together with the opacity slider.
- **Fallbacks and exceptions**: `@supports not (color-mix)` falls back to solid colours; Toast / Confirm / Prompt / Lightbox stay solid because they are teleported to `body` (outside `.cc-shell`) — topmost feedback must stay crisp.
- **Verified**: automated audit across 18 pages × dark/light = 36 runs, **0** large opaque elements remaining.

### 🪟 Desktop 32px title strip now matches the page (v3)

- **Problem**: with `titleBarStyle: 'hidden'`, v3 reserves the top 32px via `.cc-shell { padding-top: 32px }`. That strip only showed the bare wallpaper layer, while the sidebar below it is 74% card colour + blur and the header 62% — the strip ended up one shade brighter, looking like "a piece of the shell glued on top".
- **Approach**: the strip is now painted by the frontend in two segments — `.cc-titlebar__side` aligns with the sidebar layer and `.cc-titlebar__main` with the header layer (two stacked fills: `--bg-solid` at 40%/30% underneath, then 62% card colour, matching the compositing order of `.cc-chat__main` + `.cc-header`). Collapsed sidebar (`cc-sidebar-collapsed`), the login page (`is-plain`) and the no-wallpaper case each have their own branch; `<html>` now carries `cc-has-bg` / `cc-sidebar-collapsed` so the strip (outside `.cc-shell`) can read the state.
- **Shell stays neutral**: `TITLE_BAR_THEME.color` in `electron/main.js` remains fully transparent and only drives the window-control symbol colour (this release only adds comments documenting the intent); the classic frontend keeps its own `.electron-titlebar-drag` + 32px scrim. Each version paints its own strip — no version has to compromise for the other's palette.
- **Verified**: real Electron window, pixels sampled via `capturePage` — dark/light × wallpaper on/off × collapsed sidebar × login page, 7 scenarios; the strip differs from the page below by ≤ 8 levels (expected within the wallpaper's vertical gradient).

### 🔧 Tooling

- **Fixed `scripts/sync-version.sh` failing to read the version**: it looked for `<artifactId>codecraft</artifactId>` while `pom.xml` actually declares `<artifactId>code-craft</artifactId>` (with a hyphen), so running it without arguments always failed. The matcher is fixed and `tr -d '\r'` strips the CRLF tail from the extracted version.
- **Widened the sync scope**: previously only `electron/package.json`; now the electron / frontend / frontend-v3 `package.json` files plus the top-level `version` of their `package-lock.json` (only the two root nodes in each lock file — dependency versions under `node_modules` are untouched).
- **Documented the manual steps**: the Java constant `CodeCraftMcpServer.SERVER_VERSION`, the README badge and the docs version headers can't be changed safely by the script, so it now prints a reminder.

### 🧾 Version bump

- Backend: `3.0.0` → `3.0.1` (`pom.xml`, artifact `code-craft-3.0.1.jar`)
- Classic frontend: `3.0.0` → `3.0.1` (`frontend/package.json` + lock)
- v3 frontend: `3.0.0` → `3.0.1` (`frontend-v3/package.json` + lock)
- Electron: version and `extraResources` jar path → `3.0.1` / `code-craft-3.0.1.jar`
- MCP Server handshake version: `3.0.0` → `3.0.1` (`CodeCraftMcpServer.SERVER_VERSION`)
- Docs: README(_EN) badge, ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) headers, and the DEV_QUICKREF / RELEASE_CHECKLIST(_EN) build-command examples unified at v3.0.1

### ⚠️ Upgrade notes

- **No backend API or database schema changes** — just replace the installer; users on 3.0.0 can install over the top.
- The wallpaper and title-strip work are pure frontend styling, but a page refresh alone does **not** necessarily pick them up: the desktop app serves the prebuilt `v3-static/` bundle from inside the jar, so the package has to be rebuilt.

---

## [3.0.0] - 2026-10-19

> Major release: the brand-new v3 frontend (`frontend-v3/`) ships alongside the classic one
> (`frontend/`) with a runtime switcher; the Version & Updates page (F06) is added and all
> version numbers are unified at 3.0.0.
>
> **Rebuild addendum (republished under the same version)**: startup optimizations for slow
> machines (async P2P startup + JVM/Spring tuning + AppCDS class archive) and the backup-import
> limit raised from 64MB to 500MB. Functional behaviour is unchanged from the first build.

### ⚡ Startup performance (slow disks / low-end machines)

Measured against a real slow-machine log (`Started ... in 68.281 seconds`):

- **P2P moved off the startup critical path**: `ConnectionManager.start()` now runs asynchronously
  after `ApplicationReadyEvent` instead of inside the `@Bean` factory method. TLS init + Netty port
  binding (3.3s + 5.3s measured) no longer block Spring context refresh; `/api/p2p/status` reports
  `running=false` until it is ready.
- **Removed two counter-productive JVM flags**: `-XX:TieredStopAtLevel=1` (Spring's reflection-heavy
  startup is slower when pinned to C1) and `-XX:+UseSerialGC` (JDK 17 ergonomics already picks a
  suitable GC; forcing Serial limits G1's parallel collection on 8GB machines).
- **SpringDoc disabled on desktop**: `--springdoc.api-docs.enabled=false`, `--springdoc.swagger-ui.enabled=false`.
- **Hikari `minimum-idle` 5 → 1**: four fewer H2 connections opened at startup.
- **H2 `WRITE_DELAY=2000`**: coalesces the fsyncs triggered by 100+ DDL statements in `schema.sql`.
  Trade-off: a hard kill may lose up to 2 seconds of writes.
- **AppCDS class archive (automatic, self-adapting)**: 20 seconds after the first launch a background
  warm-up process writes `runtime/cds/codecraft.jsa`; subsequent launches reuse it via
  `-XX:SharedArchiveFile`. Measured on the same machine: **7.12s → 5.54s (-22%)**. The archive is
  rebuilt automatically when the jar fingerprint changes and silently falls back via `-Xshare:auto`
  if it is unusable. Backend adds `--app.cds-warmup=true` for this purpose.

### 📦 Backup import limit: 64MB → 500MB

- `spring.servlet.multipart.max-file-size` → **500MB**, `max-request-size` → 520MB,
  `server.tomcat.max-swallow-size` → 520MB (so oversized requests still receive an error response
  instead of a connection reset).
- `/api/system/import-data` now streams via `file.getInputStream()` instead of `getBytes()`, avoiding
  a second in-heap copy of a 500MB upload.
- Both frontends (v3 `DataImportCard`, classic `SystemSettingView`) accept up to 500MB and report the
  actual overshoot instead of failing silently.
- **Clearer import failure message**: an interrupted upload now reports the file size and likely cause
  (old 64MB server limit / backend exited) instead of a generic "is the backend running?".

### 🛠 Misc

- `docs/FAQ.md` gains Q20 (per-stage startup breakdown, the five optimizations, hardware checklist).

### 🎨 New: v3 frontend (`frontend-v3/`, served at `/v3/`, default entry)

- **Rewritten stack**: Vue 3.5 + Vite 8 + TypeScript + Pinia (3 stores) + hash router; no UI component library — a self-built `cc-` design system (1px outlined cards, line icons, hover-revealed actions, light/dark themes).
- **All 19 pages wired to real APIs** (no demo data left): chat, agents, skills, MCP, scheduled tasks, lessons, database connections, files, logs, data management, P2P, users, menu permissions, providers, appearance, settings hub, profile, version & updates, login.
- **Chat page parity**: markdown + highlight.js rendering (`diff` coloring, filelist / cmd cards), structured tool-result cards, independently scrolling thinking area, image lightbox (zoom/drag/multi-image), three approval modes (permission / clarification / iteration confirm +10/+20/+50), task checklist with priority & blocked-by, per-turn "rollback this turn's changes", regenerate (deletes and re-sends for real), supplement request injection, context-token accounting, `full` / `compact` context mode and runtime provider switching, multi-conversation parallel streams (background streams survive navigation), sub-agent panel (polls `sub_agent_log`), P2P chat & file transfer, right-side workspace (multi-tab + split panes + embedded web + code editor + Git client + xterm terminal).
- **Static preview `preview.html`**: an isomorphic static prototype kept on the dev machine (not shipped in this repo).
- **Silent login removed**: v3 uses a real login page (`/login`), supports logout, auto re-login after password change, and redirects 401s back to login with the original path preserved.

### 🔄 New: Version & Updates page (F06)

- **New page `/about`** (Settings → System configuration): header + version matrix (client version / backend version / build time / runtime / last check / backend PID) + update card + update settings + update-policy notes.
- **New backend endpoint `GET /api/system/version`**: returns app name, version, build time, Java version, OS and PID; **no admin check** (no sensitive data, every user should see the version). The version is injected from `application.yml` via Maven resource filtering (`@project.version@`) with a built-in fallback when the placeholder is not replaced.
- **Reuses the existing Electron update pipeline** (`electron/update-manager.js` + `electronAPI.update` from `preload.js`, zero changes): check (GitHub Releases, 6-hour cache + 30s manual interval), download with progress (mirror acceleration with direct fallback), one-click install (Windows installer / macOS dmg / Linux reveal), open release page.
- **Safe degradation in browsers**: without `electronAPI` the card becomes read-only with an explanatory note, the settings block turns into read-only text, and "Check for updates" shows a toast instead of throwing.
- **Two deliberate choices**: (1) client version and backend version are shown separately (replacing only the jar without reinstalling the client makes them diverge — merging would hide that); (2) release notes are rendered as markdown (`html:false` prevents XSS, external links get `target=_blank`).
- **Data cleanup hook**: after "Reset all data" succeeds, `clearLocalData()` wipes upgrade backups / downloaded installers / update cache so the reset is truly complete (no-op in browsers).

### 🔧 Backend

- New `GET /api/system/version` (see above).
- `application.yml` gains `app.version` / `app.build-time` (Maven resource filtering).
- `SystemController` injects the values via `@Value` and falls back to `3.0.0` when placeholders are unreplaced.
- Version bump: `pom.xml`, `CodeCraftMcpServer.SERVER_VERSION` (MCP handshake version).

### 🖥️ Desktop

- `electron/package.json` version and bundled jar path synced to `code-craft-3.0.0.jar`.
- Keeps the previously fixed macOS lifecycle handling (single-instance lock, closing the window keeps the backend alive, `activate` recreates the window with self-healing retries, minimal app menu restoring `Cmd+Q`).
- **Fixed: upgrade install could leave the app broken (important)**. `customInit` in `electron/installer.nsh` now releases file handles in three steps: graceful `CodeCraft.exe` shutdown (triggers `before-quit` → `stopBackend`) → forced kill of `CodeCraft.exe` → fallback cleanup of any `java.exe` whose command line contains `codecraft.jar`. Previously the installer killed nothing: while `codecraft.jar` / `app.asar` were locked, NSIS **silently skipped** writing them yet still showed "Installation complete", so the old jar kept running; the leftover java also held port 8084 and the H2 file lock, making the new client fail with "backend failed to start: backend startup timed out". Cleanup matches only `java.exe` with `codecraft.jar` in its command line, so other JVMs such as IntelliJ IDEA are never touched.
- **Fixed: startup self-healing**. `main.js` now detects the backend generation (`none` / `current` / `legacy` / `foreign`): a legacy backend left over from an older version is stopped before starting a new one; if the port is held by a non-CodeCraft program the owner PID is reported instead of blindly spawning a java process that is doomed to hang; the timeout message now includes the port owner and the backend log path. A new `scripts/fix-startup.bat` lets affected users clean up leftover processes in one click.
- **Fixed: no evidence at all when startup fails**. The java child process's stdout / stderr used to go only to `console.log`, which a packaged Windows GUI app simply **throws away** (no console attached). As a result, a "java never started" failure surfaced to users as "port 8084 is free, the version is correct, and the log is empty" — nothing to investigate. The child output is now persisted verbatim to `runtime/logs/backend-console.log` (header records platform / app version / Java path / JAR path / working directory), and the last 30 lines are embedded in the native failure dialog; each step of `findJava()`'s fallback chain (bundled JRE → `JAVA_HOME` → system `PATH`) is logged as well; a `java -version` pre-check now runs before spawning, so an unusable Java is reported with a readable reason.
- **Fixed: slow-starting machines were mistaken for failed startups (root cause confirmed by real logs)**. The log shows the backend starting normally, just ~40 s per step: `18:52:20` start → `18:53:01` Tomcat init (41 s) → `18:53:39` parser registration (79 s) → still not finished at `18:53:56` (96 s+). The culprit is **antivirus real-time scanning** of the 108 MB `codecraft.jar`: every class read gets scanned, making startup roughly 10× slower. The previous 90-second limit therefore declared a perfectly healthy startup a failure — the log the user saw simply stopped mid-way. The wait is now up to 300 s, the splash shows "first launch is slow" after 60 s, and the progress bar stops at 92% instead of pretending to finish; a process that actually exits still fails immediately. **Adding the install directory to the antivirus whitelist is the most effective speedup.**

### 🧾 Version sync

- Backend: `2.0.4` → `3.0.0` (`pom.xml`, artifact `code-craft-3.0.0.jar`)
- Classic frontend: `2.0.4` → `3.0.0` (`frontend/package.json` + lock)
- v3 frontend: `3.0.0` (`frontend-v3/package.json`, aligned in this release)
- Electron: `2.0.4` → `3.0.0` (`package.json` + lock + `extraResources` jar path)
- MCP Server handshake version: `2.0.4` → `3.0.0`
- Docs: README(_EN) / BUILD_AND_RUN(_EN) / CHANGELOG(_EN) / docs (ARCHITECTURE, DEEPSEEK_SERVICE_IMPL, LLM_PROVIDER_SYSTEM, DEV_QUICKREF, RELEASE_CHECKLIST) unified at v3.0.0

---

## [2.0.4] - 2026-10-18

### 🌋 New: Volcengine Ark Provider (chat / vision / balance / web search & fetch)

- **`VolcengineArkClient`** (template `volcengine`, aliases `ark` / `doubao`): endpoint resolution tolerant of baseUrl with/without `/api/v3` or a full `/chat/completions` path; `thinkingMode` maps to Ark `thinking.type` (`non-thinking→disabled`, `thinking / thinking_max→enabled`, `auto→auto`); `reasoning_effort` is only sent for `deepseek-v4` models to avoid `400 InvalidParameter`.
- **Vision**: `FileVisionService` blocks Ark-hosted `deepseek-*` text models up-front; `doubao-seed` / `vision` / `-vl` models pass. The adapter normalizes OpenAI-style `image_url.detail=auto` to `high` (Ark only accepts low/high/xhigh). Images fall back to base64 `image_url` blocks (Ark has no Files API support here).
- **Balance**: new `VolcengineBillingClient` calling Billing Center `QueryBalanceAcct` with a self-implemented **V4 HMAC-SHA256 signature**; `ProviderBalanceService` now supports multiple platforms and the VO exposes available/cash/credit/frozen/arrears fields.
- **Web search**: new `VolcengineSearchApiClient` (Doubao Search Custom edition). `web_search` prefers it and falls back to Bing/Baidu; `web_fetch` gains a best-effort "search body" channel and falls back to Jina/local fetching.
- **Data model**: new optional `llm_provider.extra_config` column (auto-created via `ALTER TABLE` at startup) for platform credentials; the provider list API masks secrets (`accessKey` / `secretKey` / `searchApiKey` / `apiKey` / `token`).

### 🔧 Improved: existing adapters aligned with current APIs

- **`AnthropicClient`**: fixed the extended-thinking parameter conflict — `max_tokens` (16000) is now strictly greater than `budget_tokens` (8000) and `temperature` is dropped in thinking mode (Anthropic requires 1); normal cap 4096 → 8192.
- **`OpenAIClient`**: reasoning-model detection covers o1/o3/o4 and gpt-5 families; default output 4096 → 8192, reasoning models use `max_completion_tokens` (16384).
- **`OllamaClient`**: no longer strips `tools` (Ollama 0.4+ supports function calling); adds `think=true` mapping (0.6+ reasoning models); `temperature` merges into `options` instead of overwriting.

### 🔎 Added: MiniMax / Xiaomi MiMo capabilities

- **`MiniMaxCodingPlanClient`**: web search (`/v1/coding_plan/search` → `organic[title/link/snippet/date]`), image understanding (`/v1/coding_plan/vlm`), Token Plan quota (`/v1/token_plan/remains`; note `current_interval_usage_count` actually means *remaining*).
- **`ProviderBalanceService`** now covers four platforms: DeepSeek (balance), Volcengine (balance), MiniMax (quota, `balanceType=quota`), MiMo (console-cookie balance, ~24h validity with an explicit re-paste hint).
- **`ProviderBalanceVO`** gains `balanceType` plus quota fields (`remainingPercent` / `remainingCount` / `totalCount` / `resetAt` / `planName`); the chat page renders quota as "💰 额度 67%".
- **Vision compatibility**: MiMo v2.5 and MiniMax M3/VL models are supported; `AbstractLLMClient.stripImageDetail` removes the undocumented `image_url.detail` field for MiMo / MiniMax.
- **Not implemented (honest scope)**: MiniMax exposes no public web-fetch API and MiMo none for search/fetch, so `web_fetch` keeps the Jina/local pipeline; MiMo cash balance has no OpenAPI and only works via console cookie.

### 🐱 New: Qwen (Alibaba Model Studio / DashScope)

- **`QwenClient`** (template `qwen`, aliases `dashscope` / `tongyi`): OpenAI-compatible endpoint with smart resolution (`/compatible-mode/v1/chat/completions`), `enable_thinking` + `thinking_budget`, chat-level `enable_search` via `extraConfig.enableSearch`, and `image_url.detail` removal for DashScope.
- **`AliyunBillingClient`**: Billing `QueryAccountBalance` with a self-implemented RPC-style **HMAC-SHA1 signature**; credentials via `extraConfig.accessKey` / `secretKey`.
- **`FileVisionService`**: Qwen-VL / Qwen3-VL / Omni allowed, text-only `qwen-plus` / `qwen-max` blocked up-front.
- Qwen has **no structured web-search API** (its `enable_search` is chat-level), so the `web_search` tool keeps Bing/Baidu.

### 🔌 Refactor: OpenAI / Anthropic become "protocol contract" adapters

- **`OpenAIClient`**: endpoint auto-resolution (bare host → `/v1`, existing `/v1` not duplicated, full URLs as-is); parameter tiering (only the official host receives `max_tokens` / `reasoning_effort`; third-party services get neither); model-key normalization (`openai/gpt-4o`, `azure/o3-mini`); reasoning models still switch to `max_completion_tokens` and drop `temperature`.
- **`AnthropicClient`**: endpoint auto-resolution (`/anthropic` compatibility layers → `/anthropic/v1/messages`); **new image protocol conversion** (`image_url → image/source`, data URLs → base64, http(s) → url); `thinking` is only sent when enabled; `max_tokens` overridable via `extraConfig.maxTokens` and widened to `budget + 8192` in thinking mode.

### 🎯 New: GPT (OpenAI official) adapter + adapter selection notes

- **`GptClient`** (template `gpt`, aliases `gpt-official` / `chatgpt`), strictly separated from `openai`:
  - always sends `max_completion_tokens` (8192 normal / 16384 reasoning; injected `max_tokens` is converted) — `max_tokens` is deprecated and GPT-5 / o-series return 400;
  - model-aware `reasoning_effort`: GPT-5 supports `none`/`minimal`, o-series rejects `none`; `thinking→medium`, `thinking_max→high`, explicit `low`/`high`/`xhigh` pass through;
  - removes `temperature` for reasoning models or active reasoning; streaming adds `stream_options.include_usage`.
- **`OpenAIClient`** returns to a pure protocol contract (no vendor-specific parameters).
- **Maintainability**: every adapter class (DeepSeek / Volcengine / Qwen / GPT / OpenAI / Anthropic / Ollama / MiMo / MiniMax) now carries a "适用范围（选型指引）" Javadoc block (applicable / not applicable / exclusive capabilities); `LLMClientManager` documents a template selection matrix; docs gained sections 4.9 (GPT vs OpenAI) and 4.10 (adapter selection matrix).

### 🖥️ Frontend

- Provider page: template dropdown adds Volcengine Ark, Qwen and GPT (openai / anthropic labelled as protocol contracts); new "Extended config" JSON input with validation; template labels/colors/icons for `volcengine` / `ark` / `doubao` / `qwen` / `dashscope` / `tongyi` / `gpt`.
- Chat page balance chip: supports DeepSeek + Volcengine + MiniMax + MiMo + Qwen, with `quota` type (percentage) and per-platform tooltips.

### ✅ Tests

- New `VolcengineAdapterTest` (32 assertions), `MiniMaxAdapterTest` (29), `QwenAdapterTest` (58), `GptAdapterTest` (31); `SearchToolLogicTest` (16) and `DeepSeekToolsApiClientTest` (26) regressions pass.

### 🔖 Version bump (2.0.3 → 2.0.4)

- Backend: `2.0.3` → `2.0.4` (pom.xml)
- Frontend: `2.0.3` → `2.0.4` (package.json + package-lock.json)
- Electron: `2.0.3` → `2.0.4` (package.json + package-lock.json, packaged jar path synced to `code-craft-2.0.4.jar`)
- MCP Server handshake version: `2.0.3` → `2.0.4` (`CodeCraftMcpServer.SERVER_VERSION`)
- Build artifacts: `code-craft-2.0.3.jar` → `code-craft-2.0.4.jar`
- Docs: README(_EN) / BUILD_AND_RUN(_EN) / CHANGELOG(_EN) / docs (ARCHITECTURE, DEEPSEEK_SERVICE_IMPL, LLM_PROVIDER_SYSTEM, DEV_QUICKREF, RELEASE_CHECKLIST, etc.) synced to v2.0.4, provider lists now include GPT / Qwen / Volcengine Ark

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
