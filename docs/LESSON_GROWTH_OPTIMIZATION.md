# 成长体系优化设计（P0 归一化管线 + P1 弯路通道）

> 状态：设计完成，待实施
> 关联模块：成长体系（lesson 踩坑经验库）
> 涉及文件：`FailureNormalizer` / `LessonReviewService` / `LessonRecorder` / `LessonService` / `LessonMapper` / `LessonTool` / `DeepSeekServiceImpl` / `schema.sql`

---

## 1. 背景与问题

成长体系（lesson）上线后暴露三个问题：

1. **LLM 很少主动记录踩坑经验** —— 记录是额外工具调用（成本），prompt 软约束无判定标准，且 LLM 知道有 C2 复盘兜底，主动记录动力不足。
2. **自动捕获经验存在遗漏** —— 失败信号是硬编码正则，覆盖不全；只认"失败"不认"弯路"；C2 只扫本轮、只处理前 3 个失败、队列满丢弃、LLM 判"无需沉淀"直接丢弃。
3. **某些类型经验无法捕获** —— 框架版本 API 变化、用户工作环境导致的命令差异等**没有工具失败信号**；用户消息（环境信息/约定）完全不在捕获范围。

本设计聚焦两个核心痛点的系统性解决：

- **P0 归一化管线**：解决"错误类型/错误码/类信息/工具名提取不到"（归一化落空率高、去重指纹退化、检索命中率低）。
- **P1 弯路通道（C3）**：解决"LLM 走弯路（全程无错误、换方案才成功）无法记录"。

---

## 2. 现有架构与根因分析

### 2.1 现有三条捕获通道

| 通道 | 触发时机 | 覆盖场景 | 缺陷 |
|---|---|---|---|
| ① 自动捕获（`LessonRecorder`） | ToolExecutor **抛异常**时 | 执行期异常 | 工具包装成友好提示（不抛异常）就漏网 |
| ② 对话复盘（`LessonReviewService` C2） | 工具循环结束后异步扫描 | 友好提示类失败 | 失败信号是硬编码正则；只看失败不看弯路；只处理前 3 个失败；队列满丢弃；LLM 判"无需沉淀"直接丢弃 |
| ③ LLM 主动记录（`lesson record`） | 靠 prompt 软约束自觉 | 任意 | LLM 几乎不主动 |

### 2.2 归一化失败的三层根因（`FailureNormalizer`）

| 提取项 | 现状实现 | 失败场景 |
|---|---|---|
| toolName | 调用方传入，null 才兜底 `"unknown"` | ① ToolExecutor 的"id 无效/工具名无效"分支硬编码传 "unknown"；② 空字符串未兜底；③ C2 复盘只取第一个失败工具归一化，其余全丢 |
| errorCategory | 8 条关键词正则，首条命中即止 | "【命令未找到】"等中文友好提示匹配不上 → OTHER；新工具新格式永远覆盖不全 |
| errorCode | 5 条正则，偏好英文异常类名 | 中文报错、通用提示（退出码 1）→ UNKNOWN；多异常嵌套取表层异常而非根因（`Caused by:` 未解析） |
| symptom | 原文截断 512 | 原文=工具包装文本，检索价值低 |

**核心缺陷：规则是静态的、文本表面级的，而错误是语义的、动态演化的。** 且 `LessonReviewService.hasFailureSignal()` 也是同一套硬编码正则——**报错文本命不中失败信号 → C2 根本不触发 → 连 UNKNOWN 草稿都没有**（提取失败与捕获遗漏同源）。

### 2.3 弯路经验抓不到的本质

```
弯路 = 方向一（全程无错误，工具全成功）→ 发现实现不了需求 → 换方向二成功
```

三条通道全部依赖"失败信号"，弯路无失败信号；弯路经验的信号源在**对话语义**里（用户说"不对/换一种"、LLM 说"改用方案二"），现有系统完全未消费。

---

## 3. 方案总览

