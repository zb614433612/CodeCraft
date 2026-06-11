> 🌐 English Version：[🇬🇧 DEEPSEEK_SERVICE_IMPL_EN](./DEEPSEEK_SERVICE_IMPL_EN.md)
# DeepSeekServiceImpl 深描：核心引擎方法调用拓扑与状态机

> 版本：v1.0.5 | 更新：2026-05-28 | 受众：开发者 / AI 协作伙伴
> 本文档解剖 129KB 的 DeepSeekServiceImpl，梳理其内部方法调用关系、Tool Loop 状态机、SSE 事件流和所有安全机制。

---

## 一、为什么它是 129KB 的单体怪兽？

```
DeepSeekServiceImpl 承担的职责（理想情况下应拆分为 4~5 个类）：
┌──────────────────────────────────────────────────────────┐
│ 1. 入口编排      streamChat / prepareConversationContext │
│ 2. 后台任务管理   startBackgroundTask / cancelRunningTask │
│ 3. 工具循环引擎   executeSemiStreamingToolCycle           │
│                 → handleToolCallIteration                │
│                 → handleStreamingPhase                   │
│ 4. 流式响应处理   SSE 解析 / 事件构建 / 消息持久化       │
│ 5. 子Agent汇总   collectPendingAgents / buildSummary     │
│ 6. 评委评估      evaluateWithJudge / buildJudgeContext   │
│ 7. 死循环检测    hasRepeatedCalls / extractToolKey        │
│ 8. 消息持久化    saveUserMessage / saveAssistantMessage   │
│ 9. API Key 管理  initDynamicApiKey                       │
│10. 语言指令注入  buildLanguageInstruction                │
└──────────────────────────────────────────────────────────┘
```

**当前拆出情况**：已抽出 `ToolLoopManager`、`ContextBuilder`、`MessagePersister`、`AgentForkManager`、`CompactionService`、`AgentEventBus` 等 6 个辅助类，但核心编排逻辑（1/3/4/5/6）仍在本类中。

---

## 二、依赖注入全景图

```
DeepSeekServiceImpl (21 个依赖)
├─ 外部通信
│   └─ WebClient deepSeekWebClient     → DeepSeek API HTTP 调用
├─ 配置
│   ├─ DeepSeekConfig                  → API URL / Key / 模型参数
│   └─ ConfigService                   → 动态 API Key 读取
├─ 数据库
│   ├─ ConversationMapper              → 会话 CRUD
│   ├─ ConversationMessageMapper       → 消息 CRUD
│   ├─ AgentConfigMapper               → Agent 配置查询
│   └─ JdbcTemplate                    → 原生 SQL（agent_task 表操作）
├─ 核心引擎组件（已拆分）
│   ├─ ToolExecutor                    → 工具调用执行
│   ├─ ToolLoopManager                 → 死循环检测 / 评委评估 / SSE 事件
│   ├─ ContextBuilder                  → 消息组装 / Token 估算
│   ├─ MessagePersister                → 消息异步持久化
│   ├─ CompactionService               → 上下文压缩
│   ├─ AgentEventBus                   → 事件总线（SSE 推送）
│   └─ AgentForkManager                → 子Agent 生命周期
├─ 工具系统
│   ├─ ToolPermissionRegistry          → 权限元数据
│   ├─ PathSecurityChecker             → 路径越界检查
│   └─ SnapshotService                 → 代码快照
├─ 技能系统
│   ├─ SkillService                    → 技能 CRUD
│   ├─ SkillMatcher                    → 技能匹配
│   └─ DeepSeekAnalyzer                → AI 分析器
├─ 权限/待处理
│   ├─ PendingQuestionStore            → 待审批问题存储
│   ├─ ExecutionTokenManager           → Token 管理
│   └─ OperationDetailGenerator        → 操作详情生成
└─ 线程池
    └─ ExecutorService taskExecutor    → 后台任务（核心 2~10 线程）
```

