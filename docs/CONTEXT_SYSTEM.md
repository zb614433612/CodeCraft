# 上下文系统深描：注入、落库与压缩

> 版本：v3.6.2 | 更新：2026-11-26 | 受众：开发者 / AI 协作伙伴
> 本文描述一次用户消息从「构建注入 → 落库 → 重建 → 发送 → 压缩」的完整链路，以及背后的取舍。
> 实现依据：2026-11-26 上下文注入落库改造（全部阶段 A~E 已完成，端到端验证待重启后执行）。
> 行号以 HEAD `de91bd1` 为准；优先按「类名 + 方法名」定位，行号仅作参考。

---

## 一、一句话定义

CodeCraft 的上下文系统把**每轮注入的内容当作真实消息落库**，让「发送时」与「重启/重连后重建时」的消息序列逐条一致，从而消除跨轮前缀断裂、重复注入和注入位置漂移。

改造前的问题（也是本次设计的目标）：

| 问题 | 现象 |
|---|---|
| 注入不落库 | 每轮请求前在内存里重建，重启/重连后注入内容消失，其后的消息整体前移 → 前缀缓存全量失效 |
| 动态块反复重排 | 执行模式 / 知识库 / 失败提示每次请求前移动到末尾，位置每轮不同 |
| 重复注入 | 发送路径与重建路径各拼一次，同一内容出现两遍 |
| 首次会话误判 | 注入块也是 SYSTEM，用「是否存在 SYSTEM 消息」判断首次会话会导致真实系统提示词永不保存 |

---

## 二、核心概念与边界

### 2.1 真实系统提示词（`base_system`）

仅指以下三项，**首次会话写入数据库，之后不再重建，永不压缩**：

1. 项目固定文本（`promptFileName`，默认 `code_agent_prompt.txt`）
2. 智能体定义的系统提示词（`AgentConfig.systemPrompt`）
3. 角色设定（`AgentConfig.characterProfile` 生成的角色段）

识别规则（三处实现必须一致）：

- `role = SYSTEM`
- `message_type` 为空 **或** `= base_system`
- 内容不包含旧标记 `[动态运行时注入]`

> 当前代码中真实系统提示词落库时 `message_type` 为 **NULL**（`DeepSeekServiceImpl` 首次会话分支调用 `saveConversationMessage(role=SYSTEM)`）；`base_system` 是保留值 + 旧数据兼容值，目前没有写入点。

### 2.2 上下文注入块

以 SYSTEM 角色构建、但**不属于**系统提示词，落库后是普通历史消息，**可以被压缩覆盖**：

| 注入块 | `message_type` | 触发时机 |
|---|---|---|
| 弯路预警 | `inject_detour` | 每轮开始（按用户文本检索，会话级缓存） |
| 技能目录（简介 + 使用规则） | `inject_skill_catalog` | 每轮开始 |
| 执行模式（手动 / 自动） | `inject_exec_mode` | 每轮开始 |
| 目录知识库 | `inject_kb` | 每轮开始 |
| 工具失败经验提示 | `inject_lesson` | 工具执行失败后追加 |

另外两个「非注入块但同属系统代写」的类型：

| 类型 | `message_type` | 说明 |
|---|---|---|
| 桌面截图自动注入 | `desktop_inject` | role 仍为 USER（API 要求图片只能进 user），前端不渲染气泡 |
| 用户中途补充需求 | `supplement_inject` | role 为 USER，参与上下文还原；**不算新的一轮**，不触发注入块 |

### 2.3 一轮的定义

- **一轮 = 用户在输入框发送的一条消息**（前端生成 `turnId`）。
- 工具循环中追加的「用户中途补充需求」不算新的一轮，不触发新的注入块。
- 每轮注入块绑定该轮用户消息的 `turnId`，用于幂等去重与回滚关联。

### 2.4 上下文范围