```
                    ┌─────────────────────────────────────────────┐
                    │             捕获层（四通道）                  │
  工具失败 ────────►│ ① 异常钩子（保留）                           │
  失败信号/启发式 ──►│ ② C2 复盘（升级：判定+归一化一次调用）        │
  用户/LLM 语义信号 ►│ ③ C3 弯路通道（新增）★                      │
  LLM 主动 ────────►│ ④ 主动记录（保留）                           │
                    └───────────────┬─────────────────────────────┘
                                    ▼
                    ┌─────────────────────────────────────────────┐
                    │     归一化引擎（二级管线，重设计）★            │
                    │  规则/字典快速通道 → LLM 语义归一化兜底        │
                    │  → 规则自学习回填（进化闭环）                  │
                    └───────────────┬─────────────────────────────┘
                                    ▼
                    ┌─────────────────────────────────────────────┐
                    │     存储层（类型化经验库）★                   │
                    │  FAILURE（原逻辑不变）                        │
                    │  DETOUR（弯路：goal 维度指纹/检索）★          │
                    └───────────────┬─────────────────────────────┘
                                    ▼
                    ┌─────────────────────────────────────────────┐
                    │     消费层                                    │
                    │  失败时注入 SOLUTION/HINT（现状）             │
                    │  新任务规划时检索 DETOUR 提示 ★               │
                    └─────────────────────────────────────────────┘
```

★ = 本次新增/重设计。四通道全部走同一入库入口（`recordLessonWithResult`），指纹去重天然兼容。

---

## 4. P0 归一化管线 · 详细设计

### 4.0 目标与验收

| 项 | 内容 |
|---|---|
| 目标 | 降低归一化落空率（UNKNOWN/OTHER），让错误类别/错误码/工具名稳定可检索、跨通道去重一致 |
| 验收 1 | 规则通道对常见错误（Maven/npm/Git/命令/中文报错）直接命中，UNKNOWN 占比显著下降（目标 <10%） |
| 验收 2 | 规则通道落空时 LLM 兜底自动补全，草稿异步增强，主流程零阻塞 |
| 验收 3 | 同一错误重复出现只调 1 次 LLM（缓存+规则命中） |
| 验收 4 | C2 复盘与归一化兜底共用同一 LLM 输出规范，跨通道指纹一致 |

### 4.1 总体架构：两级管线 + 缓存 + 回写增强

```
normalize(toolName, argsJson, errorText)
   │
   ├─① 规则快速通道（增强版 FailureNormalizer）──命中 → 返回结果
   │    正则 + 错误码字典 + Caused-by 根因解析 + 工具名兜底
   │
   ├─② 规则落空（UNKNOWN/OTHER）→ 查 lesson_norm_cache ──命中 → 返回缓存结果
   │
   └─③ 缓存未命中 → 异步提交 LLM 语义归一化（不阻塞！）→ 返回规则通道当前结果
         │
         ▼（异步线程内）
      调 LLM → 解析 JSON（worthRecord/category/code/rootCause）
         │
         ├─ 写缓存 lesson_norm_cache（7 天 TTL）
         ├─ 写规则表 lesson_rule（自学习候选）
         └─ 回写增强：按 signature 找到已落库草稿，补全 UNKNOWN 字段（只补空不覆盖）
```

**核心设计决策：同步快速、异步增强。** 调用方（`LessonRecorder`/C2/提示注入）永远不被 LLM 阻塞——首次用规则结果落库（可能 UNKNOWN），LLM 结果异步回来把草稿"变聪明"。

### 4.2 规则通道增强（改 `FailureNormalizer` + 新增 `ErrorCodeDictionary`）

#### 4.2.1 工具名兜底（现状缺陷修复）

| 缺陷 | 位置 | 修复 |
|---|---|---|
| `toolName` 空白字符串未兜底 | `FailureNormalizer.normalize()` | 补 `isBlank()` 检查，空白 → "unknown" |
| ToolExecutor 三处硬编码传 "unknown" | `ToolExecutor`（id 无效/工具名无效分支） | 从 arguments 尽力反推（如 file_path 存在 → file_explorer），仍失败才 unknown |
| C2 复盘只取第一个失败工具 | `LessonReviewService.doReview` | 改为逐个失败归一化，同 tool+同 errorCode 合并，不同坑分别入库 |

#### 4.2.2 错误码字典（新增 `ErrorCodeDictionary`，核心增强）

从"纯正则提取"升级为「正则 + 按工具域分组的字典映射」。字典条目格式：

