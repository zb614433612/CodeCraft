# 智能体互相调用（Agent-to-Agent Invocation）设计 v1.0

> 设计日期：2026-08-14
> 状态：✅ 已实现（Phase 19 P1-P4 全部完成并验证通过，P5 文档转正）
> ⚠️ 范围声明：本模块与 **AgentForkManager（fork 子 Agent / agent 工具）完全无关**，子 Agent 是独立模块。
> 本模块解决的是**智能体实例（agent_config）之间**的互相调用：Agent A 在自己的执行链中调用 Agent B，
> B 以自身身份（自己的角色/模型/工作目录/会话）独立执行任务。
> 实现记录与验证结果见文末「十、实现记录与验证结果」。

---

## 一、需求与目标

### 1.1 需求描述
1. 智能体 A 调用智能体 B 时，支持两种会话模式：
   - **create_session**：为 B 创建**新会话**执行任务（B 从零开始，任务即首条消息）
   - **continue_session**：**按会话 ID 继续** B 的既有会话任务（B 带着该会话的历史上下文干活）
2. **免授权**：A 调用 B 后，B 执行期间**不再弹出用户授权**（用户信任已通过 A 链路表达）

### 1.2 边界（非目标）
- ❌ 不是 fork 子 Agent（AgentForkManager / agent 工具）的扩展
- ❌ 不做跨设备（P2P）调用——那是 p2p/agent 模块的范畴，本设计限定**单机内多智能体**
- ❌ 不做智能体之间的"聊天/对话"（互发消息闲聊），只做**任务委托式调用**

---

## 二、现状盘点（代码事实）

| 能力 | 现状 | 位置 |
|---|---|---|
| 智能体实例 | agent_config 表：name/systemPrompt/toolNames/modelName/executionMode(manual·auto)/workDir/enabled/providerId/providerCode/characterProfile/userId | `model/entity/AgentConfig.java` |
| 会话 | conversation 表：userId/agentType/agentConfigId/workDir；按 userId+agentConfigId 查询 | `ConversationServiceImpl` |
| 执行入口 | `POST /api/deepseek/chat/stream` → `DeepSeekServiceImpl.streamChat(ChatRequest)`：ChatRequest 已支持 sessionId（续会话）/agentConfigId/executionMode/projectRoot/providerCode；无 sessionId 自动建会话 | `DeepSeekController` / `DeepSeekServiceImpl` |
| 后台任务 | agent_task 表基础列（id/conversation_id/status/iteration/max_iterations/event_count/pending_question_uuid/pending_question_text/error_message/created_at/updated_at）；getActiveTask / cancelTask / subscribeTask(cursor) / supplement（向运行中 loop 注入消息）。⚠️ **2026-08-14 任务中心可选增强已删除**：TaskController/AgentTaskScheduler/WorkDirLockRegistry 已移除，AgentTask 实体 11 个扩展字段（result/input/progress 等）已回退——代码层**不存在 result 列**（仅遗留幂等 ALTER DDL 可补列），结果需从 conversation_message 取 | `DeepSeekController` / `ToolLoopManager` / progress.md 2026-08-14 记录 |
| 授权模型 | PermissionContext 三层防护（manual affectsData / 路径越界 / auto highRisk）+ 会话级自动批准 setSessionApproved(convId)；approve 分支 = setApproved() → executeToolCalls | `tool/PermissionContext.java` / `DeepSeekServiceImpl` 2662~2892 行 |
| 任务上下文 | TaskContext / TaskContextRegistry：会话 → 活跃上下文集合，支持按 conversationId 置位取消 + 注销（多 Agent 并行根基，已落地） | `util/TaskContextRegistry.java` |
| ⚠️ 规划与现实差异 | task_plan Phase 18 P1 声称的 AgentTaskScheduler / TaskController / WorkDirLockRegistry **实际不存在**；「任务中心」未落地，当前多 Agent 并行 = 各 Agent 独立会话 + 后台任务（agent_task） | grep 确认 |