---

## 三、方法调用拓扑图

```
入口: streamChat(ChatRequest)
├─ ① prepareConversationContext(request, stream=true)
│   ├─ 获取/创建 Conversation
│   ├─ 获取 AgentConfig（工具列表、模型、思考模式等）
│   ├─ 加载技能 → SkillMatcher.match()
│   ├─ 构建 API 请求体（消息 + 工具定义 + 参数）
│   └─ 返回 ConversationContext（含 apiRequest, toolNames, skillMatchEvent）
├─ ② toolExecutor.buildToolDefinitions(toolNames)
│   └─ 构建 OpenAI Function Calling tools 数组
├─ ③ cancelRunningTask(conversationId)
│   └─ 取消同一会话旧的后台任务
├─ ④【有工具】→ startBackgroundTask(conversationId, storageConversationId, apiRequest)
│   │
│   ├─ ④a INSERT agent_task (status=running)
│   ├─ ④b 创建 Sinks.Many<String>（replay 模式，支持重连）
│   ├─ ④c agentEventBus.register(conversationId, sink)
│   ├─ ④d executeSemiStreamingToolCycle(...)
│   │      .subscribe(event → sink.tryEmitNext(event))
│   └─ ④e 返回 sink.asFlux() 作为 SSE
│
│   └─ ④d 展开 → executeSemiStreamingToolCycle(conversationId, storageConversationId, apiRequest)
│       │
│       ├─ 重置 PermissionContext（清除上一轮「本轮全部同意」）
│       ├─ 加载历史消息（contextBuilder.buildMessagesFromHistory）
│       ├─ 注入技能匹配内容到用户消息
│       ├─ 注入语言强制指令
│       ├─ 调用 handleToolCallIteration(iteration=0, maxIterations=50)
│       │   │
│       │   ├─ 迭代超限 → evaluateWithJudge()（评委评估）
│       │   │   ├─ 构建评委上下文 → 发送给子Agent评委
│       │   │   ├─ 解析评委结果（extend/reject + additionalIterations）
│       │   │   └─ extend → 回到 handleToolCallIteration
│       │   │       reject → 返回终止事件
│       │   │
│       │   └─ 正常迭代 → handleStreamingPhase()
│       │       │
│       │       ├─ 发送 HTTP 请求到 DeepSeek API（SSE 流式）
│       │       ├─ SSE 事件解析：
│       │       │   ├─ reasoning_content → createReasoningSSEEvent()
│       │       │   ├─ content delta → 累积文本
│       │       │   ├─ tool_calls delta → 累积工具调用
│       │       │   └─ finish_reason → 判断下一步
│       │       │
│       │       ├─ 响应完成后：
│       │       │   ├─ 保存 assistant 消息（messagePersister）
│       │       │   ├─ 异步预压缩（compactionService.asyncPrecompress）
│       │       │   │
│       │       │   ├─ 如果 finish_reason == "tool_calls"：
│       │       │   │   ├─ 检查 hasRepeatedCalls() → 连续4次重复 → 终止
│       │       │   │   ├─ toolExecutor.executeToolCalls()
│       │       │   │   │   └─ 每个工具 → 快照 → 权限管道 → 执行 → diff统计
│       │       │   │   ├─ 工具结果追加到消息列表
│       │       │   │   └─ 递归 handleToolCallIteration(iteration+1)
│       │       │   │
│       │       │   └─ 如果 finish_reason == "stop" → 返回 done 事件
│       │       │
│       │       └─ 错误处理：
│       │           ├─ WebClientResponseException → 错误事件
│       │           └─ 通用 Exception → 错误事件
│       │
│       └─ concatWith(Flux.defer(子Agent自动收集))
│           ├─ agentForkManager.getPendingAgentCount() > 0 ?
│           ├─ agentForkManager.collectPendingAgents(timeout=300)
│           ├─ buildSubAgentSummaryInput(subResults)
│           ├─ 注入汇总指令到消息列表
│           └─ 再次 handleToolCallIteration（最后一轮汇总迭代）
│
└─ ⑤【无工具】→ 直接流式调用 DeepSeek API
    └─ 简单模式：无工具循环，纯文本流式返回
```