```
工具域 | 匹配模式（正则/包含） | 稳定短码 | 错误类别 | 示例
command  | 不是内部或外部命令|command not found | CMD_NOT_FOUND     | ENV
command  | 退出码[:：]\s*[1-9]                     | EXIT_CODE_{n}     | COMPILE（编译类）
maven    | Could not resolve.*artifact             | MAVEN_DEP_RESOLVE | DEPENDENCY
maven    | BUILD FAILURE                           | MAVEN_BUILD_FAIL  | COMPILE
npm      | npm ERR! code (ENOENT|EACCES|...)      | NPM_{CODE}        | ENV/DEPENDENCY
git      | fatal:                                 | GIT_FATAL         | OTHER
network  | ECONNREFUSED|Connection refused         | ECONNREFUSED      | NETWORK
jdk      | (NoClassDefFoundError|ClassNotFound...) | {类名}            | DEPENDENCY
中文映射  | 命令未找到 | CMD_NOT_FOUND | ENV
中文映射  | 权限不足|permission denied | PERMISSION_DENIED | AUTH
中文映射  | 文件不存在|No such file   | FILE_NOT_FOUND    | ENV
```

**关键规则**：
- 字典命中时**类别与错误码一起带出**（联动，类别不再单独正则猜）
- 短码规范：`工具域_现象`，**禁止含路径/版本号/端口等易变信息**（易变信息留给 params）
- 字典放 `resources/error-dictionary.yml`（可配置，不写死在代码里）
- 匹配顺序：**字典精确匹配 > Caused-by 根因 > 现有正则 > UNKNOWN**

#### 4.2.3 Caused-by 根因链解析

```
错误文本含 "Caused by:" 时：
  → 取最后一个 Caused by 后面的异常类名作为 errorCode 候选
  → 与字典比对，字典优先
```

#### 4.2.4 失败信号启发式（解决"触发不到"，与归一化同源）

`LessonReviewService.hasFailureSignal()` 在正则命中之外增加启发式判定：

```
结果文本非空 且 含以下信号之一：
  失败|错误|异常|无法|不能|拒绝|超时|timeout|error|fail|exception|exception in thread
```

误报控制：启发式只负责**触发**，值得不值得由 LLM 的 `worthRecord` 判定过滤，一次 LLM 调用换来"不漏"。

### 4.3 LLM 语义归一化兜底（新增 `LessonNormalizerService`）

#### 4.3.1 职责与线程模型

- 独立类，独立单线程池（`lesson-normalizer`，队列 500，满则丢弃+日志，绝不阻塞主流程）
- 入口 `normalizeAsync(projectKey, toolName, argsJson, errorText, signature)`：
  - 调用线程只做：查缓存 → 未命中则提交异步任务（上下文值在调用线程提取，沿用 `LessonRecorder` 的 ThreadLocal 规范）

#### 4.3.2 LLM Prompt（与 C2 复盘共用同一模板，保证跨通道一致）

```
【系统】你是失败归一化引擎。给定一次工具调用失败，输出稳定的结构化结果。
硬性规范：
1. errorCategory 只能是枚举值之一：COMPILE/DEPENDENCY/NETWORK/AUTH/MCP_HANDSHAKE/SQL/PARAM/ENV/OTHER
2. errorCode 必须是稳定短码：格式「工具域_现象」（如 MAVEN_DEP_RESOLVE、NPM_ENOENT、CMD_NOT_FOUND）
   或标准异常类名（如 NoClassDefFoundError）。禁止长句子；禁止包含路径/版本号/端口/文件名等易变信息
3. toolName 使用标准工具名（command/file_writer/execute_sql/...）
4. worthRecord：该失败是否值得沉淀（有明确报错、解法可复用 → true；纯一次性误操作 → false）
5. 只输出一个 JSON 对象，不要任何其他文字。
格式：{"worthRecord": true, "errorCategory":"...", "errorCode":"...", "toolName":"...", "rootCause":"..."}
```

```
【用户】工具名：command
参数：{"command":"mvn clean compile"}
报错文本：Build failed: 找不到依赖 spring-boot-starter-web（截断800）
```

输出解析复用 `parseJsonStrict`（现成实现）；**校验不通过（errorCode 非短码/类别不在枚举）→ 降级丢弃，用规则通道结果，不污染缓存**。

#### 4.3.3 缓存设计（新表 `lesson_norm_cache`）

