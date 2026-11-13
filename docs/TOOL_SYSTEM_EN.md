> 🌐 中文版：[🇨🇳 TOOL_SYSTEM](./TOOL_SYSTEM.md)
# Tool System Deep Dive: How to Add a New AI Tool

> Version: v3.0.9 | Updated: 2026-11-13 | Audience: Developers / AI Collaborators
> This document covers the complete architecture of the tool system, execution chain, and a step-by-step checklist for adding a new Tool.

---

## 1. Architecture Overview

```
                     ┌──────────────────────────┐
                     │  Tool.java (Interface)    │
                     │  - getName()              │
                     │  - getDescription()       │
                     │  - getParameters()        │
                     │  - execute(arguments)     │
                     └────────────┬─────────────┘
                                  │ implements
                     ┌────────────┴─────────────┐
                     │  19 Tool Implementations  │
                     │  (@ToolPermission annotation)│
                     └────────────┬─────────────┘
                                  │ Auto-inject List<Tool>
      ┌───────────────────────────┼───────────────────────────────┐
      │                           │                               │
┌─────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
│ToolInitializer│  │ToolPermissionRegistry│   │  ToolRegistry        │
│(Application  │   │ (@Component)         │   │  (@Component)        │
│ Runner phase)│   │                      │   │                      │
│              │   │ Scans all @Tool-     │   │ ConcurrentHashMap     │
│ Iterate all  │   │ Permission on startup│   │ <name → Tool>        │
│ Tools →      │   │ Builds permission    │   │                      │
│ registry     │   │ metadata index       │   │ register/get/remove  │
│ .register()  │   │                      │   │                      │
└─────────────┘   └──────────────────────┘   └──────────┬───────────┘
                                                          │
                                              ┌──────────────────────┐
                                              │  ToolExecutor        │
                                              │  (Execution Engine)   │
                                              │                      │
                                              │ executeToolCalls()    │
                                              │   ├─ Parse tool_calls│
                                              │   ├─ Create snapshot │
                                              │   ├─ Permission pipe │
                                              │   └─ Diff stats      │
                                              └──────────┬───────────┘
                                                         │
                                              ┌──────────────────────┐
                                              │ ToolExecutionPipeline │
                                              │  (3-Layer Protection) │
                                              │                      │
                                              │ ① Session auto-approve│
                                              │ ② Data impact guard  │
                                              │ ③ Path traversal guard│
                                              │ ④ High-risk guard    │
                                              │ ⑤ Audit log          │
                                              │ ⑥ Execute            │
                                              └──────────────────────┘
```

---

## 2. Core Interface: Tool.java

```java
public interface Tool {
    String getName();           // Tool name (unique ID), e.g. "write_file"
    String getDescription();    // Tool description, sent to AI model
    JsonNode getParameters();   // JSON Schema format parameter definition
    String execute(JsonNode arguments);  // Execution logic, returns string result
}
```

- `getName()` → Registry index
- `getDescription()` + `getParameters()` → Build OpenAI Function Calling `tools` definition
- `execute()` → Core business logic, dispatched by `ToolExecutor`

---

## 3. Registration Mechanism: Two-Phase Startup

```
Spring Boot Startup
    ↓
    ├─ Phase 1: InitializingBean.afterPropertiesSet()
    │   └─ ToolPermissionRegistry scans all @ToolPermission annotations
    │       Builds metadataMap<toolName → permission metadata>
    │
    └─ Phase 2: ApplicationRunner.run()
        └─ ToolInitializer iterates List<Tool> (Spring auto-injects all Tool beans)
            Calls toolRegistry.register(tool) for each
```

**Key Design**: `ToolPermissionRegistry` does not depend on `ToolRegistry`; it directly injects `List<Tool>`. This ensures permission metadata is ready before tool registration, avoiding timing issues.

---

## 4. Execution Engine: ToolExecutor

### 4.1 Main Entry: `executeToolCalls(JsonNode toolCallsJson)`

AI returns `tool_calls` as an array:
```json
{
  "id": "call_xxx",
  "function": {
    "name": "write_file",
    "arguments": "{\"file_path\":\"...\",\"content\":\"...\"}"
  }
}
```