---

## 四、Tool Loop 状态机

```
                     ┌─────────────────────┐
                     │  用户发送消息        │
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
        │   (评委评估)       │  │   (调用 AI API)       │
        └──────┬───────────┘  └──────────┬───────────┘
               │                         │
        ┌──────┴──────┐         ┌────────┴────────┐
        │ extend?     │         │ finish_reason?   │
        ├──YES→加迭代  │         ├─ stop ──────────┐
        │  回到循环    │         │                 ▼
        ├──NO→终止    │         │       ┌──────────────┐
        └─────────────┘         │       │ 返回 done 事件 │
                                │       └──────────────┘
                                │
                                ├─ tool_calls ──────┐
                                │                   ▼
                                │       ┌──────────────────────┐
                                │       │ hasRepeatedCalls()?  │
                                │       ├──YES(连续4次) → 终止  │
                                │       ├──NO → 执行工具        │
                                │       │   ├─ 快照创建         │
                                │       │   ├─ 权限检查         │
                                │       │   ├─ tool.execute()   │
                                │       │   └─ diff统计         │
                                │       └──────────┬───────────┘
                                │                  │
                                │       iteration++, 追加结果
                                │       ┌──────────┴───────────┐
                                │       │ 子Agent自动收集检查   │
                                │       │ (工具循环结束后触发)  │
                                │       └──────────────────────┘
                                │
                                └─ length / error → 终止
```

---

## 五、SSE 事件类型一览

前后端通过 SSE（Server-Sent Events）通信，事件格式为 JSON 字符串。

| 事件类型 | 触发时机 | JSON 结构 |
|---------|---------|----------|
| `sessionId` | 流开始时 | `{"sessionId": 123}` |
| `skill_match` | 技能匹配完成 | `{"type":"skill_match","skills":[...]}` |
| `reasoning` | AI 思考过程 | `{"type":"reasoning","content":"..."}` |
| `text` | AI 文本输出 | `{"type":"text","content":"..."}` |
| `tool_start` | 工具开始执行 | `{"type":"tool_start","tools":["write_file"]}` |
| `tool_result` | 工具执行完成 | `{"type":"tool_result","tool":"write_file","result":"..."}` |
| `ask_user` | 需要用户确认 | `{"type":"ask_user","uuid":"...","question":"...","askType":"..."}` |
| `resume` | 用户确认后恢复 | `{"type":"resume"}` |
| `error` | 发生错误 | `{"type":"error","message":"..."}` |
| `done` | 本轮任务完成 | `{"type":"done"}` |

---

## 六、安全机制清单

### 6.1 死循环检测（hasRepeatedCalls）

```
检测逻辑（在 ToolLoopManager 中实现）：
  1. 提取最近 4 条 tool 消息
  2. 计算每条的工具名 + 关键参数（extractToolKey）
  3. 如果全部相同 → 判定为死循环
  4. 返回终止事件，不再继续
```

### 6.2 评委机制（evaluateWithJudge）

```
触发条件：iteration >= maxIterations（默认 50）
流程：
  1. 构建评委上下文（buildJudgeContext）→ 前序轮次摘要 + 当前轮次详情
  2. 发送给子Agent评委（独立的小模型调用）
  3. 解析评委结果：
     - extend + additionalIterations → 增加迭代次数
     - reject → 终止任务
  4. 累计上限：评委最多累计增加 100 次迭代（MAX_JUDGE_GRANTED_ITERATIONS）
```

### 6.3 用户取消（cancelRunningTask）

