> 🌐 中文版：[🇨🇳 DEEPSEEK_SERVICE_IMPL](./DEEPSEEK_SERVICE_IMPL.md)
# DeepSeekServiceImpl Deep Dive: Core Engine Method Call Topology & State Machine

> Version: v2.0.0 | Updated: 2026-09-16 | Audience: Developers / AI Collaborators
> This document dissects the 187KB DeepSeekServiceImpl, sorting out its internal method call relationships, Tool Loop state machine, SSE event flow, and all safety mechanisms.

---

## 1. Why Is It a 187KB Monolith?

```
DeepSeekServiceImpl responsibilities (ideally split into 4-5 classes):
┌──────────────────────────────────────────────────────────┐
│ 1. Entry orchestration    streamChat / prepareConversationContext │
│ 2. Background task mgmt   startBackgroundTask / cancelRunningTask │
│ 3. Tool loop engine       executeSemiStreamingToolCycle          │
│                         → handleToolCallIteration                │
│                         → handleStreamingPhase                   │
│ 4. Stream response handling SSE parsing / event building / persistence │
│ 5. Sub-agent collection   collectPendingAgents / buildSummary   │
│ 6. Judge evaluation       evaluateWithJudge / buildJudgeContext  │
│ 7. Dead loop detection    hasRepeatedCalls / extractToolKey      │
│ 8. Message persistence    saveUserMessage / saveAssistantMessage │
│ 9. LLM Provider routing   resolveClient / buildRequestBody      │
│10. Language directive     buildLanguageInstruction               │
└──────────────────────────────────────────────────────────┘
```

**Current extraction status**: `ToolLoopManager`, `ContextBuilder`, `MessagePersister`, `AgentForkManager`, `CompactionService`, `AgentEventBus` have been extracted, but core orchestration logic (1/3/4/5/6) remains in this class.

---

## 2. Dependency Injection Panorama

```
DeepSeekServiceImpl (28 dependencies)
├─ External Communication
│   └─ WebClient deepSeekWebClient     → DeepSeek API HTTP calls
├─ Configuration
│   ├─ DeepSeekConfig                  → API URL / Key / Model params
│   └─ ConfigService                   → Dynamic API Key reading
├─ Database
│   ├─ ConversationMapper              → Conversation CRUD
│   ├─ ConversationMessageMapper       → Message CRUD
│   ├─ AgentConfigMapper               → Agent config queries
│   └─ JdbcTemplate                    → Raw SQL (agent_task table)
├─ Core Engine Components (extracted)
│   ├─ ToolExecutor                    → Tool call execution
│   ├─ ToolLoopManager                 → Dead loop detection / Judge / SSE events
│   ├─ ContextBuilder                  → Message assembly / Token estimation
│   ├─ MessagePersister                → Async message persistence
│   ├─ CompactionService               → Context compaction
│   ├─ AgentEventBus                   → Event bus (SSE push)
│   └─ AgentForkManager                → Sub-agent lifecycle
├─ Tool System
│   ├─ ToolPermissionRegistry          → Permission metadata
│   ├─ PathSecurityChecker             → Path traversal check
│   └─ SnapshotService                 → Code snapshots
├─ Skill System
│   ├─ SkillService                    → Skill CRUD
│   ├─ SkillMatcher                    → Skill matching
│   └─ DeepSeekAnalyzer                → AI analyzer
├─ Growth System (★new in v1.1.5, evolved in v1.1.6: P0 normalization / P1 detour channel / P2 env awareness)
│   ├─ LessonService                   → CRUD + on-demand retrieval + state machine
│   ├─ LessonRecorder                  → Auto-capture drafts on failure (source=auto)
│   ├─ LessonReviewService
│   └─ FailureNormalizer               → Error-code normalization + signature dedup             → Turn-level async review (C2)
├─ Agent Invocation / File Assets (★v1.1.8 / v2.0.0)
│   ├─ AgentInvokeService              → Agent delegation (trust chain)
│   ├─ FileAssetService                → File asset management (upload/reference/cascade)
│   ├─ FileVisionService               → Vision understanding (visual injection)
│   ├─ CaptureContextRegistry          → Screenshot coordinate context (desktop automation)
│   ├─ SupplementStore                 → Supplemental requirement message store
│   └─ AttachmentStore                 → Uploaded attachment staging
├─ Permission/Pending
│   ├─ PendingQuestionStore            → Pending approval storage
│   ├─ ExecutionTokenManager           → Token management
│   └─ OperationDetailGenerator        → Operation detail generation
└─ Thread Pool
    └─ ExecutorService taskExecutor    → Background tasks (core 2~10 threads)
```