### 2.1 核心洞察（设计支点）
1. **执行器现成**：`streamChat` 已经支持「指定 agentConfigId + 新建/续会话 + 后台任务」，A 调 B 不必新造执行引擎——构造一个内部 ChatRequest 即可让 B 以自身身份跑完整工具循环。
2. **免授权有现成钩子**：授权发生在工具迭代的 needsApproval 判定；只要在 B 的工具批次前走 `PermissionContext.setApproved()` 等价路径，三层授权全部放行。内部调用需给一个**可信标记**（仅存 JVM 内，LLM 无法伪造）。
3. **结果回传不需要 SSE 桥接**：B 的任务生命周期状态在 agent_task（status：running/completed/failed/cancelled），**最终结果从 B 会话的 conversation_message 读取**（流式路径的 assistant 消息全程持久化，取该会话最后一条 assistant 消息即为 B 的最终答复）——A 轮询 agent_task 状态 + 消息表取结果，与"同步等待"语义天然契合。

---

## 三、总体设计

### 3.1 概念模型

```
┌───────────────┐  agent_invoke (invoke)   ┌──────────────────────────────┐
│  Agent A 会话  │ ───────────────────────▶ │       AgentInvokeService      │
│ (A 的工具循环)  │                          │ 1. resolveAgent(B)            │
└───────────────┘                          │ 2. 信任链校验(环/深度/userId)   │
        ▲                                  │ 3. prepareSession              │
        │  await=轮询 agent_task            │    create_session: 建 conversation
        │  (可超时/可取消)                    │    continue_session: 校验+追加消息
        └────────────────────────────────── │ 4. 内部 ChatRequest            │
                                           │    agentConfigId=B, sessionId=… │
                                           │    _internalTrust=true          │
                                           │ 5. dispatch: streamChat 后台订阅  │
                                           │ 6. await: 轮询 agent_task 至终态  │
                                           └───────────┬──────────────────┘
                                                       ▼
                              ┌────────────────────────────────────────┐
                              │  B 以自身身份执行（新会话 / 既有会话）      │
                              │  · B 的 systemPrompt / toolNames / workDir │
                              │  · B 的工具循环：_internalTrust → 免授权     │
                              │  · agent_task 记录 caller 溯源              │
                              └────────────────────────────────────────┘
```

### 3.2 信任与授权模型（核心设计）

**问题**：B 免授权 ≠ 无条件放行。需要回答「凭什么 B 可以免授权执行 command / 写文件」。

**答案——委托即授权（Trust-by-Delegation）**：
- `agent_invoke` 工具本身注册为**受限工具**（manual 模式执行前弹窗，用户点批准 = 显式委托授权；auto 模式 A 本就在自动语境，随 A 语境放行）
- 用户批准 agent_invoke 后，A 的工具执行上下文带 `_internalTrust=true`（只存在于本次 JVM 调用链的对象传递中，**不入库、不进提示词、工具参数里没有该字段**，LLM 无法注入伪造）
- B 的工具循环检测到 `_internalTrust` → 工具批次直接走 approved 等价路径（三层授权全放行），**但不调用 `setSessionApproved()` 持久化**——B 会话被 internal 调用执行完后，用户手动操作 B 会话仍走正常授权流程，互不污染

**安全护栏**（防越权）：
1. **环检测**：信任链（trustChain = 途经的 agentConfigId 列表）中出现重复 → 拒绝（A→B→A 死循环禁止）
2. **深度限制**：trustChain 长度 ≤ 3（A→B→C 合法，A→B→C→D 拒绝）
3. **身份校验**：B 必须 enabled=1；create_session 时 B.workDir 作为会话工作目录，B 的工具集受 B.toolNames 白名单约束（沿用现有工具注册/白名单机制）
4. **路径边界**：B 的 file_writer / command 等路径敏感操作仍受 B 会话 workDir（项目根）既有边界约束，不会因免授权而越出 B 的工作目录
5. **busy 保护**：continue_session 目标会话已有活跃任务（agent_task running）→ 拒绝并返回 BUSY（不粗暴取消 B 正在干的事）
6. **审计**：agent_task 新增 caller 溯源列 + agent_invoke 日志（详见 §5）