```
用户在前端点击停止 → DeepSeekController → cancelTask()
  → toolLoopManager.cancelRunningTask()
    ├─ taskSubscriptions.remove(conversationId).dispose()  // 取消 Flux 订阅
    ├─ agentEventBus.unregister(conversationId)             // 移除事件总线
    └─ UPDATE agent_task SET status='cancelled'
```

### 6.4 页面刷新重连

```
agent_task 表追踪任务状态 → 前端刷新后通过 getActiveTask() 查询
  → 如果存在 running 任务 → agentEventBus 重新订阅 Sink
  → Sink 使用 replay 模式（limit 100000），重连后可获取历史事件
  → pending_question 持久化，刷新后审批弹窗可恢复
```

---

## 七、上下文管理流程

```
每次用户发送新消息时：

prepareConversationContext()
    ↓
    ├─ CompactionService.applyCompactionRecords()
    │   └─ 将压缩摘要注入到消息列表头部
    ↓
    ├─ ContextBuilder.buildMessagesFromHistory()
    │   ├─ 系统提示词（system prompt）
    │   ├─ 压缩摘要（如有）
    │   ├─ 最近 N 轮完整对话
    │   ├─ 技能匹配内容注入
    │   ├─ 语言强制指令注入
    │   └─ Token 预算检查（trimToTokenBudget）
    ↓
    └─ 返回完整消息列表 → 作为 API 请求体

每次 AI 响应完成后：

CompactionService.asyncPrecompress()
    ↓
    ├─ 检查 Token 使用量 → 三级决策：
    │   ├─ WARN（黄色预警）→ 仅日志记录
    │   ├─ COMPACT（橙色）→ 触发 LLM 摘要压缩
    │   └─ DROP（红色）→ 丢弃最早消息
    ↓
    └─ 压缩结果持久化到 conversation_compaction 表
```

---

## 八、已知问题与拆分建议

| 问题 | 位置 | 建议 |
|------|------|------|
| 129KB 单体类 | 整体 | 按职责拆分为 4 个类 |
| SSE 事件构建方法散落 | `create*Event()` 方法 | 抽到 `SseEventBuilder` |
| `evaluateWithJudge` 逻辑 | ~100 行 | 已委托 ToolLoopManager，但调用链仍在 |
| 子Agent收集逻辑 | `executeSemiStreamingToolCycle` 尾部 | 抽到 `SubAgentCollector` |
| `handleStreamingPhase` | 最大的单个方法 | 拆分为 `sendApiRequest` + `parseSseResponse` |

**推荐拆分方案**：
```
DeepSeekServiceImpl (编排层，~200行)
    ↓
    ├─ ChatOrchestrator        # streamChat 入口 + 上下文准备
    ├─ ToolLoopEngine          # executeSemiStreamingToolCycle + handleToolCallIteration
    ├─ SseResponseHandler      # handleStreamingPhase + SSE 解析
    └─ SubAgentCollector       # 子Agent自动收集 + 汇总
```

---

## 九、关键常量速查

| 常量 | 值 | 说明 |
|------|----|------|
| `MAX_TOOL_CALL_ITERATIONS` | 50 | 工具循环最大迭代次数 |
| `MAX_JUDGE_GRANTED_ITERATIONS` | 100 | 评委累计最大扩展迭代 |
| `DEFAULT_TEMPERATURE` | 1.0 | 默认温度参数 |
| `SESSION_NAME_TRUNCATE_LENGTH` | 6 | 会话名截断长度 |
| `taskExecutor.corePoolSize` | 2 | 后台任务核心线程 |
| `taskExecutor.maximumPoolSize` | 10 | 后台任务最大线程 |
| `taskExecutor.queueCapacity` | 100 | 任务队列容量 |
| `Sinks.replay().limit` | 100000 | SSE 事件历史缓存上限 |

---

> 📌 **文档维护约定**: 拆分 DeepSeekServiceImpl 后请同步更新本文档的拓扑图和方法清单。