Execution flow:
1. Iterate each tool_call
2. Parse `function.name` → get Tool instance from ToolRegistry
3. Parse `function.arguments` → JSON string → JsonNode
4. **File modification tools** (write_file/edit_file/delete_file) → create snapshot first
5. Call `ToolExecutionPipeline.execute()` (instead of direct `tool.execute()`)
6. **File modification tools** → compute diff statistics
7. Return `ToolCallResult`

### 4.2 Build Tool Definitions: `buildToolDefinitions(List<String> toolNames)`

Build standard OpenAI Function Calling definitions array from the tool names specified in Agent configuration.

---

## 5. Permission System: 3-Layer Protection Pipeline

### 5.1 @ToolPermission Annotation

```java
@Retention(RUNTIME)
@Target(TYPE)
public @interface ToolPermission {
    OperationCategory category() default READ;  // READ/WRITE/DELETE/EXECUTE/GIT/NETWORK
    boolean affectsData() default false;         // Layer 1: Does it affect data?
    boolean isPathSensitive() default false;     // Layer 2: Does it involve file paths?
    boolean highRisk() default false;            // Layer 3: Is it a high-risk operation?
    String description() default "";
}
```

### 5.2 ToolExecutionPipeline Execution Flow

```
Phase 0: Session-Level Auto-Approve Check
  └─ PermissionContext.isSessionApproved(conversationId)?
     └─ YES → skip all permission checks, directly audit + execute

Phase 1.1: Layer 1 — Data Impact Guard (manual mode only)
  └─ executionMode == "manual" && affectsData()
     └─ PermissionContext.requestPermission() → frontend popup
        └─ User denies → return rejection info, don't execute

Phase 1.2: Layer 2 — Path Traversal Guard (manual mode only)
  └─ executionMode == "manual" && isPathSensitive()
     └─ PathSecurityChecker.checkAndRequest() → check path traversal
        └─ Traversal detected → frontend popup confirmation

Phase 1.3: Layer 3 — High-Risk Operation Guard (auto mode also requires auth)
  └─ executionMode == "auto" && highRisk()
     └─ PermissionContext.requestHighRiskPermission() → frontend popup

Phase 2: Audit Log
  └─ ToolAuditLogger.log(toolName, arguments, userId, executionMode)

Phase 3: Execute
  └─ tool.execute(arguments)
```

---

## 6. Post-Processing Pipeline: PostEditPipeline

Triggered by `write_file` and `edit_file` tools after execution:

```
PostEditPipeline.execute(filePath)
    ↓
    ├─ 1. Formatter.format(filePath)
    │    Language-specific formatter (Java→IDE, Python→black, JS→prettier...)
    │    Returns FormatResult
    │
    └─ 2. Diagnostic.diagnose(filePath)
         Compile check / lint check
         Returns DiagnosticResult (error list, warning list)
```

---

## 7. How to Add a New Tool: Step-by-Step Checklist

### Step 1: Create Implementation Class

In `tool/impl/`, create a class implementing `Tool`:

```java
@Slf4j
@Component
@ToolPermission(
    category = OperationCategory.XXX,  // Choose appropriate category
    affectsData = true/false,
    isPathSensitive = true/false,
    highRisk = true/false,
    description = "Brief description"
)
public class MyNewTool implements Tool {

    private final ObjectMapper objectMapper;

    public MyNewTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "my_new_tool";  // Unique ID, snake_case
    }

    @Override
    public String getDescription() {
        return "【Use Case】... 【How to Use】... 【Notes】...";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        // ... define each parameter's type and description
        parameters.set("properties", properties);
        parameters.putArray("required").add("param1").add("param2");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 1. Parse parameters
        // 2. Validate parameters
        // 3. Execute business logic
        // 4. Return result string
    }
}
```

### Step 2: Choose the Correct OperationCategory

| Category | Meaning | Example Tools |
|----------|---------|--------------|
| `READ` | File reading & search | file_explorer |
| `WRITE` | File write & modify | file_writer (write/edit) |
| `DELETE` | File deletion | file_writer (delete) |
| `EXECUTE` | Command execution | command (exec/start) |
| `NETWORK` | Network requests | web_search, web_fetch, http_request, check_network |
| `DATABASE` | Database operations | execute_sql |
| `GIT` | Git operations | git_query, git_submit, git_branch, git_stash, git_tag, git_remote |
| `SERVICE` | Service management | command (list/logs/stop) |
| `SKILL` | Skill management | skill, lesson |
| `COMMUNICATION` | User interaction | ask_clarification |
| `ADMIN` | Admin operations | agent, agent_invoke, task_manager, mcp_server_manager, schedule_task |
| `DESKTOP` | Desktop automation (screen capture / mouse & keyboard, high-risk) | screen_capture, desktop_control |