### 3.3 工具设计：agent_invoke（新增内置工具）

```
agent_invoke
├─ action=invoke（发起调用，可同步等待或异步返回）
│    target_agent    string   必填  B 的 agent_config_id 或名称
│    mode            string   必填  create_session | continue_session
│    session_id      long     可选  continue_session 时必填（与 call_id 二选一）
│    call_id         string   可选  continue_session 时可用：按上次 invoke 的 callId 定位 B 会话（内存注册表解析，A 不必记忆数字 ID）
│    instructions    string   必填  任务指令（B 收到的 user 消息）
│    work_dir        string   可选  覆盖 B 的默认工作目录（默认 B.workDir）
│    await           boolean  可选  默认 true：阻塞等 B 完成；false：立即返回 callId 由 poll 查
│    timeout         int      可选  await=true 时最大等待秒数（默认 300，最大 600）
├─ action=poll（异步模式查结果 / 任意时刻查状态）
│    call_id         string   可选  invoke 返回的调用 ID（与 session_id 二选一）
│    session_id      long     可选  直接按 B 会话查（即使 callId 丢失/进程重启也能查）
│    scope           string   可选  summary（默认）| full | final
├─ action=cancel（取消 B 侧任务）
│    call_id         string   可选  或 session_id / task_id
└─ 权限：@ToolPermission(category=ADMIN, affectsData=true) → manual 模式弹窗（委托凭证）
```

**A 侧返回格式（await=true 完成时，三个 ID 显式返回）**：
```
✅ 智能体 B「前端专家」已完成任务
【调用 ID】call_8f3a2c（本次调用标识，后续 poll/cancel/continue 可用）
【B 会话 ID】1024（create_session=新建 / continue_session=原会话）
【任务 ID】task_567
【模式】create_session（新会话 #1024「[A 委托] 实现登录页」）
【耗时】86 秒
【结果摘要】B 已完成登录页组件实现，修改 3 个文件：
  - frontend/src/views/LoginView.vue（新建）
  ...
【后续】继续让 B 干活：agent_invoke action=invoke mode=continue_session session_id=1024 ...
        或按调用追查：agent_invoke action=poll call_id=call_8f3a2c
```

**A 如何"记住"会话 ID（可靠性设计）**：
1. invoke 返回文本作为 tool 结果消息留在 A 的 messages 历史中——同一 A 会话内（含后续轮次、压缩后摘要），A 都能看到 B 的会话 ID，与现有 agent fork/collect 靠 agent_id 记忆的模式一致
2. 即使 A 记错/遗忘数字 ID：内存注册表维护 callId → sessionId 映射，A 只要记得 callId 就能 continue_session / poll / cancel（参数二选一）
3. 进程重启后内存注册表丢失：A 仍可直接用 B 会话 ID（落库的 conversation.id）操作——会话 ID 是持久化的，不依赖注册表

### 3.4 会话准备逻辑（prepareSession）

**create_session**：
1. resolveAgent(B)：按 id/name 查 agent_config，校验 enabled=1、非调用者自身（A 调 A 由环检测拦）
2. 建 Conversation：name = `[A 委托] ` + instructions 截断 6~20 字（沿用 SESSION_NAME_TRUNCATE_LENGTH 风格）、userId = 调用者 userId（与 A 同一用户体系）、agentType 取 B 的会话类型（沿用 code_assistant 等）、agentConfigId = B.id、workDir = B.workDir（可被参数覆盖）
3. 插入首条 user 消息（instructions），消息内容前可加溯源注记（`（由智能体 A 委托，原会话 #xxx）`）——消息文本进历史，B 知道自己是受托执行
4. 返回新 conversationId

**continue_session**：
1. 校验会话存在且 `conversation.agentConfigId == B.id`（不能让 A 往别人的会话塞任务）
2. 校验会话无活跃任务：`getActiveTask(sessionId) == null`，否则返回 BUSY + 当前任务状态
3. 追加 user 消息（instructions + 溯源注记）
4. 返回原 conversationId