| 内容 | 是否进入上下文 | 是否落库 |
|---|---|---|
| 附件提示（编号、文件名、类型、大小） | 是（用户消息 content 开头） | 是（随用户消息） |
| 图片本身 | 走独立图像通道（file 块 / 图片 URL 块） | 否（只存占位） |
| 图片引用占位 | 是（用户消息 content 末尾） | 是 |
| 工具定义 | 请求体 `tools` 字段 | 否（每次动态生成） |
| 真实系统提示词 | 是（列表第一条） | 是（首次会话） |
| 历史对话摘要 | 是（role=USER，系统提示词之后） | 是（`conversation_compaction`） |

---

## 三、最终消息序列

一次请求的消息列表（从上到下）：

```
1. 真实系统提示词                      (SYSTEM, message_type=NULL, 首次会话落库)
2. [compact 模式指令]                  (仅在 compact 模式下拼到第一条 system 末尾)
3. 历史对话摘要                        (USER, 有压缩记录时)
4. 历史消息（按 id 升序）
   ├─ 历史轮次的注入块（inject_*）
   ├─ 历史用户消息
   ├─ 历史助手回复 / 工具调用 / 工具结果
5. 本轮注入块（固定顺序，每轮各一条）
   ├─ 弯路预警        inject_detour
   ├─ 技能目录        inject_skill_catalog
   ├─ 执行模式        inject_exec_mode
   └─ 目录知识库      inject_kb
6. 本轮用户消息（附件提示 + 正文 + 图片引用占位）
7. 本轮助手回复 / 工具调用 / 工具结果（工具循环中产生）
8. 工具失败经验提示（inject_lesson，失败后追加；落库后固定位置，只消费一次）
```

**不变量**：

- 第 5 组注入块必须排在「本轮用户消息」之前，且每轮只出现一次。
- 工具迭代中注入块不移动、不删除、不重复追加。
- 重启 / 重连 / 重试后，按数据库 id 重建出的序列与发送时逐条一致。

---

## 四、落库设计

### 4.1 存储位置与字段

复用 `conversation_message` 表，**无需 DDL 变更**（`message_type VARCHAR(32)` 已存在）：

```sql
-- src/main/resources/schema.sql
CREATE TABLE IF NOT EXISTS conversation_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,          -- system / user / assistant / tool
  content LONGTEXT,
  reasoning LONGTEXT,
  tool_calls LONGTEXT,
  turn_id VARCHAR(50),                -- 前端生成的轮次编号
  message_type VARCHAR(32) DEFAULT NULL,
  latest_reminder LONGTEXT,
  created_at DATETIME NOT NULL,
  INDEX idx_conversation_id (conversation_id),
  INDEX idx_created_at (created_at)
);
```

> 注意：`schema.sql` 中 `message_type` 列的 COMMENT 只写了 `desktop_inject`，未同步新增的 `inject_*` / `supplement_inject` 取值。属于文档漂移，不影响功能。

### 4.2 类型常量

统一定义在 `service/impl/MessagePersister.java:43-58`：

```java
TYPE_BASE_SYSTEM           = "base_system"
TYPE_INJECT_DETOUR         = "inject_detour"
TYPE_INJECT_SKILL_CATALOG  = "inject_skill_catalog"
TYPE_INJECT_EXEC_MODE      = "inject_exec_mode"
TYPE_INJECT_KB             = "inject_kb"
TYPE_INJECT_LESSON         = "inject_lesson"
TYPE_DESKTOP_INJECT        = "desktop_inject"
TYPE_SUPPLEMENT_INJECT     = "supplement_inject"
```

### 4.3 落库顺序

`DeepSeekServiceImpl` 的 `handleChat`「阶段 C」（约 949-1016 行）：

```
1) 构造执行模式文本
2) 构造技能目录文本（同时合并技能自带工具到本轮工具集）
3) 构造附件提示文本 + 收集图片资产 ID
4) 取目录知识库快照（会话级）
5) 构造弯路预警（会话级缓存）
6) 按固定顺序落库四类注入块：
     弯路预警 → 技能目录 → 执行模式 → 目录知识库
7) 生成图片引用占位
8) 保存用户消息（附件提示 + 原文 + 图片引用占位）
9) 建立文件引用（attachment / file_asset 与 message 的关联）
```