| 项 | 设计 |
|---|---|
| cache_key | `md5(toolName + "|" + errorText前512字符)` —— **不含参数**（类别/错误码与参数解耦，同错误不同路径复用缓存） |
| 存储字段 | error_category / error_code / tool_name / root_cause / solution（solution 可空） |
| TTL | 7 天（updated_at 判断），容量上限 20000，超限按最旧清理 |
| 为什么不存 symptom | 缓存结果不含具体参数，symptom 每次用原文，保证现象精确 |

#### 4.3.4 回写增强（草稿"变聪明"）

- 新增 Mapper 方法 `updateNormalized(id, errorCategory, errorCode, rootCause, updatedAt)`：**只更新仍为 UNKNOWN/OTHER 的字段**（只补空不覆盖，与 F2 合并语义一致，防竞态覆盖 C2 已补全的内容）
- 回写按 signature 查草稿（`selectBySignature`），若草稿已被删除/隐藏则跳过

### 4.4 规则自学习（P0 简化版：只沉淀不淘汰）

新表 `lesson_rule`：LLM 归一化成功时，把 `(报错文本片段 → errorCategory/errorCode)` 写入候选规则。

```
提取顺序升级为：规则表命中 → 字典/正则 → 缓存 → LLM
同一 text_pattern 的 LLM 结果一致 ≥2 次 → status 转正（永久生效，命中率看板跟踪）
```

P0 只做"插入 + 提取时优先查"，转正/淘汰的完整闭环放 P2。收益：**系统用越久，LLM 调用越少**。

### 4.5 数据模型变更（schema.sql）

```sql
-- 归一化缓存表（新增）
CREATE TABLE IF NOT EXISTS lesson_norm_cache (
  cache_key      VARCHAR(64)  PRIMARY KEY,
  error_category VARCHAR(32)  NOT NULL,
  error_code     VARCHAR(128) NOT NULL,
  tool_name      VARCHAR(64)  NOT NULL,
  root_cause     VARCHAR(1024),
  solution       VARCHAR(2048),
  created_at     DATETIME NOT NULL,
  updated_at     DATETIME NOT NULL
);

-- 规则自学习表（新增，P0 简化版）
CREATE TABLE IF NOT EXISTS lesson_rule (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  text_pattern   VARCHAR(512) NOT NULL,
  error_category VARCHAR(32)  NOT NULL,
  error_code     VARCHAR(128) NOT NULL,
  hit_count      INT DEFAULT 0,
  source         VARCHAR(16) DEFAULT 'llm',
  status         TINYINT DEFAULT 0,
  created_at     DATETIME NOT NULL,
  updated_at     DATETIME NOT NULL
);
```

`lesson` 主表 P0 **不需要改**（回写只更新已有列）。配套新增：`LessonNormCacheMapper`（读写/清理）、`LessonRuleMapper`（插入/查询/转正计数）。

### 4.6 关键时序（两条主链路）

**链路 A：异常钩子（LessonRecorder 路径）**
```
工具抛异常 → recordAsync（调用线程提 projectKey）
  → normalize()：规则通道（可能 UNKNOWN）→ 草稿落库（原逻辑）
  → normalizeAsync（提交，立即返回，不阻塞工具返回给 LLM）
      → 查缓存命中？→ 回写草稿（零 LLM 成本）
      → 未命中 → LLM 归一化 → 写缓存 → 写规则表 → 回写草稿
```

**链路 B：C2 复盘（判定+归一化合并，一次 LLM 调用两用）**
```
轮末 reviewTurnAsync → extractFailedToolCalls（正则+启发式）
  → LLM 复盘 prompt 扩展：输出 JSON 增加 errorCategory/errorCode/toolName 字段
  → 入库直接用 LLM 归一化字段（signature 一致：短码规范保证）
  → 同时写缓存（复盘结果反哺缓存，其他通道受益）
```

⚠️ **一致性关键点**：C2 与归一化兜底**必须共用同一 prompt 模板和短码规范**，否则同一个坑两个通道产出不同 errorCode → 指纹分叉 → 去重失效。

### 4.7 配置项（application.yml 新增）

```yaml
lesson:
  normalize:
    enabled: true
    llm-fallback: true          # LLM 兜底开关（可关，纯规则模式）
    cache-ttl-days: 7
    cache-max: 20000
    queue-size: 500
    llm-timeout-ms: 30000
```

### 4.8 可观测性（新增指标）