### 3.5 派发与等待（dispatch + await）

**dispatch**：构造内部 ChatRequest：
```
message        = instructions（含溯源注记）
sessionId      = prepareSession 返回的会话
agentConfigId  = B.id
executionMode  = B.executionMode（B 自身模式；internal 信任覆盖授权流程）
projectRoot    = 会话 workDir
providerCode   = B.providerCode（无则 B.providerId 或默认）
userId         = 调用者 userId
（内部字段，不入 ChatRequest）→ apiRequest: _internalTrust=true, _trustChain=[...]
```
调用 `deepSeekService.streamChat(request)` 并**后台订阅消费**（Flux 必须有订阅者才会执行；事件进 B 会话的 sink replay 缓冲，无前端订阅无害——与页面刷新后后台任务继续跑的现状一致）。

**await（同步语义）**：轮询 agent_task（按 conversationId 取最新任务记录）：
- running → 按间隔（1s 起步，退避至 5s）继续等
- completed/failed/cancelled → 执行「结果打包」（见下），封装返回
- 超时（timeout 参数）→ 返回 TIMEOUT + 当前任务状态；A 可再 poll 或 cancel（任务不销毁，后台继续跑）

> 轮询实现与 fork/collect 的"轮询"只是同一种通用技术手段，本模块不依赖 AgentForkManager 任何代码。

**结果打包（Result Packaging，保证 A 获得足够信息）**：
A 需要的信息分三层，全部可从 B 会话消息表可靠获取（流式路径消息全程落库：assistant 最终答复存 content 字段；每轮工具结果经 formatToolResult 带工具名存 TOOL 消息）：
1. **任务状态**：agent_task.status（completed/failed/cancelled）+ error_message（失败原因）——A 决策"是否成功/要不要重试"的依据
2. **最终答复**：注入点之后最后一条 ASSISTANT 消息的 content 全文——B 的结论与产出说明（B 的最终回复本身会被 LLM 训练成"包含关键信息"的表述，因为 B 的提示词语境知道自己是在被委托执行）
3. **工具轨迹摘要**：注入点之后所有 TOOL 消息（reasoning 字段 = 工具名 + 格式化结果），**截断打包**——每类工具取结果首 N 字符（默认 500），总数上限（默认 30 条），超限提示「…共 N 次工具调用，其余见 B 会话 #xxx 或使用 poll scope=full」

**消息范围筛选**：AgentInvokeService 注入 user 消息时记录注入消息 id（insert 后返回 id / 或按 createdAt 边界），await 完成时只取 `id > 注入id` 的消息——continue_session 场景不会把 B 的历史消息卷进来。

**detail 选项**：poll 支持 `scope=summary`（默认，上述打包）| `scope=full`（工具轨迹不截断，适合 A 需要完整核对 B 操作时）| `scope=final`（只要最终答复，最省 token）。

**补充**：B 的产出物（写的代码文件等）落盘在 B 的 workDir（与 A 同机共享文件系统）；A 若需核实细节，可直接用 file_explorer/command 检查产出文件——结果打包只负责"状态+答复+轨迹"，文件内容由 A 按需自取，避免消息超长。

### 3.6 执行隔离与循环计数（调研结论：天然按任务/会话独立）

代码事实（DeepSeekServiceImpl / ToolLoopManager）：
1. **iteration 是任务级栈上参数**：每次用户消息/派发 → startBackgroundTask 为会话新建 agent_task 行（INSERT iteration=0, max_iterations=50），工具循环入口 `handleToolCallIteration(..., iteration=0, maxIterations=50)`，iteration 沿单任务执行链 `iteration+1` 递增，**不跨任务共享、无全局计数器**
2. **评委扩展按会话隔离**：`judgeGrantedIterations = Map<conversationId, Integer>`（223 行），任务开始 put(convId,0)、每轮 `getOrDefault(convId)` 判断上限、任务结束/取消时 remove（1541 行 / ToolLoopManager 78 行）——A 与 B 会话各自的扩展额度互不影响
3. **上限常量**：主循环 MAX_TOOL_CALL_ITERATIONS=50（187 行），评委累计扩展 100（per 会话）