**为什么先落注入块再落用户消息**：让数据库自增 id 的顺序与最终消息顺序一致，重建时只按 id 排序即可，不需要额外重排。

### 4.4 排序

- 所有重建查询统一 `ORDER BY id ASC`（不依赖 `created_at`，避免同秒多条消息顺序不稳定）。
- 涉及 SQL：
  - `ConversationMessageMapper.selectByConversationId`
  - `ConversationMessageMapper.selectForContext`（水位线增量）
  - `ConversationMessageMapper.selectByTurnIdAndMessageType`

### 4.5 幂等与去重

```java
// DeepSeekServiceImpl#persistTurnInjection（约 3204-3223 行）
if (turnId != null && messagePersister.existsSystemInjection(conversationId, type, turnId)) {
    return;  // 本轮该类型已存在，跳过
}
if (turnId == null) {
    log.warn("注入块缺少 turnId，无法按轮次幂等，按每轮落库处理: ...");
}
messagePersister.saveSystemInjection(conversationId, type, content, turnId);
```

- 唯一键语义：`conversation_id + turn_id + message_type`（`countByTurnIdAndMessageType`）。
- 重连 / 重试 / 页面刷新不会重复插入。
- `turnId` 为空时**不跳过**（避免整轮注入丢失），改为每轮落库并记 WARN。
- 落库失败只记日志，不影响主流程（上下文可用性优先于持久化完整性）。

### 4.6 附件提示与图片引用占位

实际落库格式（`DeepSeekServiceImpl#buildAttachmentSectionForTurn` / `buildImageRefMark`，约 3258-3317 行）：

```
[系统提示] 用户上传了以下附件，如需读取内容请调用 chat_attachment 工具（action=read_by_attachment）：
- attachment_id: xxx, 文件名: xxx, 类型: xxx, 大小: xxx
---

[系统提示] 用户上传了以下文档附件（本地存储），如需读取内容请调用 chat_attachment 工具（action=read_by_file_asset）：
- file_asset_id: xxx, 文件名: xxx, 类型: xxx, 大小: xxx
---

[图片引用] 本轮用户消息包含 N 张图片（file_asset_id=1,2），图片内容由独立图像通道传输，此处仅保留引用标记。
```

- 附件提示**拼在用户消息 content 开头**，图片引用占位拼在末尾，随用户消息一起落库。
- 图片二进制不入库、不进上下文；重建时按引用恢复占位。

### 4.7 回滚与清理

- 注入块携带与所属轮次相同的 `turnId`，轮次回滚 / 会话删除时一并清理。
- `conversation_message` 对 `conversation` 有 `ON DELETE CASCADE`。
- 前端历史渲染跳过注入块（见第八节）。

---

## 五、重建与发送路径

### 5.1 两条路径必须区分

| 路径 | 方法 | 用途 |
|---|---|---|
| 估算路径 | `DeepSeekServiceImpl#handleChat` | 只用于 token 估算与首轮前置准备，**不会直接发给模型** |
| 发送路径 | `DeepSeekServiceImpl#executeSemiStreamingToolCycle` | 从数据库重新构建消息列表，真正发请求 |

> 这是历史上一个隐蔽的坑：曾把注入只拼在 `handleChat` 的 `historyMessages` 上，导致模型长期收不到执行模式与知识库（注释见 `DeepSeekServiceImpl` 约 1118-1131 行）。
> 现在的原则：**注入一律以数据库为准，发送前不重新生成已落库的内容**。

### 5.2 历史重建流程

`ContextBuilder#buildMessagesFromHistory`（约 241-478 行）：