### Step 3: Configure Three-Layer Permissions

| Your Tool's Behavior | affectsData | isPathSensitive | highRisk |
|---------------------|:-----------:|:---------------:|:--------:|
| Read file content | ✓ false | ✓ false | ✓ false |
| Write/Modify file | ✓ true | ✓ true | ✓ false |
| Delete file | ✓ true | ✓ true | ✓ true |
| Execute shell command | ✓ true | ✓ false | ✓ true |
| Database write | ✓ true | ✓ false | ✓ true |
| Git push | ✓ true | ✓ false | ✓ true |
| Network request | ✓ false | ✓ false | ✓ false |

### Step 4: No Additional Registration Needed

Spring auto-discovers `@Component` → `ToolInitializer` auto-registers to `ToolRegistry` → `ToolPermissionRegistry` auto-parses `@ToolPermission`.

### Step 5: If File Modification Tool

Refer to `WriteFileTool`, call post-processing pipeline after `execute()`. Note: ToolExecutor already handles snapshot creation and diff stats uniformly — tools don't need to handle these internally.

---

## 8. All Tools Quick Reference

| Tool Name | Category | affectsData | pathSensitive | highRisk |
|-----------|----------|:-----------:|:-------------:|:--------:|
| `file_explorer` | READ | ✗ | ✓ | ✗ |
| `file_writer` | WRITE | ✓ | ✓ | ✗ |
| `command` | EXECUTE | ✓ | ✗ | ✓ |
| `web_search` | NETWORK | ✗ | ✗ | ✗ |
| `web_fetch` | NETWORK | ✗ | ✗ | ✗ |
| `http_request` | NETWORK | ✗ | ✗ | ✗ |
| `check_network` | NETWORK | ✗ | ✗ | ✗ |
| `execute_sql` | DATABASE | ✓ | ✗ | ✓ |
| `git` | GIT | ✓ | ✗ | ✗ |
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
| `desktop` | DESKTOP | ✓ | ✗ | ✓ |
| `browser` | BROWSER | ✓ | ✗ | ✓ |
| `project_map` | READ | ✗ | ✓ | ✗ |

> 📌 This table tracks the current **23 model-facing built-in tools**; MCP external tools (dynamically registered via `mcp_server_manager`) are not listed here.
> Since v3.0.9 the six `git_*` tools are merged into `git`, and `screen_capture` + `desktop_control` into `desktop`; **the legacy tool classes remain registered in `ToolRegistry`** (for historical messages and `normalizeToolNames()` compatibility) but are no longer sent to the model.
> `browser` also implements `SideEffectFreePreflight`: the six read-only actions `snapshot` / `status` / `list` / `console` / `wait` / `screenshot` skip authorization; within `tab`, only `op=list` is exempt (`new` / `switch` / `close` change window state and still require authorization).
> `project_map` writes to `.codecraft/` but is confined by `PathSecurityChecker` to the project root and never touches source code, hence `affectsData=false`; `isPathSensitive=true` drives path-escape validation.

---

### 8.1 Dual-Path Network Tools (v2.0.3)

Both `web_search` and `web_fetch` use a "primary path + fallback path" structure, fully transparent to the AI (tool descriptions are generated dynamically):

| Tool | Primary path | Enabled when | Fallback path |
|------|--------------|--------------|---------------|
| `web_search` | DeepSeek official `POST /v1/tools/search` | Provider `baseUrl` host is `api.deepseek.com` and an API key is configured | Bing Global → Bing China → Baidu (depending on proxy availability) + result cache (TTL 300s) + low-quality domain demotion |
| `web_fetch` | DeepSeek official `POST /v1/tools/fetch` | Same as above, and the target is a public address (not private/loopback) | Jina Reader (preferred when a usable proxy exists) → local fetch (UA pool + anti-bot retries) |