对 A 调 B 的含义：
- B 的每次被调用 = B 会话的**新任务**（新 agent_task 行）→ iteration 从 0 独立起算，上限 50+评委扩展同样适用；**A 的循环计数不受 B 影响，B 的也不受 A 影响**（多级 A→B→C 同理，各级独立）
- continue_session：B 的历史消息会加载，但计数从 0 重新计（新任务语义）；busy 保护保证同一会话不会有两个任务并发跑（计数天然不打架）
- **本模块无需为此做任何额外处理**——隔离是现有架构自带的；设计只需保证"一次 invoke = B 会话一个任务"（不共享 A 的会话/任务行）

---

## 四、DeepSeekServiceImpl 改动点（最小侵入）

| # | 位置 | 改动 |
|---|---|---|
| 1 | streamChat 内 apiRequest 组装 | 透传内部字段 `_internalTrust`、`_trustChain`（由服务层内部入口传入，HTTP 入口不暴露） |
| 2 | 工具迭代 needsApproval 判定处（~2662 行） | `if (Boolean.TRUE.equals(apiRequest.get("_internalTrust")))` → 跳过审批事件流，直接复用现有 approve 分支（PermissionContext.setApproved() + executeToolCalls），**不写 pendingQuestionStore、不 setSessionApproved** |
| 3 | startBackgroundTask 建任务记录 | v1 不改 agent_task schema（刚回退过扩展字段，避免再次膨胀实体）：调用溯源由 AgentInvokeService 内存注册表 + 审计日志承担（callId → callerAgent/targetAgent/sessionId/taskId/trustChain）；可选增强：补 caller 溯源列（见 §5） |
| 4 | （可选）任务完成事件 | B 完成时若 A 正在 await，无需事件——A 靠轮询感知 |

**为什么不改授权主链路**：internal 只是把「用户交互审批」替换为「信任链审批」，approve 分支之后的工具执行、消息持久化、迭代推进全部复用，零重复逻辑。

---

## 五、调用溯源（v1 内存方案，DB 列为可选增强）

**背景**：agent_task 的扩展列（result/input 等）已于 2026-08-14 随「任务中心可选增强」删除并回退实体；本模块 v1 **不再给 agent_task 加列**，避免实体反复膨胀。

**v1 溯源三件套**：
1. **内存调用注册表**（AgentInvokeService 内 ConcurrentHashMap）：`callId → {callerAgentConfigId, callerConversationId, targetAgentConfigId, targetSessionId, taskId, trustChain, createdAt, status}`；任务终态/超时清理（TTL 30 分钟，防泄漏）
2. **消息溯源注记**：B 会话的首条 user 消息文本带注记（`（由智能体 A「后端专家」委托，源自会话 #xxx）`）——消息持久化后天然可审计、可被 B 感知（B 知道自己受托）
3. **审计日志**：slf4j 日志（callId/caller/target/session/mode/耗时/结果），与项目现有审计风格一致

**可选增强（未来需要「跨重启查询调用历史/任务中心」时再做）**：
```sql
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS caller_agent_config_id BIGINT;  -- 发起方智能体（A）
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS caller_conversation_id BIGINT;  -- A 的会话
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS caller_task_id BIGINT;          -- A 侧父任务（链）
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS trust_chain VARCHAR(100);       -- 如 "3,7"
```

---

## 六、取消与异常

