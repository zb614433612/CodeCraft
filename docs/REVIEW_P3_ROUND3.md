# P3（授权分流与完成通知）三轮人工审查报告

> 审查方式：人工代码走查（不使用 OCR CLI）
> 审查范围：Phase 18 多 Agent 并行任务 P3 —— 授权分流、任务隔离、完成通知、取消竞态
> 审查日期：Phase 18 开发期
> 结论：**发现 5 个问题（3 严重 + 2 中等），全部已修复；遗留 2 个中低优先级改进建议**

---

## 一、已修复问题

### 🔴 R3-1【严重·后端】claim 失败路径仍可能误取消并发合法任务（H2/M5 深修）

**位置**：`TaskContextRegistry.register` + `DeepSeekServiceImpl.startBackgroundTask` claim 失败/异常路径

**问题**：
1. `register` 用 `byTaskId.put()` 无条件覆盖——同一 taskId 并发 claim（调度器超时重派/重复触发）时，后注册者顶掉先注册者的 ctx；
2. claim 失败路径 `getByTaskId(preTaskId)` + `ctx.cancel()`——若 map 中已是并发线程的 ctx（本线程注册被覆盖），**cancel 会误取消正在合法执行的任务**；
3. 即使未误 cancel，「后注册者移除自己」也会把先注册者的 ctx 永久丢失 → 该任务取消能力失效。

**修复**：
- 新增 `TaskContextRegistry.registerIfAbsent()`（putIfAbsent 语义）：谁 claim 成功谁持有注册，claim 失败者自然注册失败，互不覆盖；
- `registerTaskContext` 改为返回本次创建的 `TaskContext` 并内部写入 `_ctxRegistered`；
- claim 失败/异常路径：**删除 getByTaskId + cancel**，只做 `unregisterIfSame(taskId, myCtx)`（仅当 map 中仍是自己的 ctx 才移除）。

### 🔴 R3-2【严重·后端】error 回调 UPDATE 无状态守卫（H4 补漏）

**位置**：`DeepSeekServiceImpl.startBackgroundTask` 订阅 error 回调

**问题**：complete 回调已加 `AND status='running'`（H4），但 error 回调的 `UPDATE ... status='failed' WHERE id=?` 未加守卫——取消竞态下循环抛异常（如检查点主动中断）会把已置位的 `cancelled` 覆盖为 `failed`。

**修复**：error 回调 UPDATE 同样加 `AND status='running'`。

### 🔴 R3-3【严重·前端】pendingQuestion 只读 computed 赋值静默失效

**位置**：`CodeAssistantView.vue` submitPendingAnswer / handlePermissionAction（4 处）

**问题**：`pendingQuestion` 是 `computed(() => pendingQuestions.get(...))`（只有 getter）。`pendingQuestion.value = null` / `= q` 在 Vue 3 中**静默失效**（仅 dev 警告）→ 授权确认后弹窗不消失、可重复提交同一 uuid（后端报「问题不存在或已超时」）；操作失败时也无法恢复面板。

**修复**：4 处全部改为 `setPendingQuestion(currentConversationId.value, null | q)` 操作底层 Map。

### 🟡 R3-4【中等·前端】backgroundStreams 按 convId 全量移除，误删同会话其他后台流

**位置**：`CodeAssistantView.vue` onStreamComplete

**问题**：P2 双流场景下同一会话可存在多个后台流条目（前台流切走转后台 → 切回派新任务再切走）。原 `filter(s => s.convId !== convId)` 会把该会话**所有**后台流条目移除——仍在运行的任务从 `backgroundStreams` 消失 → `hasActiveStream` 查不到 → 停止按钮失效、状态残留。

**修复**：`onStreamComplete` 新增 `abortCtrl` 参数，filter 精确匹配 `convId + abortController` 只移除本流；调用点（sendMessage finally）传入局部 `abortCtrl`。

### 🟡 R3-5【中等·前端】isLastStream 漏查前台流状态，后台流结束误清前台流授权

**位置**：`CodeAssistantView.vue` onStreamComplete

**问题**：H2 修复的 `isLastStream` 只检查 `backgroundStreams`——双流并存时（前台流 + 后台流），后台流结束会误判「最后一个流」→ 清掉前台流刚设置的 `pendingQuestions`（授权弹窗一闪而过 → 后台等授权超时）和 `activeTaskMap`（任务看板消失）。

**修复**：`isLastStream = !sendingStates.has(convId) && !backgroundStreams.some(convId)`（filter 精确移除本流之后判断，语义为「该会话是否还有任何活跃流」）。

---

## 二、确认无问题的关键链路

| 链路 | 结论 |
|---|---|
| 任务注册/注销原子性（computeIfPresent） | ✅ 无竞态 |
| `getByConversationId` 快照副本 | ✅ 遍历安全 |
| 调度器回队前 `isStillPending` 校验（锁忙/线程池满两分支） | ✅ |
| 2h 超时 → cancelByTaskId → `done.get(30s)` 等流真正结束 → 仅 running 转 failed | ✅ |
| `cancelTask` 按 taskId 精确 UPDATE（不误标历史 running） | ✅ |
| AgentEventBus `synchronized emit` + 成功入 buffer 后才推进 lastSeq | ✅ |
| AgentForkManager finally `Thread.interrupted()` 清中断标志 | ✅ |
| 子 Agent ThreadLocal（ToolContext/PermissionContext/ProjectRootContext）finally 清理 | ✅ |
| 子 Agent 授权分流（120s 超时 → 授权后重执工具 → 超时清理 uuid） | ✅ |
| 主任务授权等待（5min 超时 → 清理 uuid + DB 快照） | ✅ |
| 前端 pendingQuestions Map 化 + 归属标识 + 切会话清空答案 + 角标计数 | ✅ |
| checkAndReconnect 防重入 + await 重连流 + 恢复授权弹窗 | ✅ |
| 删除会话时 clearConvState 清理各 Map | ✅ |

---

## 三、遗留中低优先级建议（未修，记录在案）

1. **【中】`AgentTaskScheduler.cancelTask` 最终仍走 `deepSeekService.cancelTask(conversationId)`（会话级）**：同一会话存在多个独立任务时，按任务取消会级联影响同会话其他任务。当前架构下同会话多任务主要是「父任务 + 子 Agent」（级联取消是设计意图），纯独立任务建议各自独立会话。若未来支持「一会话多独立任务」，需改为精确取消（complete 对应 runningFutures + 置位目标 ctx + 级联只中断该任务的子 Agent）。

2. **【低】授权等待期间取消响应延迟**：`Mono.fromFuture(pq.getFuture()).timeout(5min)` 期间 cancelFlag 置位不会立即生效，最长 5 分钟才退出。可优化为取消路径遍历 `pendingQuestionStore` complete 所有挂起 future（注意与"已授权但取消"的语义区分）。

---

## 四、验证

- ✅ `mvn compile -q -DskipTests` 通过
- ✅ `npm run typecheck`（vue-tsc --noEmit）通过