Implementation notes:

- The official path is invoked by `DeepSeekToolsApiClient`, reusing `LLMWebClientManager` auth headers, connection pool and retries;
- Official-path errors/timeouts are never exposed to the AI — the tool falls back to the legacy pipeline and logs the failure, so the tool always works;
- Detection uses the `baseUrl` host rather than `providerCode`, so third-party proxies / self-hosted compatible services never enable it (no requests leak to non-official services);
- Sub-agents pass the Provider via `ToolContext.setProviderCode(...)` so detection stays consistent inside child agents;
- Regression tests: `DeepSeekToolsApiClientTest` (26 assertions); `SearchToolLogicTest` / `WebFetchToolLogicTest` were adapted.

## 8.5. Growth System: Lesson Knowledge Base

The `lesson` tool is the entry point of the growth system (knowledge base), complementary to `skill` (catalog-resident, load-on-demand workflow templates): **lessons are "on-demand failure experiences"** — zero standing context cost, auto-hit on errors.

### 8.5.1 Data Flow

```
Tool execution fails (ToolExecutor)
    ↓ auto-capture draft (source=auto)
LessonDraft persisted (symptom + error code + tool name only)
    ↓ passive injection (dedup per conversation, at most once)
"Solution hint + completion guide" injected into LLM context
    ↓
LLM applies solution → no recurrence by session end → F3 marks "effective" → ACTIVE
LLM fails again        → F3 marks "ineffective" → demoted/hidden (HIDDEN)
```

### 8.5.2 Experience Sources (4 channels)

| source | Trigger | Description |
|--------|---------|-------------|
| `auto` | Tool call throws | ToolExecutor auto-captures a draft (symptom only, no solution) |
| `llm` | LLM proactive `lesson action=record` | Solution included in one step, highest quality |
| `manual` | Human curation (management UI) | Manually edited & completed |
| `review` | Turn-level async review | After the tool loop, LLM distills root cause / solution (C2) |

### 8.5.3 Validation Loop (F3 Tracking)

- **2 effective reuses** → experience auto-promoted (draft → ACTIVE)
- **5 consecutive failures** → experience auto-hidden, no longer retrieved
- **feedback**: LLM calls `lesson action=feedback` after applying a solution to accelerate confidence convergence
- Experiences are isolated by `project_key`; cross-project retrieval only hits `global=true` generic lessons

### 8.5.4 Management UI

"Lesson" management page (`/lesson-manage`, "AI Capabilities" menu group): paginated browsing, details, manual editing/completion, feedback, delete, plus a growth dashboard (total/draft/active/hidden/hits/success rate).

---

### 8.9 Long-Running Command Backgrounding (command, v3.0.9)

> The real problem it solves: for minute-scale commands like `mvn package`, the old model was "block synchronously, then kill on timeout" — a 120s default cap, forcefully terminated in the second minute, **wasting every minute of dependency download and compilation that came before**; meanwhile the whole agent stalled (no file reads, no code analysis), and on failure the only option was to start over.

| Aspect | Old model (≤ v3.0.8) | New model (v3.0.9+) |
|--------|----------------------|---------------------|
| Foreground wait | until `timeout` (default 120s) | at most 20s (10s for build/dependency commands) |
| On timeout | `destroyForcibly()` kills the process | **moved to a background task and keeps running** |
| Response | output so far + "terminated" | task ID + elapsed time + last 15 lines + next-step guidance |
| Control | held until timeout | returned immediately; the model can do other work |
| Follow-up | none (only re-run) | `wait` to block / `logs` for progress / `stop` to abort |
| Agent stalls | yes | no |

**State machine**

```
exec ──start──> [running] ──finishes within N s──> full output + exit code (short commands unaffected)
                   │
                   └──exceeds N s──> "backgrounded" + task ID
                                        │
                                        ├─ wait   → block (default 300s, can wait again)
                                        ├─ logs   → last N lines (default 50, tail=0 for all)
                                        └─ stop   → kill the whole process tree
```

**Key mechanisms**

