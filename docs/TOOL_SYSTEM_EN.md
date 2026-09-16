> 🌐 中文版：[🇨🇳 TOOL_SYSTEM](./TOOL_SYSTEM.md)
# Tool System Deep Dive: How to Add a New AI Tool

> Version: v2.0.0 | Updated: 2026-09-16 | Audience: Developers / AI Collaborators
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
| `GIT` | Git operations | git_query, git_submit, git_branch |
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
| `file_explorer` | READ | ✓ | ✓ | ✗ |
| `file_writer` | WRITE | ✓ | ✓ | ✗ |
| `command` | EXECUTE | ✓ | ✗ | ✓ |
| `web_search` | NETWORK | ✗ | ✗ | ✗ |
| `web_fetch` | NETWORK | ✗ | ✗ | ✗ |
| `http_request` | NETWORK | ✗ | ✗ | ✗ |
| `check_network` | NETWORK | ✗ | ✗ | ✗ |
| `execute_sql` | DATABASE | ✓ | ✗ | ✓ |
| `git_query` | GIT | ✗ | ✗ | ✗ |
| `git_submit` | GIT | ✓ | ✗ | ✓ |
| `git_branch` | GIT | ✓ | ✗ | ✗ |
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
| `screen_capture` | DESKTOP | ✗ | ✗ | ✓ |
| `desktop_control` | DESKTOP | ✓ | ✗ | ✓ |

> 📌 This table tracks the current 24 built-in tools; MCP external tools (dynamically registered via `mcp_server_manager`) are not listed here.

---

## 8.5. Growth System: Lesson Knowledge Base

The `lesson` tool is the entry point of the growth system (knowledge base), complementary to `skill` (standing, always-matched workflow templates): **lessons are "on-demand failure experiences"** — zero standing context cost, auto-hit on errors.

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

"Lesson" management page (`/lesson-manage`, SETTING menu group): paginated browsing, details, manual editing/completion, feedback, delete, plus a growth dashboard (total/draft/active/hidden/hits/success rate).

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