---

## 3. Method Call Topology

```
Entry: streamChat(ChatRequest)
├─ ① prepareConversationContext(request, stream=true)
│   ├─ Get/Create Conversation
│   ├─ Get AgentConfig (tool list, model, thinking mode, etc.)
│   ├─ Load skills → SkillMatcher.match()
│   │   ├─ Load lessons → LessonService.retrieveRelevant (passive injection, dedup per conversation)
│   ├─ Build API request body (messages + tool definitions + params)
│   └─ Return ConversationContext (apiRequest, toolNames, skillMatchEvent)
├─ ② toolExecutor.buildToolDefinitions(toolNames)
│   └─ Build OpenAI Function Calling tools array
├─ ③ cancelRunningTask(conversationId)
│   └─ Cancel old background task for same conversation
├─ ④ [Has tools] → startBackgroundTask(conversationId, storageConversationId, apiRequest)
│   │
│   ├─ ④a INSERT agent_task (status=running)
│   ├─ ④b Create Sinks.Many<String> (replay mode, reconnect support)
│   ├─ ④c agentEventBus.register(conversationId, sink)
│   ├─ ④d executeSemiStreamingToolCycle(...)
│   │      .subscribe(event → sink.tryEmitNext(event))
│   └─ ④e Return sink.asFlux() as SSE
│
│   └─ ④d Expanded → executeSemiStreamingToolCycle(...)
│       │
│       ├─ Reset PermissionContext (clear "approve all" from last round)
│       ├─ Load history messages (contextBuilder.buildMessagesFromHistory)
│       ├─ Inject skill-matched content into user message
│       ├─ Inject language enforcement directive
│       ├─ Call handleToolCallIteration(iteration=0, maxIterations=50)
│       │   │
│       │   ├─ Iteration exceeded → evaluateWithJudge()
│       │   │   ├─ Build judge context → send to sub-agent judge
│       │   │   ├─ Parse judge result (extend/reject + additionalIterations)
│       │   │   └─ extend → back to handleToolCallIteration
│       │   │       reject → return terminate event
│       │   │
│       │   └─ Normal iteration → handleStreamingPhase()
│       │       │
│       │       ├─ Send HTTP request to DeepSeek API (SSE streaming)
│       │       ├─ SSE event parsing:
│       │       │   ├─ reasoning_content → createReasoningSSEEvent()
│       │       │   ├─ content delta → accumulate text
│       │       │   ├─ tool_calls delta → accumulate tool calls
│       │       │   └─ finish_reason → decide next step
│       │       │
│       │       ├─ After response:
│       │       │   ├─ Save assistant message (messagePersister)
│       │       │   ├─ Async pre-compact (compactionService.asyncPrecompress)
│       │       │   │
│       │       │   ├─ If finish_reason == "tool_calls":
│       │       │   │   ├─ Check hasRepeatedCalls() → 3 consecutive → flag for Judge
│       │       │   │   ├─ toolExecutor.executeToolCalls()
│       │       │   │   │   └─ Per tool → snapshot → permission pipeline → execute → diff
│       │       │   │   │   │   │   │   │   │   │       └─ Tool throws → LessonRecorder auto-captures draft (symptom only, source=auto)
│       │       │   │   ├─ Append tool results to message list
│       │       │   │   └─ Recurse handleToolCallIteration(iteration+1)
│       │       │   │
│       │       │   └─ If finish_reason == "stop" → return done event
│       │       │
│       │       └─ Error handling:
│       │           ├─ WebClientResponseException → error event
│       │           └─ Generic Exception → error event
│       │
│       │   ├─ After tool loop → lessonReviewService.reviewAsync (async review, distill root cause/solution, C2)
│       └─ concatWith(Flux.defer(sub-agent auto-collect))
│           ├─ agentForkManager.getPendingAgentCount() > 0 ?
│           ├─ agentForkManager.collectPendingAgents(timeout=300)
│           ├─ buildSubAgentSummaryInput(subResults)
│           ├─ Inject summary instruction into message list
│           └─ handleToolCallIteration again (final summary iteration)
│
└─ ⑤ [No tools] → Direct streaming DeepSeek API call
    └─ Simple mode: no tool loop, pure text streaming
```

---

## 4. Tool Loop State Machine