```
1. 读取压缩快照（水位线 + 累进摘要）
2. 有水位线 → selectForContext(conversationId, watermark)
      只取「真实系统提示词 + id > watermark」
   无水位线 → selectByConversationId（全量，按 id 升序）
3. 摘要插回位置（CompactionService#insertSummaryBefore）
4. 逐条转换为 API 格式：
      · SYSTEM / USER → role + content
      · 带 message_type 的消息 → 额外写入内部字段 _messageType
      · ASSISTANT → tool_calls、reasoning_content（按上下文模式决定真实值/空串）
      · TOOL → 从 reasoning 列剥离展示包装，还原当轮发送的原始 content
5. ToolCallSequenceSanitizer 净化（保证 assistant.tool_calls 与 tool 严格配对）
6. compact 模式额外摘要化历史工具结果
7. reasoning_content 兜底补齐
8. hoistSystemMessages：只把真实系统提示词提到列表最前
```

关键点：

- **`_messageType` 是内部字段**，只用于重建 / 压缩识别，请求出口由 `stripInternalMessageFields` 清理（`DeepSeekServiceImpl` 约 3180-3193 行，只复制含 `_` 前缀字段的消息，其余原样引用，避免深拷贝开销）。
- **`hoistSystemMessages` 只提真实系统提示词**（约 493-522 行）：注入块保留在数据库 id 决定的位置，固定在本轮用户消息之前。这修复了「把注入块当系统提示词提到最前，导致每轮顺序不同」的问题。
- **工具结果包装剥离**（`stripToolResultWrapper`，约 540-565 行）：`reasoning` 列存的是前端展示用的包装文本（虚线框 + 工具名），发送时是未包装的原始内容。不剥离会导致同一条消息两种形态，前缀从第一条 TOOL 起全部失效。

### 5.3 发送时还拼什么

`executeSemiStreamingToolCycle`（约 2219-2312 行）在从数据库重建之后，只做三件事：

1. **compact 模式指令**：`appendToFirstSystem(messages, buildCompactModeInstruction(...))`——内容含 `conversationId`，会话内恒定。
2. **图像块注入**：`FileVisionService.injectBlocksIntoLastUserMessage`，幂等（已含图片块则跳过）。
3. 其余（弯路预警 / 技能目录 / 执行模式 / 知识库 / 附件提示）**全部已在数据库里，不再拼接**。

> 已知隐患：`injectBlocksIntoLastUserMessage` 只改内存中的最后一条 user，若该消息下一轮退回历史，形态可能与重建态不同。当前只在有图片块时触发，已在代码注释中标记为已知项。

---

## 六、压缩规则

### 6.1 不压缩

- 真实系统提示词（`message_type` 为空或 `base_system`，且不含旧动态块标记）。
- 识别实现：
  - `ConversationMessageMapper.countRealSystemMessages`
  - `ConversationMessageMapper.selectForContext` 的过滤条件
  - `CompactionService#applyCompactionRecords` / `#insertSummaryBefore`
  - `ContextBuilder#hoistSystemMessages`

### 6.2 压缩

- 其余历史消息**全部参与压缩**，包括历史注入块、用户消息、助手回复、工具结果。
- **压缩输入只带 USER / ASSISTANT 的 content**（`CompactionService#formatMessagesForCompaction`，约 1186-1202 行）：
  - 不带 SYSTEM（含注入块）
  - 不带 TOOL 消息
  - 不带 assistant 的 reasoning
- 注入块被水位线覆盖时直接丢弃，不单独保留。

### 6.3 水位线与重建

- `conversation_compaction.end_message_id` 即水位线。
- 重建时：`id <= watermark` 且**不是真实系统提示词**的消息全部丢弃，在原位置插入一条摘要（role=USER）。没有区间匹配、没有负 id 叠加，因此不会出现索引错位。
- 增量查询只读「真实 SYSTEM + id > watermark」，长会话不再全量加载（实测某会话 31426 条 / 4412 万字符）。

### 6.4 内存侧同步（`replaceInMemory`）

压缩完成后，当前请求的内存消息列表也要同步，规则与 DB 侧一致：