| 指标 | 来源 |
|---|---|
| UNKNOWN/OTHER 占比（归一化落空率） | `LessonService.getStats` 扩展 |
| 规则通道命中率 / 缓存命中率 / LLM 调用次数 | `LessonNormalizerService` 计数 |
| 草稿增强数（UNKNOWN 被 LLM 结果回写） | `updateNormalized` 返回行数累计 |
| 规则表转正数 | `lesson_rule` status 统计 |

### 4.9 P0 实施任务拆解

| 任务 | 内容 | 依赖 |
|---|---|---|
| T1 | `ErrorCodeDictionary` + 字典 yml + FailureNormalizer 增强（工具名兜底/Caused-by/中文映射） | - |
| T2 | `hasFailureSignal` 启发式 + C2 逐个失败归一化合并 | T1 |
| T3 | schema.sql 新表 ×2 + `LessonNormCacheMapper`/`LessonRuleMapper` | - |
| T4 | `LessonNormalizerService`（缓存读写/LLM 调用/回写 `updateNormalized`）+ LessonMapper 新方法 | T1, T3 |
| T5 | C2 复盘 prompt 扩展（输出 errorCategory/errorCode）+ 共用模板抽取 | T1 |
| T6 | 配置项 + 指标 + 日志 | T4 |
| T7 | 单元测试：字典命中/LLM 兜底/缓存去重/回写只补空 | T4, T5 |

---

## 5. P1 弯路通道（C3）· 详细设计

### 5.0 目标与验收

| 项 | 内容 |
|---|---|
| 目标 | 捕获"无工具失败但方案走弯路"的经验（方向 A 全程无错、实现不了、换方向 B 成功），沉淀为可检索复用的 DETOUR 经验 |
| 验收 1 | 用户说"不对/换一种方式"或 LLM 自述"改用方案二"后，能沉淀 DETOUR 经验（含被放弃方案+原因+最终方案） |
| 验收 2 | 新任务通过 `lesson search`（type=detour）能命中弯路经验，LLM 不再重蹈覆辙 |
| 验收 3 | 误检可控：权限类指令（"不要删除文件"）不触发；LLM `worthRecord` 二次过滤 |
| 验收 4 | 主流程零阻塞、零额外常驻成本（信号未命中时不产生任何 LLM 调用） |
| 验收 5 | 与 FAILURE 经验共存互不影响：指纹分叉、检索可过滤、老数据零迁移 |

### 5.1 总体架构

```
轮末（triggerLessonReview 旁新增触发点）
   │
   ▼
LessonDetourService.detourReviewAsync()
   ├─ ① 信号检测（DetourSignalDetector，同步扫描，纯字符串匹配零成本）
   │     A 用户否定/纠正词（user 消息）
   │     B LLM 自述换方案（assistant 消息）★ 高置信，可单独触发
   │     C 工具序列模式（file_writer 重写 / 命令族切换）— 仅辅助证据
   │
   ├─ 无信号 → 返回（零成本，不调 LLM）
   │
   ├─ 幂等检查（conversationId:turnId，独立表，TTL 2h）
   │
   ├─ 短路检查：本轮最终回复已含【方案取舍】块且已入库 → 跳过
   │
   └─ 异步提交（独立线程池 lesson-detour）
         ▼
       LLM 判定+提炼（一次调用，worthRecord 过滤）
         ▼
       worthRecord=true → 入库 DETOUR（type 指纹去重，source=review）
```

**与 C2 的关系**：独立服务、独立线程池、独立幂等表，互不干扰。C2 管"工具失败"，C3 管"方案弯路"，信号源不同，产出类型不同（FAILURE vs DETOUR）。

### 5.2 信号检测设计（新增 `DetourSignalDetector`）

纯字符串匹配（同步、微秒级、零 LLM 成本），规则可配置（`resources/detour-signals.yml`）。

#### 5.2.1 信号 A：用户否定/纠正（user 消息）

| 类别 | 词表（示例） |
|---|---|
| 方案否定 | 不对、不行、不是这样、不是这个意思、方向不对、思路不对、这样不行、搞错了、理解错了 |
| 要求更换 | 换一种、换方案、换个方式、别用、不要这样、不能用这个、改一下方案、重新做、重来、推倒重来、推翻、白做了、白费 |
| 结果否定 | 实现不了、达不到、做不到、没法实现、不满足需求、不符合要求、不是我要的 |