| Mechanism | Implementation | Why it is required |
|-----------|----------------|--------------------|
| Slow-command detection | `SLOW_COMMAND_PATTERN` matches `mvn\|gradle\|npm\|pnpm\|yarn\|pip\|cargo\|go\|dotnet\|docker\|make\|...` | These take tens of seconds anyway; no point making the user wait the full 20s |
| De-duplication | `findRunningTask(workDir, command)` — same cwd + same command + still running → return "already running (task #N)" | Otherwise the model re-issues it: two mvn processes fight over the `.m2` lock, blow up memory, overwrite each other's artifacts |
| Separate pools | one-shot long tasks capped at 10 (`isTask()` count) / long-lived services capped at 5 | A 20-minute mvn must not consume the slot needed by `npm run dev` |
| Output retention | kept for 30 minutes after completion (`TASK_RETAIN_MS`); `cleanupFinishedServices()` now does TTL cleanup instead of deleting immediately | So `logs` can still explain a failure — immediate deletion means "just finished, already gone" |
| Failure output governance | `ERROR_LINE_PATTERN` extracts key lines (`[ERROR]` / `Caused by` / `BUILD FAILURE` / `npm ERR` / `at Xxx.java:N`) then appends the last 30 lines | A 1MB truncation tends to cut out exactly the error buried in the middle |

**Configuration and rollback**

```yaml
command:
  exec:
    background-after-seconds: 20        # foreground window for normal commands; 0 or negative = old behaviour
    background-after-build-seconds: 10  # window for build/dependency commands
```

> ⚠️ **Semantic change**: the `exec` `timeout` parameter **no longer kills the process** — it now only applies to `wait`. Scenarios relying on "timeout means abort" must call `command(action=stop, service_id=N)` explicitly.

**Contract with the model**: `getDescription()` and the system-prompt section "Long-running commands" jointly constrain the model — "backgrounding is not a failure", "do not re-run the same command", "follow up with wait / logs". Miss either one and the model treats "backgrounded" as a failure and retries.

---

### 8.10 Tool Merging Strategy (git / desktop, v3.0.9)

**Motivation**: tool definitions ship in the system prompt every turn, and with the DeepSeek cache-hit price at 1/40 of a miss, **any schema change invalidates the entire prefix**. Six similarly-named git tools also invite the model to pick the wrong one — costing an extra turn, which is far more expensive than the schema saved.

**Approach: pure delegation, zero logic duplication**

| Merge | Original tools | New tool | Routing |
|-------|----------------|----------|---------|
| Git | `git_query` / `git_submit` / `git_branch` / `git_stash` / `git_tag` / `git_remote` | `git` | `ACTION_ROUTING` (`LinkedHashMap` keeps the order stable so it never perturbs the cache prefix) → route by `action` to the atomic instances with parameters passed through |
| Desktop | `screen_capture` / `desktop_control` | `desktop` | action dispatch (`capture` / `click` / `type` / `key` …) |

**Compatibility design (essential)**

- **Legacy classes stay registered**: they still implement `Tool` and exist in `ToolRegistry`, just no longer sent to the model — historical `git_query(...)` calls in old conversations still execute instead of erroring with "unknown tool".
- **`normalizeToolNames()`**: normalizes old names to the new ones, so legacy tool whitelists and custom agent configs need no migration.
- **Behavioural parity**: pure delegation means the execution path is identical before and after — there is no "merging introduced a difference" risk.

**Action naming convention** (avoiding collisions with sub-tool actions)

- Git queries keep their original semantics: `status` / `diff` / `log` / `show`
- Others are domain-prefixed: `branch_list` / `branch_create` / `stash_save` / `stash_pop` …
- Commit-related stay short: `add` / `commit` / `push` / `pull` / `reset` / `revert`

---

## 9. Collaboration Interface with DeepSeekServiceImpl

```
DeepSeekServiceImpl
    ↓
    ├─ toolExecutor.buildToolDefinitions(toolNames) → Build tools array, send to AI
    │
    ├─ toolExecutor.executeToolCalls(toolCallsJson) → AI requests tools, execute
    │   └─ ToolExecutionPipeline.execute()
    │
    └─ toolExecutor.buildToolMessages(results) → Tool results → message format, append
```

Permission context (`PermissionContext`) is reset by `DeepSeekServiceImpl` at the start of each new user message via `PermissionContext.removeSessionApproved()`.

---

> 📌 **Doc Maintenance Convention**: After adding a new tool, please update this document's "All Tools Quick Reference" table.