```
cut = 倒数第 protectRounds 条 user 消息的下标
i < cut 的消息：
   · 非 SYSTEM → 丢弃
   · SYSTEM 且是注入块（inject_* 或旧动态块）→ 丢弃
   · 真实系统提示词 → 保留
i >= cut 的注入块（本轮保护带）→ 保留
```

若压缩区间内只有真实系统提示词，摘要插在它之后，不会顶到列表最前面。

> 为什么注入块必须一起丢：若当前请求仍带旧注入块、下一轮重建时它们已消失，会出现「压缩后估算不降、前后两轮请求体不一致」。

### 6.5 压缩配置

`application.yml`（约 385-394 行）：

| 配置 | 默认值 | 含义 |
|---|---|---|
| `deepseek.compaction.enabled` | true | 总开关 |
| `warning-threshold` | 0.7 | 黄色预警 |
| `compact-threshold` | 0.85 | 同步兜底压缩阈值 |
| `drop-threshold` | 0.95 | 压缩后仍超限的告警线 |
| `protect-rounds` | 2 | 保护最近 2 轮提问 |
| `batch-size` | 10 | 每次压缩最多合并 10 轮 |
| `async-precompress` | true | 请求完成后后台预压缩 |

### 6.6 预期内的断裂（不是 bug）

1. **压缩推进水位线**：历史前缀改变一次，这是压缩的固有代价。
2. **工具失败提示首次出现**：追加在其后，会让后面的消息整体后移一次；之后位置固定。

---

## 七、上下文模式（ContextMode）与注入块

三档模式只影响**历史 reasoning** 与**历史工具结果**，不影响注入块：

| 模式 | 历史 reasoning | 历史工具结果 | 注入块 |
|---|---|---|---|
| `full` | 回传真实值 | 原样保留 | 保留 |
| `balanced`（默认） | 空串占位（字段必须存在） | 原样保留 | 保留 |
| `compact` | 空串占位 | 摘要化 | 保留（`compactHistoryMessages` 只处理 `lastUserIdx` 之前的 assistant/tool） |

解析优先级：请求参数 `contextMode` > `sys_config(context_mode)` > `application.yml deepseek.context-mode` > `balanced`。

> 为什么 balanced 要「空串占位」而不是删字段：DeepSeek thinking 模式（尤其带 tools）对每条 assistant 强校验 `reasoning_content`，缺失直接 400；空串约 3~5 token，而回传整段思考链按未命中输入再计费，单价差三个数量级。

---

## 八、前端渲染规则

两套前端行为必须一致：

| 文件 | 位置 |
|---|---|
| `frontend-v3/src/api/conversation.ts` | 约 242-267 行 |
| `frontend/src/api/conversation.ts` | 约 190-210 行 |

规则：

1. `messageType.startsWith('inject_')` → 跳过，不渲染、不打断助手消息聚合。
2. `desktop_inject` → 跳过用户气泡，把截图资产归并到宿主 AI 消息。
3. `supplement_inject` → 跳过，不渲染成用户发言。
4. 用户气泡正文做两段剥离：
   - 开头附件提示：`/^(?:\s*---\s*\n\[系统提示\] 用户上传了以下[\s\S]*?---\s*\n)+/`
   - 结尾图片引用：`/\n*\[图片引用\] 本轮用户消息包含 [\s\S]*$/`
5. token 数按**剥离后**的正文估算（避免附件提示把用户侧 token 算大）。
6. 附件缩略图按本地文件引用展示。

> 正则只在消息开头/固定格式处匹配，避免误删正文中恰好出现的 `---` 或 `[图片引用]` 文本。

---

## 九、设计取舍与已知项

### 9.1 已接受的取舍

1. **注入块随轮次累积**：旧技能目录、旧执行模式会留在历史中，模型以最新轮次为准，不做额外清理。
2. **工具定义不入库**：留在请求体 `tools` 字段，由工具注册表与技能配置决定。
3. **图片不存二进制**：只保留引用占位，图片走独立通道。
4. **落库失败不阻断对话**：注入块写库异常只记 WARN，本轮仍可在内存中使用。