**排除规则（防误报）**：命中词 + 权限指令语境（"不要删除/不要动/别改" + 文件/目录对象）→ 标记为低置信，不单独触发，仅作为辅助证据。歧义消解交给 LLM 判定。

#### 5.2.2 信号 B：LLM 自述换方案（assistant 消息）★ 核心信号

正则匹配（示例）：
```
方案.{0,12}(不行|不可行|无法|失败|放弃|走不通|不适用|不合适)
改用|换成|换用|换一种|改为|重新实现|推翻重来|重写(了)?(一|两|几)遍
试了.{0,12}才成功|多次尝试后|绕了远路|走了弯路|最终(采用|选择|用)(方案|方式)?.{0,6}(二|B|2)
```

**强度分级**：
- **强**（单独命中即可触发）：`改用|换成|换一种|试了.*才成功|绕了远路|走了弯路`
- **中**（需配合信号 A 或 C）：`重新实现|推翻重来|重写`（可能是正常重构而非弯路）

#### 5.2.3 信号 C：工具序列模式（辅助证据，不单独触发）

| 模式 | 检测规则 |
|---|---|
| 文件重写 | 同一轮内 `file_writer` 对**同一 file_path** 的 write/edit ≥2 次 |
| 命令族切换 | 同一轮内 `command` 执行的命令**前缀族** ≥2 类（mvn/npm/git/curl/node） |

信号 C 命中时把"重写/切换"事实作为上下文传给 LLM，帮助判断是否是弯路。

#### 5.2.4 触发判定矩阵

| 信号组合 | 是否触发 LLM 提炼 |
|---|---|
| B 强（单独） | ✅ 直接触发 |
| A + （B 中 或 C） | ✅ 触发 |
| A 单独 | ⚠️ 触发（LLM 判定，worthRecord 过滤） |
| B 中 单独 / C 单独 | ❌ 不触发（避免把正常重构误判为弯路） |

### 5.3 LLM 判定+提炼设计（新增 `LessonDetourService`）

#### 5.3.1 输入组装

```
【本轮对话摘要】
- 用户需求原文（截断 500）
- 工具调用序列摘要（工具名+关键参数，截断 1500，标记信号 C 命中的重写/切换）
- 命中信号 A/B 的用户/assistant 消息原文（截断 300/条，含信号词标记）
- LLM 最终回复（截断 1000）
```

#### 5.3.2 LLM Prompt

```
【系统】你是方案复盘助手。给定一轮 AI 助手执行任务的对话记录，判断是否存在"走了弯路"：
尝试的方案不可行/无法满足需求，更换方案后才成功。
判断规则：
1. 只有真正更换方案且最终成功才值得记录；一次性正常完成、或正常重构不记录
2. 用户说"不要删除文件"这类权限指令不是方案否定，不记录
3. 输出 JSON，只输出 JSON：
{
  "worthRecord": true|false,
  "goal": "任务目标一句话（检索主维度，不含具体文件名/路径）",
  "abandonedApproach": "被放弃的方案（可识别描述）",
  "abandonReason": "放弃原因（为什么走不通，这是关键知识）",
  "adoptedApproach": "最终方案",
  "solution": "可复用建议：下次遇到同类目标直接怎么做",
  "keywords": "3~5 个检索标签，空格分隔"
}
4. worthRecord=false 时，其他字段全部为 null
```

#### 5.3.3 输出校验与入库

- 解析复用 `parseJsonStrict`（C2 现成实现）
- 校验：worthRecord=true 时必须 goal/abandonedApproach/abandonReason/adoptedApproach 非空，否则丢弃（宁缺毋滥）
- **判定+提炼一次调用**，与 C2 的 `reviewWithLLM` 同模式；超时 60s；失败静默不重试

### 5.4 存储与指纹设计

#### 5.4.1 lesson 表加两列（老数据零迁移）

```sql
ALTER TABLE lesson ADD COLUMN IF NOT EXISTS type VARCHAR(16) DEFAULT 'FAILURE' COMMENT 'FAILURE=失败经验 / DETOUR=弯路经验';
ALTER TABLE lesson ADD COLUMN IF NOT EXISTS goal VARCHAR(256) COMMENT 'DETOUR 专用：任务目标（检索维度）';
CREATE INDEX IF NOT EXISTS idx_lesson_type ON lesson(project_key, type);
```