```
                     ┌─────────────────────┐
                     │  User sends message  │
                     └──────────┬──────────┘
                                │
                     ┌──────────▼──────────┐
                     │ iteration = 0       │
                     │ maxIterations = 50  │
                     └──────────┬──────────┘
                                │
                  ┌─────────────┴──────────────┐
                  │ iteration >= maxIterations? │
                  └──┬────────────────────┬───┘
                     │YES                  │NO
                     ▼                     ▼
        ┌──────────────────┐  ┌──────────────────────┐
        │ evaluateWithJudge │  │ handleStreamingPhase  │
        │   (Judge Eval)    │  │   (Call AI API)       │
        └──────┬───────────┘  └──────────┬───────────┘
               │                         │
        ┌──────┴──────┐         ┌────────┴────────┐
        │ extend?     │         │ finish_reason?   │
        ├──YES→add iter│         ├─ stop ──────────┐
        │  back to loop│         │                 ▼
        ├──NO→terminate│         │       ┌──────────────┐
        └─────────────┘         │       │ Return done   │
                                │       └──────────────┘
                                │
                                ├─ tool_calls ──────┐
                                │                   ▼
                                │       ┌──────────────────────┐
                                │       │ hasRepeatedCalls()?  │
                                │       ├──YES(4×) → terminate │
                                │       ├──NO → execute tools  │
                                │       │   ├─ Create snapshot │
                                │       │   ├─ Permission check│
                                │       │   ├─ tool.execute()  │
                                │       │   └─ Diff stats      │
                                │       └──────────┬───────────┘
                                │                  │
                                │       iteration++, append result
                                │       ┌──────────┴───────────┐
                                │       │ Sub-agent auto-collect│
                                │       │ (after tool loop ends)│
                                │       └──────────────────────┘
                                │
                                └─ length / error → terminate
```

---

## 5. SSE Event Types

| Event Type | Trigger | JSON Structure |
|-----------|---------|---------------|
| `sessionId` | Stream start | `{"sessionId": 123}` |
| `skill_match` | Skill matching complete | `{"type":"skill_match","skills":[...]}` |
| `reasoning` | AI thinking process | `{"type":"reasoning","content":"..."}` |
| `text` | AI text output | `{"type":"text","content":"..."}` |
| `tool_start` | Tool execution begins | `{"type":"tool_start","tools":["write_file"]}` |
| `tool_result` | Tool execution completes | `{"type":"tool_result","tool":"write_file","result":"..."}` |
| `ask_user` | User confirmation needed | `{"type":"ask_user","uuid":"...","question":"..."}` |
| `resume` | User confirmed, resume | `{"type":"resume"}` |
| `error` | Error occurred | `{"type":"error","message":"..."}` |
| `done` | Current task complete | `{"type":"done"}` |

---

## 6. Safety Mechanisms

### 6.1 Dead Loop Detection (hasRepeatedCalls)

```
Detection logic (in ToolLoopManager):
  1. Extract recent consecutive tool messages (threshold=3)
  2. Calculate tool name + key params for each (extractToolKey)
  3. If all identical → flagged as suspected dead loop (for Judge context)
  4. Main loop over-iteration → Judge decides terminate or continue
```

### 6.2 Judge Mechanism (evaluateWithJudge)

```
Trigger: iteration >= maxIterations (default 50)
Flow:
  1. Build judge context (buildJudgeContext) → preceding round summary + current round details
  2. Send to sub-agent judge (independent small model call)
  3. Parse judge result:
     - extend + additionalIterations → increase iteration count
     - reject → terminate task
  4. Cumulative limit: judge max grants 100 additional iterations (MAX_JUDGE_GRANTED_ITERATIONS)
```

### 6.3 User Cancel (cancelRunningTask)

```
User clicks stop in frontend → DeepSeekController → cancelTask()
  → toolLoopManager.cancelRunningTask()
    ├─ taskSubscriptions.remove(conversationId).dispose()
    ├─ agentEventBus.unregister(conversationId)
    └─ UPDATE agent_task SET status='cancelled'
```

### 6.4 Page Refresh Reconnect

```
agent_task table tracks task state → frontend queries via getActiveTask() after refresh
  → If running task exists → agentEventBus resubscribes to Sink
  → Sink uses replay mode (limit 100000), can retrieve historical events after reconnect
  → pending_question persisted, approval dialog recoverable after refresh
```

---

## 7. Context Management Flow