### 9.2 旧数据兼容

- `message_type` 为 NULL 的 SYSTEM 消息继续视为真实系统提示词。
- 旧的 `[动态运行时注入]` 标记保留识别：含该标记的 SYSTEM 消息**不算**真实系统提示词，可随水位线被压缩覆盖。
- 旧会话中的旧动态块不会自动迁移。

### 9.3 注释与实现漂移（维护时注意）

代码在快速演进，以下注释已过时，读代码时不要被误导：

| 位置 | 过时内容 | 实际实现 |
|---|---|---|
| `DeepSeekServiceImpl` 约 2260-2271 行 | 仍在描述「静态块拼第一条 system、动态块放列表末尾（`placeDynamicBlocksAtTail`）」 | 动态块已删除；执行模式 / 知识库 / 弯路预警均已落库（`placeDynamicBlocksAtTail` 在约 3319-3320 行注明已移除） |
| `DeepSeekServiceImpl` 约 2804-2809 行 | 说失败提示「只写会话级缓存，由 `placeDynamicBlocksAtTail` 放末尾」 | 已改为落库（`inject_lesson`）+ 直接追加到当前列表末尾 |
| `DeepSeekServiceImpl#appendToFirstSystem` 注释 | 说「这些注入项都不落库」 | 仅 compact 模式指令还走它，其余已落库 |
| `ContextBuilder` 类头注释 | 仍列「语言指令注入」 | 语言强制指令已于 2026-11-26 取消 |
| `SkillCatalogBuilder` 约 86 行文案 | 「优先级仅次于系统注入的语言强制指令」 | 语言指令已删，用户可见文案应改为「仅次于系统提示词」 |
| `ContextFingerprint` 注释 | 仍以语言指令为例 | 仅作历史说明 |
| `schema.sql` `message_type` 列 COMMENT | 只写 `desktop_inject` | 实际还有 `inject_*` / `supplement_inject` |

### 9.4 已知遗留

- `sessionLessonHintCache` / `takeLessonHintOnce` / `placeDynamicBlocksAtTail` / `DYNAMIC_BLOCK_MARK` 等已删除（见交付记录），若在旧分支看到属于历史代码。
- 无 `turnId` 时无法按轮次幂等，只能每轮落库 + WARN。
- 端到端验证（重启后序列一致性）需在正常重启后进行；8084 是桌面应用本体端口，验证时不要单独停止。

---

## 十、代码索引