（H2 2.x 支持 `ADD COLUMN IF NOT EXISTS`；实体 `Lesson` 同步加 `type`/`goal` 字段 + `TYPE_DETOUR`/`TYPE_FAILURE` 常量）

#### 5.4.2 DETOUR 入库映射（复用 `recordLessonWithResult`，扩展参数）

| 字段 | FAILURE（现状） | DETOUR（新增） |
|---|---|---|
| toolName | 实际工具名 | `"detour"` |
| errorCategory | 归一化类别 | `"DETOUR"` |
| errorCode | 归一化短码 | `"DETOUR"` |
| symptom | 失败现象 | `方案绕路: {abandonedApproach} 不可行（{abandonReason}），改用 {adoptedApproach}` |
| rootCause | 根因 | abandonReason |
| solution | 解法 | solution（可复用建议） |
| applicableCond | 适用条件 | `目标为: {goal} 时`（自动生成） |
| keywords | 标签 | keywords |
| goal | - | goal（新列） |
| type | FAILURE | DETOUR |

#### 5.4.3 指纹分叉（去重一致性）

```
FAILURE: md5(projectKey | toolName | category | errorCode | paramsHash)   ← 不变
DETOUR:  md5(projectKey | "detour" | normalizedGoal)                       ← 新增
normalizedGoal = goal 去标点/空白、转小写、截断 60 字符
```

- 指纹计算在 `LessonService.recordLessonWithResult` 内按 type 分叉，同坑命中走现有 `selectBySignature` + hit_count +1 逻辑，**并发竞态兜底（DuplicateKey）天然复用**
- 弯路是低频事件，goal 描述差异导致的指纹分叉影响可忽略

### 5.5 检索与注入设计

#### 5.5.1 search 工具扩展（`LessonTool` + `LessonService` + `LessonMapper`）

- `LessonTool.getParameters` 增加 `type` 参数（search 可选：`FAILURE`/`DETOUR`/不传=全部）
- `LessonService.searchLessons` 增加 `type` 过滤，透传 Mapper：
  - `selectByScope`/`selectFuzzy` 加 `<if test='type != null'> AND type = #{type}</if>`
- **DETOUR 检索路径**：`goal`/`keywords` LIKE 兜底（复用 selectFuzzy 的 symptom/root_cause/solution/keywords 四字段 + goal 加进 LIKE 集合），无需新 SQL

#### 5.5.2 注入时机（P1 简化版：prompt 规则驱动，零新增注入通道）

`code_agent_prompt.txt` 的 lesson 章节增加两条规则：

```
- 接到新任务、规划方案前：先 lesson action=search（type=detour + 目标关键词），
  若命中弯路经验（"该目标曾用方案A失败"），直接采用可行方案，避免重蹈覆辙
- 任务过程中更换过方案的：最终回复必须附【方案取舍】块（格式见下），
  由系统自动解析入库，无需额外调用 lesson 工具
```

#### 5.5.3 【方案取舍】块（结构化素材 + C3 短路）

```
约定格式（最终回复末尾）：
【方案取舍】
目标：xxx
尝试：方案A（原因：yyy 不可行）
最终：方案B

后端解析（新增 DetourBlockParser，正则提取）：
→ 命中 → 直接入库 DETOUR（source=llm，走同指纹去重）
→ 本轮 C3 检测到信号时先检查本块，已入库则短路（复用 C2 的 hasSuccessfulLessonReport 模式）
```

### 5.6 关键时序（完整链路）

```
轮末 triggerDetourReview（DeepSeekServiceImpl，与 triggerLessonReview 并列调用）
  → detourReviewAsync(conversationId, turnId, projectRoot, messages, client, model)
      → DetourSignalDetector.scan(messages) → 无信号 → return（零成本）
      → 幂等检查（detourDedup，TTL 2h，上限 2000）
      → 短路检查（【方案取舍】块已入库？）
      → 异步提交（线程池 lesson-detour，队列 200，满丢弃+日志）
          → LLM 判定+提炼（一次调用）
          → worthRecord=false → 丢弃
          → true → recordLessonWithResult(..., type=DETOUR, goal=...)
              → 指纹查重 → 命中：hit+1（F2 合并语义同 FAILURE）
              → 新增：落库（source=review）
```