| 场景 | 行为 |
|---|---|
| A 取消自己的任务（停止按钮 / cancelTask） | 现有链路：TaskContextRegistry.cancelAndUnregisterByConversationId(A 会话) → 工具循环检查点终止。扩展：AgentInvokeService 注册 A 会话 → 委托任务映射，A 任务终态时若 B 任务仍 running → 级联取消 B（按 caller_conversation_id 定位） |
| B 任务失败 | agent_task.error_message 记错误摘要 + 消息表留存失败上下文；A await 返回失败原因；A 自行决定重试/换方案（不自动重试，防放大） |
| B busy（continue_session 命中运行中任务） | 返回 BUSY + 当前任务状态（iteration/status），A 可改 create_session 或稍后重试 |
| 环 / 深度超限 | 调用前校验拒绝，返回明确原因（A→B→A 不允许等） |
| B 被删除/停用 | resolveAgent 失败 → 返回「智能体 B 不存在或已停用」 |

---

## 七、前端与提示词

### 7.1 提示词（code_agent_prompt.txt）
- 内置工具清单 21 → 22，新增 agent_invoke 使用规则：
  - 需要其他智能体（如后端/前端/测试专家）的专业能力时，用 agent_invoke 委托；B 执行期间免授权（用户已通过批准委托表达信任）
  - invoke 后必须 await/poll 拿到真实结果再继续，**禁止编造 B 的结果**
  - 优先 create_session（职责清晰）；需要 B 的既有上下文时用 continue_session + session_id

### 7.2 前端（v1 最小改动）
- 委托创建的 B 会话在 B 的会话列表可见（名称 `[A 委托] xxx`，现有会话列表无需大改）
- 可选优化（v2）：消息气泡/会话卡片标注「由 A 委托」，B 执行中状态沿用现有后台任务指示器

---

## 八、实施拆解

| 阶段 | 内容 | 验收 |
|---|---|---|
| P1 后端核心 | AgentInvokeService + streamChat internal 信任 + agent_task 溯源列 + 环/深度/busy 校验 | mvn compile；单测/临时 main：create_session 建会话、continue_session 校验、环拒绝、busy 拒绝 |
| P2 工具面 | AgentInvokeTool（invoke/poll/cancel）+ 权限注册 + 提示词接入 | 工具注册可见；manual 模式 agent_invoke 弹窗 |
| P3 加固 | A 取消级联 B + 审计日志 + 前端委托标识 | 取消链路验证 |
| P4 端到端验证 | A（auto）调 B（manual，含 command/file_writer 工具集）：委托 B 建会话改代码，全程无授权弹窗；continue_session 续跑；A 侧 await 拿到结果 | 双 Agent 实测通过 |
| P5 文档 | 本设计转正式版 + README/TOOL_SYSTEM 更新 | 文档一致 |

## 九、风险与待决点

- R1（内部直调副作用）：streamChat 内部调用无前端 SSE 订阅——后台订阅机制已存在（后台任务本来就不依赖前端连接），需实测确认事件 sink 无订阅者时 Flux 完整执行（预期 OK，subscribe 即驱动）
- R2（免授权边界）：internal 放行的是「B 的工具集内全部操作」。若 B 配置了 command/delete 等高危工具，委托后 A 可借 B 之手执行任意命令——护栏 = agent_invoke 弹窗委托凭证 + B.workDir 边界 + 环/深度 + 审计。如需更严，可加「internal 只放行 A 自身已获授权的工具类别」（v1.1 候选）
- R3（busy 语义）：continue_session 遇 busy 拒绝后，A 需要 fallback 策略（提示词引导 A 改 create_session 或等待）
- D1：await 用 agent_task 轮询（简单可靠）而非 Flux 桥接
- D2：免授权用批次级内部信任，不持久 sessionApproved（不污染 B 会话）
- D3：create_session 归属调用者 userId；会话列表透明可见（可审计）
- D4：信任深度 ≤3、链上禁止重复

---

## 十、实现记录与验证结果（2026-08-14，全部完成 ✅）