| 职责 | 文件 | 方法 / 位置 |
|---|---|---|
| 类型常量 | `service/impl/MessagePersister.java` | 43-58 行 |
| 保存注入块 | `service/impl/MessagePersister.java` | `saveSystemInjection`（149-157 行） |
| 幂等查询 | `service/impl/MessagePersister.java` | `existsSystemInjection`（167-172 行） |
| 本轮注入构建与落库顺序 | `service/impl/DeepSeekServiceImpl.java` | `handleChat` 阶段 C（约 949-1016 行） |
| 落库幂等 + 告警 | `service/impl/DeepSeekServiceImpl.java` | `persistTurnInjection`（约 3204-3223 行） |
| 首次会话判断 | `service/impl/DeepSeekServiceImpl.java` | 约 1084 行 |
| 附件提示 / 图片占位 | `service/impl/DeepSeekServiceImpl.java` | 约 3258-3317 行 |
| 工具失败提示落库 | `service/impl/DeepSeekServiceImpl.java` | 约 2804-2821 行 |
| 内部字段清理 | `service/impl/DeepSeekServiceImpl.java` | 约 3180-3193 行 |
| 发送路径重建 | `service/impl/DeepSeekServiceImpl.java` | `executeSemiStreamingToolCycle`（约 2219-2312 行） |
| 历史重建 | `service/impl/ContextBuilder.java` | `buildMessagesFromHistory`（约 241-478 行） |
| 真实系统提示词判断 | `service/impl/ContextBuilder.java` | `hasRealSystemMessage`（197-202 行） |
| system 归位 | `service/impl/ContextBuilder.java` | `hoistSystemMessages`（493-522 行） |
| 工具结果包装剥离 | `service/impl/ContextBuilder.java` | `stripToolResultWrapper`（540-565 行） |
| 压缩应用 | `service/impl/CompactionService.java` | `applyCompactionRecords`（656-693 行） |
| 摘要插入 | `service/impl/CompactionService.java` | `insertSummaryBefore`（620-639 行） |
| 压缩输入裁剪 | `service/impl/CompactionService.java` | `formatMessagesForCompaction`（1186-1202 行） |
| 内存侧同步 | `service/impl/CompactionService.java` | `replaceInMemory`（1234-1290 行） |
| 注入块判定 | `service/impl/CompactionService.java` | `isInjectedContextBlock`（1298-1305 行） |
| 真实系统提示词统计 | `mapper/ConversationMessageMapper.java` | `countRealSystemMessages`（58-61 行） |
| 水位线增量查询 | `mapper/ConversationMessageMapper.java` | `selectForContext`（113-120 行） |
| 上下文模式 | `common/enums/ContextMode.java` | 全文 |
| 前端跳过注入块 | `frontend-v3/src/api/conversation.ts` | 242-267 行 |
| 前端跳过注入块（经典版） | `frontend/src/api/conversation.ts` | 190-210 行 |

---

## 十一、自检清单

改动上下文相关代码后，按此清单回归：

- [ ] 同一轮内多次工具迭代，注入块的位置、内容、数量不变。
- [ ] 每轮四类注入块在消息列表中各只出现一次。
- [ ] 重启 / 重连后重建的消息序列与发送时逐条一致（顺序、内容、角色）。
- [ ] 附件提示、图片引用占位重建后仍然存在，且前端气泡不显示提示文本。
- [ ] 工具失败后 `inject_lesson` 只落一条；下一轮作为历史消息参与组装，位置不变。
- [ ] 压缩后真实系统提示词完整保留，注入块随水位线被覆盖。
- [ ] 请求中不再出现「语言强制」system 消息。
- [ ] 请求体不含 `_messageType` 等内部字段。
- [ ] 首次会话能正常写入真实系统提示词（不被注入块误判为「已有系统提示词」）。
- [ ] 前端历史不渲染注入块；token 数按剥离附件提示后的正文计算。

---

## 十二、常见问题

**Q：为什么注入块要用 SYSTEM 角色，而不是拼进用户消息？**
A：SYSTEM 角色不占用户轮次、不影响「倒数第 N 条 user」的压缩边界，且语义上属于运行时上下文。拼进用户消息会让每条用户消息的形状随注入变化，跨轮重建时难以逐字还原。

**Q：为什么真实系统提示词不压缩？**
A：它是身份、工具规范与工作目录。一旦被摘要化，模型行为会整体漂移，且前缀从第 0 条起就不稳定。

**Q：为什么注入块在聊天记录里看不见？**
A：前端按 `message_type` 过滤（`inject_*`），它们只参与模型上下文，不是用户可见的对话内容。

**Q：重启后注入块会重复吗？**
A：不会。落库前按 `turn_id + message_type` 查询，已存在则跳过。重连、重试、页面刷新同理。

**Q：旧会话里的 `[动态运行时注入]` 块怎么处理？**
A：保留识别但不算真实系统提示词，随压缩水位线被覆盖；不会自动迁移为 `inject_*` 类型。

**Q：为什么 `handleChat` 里看到的注入逻辑和实际发送的不完全一样？**
A：`handleChat` 的消息列表只用于 token 估算；实际发送由 `executeSemiStreamingToolCycle` 从数据库重建。两者若不一致只影响估算精度，不影响最终请求内容——但新增注入项时**必须同时考虑两条路径**，否则会出现「估算有、发送没有」。