**线程池/幂等规范完全对齐 `LessonReviewService`**（单线程 + 有界队列 + 满丢弃 + ThreadLocal 调用线程提取），代码可大量复用模式。

### 5.7 配置项（application.yml 新增）

```yaml
lesson:
  detour:
    enabled: true
    signal-a: true        # 用户否定信号开关
    signal-b: true        # LLM 自述换方案信号开关
    signal-c: true        # 工具序列辅助信号开关
    queue-size: 200
    llm-timeout-ms: 60000
```

### 5.8 可观测性（新增指标）

| 指标 | 说明 |
|---|---|
| detourCount（getStats 扩展） | DETOUR 经验总数（看板展示） |
| 信号命中次数（A/B/C 分计） | 判断词表覆盖率与误检率 |
| worthRecord 过滤率 | 误检控制效果 |
| 【方案取舍】块解析成功数 | LLM 配合度 |
| C3 短路次数 | 避免重复 LLM 调用的效果 |

### 5.9 P1 实施任务拆解

| 任务 | 内容 | 依赖 |
|---|---|---|
| T1 | schema 加 type/goal 列 + `Lesson` 实体 + Mapper（type 过滤/统计）+ 索引 | - |
| T2 | `DetourSignalDetector`（信号 A/B 词表 + C 序列检测，yml 可配置） | - |
| T3 | `LessonDetourService`（幂等/线程池/LLM 提炼/入库）+ `LessonService` 指纹分叉 | T1 |
| T4 | `DeepSeekServiceImpl` 触发点 + `DetourBlockParser`（方案取舍块解析）+ 短路 | T3 |
| T5 | search 扩展 type + `LessonTool` 参数 + prompt 两条规则 | T1 |
| T6 | 管理页 type 筛选 + 看板 detourCount | T1 |
| T7 | 单测：信号匹配/误报排除（"不要删除文件"）/LLM 输出校验/指纹去重/取舍块解析 | T2-T5 |

---

## 6. 实施路线图

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **P0 归一化管线** | 规则增强（字典/Caused by/工具名兜底/中文映射）+ LLM 兜底（判定+归一化一次调用+缓存）+ 指标 | UNKNOWN 占比降到 <10%；OTHER 占比明显下降 |
| **P1 弯路通道** | 信号扫描 + LLM 提炼 + type/goal 字段与检索 + prompt | 用户说"换一种方式"后能沉淀 DETOUR 经验；新任务 search 能命中 |
| **P2 进化闭环** | 规则自学习转正/淘汰 + 任务启动自动注入 DETOUR + 看板完善 | 规则表命中率持续上升，LLM 归一化调用量持续下降 |

## 7. 风险与成本控制（设计已内置）

| 风险 | 对策 |
|---|---|
| LLM 短码不稳定 → 指纹分叉 | prompt 硬约束 + 输出校验（非短码/非枚举 → 丢弃降级）；缓存保证同文本同结果 |
| 回写竞态覆盖 C2 内容 | `updateNormalized` 只补空不覆盖（与 F2 同语义） |
| LLM 调用成本 | 缓存优先 + 规则表优先 + 只对落空触发 + 异步限流，稳态调用趋近于零 |
| 字典维护成本 | yml 配置化 + 规则自学习自动沉淀，人工只补高频新坑 |
| 用户否定词误检（权限指令 vs 方案否定） | 排除规则 + 仅触发判定、LLM worthRecord 二次过滤 |
| 取舍块解析失败 | 解析失败不报错，C3 正常兜底（双保险） |
| 隐私 | 用户消息只在内存扫描，落库仅提炼后的结构化经验，不存对话原文 |

## 8. 后续可做（未纳入 P0/P1）

- **P2 任务启动自动注入 DETOUR**：新 user 消息后系统异步检索，命中则下一轮 system 注入（P1 用 prompt 规则驱动，P2 升级为系统级自动注入）
- **P2 规则自学习完整闭环**：候选规则转正/淘汰机制 + 命中率看板
- **P2 环境参数检索**：经验带环境参数（os/框架版本），"Windows 的坑"不套到 Linux 场景
- **对话内容捕获扩展**：环境/约定/知识类经验（type=ENV/CONVENTION/KNOWLEDGE），解决"框架版本 API 变化"等无失败信号的经验类型
- **依赖版本变化巡检**：检测 pom.xml/package.json 依赖变更，沉淀"升级注意"类经验