```
On each new message from user:

prepareConversationContext()
    ↓
    ├─ CompactionService.applyCompactionRecords()
    │   └─ Inject compaction summary at head of message list
    ↓
    ├─ ContextBuilder.buildMessagesFromHistory()
    │   ├─ System prompt
    │   ├─ Compaction summary (if any)
    │   ├─ Last N complete dialogue rounds
    │   ├─ Skill matched content injection
    │   ├─ Language directive injection
    │   └─ Token budget check (trimToTokenBudget)
    ↓
    └─ Return complete message list → as API request body

After each AI response:

CompactionService.asyncPrecompress()
    ↓
    ├─ Check Token usage → 3-tier decision:
    │   ├─ WARN (yellow) → log only
    │   ├─ COMPACT (orange) → trigger LLM summary compaction
    │   └─ DROP (red) → discard oldest messages
    ↓
    └─ Compaction result persisted to conversation_compaction table
```

---

## 8. LLM Provider Routing Mechanism (New in v1.1.3)

### 8.1 Routing Priority

```
Frontend dynamic providerCode (ChatRequest.providerCode)
    ↓ Not found
Agent config providerId (AgentConfig.providerId)
    ↓ Not found
First available Provider (LLMClientManager.getFirstClient())
    ↓ Not found
Throw exception: No available LLM Provider
```

### 8.2 Key Code Path

```
prepareConversationContext()
    ↓
    ├─ request.getProviderCode() → llmClientManager.resolveClientByCode()
    ├─ agentConfig.getProviderId() → llmClientManager.resolveClientByProviderId()
    └─ llmClientManager.getFirstClient()
    ↓
    llmClient.buildRequestBody(messages, model, temperature, thinkingMode, stream, tools)
    ↓
    apiRequest.put("_llmClient", llmClient)  // Store in internal field
    ↓
    Streaming/blocking call:
    ├─ activeWebClient = llmClient.getWebClient()  // Use Provider's WebClient
    ├─ llmClient.getChatEndpoint()                  // Use Provider's endpoint path
    └─ llmClient.extractContentFromStreamChunk()    // Use Provider's response parser
```

### 8.3 Judge/Sub-Agent Following Mechanism

Judges and sub-agents use the same LLM Provider as the main Agent, propagated via `ToolContext.setProviderCode()`:
- Judge API call: Extract `providerCode` from `_llmClient`, pass to `deepSeekAnalyzer.analyzeWithoutThinking()`
- Sub-agent tool call: Set to thread context via `ToolContext.setProviderCode()`

---

## 9. Known Issues & Split Recommendations

| Issue | Location | Recommendation |
|-------|----------|---------------|
| 187KB monolith | Overall | Split into 4 classes |
| SSE event building scattered | `create*Event()` methods | Extract to `SseEventBuilder` |
| `evaluateWithJudge` logic | ~100 lines | Delegated to ToolLoopManager, but call chain remains |
| Sub-agent collection logic | Tail of `executeSemiStreamingToolCycle` | Extract to `SubAgentCollector` |
| `handleStreamingPhase` | Largest single method | Split into `sendApiRequest` + `parseSseResponse` |
| LLM Provider routing | prepareConversationContext | Already decoupled via LLMClientManager, routing logic can be further isolated |

**Recommended split plan**:
```
DeepSeekServiceImpl (orchestration layer, ~200 lines)
    ↓
    ├─ ChatOrchestrator        # streamChat entry + context prep
    ├─ ToolLoopEngine          # executeSemiStreamingToolCycle + handleToolCallIteration
    ├─ SseResponseHandler      # handleStreamingPhase + SSE parsing
    └─ SubAgentCollector       # Sub-agent auto-collect + summary
```

---

## 10. Key Constants Quick Reference

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_TOOL_CALL_ITERATIONS` | 50 | Max tool loop iterations |
| `MAX_JUDGE_GRANTED_ITERATIONS` | 100 | Max judge-granted additional iterations |
| `DEFAULT_TEMPERATURE` | 0.3 | Default temperature (3-tier priority: frontend > Agent config > default) |
| `taskExecutor.corePoolSize` | 2 | Core background task threads |
| `taskExecutor.maximumPoolSize` | 10 | Max background task threads |
| `taskExecutor.queueCapacity` | 100 | Task queue capacity |
| `Sinks.replay().limit` | 100000 | SSE event history cache limit |

---

> 📌 **Doc Maintenance Convention**: When splitting DeepSeekServiceImpl, please sync this document's topology and method list.