### 10.1 交付差异说明（设计 vs 实现）
| 设计点 | 实现情况 |
|---|---|
| streamChat internal 信任 | ✅ DeepSeekServiceImpl 拆 doStreamChat + 新增 streamChatInternal（callerAgentConfigId 非 null 注入 `_internalTrust/_callerAgentConfigId/_callerConversationId/_trustChain`）；信任仅 JVM 对象传递，Controller 只暴露 streamChat，外部无法伪造 |
| 免授权两处接入 | ✅ 权限判定点（needsApproval=false 跳过弹窗）+ 工具批次前 `PermissionContext.setApproved()`（不持久 setSessionApproved，不污染 B 会话后续手动操作） |
| 调用溯源三件套 | ✅ 内存注册表（callId→映射，TTL 30min/容量 5000）+ 消息溯源注记（B 首条 user 消息带「由智能体 A 委托，信任链…」）+ slf4j 审计日志；**v1 未给 agent_task 加 caller 列**（扩展列已回退，避免实体再膨胀） |
| 结果打包 | ✅ 状态(agent_task) + 最终答复(conversation_message 最后 ASSISTANT content→reasoning 兜底) + TOOL 轨迹摘要（scope=summary/full/final，基线消息 id 防卷历史） |
| 多级委托信任链 | ✅ ToolContext 新增 trustChain（P2），DeepSeekServiceImpl 两处工具上下文设置点同步 apiRequest._trustChain——B→C 委托链不断 |
| 级联取消 | ✅ AgentInvokeService.cancelByCallerConversation + DeepSeekServiceImpl.cancelRunningTask 接入（@Lazy 注入打破循环依赖）——A 停止即取消 B 委托任务 |
| agent_invoke 工具 | ✅ tool/impl/AgentInvokeTool（invoke/poll/cancel；无 A 身份拒绝；@ToolPermission(ADMIN, affectsData=true, highRisk=false)） |
| 默认工具可见性 | ✅ application.yml tool-groups.code_agent_prompt.txt 追加 agent_invoke（21→22）——**P4 联调前置补漏**（P2 只注册未入默认组，tool_names=null 的智能体看不到新工具） |

### 10.2 验证结果（31 用例全过，真实 LLM 环境）
- **P1 后端核心 12/12**：SELF_INVOKE（A 调 A 拒绝）/ AGENT_NOT_FOUND / DEPTH_LIMIT（链满 3）/ CYCLE（链含目标）/ SESSION_MISMATCH（会话归属）/ BUSY（running 拒绝 continue）/ 异步派发返回 sessionId+callId / **manual B 免授权执行完成** / 结果打包含最终答复 / continue_session 续跑 / 结果不卷历史（baseline）/ poll
- **P2 工具面 8/8**：工具注册 / 权限元数据（affectsData=true, highRisk=false）/ 无 A 身份拒绝 / poll·cancel 缺参提示 / **工具端到端 invoke（manual B 免授权）** / 返回含 B 会话 ID 与最终答复
- **P3 加固 4/4**：异步派发 / B running / **cancelTask(A) → B 级联取消终态 cancelled** / poll 反映 CANCELLED
- **P4 双智能体联调 7/7**：用户对话 A（AI 助手）→ A **自主**调用 agent_invoke → B（manual）免授权写文件 → B 会话创建（名带「[AI 助手 委托]」前缀）→ A 任务 completed、B 任务 completed、产物落盘、B 最终答复 242 字符

### 10.3 已知限制（v1.1 候选）
1. **调用注册表是内存态**：进程重启后 callId 关联丢失（会话 ID 持久化不受影响，A 可直接用 B 会话 ID continue_session/poll）
2. **用户自建智能体**（显式 toolNames 白名单）需在智能体配置中显式加入 `agent_invoke` 才能互调（默认工具组已含）；测试智能体等旧配置不含
3. **B busy fallback 靠提示词**：continue_session 遇 BUSY 时由 A 的 LLM 决策改 create_session 或等待（无自动排队）
4. **R2 免授权边界维持 v0.1 决策**：internal 放行 B 工具集内全部操作（用户已确认）；「internal 只放行 A 已获授权类别」留 v1.1 候选
5. **fork 子 Agent 内不可发起 agent_invoke**：子 Agent 线程未设置 agentConfigId（caller=null 被工具拒绝）——fork 与互调两机制本就独立，符合设计边界
