> 🌐 English Version：[🇬🇧 CHANGELOG_EN](./CHANGELOG_EN.md)
# 更新日志

本文档记录 CodeCraft 项目的所有重要版本变更。

---

## [3.4.6] - 2026-11-25

> **首 token 偶发卡 10 秒的根因修复**：真正原因不是缓存、不是上下文、也不是服务端排队，
> 而是** TCP SYN 被丢弃 + 内核 SYN 重传**（Windows 退避 1s→2s→4s→8s，丢几个包就是 1~11 秒），
> 且建连最终成功、不抛异常 —— 所以日志里一条重试记录都没有。
> 本版新增「IP 健康优选」：提前探活出坏 IP 并排除，让 Netty 只用能秒连的 IP。
> 必须重启后端；**无数据库结构变更**。

### 🔍 问题：为什么是 10 秒，为什么日志里没有任何报错

**实测方法**：对 `api.deepseek.com:443` 连续做 20 次**全新** TCP 建连，同时 ping 同域名 20 次。

```
TCP 建连耗时（ms）：
  48,48,49,49,49,49,49,49,50,50,50,50,50,50,50,51,56   ← 17 次正常（约 50ms）
  1057                                                 ← 1 次卡 1 秒
  11073                                                ← 1 次卡 11 秒

ping 同域名 20 次：0% 丢包、7~9ms
Windows TCP 参数：Initial RTO = 1000ms、Max SYN Retransmissions = 4
```

**结论**：
- **ICMP 不丢包，丢的是 TCP SYN**（两条路径的队列/优先级不同）
- 丢 SYN 后由**内核**重传，退避为 `1s → 2s → 4s → 8s`（累计 1/3/7/15 秒）
- 建连**最终成功**，不抛 `ConnectTimeoutException` → 应用层不会重试、不会报错
- 业务侧只看到「首 token 卡 10 秒」（本版埋点实测：`TTFB=10550ms`，
  其中**本地准备仅 9ms**、**网络+服务端 10541ms**）

**为什么是「偶发」而不是「一直慢」**：

```
DNS 对同一域名返回多个 CDN IP，且顺序会轮换（实测两次解析顺序相反）：
  103.220.64.100, 171.214.25.232
  171.214.25.232, 103.220.64.100

Netty 的 DefaultNameResolver → InetAddress.getByName() → 只取【第一个】IP，无 Happy Eyeballs
```

于是「DNS 恰好把坏 IP 排第一」时必然卡满重传窗口，「排第一是好 IP」时只要 50ms。

**已被实测排除的怀疑**（避免以后重复排查）：
DNS（9~563ms）、端口耗尽（动态端口 16384 个）、TIME_WAIT 堆积（全局仅 9 个）、
IPv6（无 AAAA 记录）、hosts / 系统代理（直连）、限流（`maxWaitMs=5s` 且超时会抛异常）、
请求体体积（673K 字符仍 308ms）、缓存命中率（慢的那几次 hit 达 99.0~99.2%）。

### 🔧 修复：IP 健康优选（不缩短超时、不硬编码 IP）

1. **新增 `LlmEndpointHealthRegistry`**：按 host 维护「全部 IP / 健康 IP」，
   健康的按**探活耗时升序**排列（最快的排第一）；
2. **新增 `LlmEndpointHealthChecker`**：`@Scheduled` 每 60 秒对每个已跟踪 host 的**所有** IP
   做并发 TCP 探活（单 IP 超时 1.5 秒，独立 `llm-ip-probe-*` 线程池，不占 Reactor 线程）；
3. **新增 `HealthyAddressResolverGroup`**：自定义 Netty resolver，`doResolve` 优先返回健康 IP；
   **无探测记录时原样回退默认解析**（行为与未引入时完全一致）；
4. **`LLMWebClientManager`**：WebClient 创建时登记 `host:port`，并挂载
   `.resolver(new HealthyAddressResolverGroup(...))`；
5. **一键回退**：`llm.resolver.prefer-healthy=false`（重启生效，无需回滚代码）。

### ⚠️ 明确不改的两个参数（这也是重要结论）

| 参数 | 保持原值 | 原因 |
|---|---|---|
| `maxIdleTime` | **20s** | 缩短超时/改回收窗口都只是改变「撞上坏 IP 的频率」，不解决「为什么坏」 |
| `CONNECT_TIMEOUT_MILLIS` | **10000** | **不能调小**：建连最坏需要 15 秒（`1+2+4+8`），调到 3 秒会**打断正在重传的 SYN**，把「偶发慢 11 秒」变成「大概率连不上」 |

> 曾短暂把 `CONNECT_TIMEOUT` 调成 3000ms 并已回退 —— 这一点写进代码注释，防止后人重犯。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.4.6（产物 `code-craft-3.4.6.jar`）
- `frontend/package.json` + `frontend/package-lock.json`
- `frontend-v3/package.json` + `frontend-v3/package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.4.6.jar` 引用）+ `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION` → 3.4.6
- README / README_EN 版本徽章、8 个 docs 文件头、DEV_QUICKREF / RELEASE_CHECKLIST 打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **无数据库结构变更**，可覆盖安装
- 必须**重启后端**才生效（探活任务在启动 8 秒后跑首轮，之后每 60 秒一轮）
- 启动后可在日志中搜索 `[LLM-IP]` 确认效果：
  - `host=api.deepseek.com 探测完成：健康 2/2，首选=xxx` → 两个 IP 都健康
  - `健康 1/2，首选=xxx` → **探测出一个坏 IP 并已排除**（这正是本版的价值）
- 若出现异常，设 `llm.resolver.prefer-healthy=false` 重启即可回退到默认解析

---

## [3.4.5] - 2026-11-24

> ⚠️ **v3.4.4 未发布**：首次 tag 因 electron/package-lock.json 中 jackspeak 依赖被误改（PowerShell 正则重载歧义）导致 CI 打包失败；本版为修复后重发，功能内容与 3.4.4 一致。

> **跨轮首 token 缓存断裂修复**：用户「中途补充需求」的消息只进内存不落库，
> 下一轮重建上下文时它消失，导致其后**所有消息整体前移一位**，从该位置起
> 全部按未命中重新 prefill —— 实测首 token 10.3~10.7 秒，而同轮内后续请求仅
> 226~412ms。本版改为「落库 + 前端不渲染气泡」，并补上 TTFB 分段埋点。
> 必须重启后端；**无数据库结构变更**（沿用已有 `message_type` 列）。

### 🔍 问题：一条不落库的 user 消息毁掉跨轮缓存前缀

`/deepseek/supplement`（生成过程中补充需求）的旧实现是：

- `SupplementStore` 入队 → 工具循环下一轮 `poll` 取出；
- 拼成 `role=user` 的「【用户中途补充需求】…」消息，`messages.add()` **只加进内存**；
- 本轮内位置稳定，缓存正常（`iteration≥1` 命中 93~98.6%）；
- 但下一轮 `buildMessagesFromHistory` 从 DB 重建时**这条不存在**，
  其后所有消息整体前移一位 → 从该 index 起全部失配。

**实测证据**（会话 321，同一会话两次复现）：

| 断裂点 | 命中率 | TTFB |
|---|---|---|
| `index=11` `role: user → assistant` | 74.3%（hit 38272 / miss 13194） | **10321ms** |
| `index=77` `role: user → assistant` | — | **10676ms** |
| 同轮内 `iteration≥1` | 93~98.6% | 226~412ms |

`index` 从 11 涨到 77，是因为中间跑了一整轮 35 次工具迭代 —— 断裂点始终跟着那条补充需求走。

### 🔧 修复

1. 新增 `MessagePersister.TYPE_SUPPLEMENT_INJECT`（`supplement_inject`）
   + `saveSupplementUserMessage(conversationId, content, turnId)`：**先落库、再注入内存**；
2. 落库失败不阻断主链路（补充需求本轮仍生效），仅 WARN 留痕便于排查；
3. `ConversationMessageMapper.selectNthUserMessageIdFromTail` 分页 SQL 同步排除
   `supplement_inject` —— 否则补充需求会被当成「一轮的起点」，导致向上翻页出现空轮；
4. **前端不渲染气泡**（与 `desktop_inject` 同一机制）：
   `frontend/src/api/conversation.ts` 与 `frontend-v3/src/api/conversation.ts`
   的 `processMessageGroups` 在 `role === 'user'` 分支提前 `continue`。
   落库只为「重建时位置固定」，不作为用户发言展示。

### 📊 TTFB 分段埋点

`[Perf]` 日志从单一 TTFB 扩展为分段：

```
[Perf] conversationId=321, iteration=0, LLM首字节到达, TTFB=10352ms
  | 本地准备=45ms[动态块=2 净化序列化=18 指纹=12 兜底=13]
  | 网络+服务端=10307ms  ⚠️ 网络段异常(...)
```

5 个打点：动态块重排后 / 净化+序列化后 / 前缀指纹后 / reasoning 兜底后 /
`doOnSubscribe`（真正开始发送）。网络段 ≥5000ms 自动加 ⚠️ 标记，
用于区分「卡在建连」与「卡在服务端」，避免下次再靠时间间隔反推。

### ⚠️ 一处已定位但本版未调整的问题

10.3 秒 TTFB 还有**第二个独立成因**，与缓存无关：连接池 `maxIdleTime=20s`
回收连接后需重建，而 `CONNECT_TIMEOUT_MILLIS=10000`。已实测排除
DNS(9~563ms)、TCP(10~90ms)、IPv6(无 AAAA)、hosts、系统代理、
限流(5s 且超时会抛异常)、请求体体积(673K 字符仍 308ms)。
等分段埋点数据确认「建连 vs 服务端」后再调参，本版只观测、不改行为。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.4.5（产物 `code-craft-3.4.5.jar`）
- `frontend/package.json` + `frontend/package-lock.json`
- `frontend-v3/package.json` + `frontend-v3/package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.4.5.jar` 引用）+ `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION` → 3.4.5
- README / README_EN 版本徽章、8 个 docs 文件头、DEV_QUICKREF / RELEASE_CHECKLIST 打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **无数据库结构变更**（沿用 3.4.3 已有的 `message_type` 列）
- 必须**重启后端**才生效；前端需重新构建（两套前端均有改动）
- 可覆盖安装；修复后「补充需求那一轮的下一枪」不再全量重算

---

## [3.4.3] - 2026-11-24

> **踩坑提示重复注入修复**：上一版把提示挪到列表末尾，解决了「前缀断裂」；但缓存只写不删，
> 同一条提示会在**后续每一轮**都被重新拼进请求，模型被迫每轮表态
> 「该经验与本次任务无关，跳过」，形成明显复读噪声。本版改为
> 「一次性消费 + TTL 兜底」，并弱化提示措辞。必须重启后端；无数据库结构变更。

### 🔍 问题：一条过时提示污染后续所有轮次

v3.4.2 引入 `sessionLessonHintCache`（conversationId → 提示正文）后：

- `injectLessonHint` 只做 `put`，**全流程没有任何 `remove`**；
- `placeDynamicBlocksAtTail` 每次请求都 `get()` 并拼到消息末尾。

于是某轮某工具失败命中经验 X 后，这条提示永久驻留，之后**每一轮请求**都携带它。
而提示原文是「请判断是否适用当前场景后参考执行」——对模型属于强制指令，
一旦该经验与当前任务无关，模型就会专门输出一段
「该经验描述的是 X 问题，与本次任务无关，跳过」，纯噪声且逐轮重复。

### 🔧 修复

1. 新增 `sessionLessonHintAt`（conversationId → 写入时间）+ `LESSON_HINT_TTL_MS`（10 分钟）；
2. 新增 `takeLessonHintOnce(conversationId)`：**取出即删除**，超时条目直接丢弃；
3. 请求出口改用 `takeLessonHintOnce`，提示只在命中它的那一轮携带；
4. 措辞弱化为「历史经验参考……无关则直接忽略，不要在回复中说明是否采用」，
   去掉「请判断是否适用」这类强制表态要求，同时保留 `lesson action=feedback` 引导。

### 📊 不破坏前缀缓存

动态块位置不变（仍由 `placeDynamicBlocksAtTail` 追加到列表**绝对末尾**），
本次只改变「是否携带」与「措辞」，不触碰历史消息顺序，
不新增前缀断点；`ContextFingerprint` 继续按 `[动态运行时注入]` 标记跳过。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.4.3（产物 `code-craft-3.4.3.jar`）
- `frontend/package.json` + `frontend/package-lock.json`
- `frontend-v3/package.json` + `frontend-v3/package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.4.3.jar` 引用）+ `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION` → 3.4.3
- README / README_EN 版本徽章、8 个 docs 文件头、DEV_QUICKREF / RELEASE_CHECKLIST 打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- 仅后端注入逻辑改动，**无数据库结构变更**、无 API 行为变化
- 必须**重启后端**才生效（运行中的实例仍持有旧缓存与旧提示文案）
- 可覆盖安装；重启后首轮即不再出现无关经验提示

---

## [3.4.2] - 2026-11-24

> **踩坑提示注入修复版**：工具失败后注入的经验提示，从「插到消息列表中前部」改为
> 「每次请求前重排到列表绝对末尾」。这是第 13 个、也是最后一个已知的前缀断裂源。
> 必须重启后端；无数据库结构变更；无 API 行为变化；可覆盖安装。
> ⚠️ 升级后第一轮会断一次（提示位置变了），之后稳定。

### 🔍 问题：一次工具失败的代价是十几万字符重算

`injectLessonHint` 在工具执行失败后会检索经验库并注入一条 system 提示。旧实现：

```java
int insertIdx = 0;
for (int i = 0; i < messages.size(); i++) {
    if (!"system".equals(messages.get(i).get("role"))) { insertIdx = i; break; }
    insertIdx = i + 1;
}
messages.add(insertIdx, hintMsg);   // 插在「system 之后、第一条非 system 之前」
```

三个致命点：

1. **是插入不是追加** —— 把后面所有消息（含当前 user 的 11772 字符）整体挤后 1 位；
2. **不落库** —— 下一轮重建时这条 system 消失，位置又缩回去；
3. 于是「插入那一轮」与「下一轮」在插入点完全错位。

实测（会话 550，v3.4.1）：

```
12:57:17.856  踩坑经验被动注入: tool=file_explorer, lessonId=1731, type=SOLUTION
12:57:18.256  [缓存告警] 系统提示词前缀变更 5bdca545 → 1500fafb
12:57:18.258  [缓存告警] 消息前缀断裂 index=2  role: user → system，长度 11772 → 362（差 -11410）
```

一次读文件失败，就让 index=2 之后的全部历史重新 prefill。

### 🔧 修复：并入「动态块」机制

- 新增会话级 `sessionLessonHintCache`（conversationId → 提示正文），注入时**只写缓存**，不再自己挑位置；
- `placeDynamicBlocksAtTail` 增加第三个动态块：执行模式 → 知识库摘要 → 踩坑提示，
  **顺序固定、位置永远是列表最后一条**；
- 复用 `[动态运行时注入]` 标记，`ContextFingerprint` 自动跳过，不会误报；
- 跨轮时从缓存取回同一份文本，位置恒定 → 前缀不再被切。

### 📊 至此所有「不落库的运行时注入」已收敛为两条规则

| 类型 | 位置 | 成员 |
|---|---|---|
| 静态块 | 拼第一条 system / add(0) 到头部 | compact 说明、技能目录、弯路预警、语言指令 |
| 动态块 | 每次请求前重排到列表绝对末尾 | 执行模式、知识库摘要、踩坑提示 |

### 🔇 顺带修复

`ContextFingerprint.checkMessages` 的 Javadoc 在 v3.4.1 被新增常量挤到了常量头上，
导致方法本身失去文档注释。本次已把注释归位（纯注释调整，无行为变化）。

### ⬆️ 升级说明

- 必须重启后端；无数据库结构变更；无 API 行为变化；可覆盖安装。
- **⚠️ 升级后第一轮会断一次**：踩坑提示从「列表中前部」移到末尾，位置改变。
- 之后若同一会话再次触发新的踩坑提示，只影响末尾几百字符。

## [3.4.1] - 2026-11-24

> **注入链路修复版**：把「执行模式指令 / compact 模式说明 / 项目知识库摘要」三项
> 真正接入实际发请求的路径（此前它们只拼在一条从不发送的消息列表上）。
> 同时按「静态 / 动态」分流注入位置：静态块拼第一条 system，动态块每次请求前放到列表末尾。
> 必须重启后端；无数据库结构变更；无 API 行为变化；可覆盖安装。
> ⚠️ 升级后第一轮会断一次（system 头新增模式与知识库段），之后稳定。

### 🔍 问题：三项注入从未发送给 LLM

`handleChat` 构建阶段（1000~1187 行）会往 `historyMessages` 里拼：
执行模式指令、compact 模式说明、项目知识库摘要（`injectBootstrap`）。

但该列表**没有进入 `apiRequest`** —— 真正发请求的是 `executeSemiStreamingToolCycle`，
它内部从 DB 重新构建 messages 并自行注入。于是这三项实际效果是：

- 执行模式：模型不知道当前是手动还是自动；
- compact 说明：模型不知道历史工具结果已被摘要，也不知道可用 `query_tool_history` 查详情；
- 项目知识库：模型看不到 `.codecraft/INDEX.md` 的模块索引。

（此前 `historyMessages` 仅用于 token 估算。）

### 🔧 修复：统一在请求路径注入，并按静态/动态分流位置

**静态块（位置绝对固定，拼第一条 system；顺序恒定）**

1. compact 模式说明（含 conversationId，会话内恒定）
2. 技能目录（v3.3.10 已静态化）
3. 弯路预警（会话级缓存 + 落盘，2026-11-17 已加固）
4. 语言指令（常量，`add(0)` 到头部）

**动态块（每次请求前重排到消息列表绝对末尾）**

1. 执行模式指令（手动/自动可切换）
2. 项目知识库摘要（`INDEX.md` 会被 `maintainAfterTask` 异步维护）

动态块的处理方式：

- 新增 `placeDynamicBlocksAtTail()`，在请求出口（每次迭代）先**按标记移除旧块**，再按固定顺序
  **追加到列表末尾**；
- 为什么不能插在「最后一条 user 之前」：动态块不落库，跨轮重建后历史里没有它，插在中间会让
  两轮位置完全错开，断裂点落到上一轮中段（弯路预警 2026-11-17 实测：其后 14 次工具结果
  ≈14 万字符作废，TTFB 14~25 秒）；
- 只有「绝对末尾」在跨轮与轮内两种场景下位置都稳定：变化时只影响「动态块 + 本轮新 user」；
- 代价：每次请求多 prefill 这一小段（模式约 100 字符、知识库摘要通常 1~3K），
  远低于「偶发一次全断」。

**知识库额外加会话级快照**：`resolveKbSectionSnapshot()` 按会话缓存首次取到的摘要文本，
因为 `INDEX.md` 会在任务结束后异步更新 —— 内容一变就会让 system 头变化。快照保证同一会话内
逐字节恒定，知识库更新对新会话自然生效。

### 🔇 监控适配

`ContextFingerprint.checkMessages` 增加对 `[动态运行时注入]` 块的跳过：
该块固定在末尾、位置每轮后移，计入会让监控每轮必报，噪音淹没真正的断裂点。

### ⬆️ 升级说明

- 必须重启后端（Java 改动）。
- 无数据库结构变更，无需迁移。
- 无 API 行为变化，前端无需改动。
- **⚠️ 升级后第一轮会断一次**：system 头新增「模式 + 知识库」段，前缀整体改变；之后稳定。
- 知识库更新策略：同一会话内使用首次快照；新会话自动取最新版本。

## [3.3.10] - 2026-11-24

> **技能目录静态化版**：把技能目录里会变化的统计数字与排序键去掉，让这段拼在 system 头部的
> 文本**逐字节恒定**，system 前缀不再因 `skill report` 而失效。
> 必须重启后端；无数据库结构变更；可覆盖安装。
> ⚠️ 升级后第一轮仍会断一次（目录格式变了），之后只要技能不增删就长期稳定。

### 🔍 问题：技能目录是 system 头部唯一的动态源

`SkillCatalogBuilder` 渲染的目录行原本形如：

```
- 【技能名】(ID:15 | 置信度 96% | 用 22 次) 触发: ... | ...
```

且列表**按置信度降序**排列。而这份目录拼接在**第一条 system 末尾**，位于上下文缓存前缀的最前端：

- 只要本轮调用过一次 `skill action=report`，下一轮置信度与使用次数就会变；
- 置信度变化还可能让两个技能的**排序互换**，整段目录文本跟着重排。

旧版本日志佐证：2026-11-17 会话 389 连续出现 8 次
`[缓存告警] 系统提示词前缀变更`，全部落在 `轮次=0` 的跨轮第一枪。

### 🔧 修复：只渲染稳定字段 + 确定性排序

- 目录行不再输出「置信度 xx%」与「用 n 次」，只保留 名称 / ID / 触发词 / 描述。
- 列表排序从「置信度降序」改为 **id 升序** —— 即使数字去掉，排序若仍依赖置信度，
  一次 report 让两个技能换位，整段文本照样变。
- 预算截断提示从「按置信度列出前 N 个」改为「列出前 N 个」，相关注释同步更新。
- **未改动**：淘汰机制（置信度低于 10% 不进目录）、3500 字符预算、
  `skill action=list` 全量查询能力，全部保持原样。

### 📉 代价与收益

- 代价：LLM 不再看到「这个技能用过 22 次、置信度 96%」。统计信息对选技能的边际价值，
  远小于它带来的缓存代价（一次全断等于十几万 token 按未命中计价）。
- 收益：技能集合不变时 system 头部逐字节恒定，跨轮第一枪可长期命中。

### ⬆️ 升级说明

- 必须重启后端（Java 改动，无配置格式变化）。
- 无数据库结构变更，历史数据无需迁移。
- 无 API 行为变化；技能管理页面的置信度展示不受影响（页面读的是 DB 原值）。
- **⚠️ 升级后第一轮会断一次**：目录格式从含统计变为纯清单，system 头整体改变；之后稳定。
- 保留监控：`[缓存告警] 系统提示词前缀变更` 继续用于捕捉未来任何新的头部动态源。

## [3.3.9] - 2026-11-24

> **跨轮首枪缓存断裂修复版**：修掉「同一条 TOOL 消息在内存态与重建态存在两种形态」导致的
> 前缀失效。实测证据（会话 550，安装版 v3.3.8）：跨轮第一条请求 585017 字符，
> 逐消息指纹报 `index=190 role: tool → tool，长度 210 → 336（差 126）`，
> 与 `formatToolResult` 的包装长度「113 + 工具名长度」完全吻合（`file_explorer` = 13 → 126）。
> 修复后重建历史下发的就是当轮真正发给模型的原始工具结果，跨轮前缀不再因展示包装而翻转。
> ⚠️ **上线后所有现存会话的前缀会再断一次**（发送内容变了），属一次性代价；
> 必须重启后端；无数据库结构变更；可覆盖安装。

### 🔍 根因：同一条 TOOL 消息存在两种形态

- **当轮发给模型的**：`ToolExecutor#buildToolMessages` 写入 `content`，内容是
  `ToolResultBudget` 裁剪后的**原始工具结果**，不含任何包装。
- **落库的**：`DeepSeekServiceImpl#appendToolResultMessages` 调用
  `saveToolMessage(formatToolResult(toolContent, toolName))`，而
  `MessagePersister#saveToolMessage` 把它写进 **reasoning 列**（content 恒为 null）。
  `formatToolResult` 会加上 `\n\n` + 48 个 `-` + `工具调用:` + 48 个 `-` + `\n`
  + `> **工具名**\n` 的头部，以及末尾两个换行。
- **重建时取的**：`ContextBuilder` 的 TOOL 分支 `msg.getReasoning() != null ? ...`
  —— 取到的正是**包装版**。
- **后果**：同一轮内（iteration≥1）消息在内存里自增、从不过 DB，所以命中率 95%+；
  一旦跨轮重建，从「上一轮新增的第一条 TOOL 消息」开始字节不同，
  该位置之后全部按未命中重新 prefill。

### 🔧 修复：重建时剥离展示包装（DB 与前端零改动）

- 新增常量 `TOOL_RESULT_WRAPPER_PREFIX`，与 `ToolLoopManager#formatToolResult`
  的固定头逐字节一致。
- TOOL 分支重建时调用 `stripToolResultWrapper(content)`：跳过固定头与
  `> **工具名**` 整行，末尾等量剥回两个换行。
- **可逆性**：包装是「固定头 + 原始内容 + 固定两个换行」的纯拼接，
  因此剥离可逐字节还原（空结果场景剥完为 `""`）；不以包装头开头的消息
  （旧数据 / 非本路径写入）原样返回，绝不误伤。
- **取舍**：没有采用「落库改存原始内容」方案 —— 那需要改表结构、迁移历史数据、
  调整前端展示，风险与成本都远高于「只在重建侧剥离一次字符串」。
  DB 中的包装文本继续保留，前端工具卡片照常显示虚线框与工具名。

### 📊 实测数据（会话 550）

- 跨轮首枪请求体：494041 → 585017 字符（上一轮新增约 9 万字符）
- 断裂点：`index=190`（从 0 计），即上一轮新增的第一条 TOOL 消息
  （DB `id=91423`，`created_at=11:36:02`）
- 该条：内存态长度 210（原始 content 162 字符）→ 重建态长度 336（reasoning 288 字符），
  差 **126** = `2 + 101 + 1 + (4 + 13 + 3) + 2`
- 断裂点之前的 190 条消息完全一致（全部来自更早轮次的 DB 重建）

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.3.9
- `frontend/package.json` + `frontend-v3/package.json` + 两个 `package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.3.9.jar`）+ `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION` → 3.3.9
- README / README_EN 徽章、8 个 docs 文件头（版本 → 3.3.9，日期保持 2026-11-24）
- DEV_QUICKREF / RELEASE_CHECKLIST 打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **必须重启后端**（Java 改动，无配置文件格式变化）。
- **无数据库结构变更**：不新增表、不新增列、不改任何持久化字段；历史数据无需迁移。
- **无 API 行为变化**：响应体不变，前端无需改动。
- **可覆盖安装**：Electron 安装包直接覆盖即可。
- **⚠️ 首次启动后的第一轮请求仍会未命中一次**：历史 TOOL 消息的下发内容从
  「包装版」变为「原始版」，前缀整体改变；从第二轮起恢复稳定命中。
- **保留监控**：`[缓存告警] 消息前缀断裂` 继续保留，用于监测未来任何新的前缀翻转来源。

## [3.3.8] - 2026-11-24

> **缓存前缀断裂可定位版本**：本版**不改任何发送内容**，只补观测能力。
> 背景现象：同一轮工具循环内 `iteration≥1` 的缓存命中率稳定在 95%+，
> 但**每轮第一条请求**只剩 16.5%（实测 186379 prompt_tokens 仅命中 30720，
> 155659 全额按未命中 prefill，首 token 18 秒）。
> 排查确认头部（system / 工具定义）都没有变化，而现有监控对
> 「历史消息在重建时被改写」这一类断裂完全无感 —— 本版把逐条消息的前缀指纹补上，
> 让日志直接点名「第几条、什么 role、长度差多少」。
> 本版**必须重启后端**；无数据库结构变更；可覆盖安装。

### 🔍 新增：逐消息前缀指纹（直接定位「哪条历史被改写」）

- **旧监控的两个盲区**：`extractSystemPromptForFingerprint` 只取**第一条** system 就
  `return`；`tools` 走独立指纹；而**历史消息完全没有参与任何指纹**。
  于是出现「system 没变、tools 没变、日志一条告警都没有，缓存却从历史前段整段断掉」——
  旧监控对此类断裂是瞎的。
- **做法**：每次请求前，把每条消息的 `role + content + tool_call_id + tool_calls +
  reasoning_content` 压成 `(role, 长度, 哈希)` 三元组，存进会话级列表；
  下次请求逐条比对：前缀全一致视为正常增长（不打日志），
  出现第一条不一致就 WARN 打印 `index / role / 长度差 / hash`。
- **为什么按「上一次请求」而不是「轮次边界」比对**：工具循环里每次迭代都会经过这一处，
  相邻两次天然是「前缀 + 追加」关系；一旦跨轮重建改写了历史，
  第一个撞上的就是那条被改写的消息 —— 不需要额外维护轮次边界，也不会漏掉轮内断裂。
- **告警长这样**（长度差是判据，可直接反推改写来源）：
  `[缓存告警] 消息前缀断裂 —— 会话=549 轮次=0 首条不一致 index=112（从 0 计）
  role: tool → tool，长度: 16126 → 16251（差 +125）`。
- **开销与边界**：只读观测、异常全部吞掉，绝不影响主流程；
  单会话最多追踪 4000 条、最多 800 个会话，超限整体清理，避免长跑进程内存膨胀。
  300 条消息的一次比对约 1~2ms，相对单次请求耗时可忽略。

### 🔧 修复：system 段指纹只取第一条 → 扩展为「开头连续的所有 system」

- **旧行为**：`extractSystemPromptForFingerprint` 遍历到第一个 `role=system` 就返回。
  核对注入链路后发现：技能目录、执行模式指令、compact 指令**都是拼在第一条 system 上**的
  （`prepareConversationContext` 里遍历到第一条 system 后 `break`），
  而项目知识库摘要与语言强制指令是**追加到消息列表末尾**的。
- **新行为**：拼接「开头连续的所有 system 消息」作为指纹输入，遇到第一条非 system 即停止。
  这样既覆盖真正的头部内容，又不会被每轮追加在尾部的语言指令 / 知识库摘要制造假告警。
- **取舍**：没有采用「所有 system 全部纳入」的方案 —— 那样每轮都会因尾部注入告警一次，
  真正有价值的头部变更会被噪音淹掉。

### 📌 说明：本版不含行为变更

- 新增的全部是观测能力：不修改消息内容、不修改注入顺序、不影响请求体字节。
- 排查过程中同时确认了两个**非元凶**，记录在此避免后续重复走弯路：
  ① 工作目录注入（知识库摘要追加在列表末尾，只会让末尾小段未命中，毫秒级）；
  ② 上下文压缩（实测会话 549 的 `conversation_compaction` 记录为 0 条，
  请求前守卫未触发压缩，不存在摘要漂移）。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.3.8
- `frontend/package.json` + `frontend-v3/package.json` + 两个 `package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.3.8.jar`）+ `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION` → 3.3.8
- README / README_EN 徽章、8 个 docs 文件头（版本 + 更新日期 2026-11-24）
- DEV_QUICKREF / RELEASE_CHECKLIST 打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **必须重启后端**（Java 改动，无配置文件格式变化）。
- **无数据库结构变更**：不新增表、不新增列、不改任何持久化字段。
- **无 API 行为变化**：请求体与响应体字节级不变，前端无需改动。
- **可覆盖安装**：Electron 安装包直接覆盖即可。
- **日志变化**：跨轮首条请求若存在历史改写，会新增一条 `[缓存告警] 消息前缀断裂` WARN；
  正常会话只在消息数缩减（压缩/裁剪）时打 DEBUG，默认日志级别下无感。

## [3.3.7] - 2026-11-23

> **上下文压缩守卫收敛版本**：修掉「压缩永远压不动、但每次请求都重试」与
> 「压缩器认为没超、真正请求却超限」两个问题。
> 根因有三条：① `loadState` 会把旧版区间式记录全部拼接（实测某会话 **236 条 active、
> 摘要合计 86.7 万字符**），这段文本被整段拼进压缩 prompt，45 秒内 prefill 必超时
> → 失败即零变更 → 水位线不动 → 下次继续触发，形成死循环；② 压缩判断分散在三个位置、
> 三套口径（是否含 tools / 是否含动态注入各不相同）；③ 上下文重建每次请求都**全量读库**
> （实测 31426 条 / 4412 万字符），压缩只减小了发给模型的内容，没减少 DB 读取量。
> 本版**必须重启后端**；无数据库结构变更；可覆盖安装。

### 🔧 修复：压缩「永远压不动但每次都重试」的死循环

- **根因**：`CompactionService.loadState` 为兼容旧版「区间式」记录，把同一会话所有
  `superseded=0` 的摘要拼接且**没有体积上限**。实测某会话 236 条 active 记录拼出
  **86.7 万字符**摘要，每次压缩都把它整段拼进 prompt（再叠加单批 15 万字符），
  45 秒超时必然失败；失败不写记录、水位线不推进 → 下次请求继续触发 → 白等几十秒。
  巨型摘要本身也把上下文顶在阈值附近，形成「越压越大」的观感。
- **旧摘要分批归并**：`doCompact` 增加体积预检——摘要超过 3 万字符时，先按
  15 万字符/批把它自己归并成一份精简摘要，再压历史；归并结果随本次一起落库，
  旧记录被 `markSupersededExcept` 废弃。
- **摘要硬上限兜底**：每批产出都过一遍 `enforceSummaryLimit`（3 万字符，截断保留
  较晚事实、对齐换行）。这一步是必需的：归并本身**不推进水位线**，若只做「超了就归并」，
  按「水位线之后新增字符」计算的冷却永远不生效，会退化成「每次请求都归并一次」。
- **失败熔断**：同一会话连续 3 次压缩失败后熔断 10 分钟（视为「刚压缩过」），
  不再每次请求都白等一次 LLM 超时。
- **同会话压缩互斥**：同步压缩与异步预压缩共用会话级锁，避免两条请求从同一水位线
  出发、重复调用 LLM 并互相 superdede。

### 🔧 修复：三套压缩口径不一致（压缩器认为没超、真实请求已超限）

- **旧实现的三套口径**：异步预压缩（不含 tools、不含动态注入）／`ContextBuilder`
  构建阶段（不含 tools、不含注入，且发生在所有注入之前）／工具循环守卫（含 tools + 注入）。
  本项目 tools schema 恒占约 6 万 token、技能目录/知识库/附件注入另有数万字符，
  前两处系统性低估 —— 于是出现「压缩器认为还能跑、守卫判定已超限」，反之估算偏高时
  又会「刚压完又被判超限、反复改前缀」。
- **统一为「请求前守卫」**：在 `prepareConversationContext` 末尾、所有注入完成、
  `tools` 构建之后，用 `estimateTotalRaw(messages, tools)` + provider 校准比率判断；
  `ContextBuilder` 从此不再自行压缩（只保留 `compaction=false` 时的裁剪兜底）。
- **异步预压缩补齐口径**：新增 `toolsRawTokens` 与**会话实际 contextMode** 两个入参。
  此前用全局 `isStripHistoryReasoning()` 估算，而真实请求按「请求参数 > sys_config >
  全局」解析出的 mode 构建 —— 两者不一致时会高估或低估整段 reasoning。
- **compact 模式专用**：预压缩估算只把「最后一条 user 之前」的工具结果按摘要长度计
  （与 `ContextBuilder#compactHistoryMessages` 同一边界），本轮对话的工具结果仍按原文计。

### ✨ 新增：压缩过程可见 + SSE 心跳保活

- **先推事件再压缩**：`prepare` 阶段只判断「是否需要整理」，真正压缩移到 Flux 首部 ——
  先推「🧹 上下文接近上限，正在整理历史上下文…」，再在 `boundedElastic` 线程执行压缩。
  此前压缩阻塞在 SSE 建立之前，用户点发送后只能长时间干等，体感是卡死。
- **每 15 秒心跳**：长压缩期间推「⏳ 仍在整理上下文…（已用 N 秒）」，防止被反向代理的
  60 秒读超时判定为空闲连接而断开；压缩结束（正常/异常/取消）心跳立即停止，不泄漏定时器。
- **压缩后再次净化消息序列**：压缩可能切断 `assistant(tool_calls)` 与 `tool` 的配对，
  发送前统一走 `ToolCallSequenceSanitizer.sanitize`（幂等）。

### ⚡ 性能：上下文重建改为按水位线增量读取

- **根因**：`buildMessagesFromHistory` 每次请求都 `selectByConversationId` 全量加载整个
  会话（实测某会话 31426 条消息 / 4412 万字符），再在内存里把 id ≤ 水位线的部分丢弃 ——
  压缩只减少了发给模型的内容，DB 读取量完全没变，长会话每个请求都白读几十 MB。
- **新增 `selectForContext`**：SQL 层过滤 `role='SYSTEM' OR id > 水位线`（SYSTEM 永不参与
  压缩），命中 `idx_conversation_id` + 主键；`ContextBuilder` 与 `asyncPrecompress` 都改走
  增量查询，摘要再插回「第一条非 SYSTEM 之前」——最终序列与全量路径完全一致：
  `system + 摘要 + 水位线之后的对话`。
- **构建阶段不再重复分词**：开启压缩时只保留请求前的一次估算（此前同一请求会分词两遍，
  几十万 token 的 CL100K 分词是百毫秒级开销）。

### 🔧 修复：最小收益闸门（不再「压 2 条消息就改一次前缀」）

- **现象**：压缩上界是「倒数第 2 条 user」，它会随用户每次新提问前移。没有收益判断时，
  只要上下文仍在阈值附近，用户每发一句就会「压掉刚空出来的一小段」——实测出现过一条
  记录 `start=81030, end=81031` 只覆盖 **2 条消息**却生成 1308 字摘要，
  改前缀的成本远大于省下的 token，模型侧表现为「上下文被打乱、反复失忆」。
- **动态阈值**：`max(3 万字符, 生效上限 × 5% ÷ 0.425)` —— 1M 窗口约 11.8 万字符，
  128K 中转站降到 3 万字符，避免小窗口套用大窗口的绝对阈值导致「攒不够就一直不压」。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.3.7
- `frontend/package.json` + `frontend-v3/package.json` + 两个 `package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.3.7.jar`）+ `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION` → 3.3.7
- README / README_EN 徽章、8 个 docs 文件头（版本 + 更新日期 2026-11-23）
- DEV_QUICKREF / RELEASE_CHECKLIST 打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **必须重启后端**（Java 改动，无配置文件格式变化）。
- **无数据库结构变更**：`conversation_compaction` 表字段不变；旧的多条 active 记录会在
  首次压缩时自动分批归并成一条（该次压缩可能多花几十秒，属一次性自愈成本）。
- **可覆盖安装**：Electron 安装包直接覆盖即可。
- **API 行为变化**：压缩从「SSE 建立前」移到「首 token 之前」，前端会先收到一条
  `reasoning` 事件（整理提示）与若干心跳事件；不影响正文与工具事件顺序。

---

## [3.3.6] - 2026-11-19

> **上下文压缩重构版本（写法B）**：修掉「长上下文下模型反复输出『好。执行。』」与
> 「压缩后模型忘记本轮任务、继续做历史任务」两个问题。
> 根因是**压缩一直在失败**：旧实现把整个可压区间一次性塞进一个 LLM 请求，
> 上下文到 60 万 token 时待压历史已有几十万 token，prefill 就要几十秒，
> 20 秒超时必然失败 —— 实测 11-19 某会话 122 次请求**一次都没压成功**，
> 上下文从 54 万一路涨到 92 万，模型在超长上下文里开始 token 级重复
> （一条 1288 字符的思考里出现 77 个「好。」）。
> 本版**必须重启后端**；无数据库结构变更；压缩记录格式向后兼容（旧多条记录会自动拼接）。

### 🔧 修复：上下文压缩「压不动」导致模型退化循环

- **根因**：`CompactionService.compact` 把「水位线 → 保护带」整个区间一次性拼成一个
  压缩 prompt。11-19 实测该区间达 174 万字符，压缩请求本身就有 40~50 万 token，
  在 20 秒超时窗口内 prefill 根本跑不完 → 每次都超时 → 异常被 catch 后返回 -1
  → 不写记录 → 下一轮又重试 → **永远失败**，上下文只能一路涨。
- **改为分批压缩（写法B）**：内部按 15 万字符/批切分（同时受 `batch-size` 轮数约束），
  逐批生成摘要并**累进**（旧摘要作为下一批的输入）。
  关键约束：**全部批次成功后才一次性落库** —— 对模型来说前缀只变一次，
  因此只产生一次缓存未命中（而不是每批一次）。
  任何一批失败则整体放弃，数据库零变更，下次重试。
- **累进不丢历史**：旧实现 `toCompress` 排除旧摘要（负 id），但旧记录又被标记
  `superseded` 废弃 —— 40 天历史被折成 2500 字符摘要后，旧摘要承载的信息全部蒸发
  （实测某会话一次压缩声称覆盖 17055 条消息，实际只读到约 25 条）。
  新实现把旧摘要一起压进新摘要，只增不减。
- **边界全部改用 message id**：旧实现先用 API 消息列表的下标算 `compressStart/compressEnd`，
  再拿这两个下标去遍历 DB 消息列表。两个列表条数天生不等长（TOOL 消息会被跳过、
  sanitizer 会丢消息），因此区间必然错位，而且**静默写错、不报错**。
  新实现只用「`id > 水位线` 且 `id < 倒数第 N 条 user`」定位，不存在下标对齐问题。
- **修正 off-by-one**：`selectNthUserMessageIdFromTail` 的 `OFFSET 0` 是「最新一条 user」，
  而区间用 `id <`（不含上界），直接传 `protectRounds` 会多保护一轮。改为 `protectRounds-1`。
- **系统提示词不再参与压缩**：压缩时显式排除 SYSTEM 消息，重建时也原样保留 ——
  身份 / 工具规范 / 工作目录永远不会进摘要。

### 🔧 修复：压缩摘要被当成「当前指令」执行

- **摘要角色从 `system` 改为 `user`**：旧实现把摘要作为 SYSTEM 消息注入，
  `hoistSystemMessages` 又把它提到系统提示词正下方 —— 模型把它当系统级要求执行，
  表现就是「忘了本轮问题，继续做历史任务」。
- **加降权声明**：摘要正文前固定注入「历史对话摘要 · 仅供背景参考 / 与当前这轮任务无关 /
  其中的计划待办都是过去时，一律不要执行 / 与最新提问冲突时以最新提问为准」。
- **压缩 prompt 去掉现在时措辞**：旧 prompt 要求输出「## 进行中」「## 待办事项」，
  等于把历史状态写成当前指令。新 prompt 只允许客观事实记录（过去时、禁祈使句、
  禁计划建议），保留文件路径 / IP / 命令 / 配置值 / 报错 / 结论等关键事实。

### 🔧 修复：压缩冷却可能把上下文拖到 400

- **冷却条件从「轮数」改为「字符量」**：一轮工具循环可能只有几条消息，也可能上百条。
  按轮数冷却会让大轮次会话在冷却期内暴涨到窗口上限；改为
  「水位线之后累计新增 40 万字符（约 17 万 token）」才允许下一次压缩。
- **冷却只约束异步预压缩**：同步 / 紧急路径（上下文已越过压缩阈值）必须无视冷却 ——
  否则刚压完进入冷却期，上下文又涨到 85% 时会被跳过，直接冲 100% 报错。
- **把 `batch-size` 真正用起来**：该配置项此前只定义未使用（全项目零引用），
  现在作为「单批最多合并几轮」的上限生效。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.3.6
- `frontend/package.json` + `frontend-v3/package.json` + 两个 `package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.3.6.jar`）+ `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION` → 3.3.6
- README / README_EN 徽章、8 个 docs 文件头（版本 + 更新日期 2026-11-19）
- DEV_QUICKREF / RELEASE_CHECKLIST 打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **必须重启后端**（Java 改动 + `application.yml` 配置调整）。
- **无数据库结构变更**：`conversation_compaction` 表字段不变；
  旧的多条 active 记录会被自动拼接成一份摘要读取（不丢内容），
  新压缩产出一条记录并废弃其余记录。
- **可覆盖安装**：Electron 安装包直接覆盖即可。
- **历史会话提示**：旧逻辑压坏的摘要（覆盖范围很大但内容很少）不会自动修复，
  新逻辑只保证从今往后的压缩不再丢内容。

---

## [3.3.5] - 2026-11-18

> **计费精度版本（DeepSeek 官网峰谷计价）**：修掉「成本看板估算金额比实际账单少很多」的问题。
> 根因两条：① 单价表停在旧价（`deepseek-flash` 输出价只有官网空闲价的 50%，
> `deepseek-v4-pro` 三项全部大幅偏低）；② 项目完全没有「峰谷时段」概念 ——
> DeepSeek 官网按**北京时间工作日 09:00–12:00、14:00–18:00（高峰）**与其余时段（空闲）
> 两套单价计费，空闲价仅为高峰价的 50%。
> 本版**必须重启后端**；新增 3 个数据库列（启动自动迁移，可覆盖安装）；
> **只影响 DeepSeek 官网**，其他 Provider（含火山方舟）行为完全不变。

### ✨ 新增：DeepSeek 官网峰谷计价（空闲价 / 高峰价双价）

- **价格表按官网现价修正**（`application.yml` → `llm.pricing.models`，2026-11-18 抓取）：

  | 模型 | 计费项 | 旧配置 | 空闲价（新） | 高峰价（新） |
  |---|---:|---:|---:|---:|
  | deepseek-flash | 命中 / 未命中 / 输出 | 0.02 / 1.0 / **2.0** | 0.02 / 1.0 / **4.0** | 0.04 / 2.0 / 8.0 |
  | deepseek-v4-pro | 命中 / 未命中 / 输出 | **0.025 / 3.0 / 6.0** | **0.15 / 4.5 / 13.5** | 0.30 / 9.0 / 27.0 |
  | deepseek-v4-flash（旧模型名兜底） | 命中 / 未命中 / 输出 | — | 0.02 / 1.0 / 4.0 | 0.04 / 2.0 / 8.0 |

- **时段判定**（`PeakPeriodPolicy`）：北京时间（`Asia/Shanghai`，**强制显式时区，不依赖 JVM 默认**），
  周一至周五 09:00–12:00、14:00–18:00 为高峰（**左闭右开**：12:00 整算空闲），
  周末与法定节假日全天空闲。
- **节假日自动判断**（`HolidayCalendar`）：三层数据源
  ① 内置表 `resources/holidays/cn-holidays.json`（2026 年官方安排，离线永久兜底）；
  ② 本地缓存 `data/holiday-cache/cn-<year>.json`；
  ③ 远端 `holiday-cn`（启动预热 + 每日 03:00 异步刷新，**计费主链路绝不联网**）。
  另支持 `extra-holidays` / `extra-workdays` 手工覆盖；调休上班日口径由平台档案
  `honorMakeupWorkdays` 声明，默认**严格按官网字面**（周末一律空闲）。
- **计价快照落库**：`llm_token_usage` 新增 `pricing_period`（peak / off-peak）、
  `pricing_version`（口径版本）、`unit_price_snapshot`（当时生效单价，审计用）；
  **历史数据一律不回算**（改历史等于篡改当时的账），靠 `pricing_version` 区分两批记录。
- **费用结构按 (模型, 时段) 分组拆分**：`TokenUsageService.enrichSummary` 改为按
  `(model, pricing_period)` 取真实 token 量、各用该时段单价算三段后累加，
  避免「单一参照价」把高峰与空闲混算导致结构失真。
- **平台档案化（可扩展，不散落）**：峰谷规则挂在 `ProviderProfile.PeakPricingSpec`，
  与 `pricingMode` / `usageProtocol` 同源注册；计价链路统一入口
  `TokenUsageParser.resolveProviderContext(provider)` → `PeakPeriodPolicy.isPeak()` →
  `TokenPricingConfig.resolveFor(model, isPeak)`。
  **其他 Provider 将来要支持峰谷，只需在 `ProviderRegistry` 补几行档案 + 配置 `peak-*` 单价，
  计价代码无需改动。**
- **新接口** `GET /api/token-usage/pricing-context`：返回当前时段、下次切换时刻、
  高峰窗口与各模型两档单价（只读内存，不查库不联网）。
- **前端（经典版 + v3 双端）**：成本看板「估算费用」卡片新增
  「当前：高峰时段 · 18:00 后转空闲」提示；时间按平台时区展示
  （不做本地时区换算，避免海外用户看到与规则不符的时间）。

### 🛡️ 兼容与回滚

- **不影响其他 Provider**：档案未声明峰谷的平台 `enabled=false`；模型未开 `peak-pricing` 时
  `PriceItem.effective()` 直接返回原对象 → 金额**逐位不变**（火山方舟 QUOTA 套餐制仍不估算金额）。
- **既有测试零改动**：`TokenUsageParser` 保留 3 参兼容构造器（内部使用「总开关关闭」策略）。
- **两级回滚开关**（均无需改代码）：
  `llm.pricing.peak-period.enabled: false`（全局按空闲价）/ 模型级 `peak-pricing: false`。
- **老库迁移双保险**：`schema.sql` 与启动期 `DeepSeekServiceImpl` 都会尝试
  `ADD COLUMN IF NOT EXISTS`；即使迁移失败也不影响对话主流程。

### 🧪 验证

- `PeakPricingTest`：**37 通过 / 0 失败**（时段 7 个边界、周末、国庆/春节/清明、调休两种口径、
  UTC 时区换算、总开关、双价与倍率、`cacheWrite` 不双重放大、下次切换、全局配置继承）。
- `mvn -DskipTests compile`：Java 378 文件 + 经典版 + v3 双前端 **BUILD SUCCESS**。

### ⚠️ 已知限制

- 历史记录不回算（设计选择：不篡改当时的账）。
- 聊天页（`ChatComposer` / `CodeAssistantView`）暂未加时段标签，仅成本看板展示。
- 工具调用轮的 `usage` 帧漏记（`takeWhile` 提前取消订阅导致）为**独立问题**，本版未修改。
- 内置节假日表仅含 2026 年；2027 年依赖远端或 `extra-holidays` 手工补充。

### 🔁 版本号同步

- `pom.xml` 项目版本 → 3.3.5
- `frontend/package.json` + `package-lock.json`
- `frontend-v3/package.json` + `package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.3.5.jar`）+ `package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION`
- `README.md` 版本徽章

## [3.3.3] - 2026-11-17

> **性能回归修复版本**：修掉长上下文会话「首 token 从 10 秒退化到 30~60 秒」的 P0 回归，
> 并修复「编辑文件工具偶发卡死」「内容输出完会话不结束」「多 Provider 扣错账户」三个高频故障。
> 核心是一条**前缀缓存全断**的连锁反应：token 估算虚高 3.4 倍 → 每轮误判超限 → 误压缩/裁剪
> → 请求前缀每轮重组 → DeepSeek 上下文缓存 100% miss。
> 本版**必须重启后端**（前端改动需重新加载页面）；未改数据库结构，
> 新增 1 个可自动创建的缓存文件，可覆盖安装。

### 🔥 修复：长上下文首 token 从 10 秒退化到 30~60 秒（用户报告，P0）

- **症状**：同一会话聊到 10 万 token 以上后，每轮第一条请求（`iteration=0`）首字节要 24~41 秒，
  而同轮内工具迭代（`iteration>=1`）只要 1~3 秒。数据库实测
  `llm_token_usage` 中 `iteration=0` 的 `cache_hit_tokens` 从 98.8% 直接掉到 **0**。
- **根因（四条链路叠加）**：
  1. **token 估算虚高 3.4 倍**：`TokenEstimator` 用固定字符系数估算中文 + 代码，
     实测估算 438,225 vs 真实 130,290（3.36 倍）；
  2. **估算虚高 → 每轮误判超限 → 误触发压缩/裁剪**：`ContextBuilder` 在 85% 阈值就压缩，
     且裁剪只改内存不回写 DB，下一轮重建出的前缀必然断裂；
  3. **弯路预警注入位置每轮漂移**：注入到「最后一条 user 消息」或新增 system 消息，
     导致插入点之后的历史全部不再是上次请求的真前缀；
  4. **SYSTEM 消息顺序错乱**：历史重建时 system 散布在中间，与上次请求的排列不同。
- **修复（四层）**：
  1. `TokenEstimator` 新增**按 Provider 在线校准**：用真实 `usage.prompt_tokens` 反推比率
     （滑动平均 α=0.3，观测区间 [0.10, 2.00]），挂载在唯一能拿到真值的
     `persistTokenUsageSafely`；估算口径统一为「messages + tools」
     （工具 schema 约 6 万 token 的固定地板必须计入）；`usage` 帧丢失时用
     「请求体字符数 × 0.425」兜底校准；
  2. **废除裁剪、改为纯压缩落库**：超 85% 触发压缩、超 95% 强制压缩，
     压缩写入 `conversation_compaction` 后统一由 DB 重建前缀，保证跨轮一致；
     压缩范围跳过开头连续 system，并保留区间内真实 system 消息；
  3. 弯路预警改为**注入第一条 system 消息**（文本按会话缓存一字不差 + 位置固定），
     并把这份文本**跨进程持久化**到 `<userData>/runtime/detour-hint-cache.json`（24h TTL），
     消除「重启后重新检索 → 文本不同 → 全量 miss」；
  4. `ContextBuilder` 新增 `hoistSystemMessages()`，把全部 system 提到最前（保持相对顺序）。
- **验证（同一会话实测对比）**：
  - 修复前：`iteration=0` 全量 miss（`输入=154490(命中0/未命中154490)`、费用 ¥0.155），
    同轮 `iteration>=1` 命中 99.7%（费用 ¥0.007/次），相差 **21 倍**；
  - 修复后：`ContextFingerprint` 零告警（system/tools 指纹全程稳定），
    `iteration=0` 的 TTFB 回落至 **357~443ms**，上下文规模单调增长无跳变；
  - 自检用例 `TokenEstimatorTest` **25 通过 / 0 失败**。

### 🔥 修复：编辑文件工具偶发卡死（用户报告）

- **症状**：`file_writer` 调用后长时间无响应（数十秒到数分钟）；命中缓存时 222ms 完成。
- **根因**：编辑后处理无条件执行 `mvn spotless:apply` 与 `mvn javaformat:format`，
  但本项目 `pom.xml` 从未声明这两个插件 → 命令必然失败，而 Maven 启动 + 解析 pom +
  尝试解析插件坐标（本地无缓存 / 网络抖动时）可耗十几秒到数分钟，两次叠加 → 卡死。
  同类风险：`npx --yes prettier` 在本地无 prettier 时会自动联网下载。
- **修复**：
  - `Formatter.formatJavaFile` 先读 `pom.xml` 文本，未声明插件则完全跳过 Maven；
  - `Formatter.formatWithPrettier` 的 `npx --yes` → `npx --no-install`（禁止联网下载）；
  - `Diagnostic.execCommandWithOutput` 输出改由独立守护线程读取，主线程只
    `waitFor(timeoutMs)`，让超时机制真正生效（原先输出读取阻塞导致超时形同虚设）。
- **验证**：替换 jar 后连续两次 `file_writer` 调用均秒回成功。

### 🔥 修复：内容输出完但会话不结束（用户报告）

- **症状**：模型内容已完整输出，前端仍显示「生成中」，需手动点停止。
- **定位**：后端日志 `后台任务完成` 与 `流式响应完成` 均正常触发（比用户手动结束早 35 秒）
  → **后端无 bug，问题在前端读取层**。
- **根因**：前端 `await reader.read()` 无超时保护，HTTP/2 / Electron 下服务端已关闭连接
  但 `read()` 长期 pending → 生成器挂住 → `finally` 收尾不执行。
- **修复**：两套前端（`frontend-v3` 与 `frontend`）的 `sse-client.ts` 各加 **90 秒空闲超时兜底**
  （`Promise.race` + `reader.cancel()` + 正常 `yield complete`）；
  用户主动停止走 `signal.aborted` 分支，不受影响。

### 🔥 修复：多 Provider 场景辅助调用扣错账户（用户报告）

- **症状**：Agent 绑定火山方舟（`huoshan`）时，压缩 / 评审 / 子 Agent / 复盘等**辅助调用**
  却打到 DeepSeek 官方账户 → 扣错账户、模型行为不一致。
- **根因**：默认 Provider 选择依赖 `sort_order`，而 `deepseek` 与 `huoshan` 的 `sort_order` 相同；
  辅助调用链路未强制透传当前会话的 `providerCode`，为空时静默回退到默认 Provider。
- **修复**：7 处辅助调用链路（压缩 / 评审 / 子 Agent / 复盘 / 弯路提炼 / 归一化 / 提示词优化）
  在 `providerCode` 为空时**拒绝回退默认 Provider**并抛出明确异常，强制上游透传真实 provider。

### ⚙️ 变更：上下文上限提升至 1M（按用户偏好）

- `application.yml` 的 `provider-max-context-tokens.huoshan`：`400000` → **`1000000`**，
  压缩触发水位因此变为 **850K**（`compact-threshold=0.85`），目标是「尽量少压缩、保住前缀缓存」。
  > 权衡提示：85 万 token 一次全量 prefill 约 105 秒，34 万约 15 秒。
  > 若希望更快的恢复速度，可把该值调回 `400000`。

### 🔧 其它修复

- **会话消息分页接口**新增 `compactedUpToMessageId` / `compactedSummaryTokens` 两个字段，
  两套前端据此**排除已压缩消息**并叠加摘要 token，前后端上下文用量口径统一
  （此前前端显示 171K 而后端真实约 5 万，偏高约 3.4 倍）。
- **空回合重试上限从 3 降到 1**：空回复成因是模型在超长上下文下「思考完直接 stop」
  （`finish_reason=stop`，`completion_tokens=1850` / `reasoning_tokens=1817`，content 全空），
  非 `max_tokens` 截断，重试不保证成功；而每次重试都改动前缀 → 必然 miss → 每轮白等 41 秒。
- **压缩服务 LLM 调用超时**从 60 秒收紧到 20 秒，避免压缩本身成为新的卡顿源。
- **压缩间隔防抖**（`MIN_COMPACT_INTERVAL_MS=120s`）修复重复压缩。
- **子 Agent 面板**只显示 `running` 状态的条目，不再堆叠已完成记录。

### ⚠️ 升级提示

- **必须重启后端**（token 校准、压缩策略、弯路预警持久化缓存均在启动时初始化）。
- **前后端需一起更新**（SSE 空闲超时与压缩边界字段在前端侧）。
- 未改数据库结构；首次启动会按需自动创建
  `<userData>/runtime/detour-hint-cache.json`（可安全删除，删除后下一次请求自动重建）。
- **本版会经历一次「重启后首次 miss」**：升级后的第一次请求需要重新建立缓存基线，
  之后的重启不再产生 miss（文本已落盘；仅 24h TTL 到期时会再产生一次）。

---

## [3.3.2] - 2026-11-16

> **单点修复版本**：修掉「设备互联 → Agent 授权」接口必现的 HTTP 500。
> 根因是一列**历史遗留的窄列定义**：`agent_avatar VARCHAR(20)` 装不下图片头像的 URL。
> 本版**需要重启后端**；含老库自动扩容迁移，无需手工执行 SQL，可覆盖安装。

### 🔥 修复：设备互联「Agent 授权」必现 HTTP 500（用户报告，P0）

- **症状**：v3 前端「设备互联」页面勾选 Agent 点授权，弹出
  「服务异常 HTTP 500 Internal Server Error」，接口为
  `POST /api/p2p/agent-auth/grant/{peerId}`。取消授权、查询授权列表都正常，
  **只有「授权」这一步失败**。
- **根因（一列宽度不够，全链路雪崩）**：
  - `p2p_agent_authorization.agent_avatar` 定义为 **`VARCHAR(20)`**，
    而 `agent_config.avatar` 与 `sys_user.avatar` 都是 **`VARCHAR(255)`**——
    这张表是当初只按「emoji 头像」设计时留下的，后来没人同步放宽。
  - 用户把 Agent 头像换成了**图片**，存的是 URL：
    `/api/avatar/file/f4fe889fb991454496e272fbc6ede39b.jpg`，长 **53 字符**。
  - 授权时 `grantAuthorization` 把该头像 URL 作为冗余字段写进 `agent_avatar`，
    数据库直接抛 `Value too long for column "agent_avatar CHARACTER VARYING(20)"`
    （H2 错误码 `22001-232`），未被捕获 → 冒泡成 HTTP 500。
  - **为什么其它接口没事**：`getMyAuthToPeer` / `getPeerAuthToMe` 只**读**不写，
    `cancelAgentAuth` 只做 `UPDATE status='cancelled'`（不碰头像列），
    所以只有「授权」这条写入路径会踩到列宽。
  - **为什么日志里查不到线索**：`app.log` 只有启动记录、`trace.db` 中
    `MERGE INTO p2p_agent` 一条都没有——说明异常发生在应用层之前就被截断，
    实际证据需要直接对数据库复现才能拿到。
- **修复（三处 + 一处兜底）**：
  1. `P2pAgentAuthorizationMapper.createTableIfNotExists` —
     建表时 `agent_avatar` 改为 `VARCHAR(255)`，与 `agent_config.avatar` / `sys_user.avatar` 对齐；
  2. 新增 `widenAgentAvatar()` — 幂等 ALTER：
     `ALTER TABLE p2p_agent_authorization ALTER COLUMN agent_avatar VARCHAR(255)`；
  3. `P2pAgentService.init()` 中调用该迁移（包 try-catch，失败仅 warn 不阻断启动）。
     **这一步是老用户能修好的关键**——`CREATE TABLE IF NOT EXISTS` 对已存在的表不生效，
     没有这段显式扩容，老库升级后仍是 `VARCHAR(20)`，500 会继续发生；
  4. 同步修正两处同源的错误定义，避免「新建库又被写成窄列」：
     - `P2pAgentService.migrateAuthTable()` 中的 `agent_avatar VARCHAR(20)` → `VARCHAR(255)`；
     - `DeepSeekServiceImpl` 建 `agent_config` 时的 `avatar VARCHAR(20)` → `VARCHAR(255)`。
- **验证（修复前后对比，均在同一数据库实测）**：
  - 修复前，用真实的 53 字符头像 URL 执行同一条 MERGE：
    `Value too long for column "agent_avatar CHARACTER VARYING(20)": "...jpg" (53)` ❌
  - 扩容后同一条 SQL：**执行成功，影响 1 行** ✅
  - 幂等性：重复执行 `ALTER COLUMN ... VARCHAR(255)` 不报错 ✅
  - 列宽复查：`INFORMATION_SCHEMA` 中 `agent_avatar` = **255** ✅
  - `mvn -DskipTests compile` → `BUILD SUCCESS` ✅
- **影响面确认**：全库扫描 `avatar` 列，仅此一处为 20，
  `agent_config.avatar`(255)、`sys_user.avatar`(255) 均正常。
  同表其它列 `agent_name VARCHAR(100)`、`agent_description VARCHAR(500)`
  与源表 `agent_config` 的上限一致，无溢出风险。

### ⚠️ 升级提示

- **必须重启后端**才生效（迁移在 `@PostConstruct init()` 中执行）。
- 老库会自动扩容，**无需手工执行 SQL**；若部署环境限制 DDL，
  可手工执行一次：
  `ALTER TABLE p2p_agent_authorization ALTER COLUMN agent_avatar VARCHAR(255)`
- 未改其它表结构，无数据迁移，可覆盖安装。

---

## [3.3.1] - 2026-11-16

> **稳定性修复版本**：集中修复前两轮代码审查发现的 20 处功能性缺陷，其中 2 处是
> **用户可见的直接故障**（聊天输入框异常变高、删除会话后侧栏不刷新），
> 4 处是静默的**状态泄漏 / 数据错位**，3 处是**参数未钳制**导致的溢出与 DoS 面。
> 本版**需要重启后端 + 重新加载前端**；未改数据库结构，无数据迁移，可覆盖安装。

### 🔥 修复：聊天输入框发送后异常变高，要再敲字才恢复（用户报告，P0）

- **症状**：在 v3 聊天页输入较长内容（超过 200px 高度）并发送后，输入框**一直停在最高**，
  须再输入任意字符才恢复正常高度。
- **根因（Vue 响应式时序）**：`ChatComposer.vue` 里 `watch(() => props.modelValue, () => autoResize())`
  使用默认的 `flush: 'pre'`，回调在 **DOM 更新之前**执行。发送时 `ChatView` 先
  `emit('send')` 再 `draft.value = ''`，watch 立刻执行 `autoResize()`，此时 textarea 的
  **DOM value 仍是刚才那段长文本**，于是 `el.style.height = min(scrollHeight, 200) = 200px` 被写死；
  紧接着 DOM 被清空，但 `autoResize` 已经跑完，**没人再纠正** → 输入框保持 200px。
  短文本时算出来是 54px，所以表现为「有时」才出现。
- **修复**：
  - `watch` 改为 `{ flush: 'post' }`，确保在 DOM 更新后读取真实 `scrollHeight`；
  - 回调内再套 `nextTick(autoResize)`，兜住「同一 tick 内多次改值」的极端情况；
  - 新增 `onMounted(() => autoResize())`：组件在带初始草稿的情况下被创建时也算一次；
  - 新增 `watch(() => props.rows, ...)`：空态（rows=3）与会话中（rows=2）切换时重算高度。

### 🔥 修复：删除会话后侧栏列表不刷新，要刷新浏览器才消失（用户报告，P0）

- **症状**：删除某个会话后，被删的会话**仍留在侧栏**，点进去才发现已 404；
  刷新一次浏览器后才消失。
- **根因（并发去重把「旧结果」当成「新结果」）**：`App.vue` 的 `loadConversations()`
  只要 `conversationsInFlight` 非空就**无条件返回那笔旧请求的 Promise**。
  删除会话时这个时序很容易踩中——流式收尾、后台轮询、切会话都可能正在飞一次
  `loadConversations`，于是删除后调用的 `await loadConversations()` 拿到的是
  **删除之前**那笔请求的结果，被删的会话原样写回列表。
- **修复**：新增 `force` 参数，写操作后的刷新可**穿透去重**：
  - `force=true` 时先 `await` 旧请求落地（吞掉其错误），再发起一笔新请求；
  - 等待期间若已有更新的请求在飞，直接复用它（它必然比旧的更新）；
  - 已为 7 处写路径传 `force=true`：删除会话、一轮对话结束（影响排序）、
    新会话拿到真实 id、切 Agent（3 处）、委托跳转需确认会话存在；
  - 3 处**首屏加载**保持不带 `force`（此时列表为空，保留「并发只发一次请求」的收益）。

### 🔥 修复：`file_writer` 必填校验字段名错位，按 schema 调用必被拦截（P0）

- **症状**：模型按工具 schema 规范传 `{"action":"write","file_path":"...","content":"..."}`
  时，必然收到「缺少必填参数：path」，工具根本没被执行。
- **根因**：`ToolExecutor.REQUIRED_PARAMS` 里 `write`/`edit` 登记为 `path`，
  而 `FileWriterTool` 实际读的是 `args.path("file_path")`（schema 里也是 `file_path`）；
  `isNonEmpty` 无别名处理，两边不一致就直接拦截。只有 `delete` 真的读 `path`。
- **修复**：校验表 `write` → `["file_path","content"]`、`edit` → `["file_path","old_text","new_text"]`，
  `delete` 保持 `["path"]`。已当场实证：修复前自身调用被拦截，修复后正常执行。

### 🔥 修复：`iteration` 未递增，`maxIterations` 守卫失效可无限递归（P0）

- **症状**：截断续写 / 补充需求注入 / `concatWith` 兜底补充三条路径重新进入工具循环时，
  迭代计数不增加，长任务可能无限递归下去。
- **根因**：`DeepSeekServiceImpl` 这三处递归调用传的是 `iteration` 而非 `iteration + 1`
  （正常工具执行路径的 5 处都是 `+1`）。
- **修复**：三处（补充需求注入、`finish_reason=length` 截断续写、`concatWith` 兜底补充）
  统一改为 `iteration + 1`。评委扩容分支（传 `iteration` + 提高 `newMaxIterations`）属正确设计，未改。

### 🐛 修复：`AbortError` 被无差别当成「请求超时」，误导用户排查后端

- **症状**：用户主动取消请求（或组件卸载、请求被抢占）时，界面提示
  「请求超时，请检查后端服务是否正常」，把排查方向完全指错。
- **根因**：两套前端的 `http-client` 都把**任何** `AbortError` 映射成「超时」。
  实际上 `AbortError` 有两种来源：①本模块的超时定时器触发；②调用方自己的
  `AbortController`（取消 / 卸载 / 抢占）——后者与后端毫无关系。
- **修复**：
  - `frontend-v3`：`rawFetch` 在 catch 中用「外部 signal 是否已 aborted」判定来源，
    标记到 `WeakSet`；`normalizeError` 据此返回「请求已取消」而非「请求超时」。
    用 signal 状态而非给 error 打属性，是因为 fetch 抛出的 `DOMException`
    在不同宿主下不保证同一对象引用。
  - `frontend`（经典版）：catch 中判断 `options.signal?.aborted`，区分两种取消语义。

### 🐛 修复：会话分片状态漏清 / 漏迁移，导致徽章残留与分页错乱

- **症状**：删除会话后状态栏仍显示「N 轮可回滚」徽章；草稿会话转正后
  「AI 浏览器」入口消失、首次上滑可能加载出重复/空的更早历史。
- **根因**：`App.vue` 的 `dropConversationState` 漏清 7 个按会话分片的状态，
  `migrateConversationKey` 漏迁 5 个。两者是同一病根——
  **「集合类状态只有 add/set，没有 delete/migrate」**。
- **修复**：
  - `dropConversationState` 补清 `snapshotCountMap`、`snapshotHintMap`、`cursorMap`、
    `hasMoreMap`、`browserTabsMap`、`conversationAgentMap`、`conversationTitleMap`；
  - `migrateConversationKey` 补迁前 5 个（后两个由 `loadConversations` 按真实 id 直接写入，
    草稿 key 上本无有效值，刻意不迁）；
  - 在两处函数的注释里写明「新增任何按会话 key 分片的 ref 必须同步加进这两个函数」，
    避免同类遗漏再次发生。

### 🐛 修复：AI 浏览器标签轮询定时器卸载时未清理

- **症状**：热更新（HMR）反复重建组件时，每轮残留一个 4 秒轮询，累积后持续空转打后端。
- **根因**：`App.vue` 的 `onBeforeUnmount` 只清了 `delegateClock` 与 `subAgentTimers`，
  漏了 `browserPollTimer`（上一轮修复「子Agent浮层不消失」时的同类遗漏）。
- **修复**：`onBeforeUnmount` 补 `stopBrowserPoll()`，并在注释中列出三类定时器清单。

### 🐛 修复：用户列表分页 `offset` 整数溢出

- **症状**：`page` 传大值时列表返回异常结果或数据库报错。
- **根因**：`UserServiceImpl.listUsers` 用 `int offset = (page - 1) * pageSize`，
  而 `UserController` 的 `page`/`pageSize` 都直接取自 `@RequestParam`、**无任何上限校验**。
  `page=2147483647, pageSize=100` 时乘积溢出为负数 → `LIMIT -214748..., 100`。
- **修复**：`pageSize` 夹到 `[1, 200]`、`page` 至少 1，`offset` 用 `long` 计算后
  收敛到 `Integer.MAX_VALUE`（越界页返回空页 + 正确 total，不抛异常以保持分页契约稳定）。
- **备注**：同项目的 `FileAssetService`（上限 200）与 `LessonService`（上限 100）
  本就有钳制，本次是补齐唯一遗漏的入口。

### 🐛 修复：P2P 历史消息分页无上限

- **症状**：`limit` 传极大值可一次拉空整张聊天记录表。
- **根因**：`P2pController.getHistory` 的 `offset`/`limit` 直接透传，
  经 `P2pChatService` 原样进 SQL，无任何钳制。Peer 通道是可连入的入口，构成内存/带宽放大面。
- **修复**：在 Service 层统一收敛——`offset` 至少 0，`limit` 夹到 `[1, 200]`
  （放在 Service 而非 Controller，使未来的其他调用方同样受保护）。

### 🐛 修复：7 个线程池缺少 `@PreDestroy`，上下文重建时累积泄漏

- **症状**：热部署 / 多次 `@SpringBootTest` 刷新后，空闲线程与排队任务持续累积。
- **根因**：线程虽为 daemon（JVM 退出不阻塞），但 Spring 上下文重建时旧池无人关闭。
  上一轮只为 `CompactionService` 补了关闭，其余遗漏。
- **修复**：补齐 6 处 `@PreDestroy`：
  - `DeepSeekServiceImpl` — `bg-task-` 池；先 `dispose` 在途任务订阅再关池，
    并清空 `taskSubscriptions` / `judgeGrantedIterations`；
  - `AgentForkManager` — `sub-agent-` 池（`corePoolSize=20` 常驻不回收，累积最明显）；
    并清空 4 个跟踪表；
  - `LessonRecorder` / `LessonReviewService` / `LessonDetourService` / `LessonNormalizerService` — 4 个单线程池。
  统一模式：`shutdownNow()` + `awaitTermination`（3~5 秒）+ 中断标志恢复。

### 🐛 修复：更新包选择忽略 Windows 架构

- **症状**：若发布同时含 x64 与 arm64 的 exe，ARM 设备会拿到 x64 安装包
  （能装上但性能异常或无法运行），且是静默选错、用户无从判断。
- **根因**：`electron/update-utils.js` 的 `pickAsset` 在 `win32` 分支**完全不看 arch**，
  只取「第一个名字像 setup 的 exe」。且 `update-manager` 调用时未传 `platform`/`arch`（靠默认值）。
- **修复**：
  - `platform`/`arch` 参数补默认值（`process.platform` / `process.arch`）；
  - Windows 分支新增三级匹配：架构后缀精确匹配 → 无架构后缀的通用包（兼容现有命名）→ 兜底第一个；
  - Linux 分支同步支持架构后缀匹配。
  - 已实测 7 种场景（含双架构 x64/arm64 设备、单通用包、mac、linux），全部选对。
- **备注**：当前发布仅单一架构（走「通用包」分支），本改动为将来多架构发布预先兜底。

### 🐛 修复：极小预算下工具结果仍会超预算

- **症状**：预算设为 300 时实际输出 304 字节，末尾兜底还会把结果顶超预算。
- **根因**：极小预算分支漏算两处 `SEP` 分隔符开销；末尾 `Math.max(MIN_HEAD, ...)`
  在极端情况下把已压缩的头部重新顶回最小值，导致总量超限。
- **修复**：改为精确 clamp，实测预算 300 → 实际输出 300。

### 🐛 修复：历史摘要标记与 compact 前缀撞车，精简过的消息被永久跳过

- **症状**：被 `compactHistoryMessages` 精简过的历史消息会被误判为「已摘要」而永久跳过，
  导致后续裁剪失效。
- **根因**：`ContextBuilder.TRIM_SUMMARY_MARK` 为 `"[工具 "`，而 `compactHistoryMessages`
  生成的摘要前缀同样是 `"[工具 ..."`，两者撞车。
- **修复**：标记改为 `"[CC-TRIM][工具 "`（带唯一前缀），并修正生成处重复拼接「工具」的瑕疵。

### 🐛 修复：`tool_calls` 固定按 15 token 估算，严重低估写大文件场景

- **症状**：写大文件的工具调用被低估约 1400 倍（实测 15 vs 21028），裁剪决策失效导致 API 400。
- **根因**：`TokenEstimator` 对 `tool_calls` 一律按固定 15 token 计，不看实际 arguments 大小。
- **修复**：新增 `estimateToolCalls()`，按实际 JSON 长度估算。已补 `TokenEstimatorTest`
  6 个用例，并做反向验证（临时回退到固定 15 → 3 条断言失败，证明测试有效）。

### 🐛 修复：日志搜索分页 `int` 溢出

- **症状**：大页码时静默返回空页（不报错，最难排查）。
- **根因**：`page * size` 与 `(page - 1) * size` 在 int 上溢出为负数，
  滑动窗口的「超限即丢弃」条件恒成立，窗口永远为空。
- **修复**：两处改用 `long` 计算并 clamp 到合理上限，不改变正常分页语义。

### 🐛 修复：`provider-max-context-tokens` 大小写不一致时静默回落

- **症状**：yml 里写 `huoshan: 400000` 但代码查 `huoshan` 小写键，
  若 yml 写成 `HuoShan` 则静默回落到全局 300000，无任何提示。
- **根因**：`DeepSeekConfig.resolveMaxContextTokens` 直接用
  `providerMaxContextTokens.get(providerCode.trim().toLowerCase())`，而 yml key 原样保留。
- **修复**：增加忽略大小写的兜底匹配 + `warn` 日志，配置不匹配时不再静默。

### 🐛 修复：`validateToken` 的 `Long.parseLong` 未捕获异常

- **症状**：非法 token 传入时抛 `NumberFormatException` 导致 500，而非按未登录处理。
- **修复**：加 `try-catch`，非法时返回 `null`。

### 🐛 修复：`msgTokenCache` 溢出时整表清空，造成周期性卡顿

- **症状**：长会话滚动到一定规模会出现一次明显卡顿，之后周期性复现。
- **根因**：`msgTokenCache` 超过 4000 条时 `clear()` 整表，瞬间所有消息估算失效，
  下一次刷新会把整个已加载列表全部重算。
- **修复**：改为**按插入序淘汰最旧的 1/4**（Map 保持插入序），容量上限不变但淘汰是渐进小批量，
  不再有整表重建的尖峰。

### 🐛 修复：登录随机码使用可预测的 `java.util.Random`

- **症状**：登录挑战码理论上可被反推。
- **根因**：`RandomCodeUtil` 用共享 `java.util.Random`——线性同余伪随机，
  连续少量输出即可反推内部状态；本类生成的是 `MD5(MD5(密码) + 随机码)` 的挑战码，
  可预测意味着摘要可被预先计算。另外多线程并发 `nextInt` 存在种子更新丢失。
- **修复**：改用 `SecureRandom`（线程安全 + 密码学安全），仍保持静态单例复用
  （避免每次初始化熵源的开销），并对 `length <= 0` 做防御。

### 🐛 修复：`WorkspacePane` 在 setup 顶层挂载 document 监听

- **症状**：现行功能正常，但属脆弱写法：SSR 环境直接抛错；若组件将来被 `<KeepAlive>`
  或 `v-if` 复用，`setup` 只执行一次而 `onBeforeUnmount` 每次都移除，监听会永久丢失。
- **修复**：移到 `onMounted`，与 `onBeforeUnmount` 的移除严格配对。

### ✅ 顺带确认无问题的部分

本轮同时交叉核对了以下模块，确认**实现正确**，未做改动：

- `ToolExecutor.REQUIRED_PARAMS` 全表（`file_explorer`/`file_writer`/`command`/`desktop`/`git`
  六类工具）逐一与实际读取字段名对照，`file_writer` 是唯一错位处；
- `ConversationController` 消息分页契约（`beforeId`/`rounds` 字段名一致，`rounds` 上限 50 有保护）；
- `getMessagesPage` 的轮次游标算法（`${offset}` 由 Service 限定 0~49，无注入风险）；
- `execute_sql` 的三重保护（单语句 + 类型白名单 + 长度限制）；
- 全部 `*Drawer.vue` 的 `watch` 回填与 `submit` 的 `finally` 复位；
- `P2pChatDrawer` 生命周期（keydown 监听与轮询均严格配对）；
- Electron `before-quit` 的三层清理链路（PTY 会话 / 浏览器桥 / 后端进程）；
- Electron 下载完整性校验与「已下载态」双重校验。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.3.1
- `frontend/package.json` + `package-lock.json`
- `frontend-v3/package.json` + `package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.3.1.jar`）+ `package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION`
- README 中英徽章、13 个 docs 文档头与打包命令示例

### 📋 验证记录

本次发布前已完成以下验证，全部通过：

- `mvn -DskipTests compile` → `BUILD SUCCESS`
- 5 个 `main` 方法自检程序全部通过，**共 57 项断言，0 失败**：
  `TokenEstimatorTest`（6）、`ToolResultBudgetTest`（34）、`LogServiceSearchTest`（6）、
  `ContextBuilderTrimTest`（5）、`SnapshotDirGuardTest`（6）
- `frontend-v3` typecheck + build：0 error
- `frontend`（经典版）typecheck + build：0 error
- `pickAsset` 7 种平台/架构组合实测，全部选对

### ⚠️ 升级提示

- **必须重启后端**，并重新加载前端（后端 embed 前端产物到 `static/`，需重新打包才生效）。
- 未改数据库结构，**无数据迁移**，可直接覆盖安装。
- 「输入框异常变高」与「删除会话后列表不刷新」两个用户可见故障，建议升级后实测确认：
  前者输入 10 行以上文字→发送→观察是否恢复默认高度；
  后者令某会话在后台流式生成时删除另一会话→观察侧栏是否立即移除。

---

## [3.3.0] - 2026-11-16

> **上下文与日志可见性版本**：修掉「工具循环裁剪把历史工具结果抹成空壳、并每轮重复改写」
> 导致的模型失忆现象，同时让被截断的输出不再被误判成「任务完成」（改为自动续写）；
> 另修掉「日志页永远看不到最新日志」与「快照把目录当文件读」两个长期缺陷。
> 本版**需要重启后端**；未改数据库结构，无数据迁移，可覆盖安装。

### 🔥 修复：工具循环裁剪把历史抹成空壳，导致模型反复失忆（P0）

- **症状**：长任务里模型「干到一半忘了自己在干什么」，反复输出同一句开场白
  （例如连续多轮都是「我不再验证了，直接动手修复」），然后重复执行已经做过的操作；
  同一任务在「精简 / 全量」上下文模式下正常，**只有「均衡」模式明显**。
- **根因（三处叠加）**：
  1. **信息归零**：成功工具结果被整体替换成
     `[工具 xxx 调用成功 - 上下文超限已裁剪，完整结果请用 query_tool_history 查询]`——
     这是一句不含任何内容的空壳。工具循环里模型靠「工具结果」判断进度，
     结果全被抹成同样的空串后，它不知道「读过哪个文件、发现了什么、改到第几步」。
  2. **覆盖不足 → 每轮重复改写**：旧实现只摘要化 `[0, summaryEnd)`（保留最近 2 轮），
     而 token 大头常压在尾部；一次摘要降不到上限，于是**每一轮**都判定「仍超限 → 再裁一次」。
     实测日志可见 `估算 438225 → 438096 tokens`（只降 **129**，几乎等于没裁），
     历史被反复改写导致前缀缓存持续失效（实测 TTFB 从 1.4s 退化到 20s）。
  3. **保护窗口过窄**：旧实现只保留最近 **2** 轮工具调用完整，工具结果是按轮次滑动的——
     第 N 轮时 t(N-1)/tN 完整，到第 N+1 轮 t(N-1) 就退化成「倒数第三条」被立刻摘要掉。
     于是每个工具结果只完整存活 2 轮，模型永远只看得到「刚刚这两步」。
- **修复**：
  - 摘要**保留结果首部**：成功保留 400 字符、失败保留 300 字符（失败信息对避免重复踩坑价值极高），
    模型据此能认出「这一步做过、结果大意是什么」；
  - 新增幂等标记 `[工具 `：已摘要的消息后续轮次**原样跳过**，
    保证「第一次裁完就稳定」，不再每轮改写历史；
  - 新增 `findNextSummaryCursor` 逐级扩展：摘要后重新估算，仍超限就把下一条
    `assistant(tool_calls)` 纳入可摘要区，直到降到上限或无处可扩；
  - 保护窗口由硬编码 2 轮提升为常量 `PROTECT_RECENT_TOOL_ROUNDS = 8`：
    8 轮 ≈ 2 万~2.4 万 tokens，仅占 30 万预算的 8%，换来 8 步连续视野；
  - **底线**：最后一条 `assistant(tool_calls)` 及其之后（当前正在进行的轮次）永不摘要，
    否则模型连「刚刚这一步的结果」都看不到，会立刻重复同一次工具调用。
- **三种模式为何只有均衡出问题**：全量模式回传真实 `reasoning_content`，靠思考链兜住连续性；
  精简模式在构建阶段就把工具结果确定性摘要化（短），token 降下来后**根本不会走到**
  这条兜底裁剪路径；只有均衡模式既丢了 reasoning、又保留原始长结果，必然触发裁剪。

### 🔥 修复：`finish_reason=length` 被误判为「任务完成」，长任务十几轮就收工（P0）

- **症状**：模型输出一句「开始。」后就没有下文，任务被标记完成；日志里每一轮都以
  `[DONE]` 漂亮收尾，没有任何 `IOException` / `timeout` / `connection reset`，
  用常规「网络断连」思路完全查不出问题。同一任务在 DeepSeek 官方能跑到
  `iteration=125`，在火山方舟只到 `iteration≈18~19`。
- **根因**：判断「任务是否结束」的依据是**这一轮有没有 `tool_calls`**，
  而全库**从不解析 `finish_reason`**。OpenAI 协议里 `length` 表示**输出被 max_tokens 截断**——
  此时模型话没说完、`tool_calls` 也没来得及吐出来，代码却当成「无工具调用 → 自然结束」，
  保存半截回复并收尾任务。火山更易触发，是因为 `deepseek-v4.1-flash` 走思考模式，
  `reasoning_content` 同样消耗输出额度，思考链一长就把额度吃光。
- **修复**：
  - 流式解析中捕获 `choices[0].finish_reason`（`AtomicReference`，仅非空时覆盖，
    避免中间帧把已拿到的值冲成空）；
  - `length` 时**不**判定任务完成：不结算成长体系追踪、不触发对话复盘
    （内容不完整，复盘结论会失真），并标记 `_finishReason=length`；
  - 已输出的半截内容作为**中间 assistant 消息**入上下文（模型下轮才能接着写），
    而不是当成任务结果落库；
  - 外层 `concatWith` 检测到截断标记后注入
    `【系统自动续写】上次输出因长度上限被截断，请从中断处继续…` 并**重新进入工具循环**，
    前端可见 `✂️ 输出达到长度上限，正在自动续写...`；续写仍受 `maxIterations` 与既有守卫约束，不会死循环。

### 🐛 修复：日志页永远看不到最新日志（分页方向反了）

- **症状**：日志页面查不到近期日志（例如 09:55 之后的记录全部看不到）。
- **根因**：`LogService.search()` 用 `maxCollect = page * size` 从**文件头**开始收集，
  `page=1` 只返回**最旧**的 500 行。实测 `app.log` 13018 行，09:55 在第 383 行，
  之后 12564 行全部落在窗口之外——越新的日志越看不到。
- **修复**：
  - 后端改为**滑动窗口**收集（`ArrayDeque` + `addLast` / `removeFirst`），
    只保留最新 `maxCollect` 条，`page=1` 返回**最新一页**，与 tail 模式语义对齐；
  - 前端 `App.vue` 新增 `LOG_SEARCH_PAGE_SIZE=200`、`logSearchPage`、`logLastQuery`，
    搜索模式支持 `page+1` 翻更早的一页；
  - `LogListView.vue` 解除「只有 tail 模式才显示加载更早按钮」的 UI 限制，
    并在 `panelMeta` / `statusText` 中补上搜索模式的已加载量与翻页提示。

### 🐛 修复：快照把目录当文件读，抛 `AccessDeniedException`

- **症状**：`computeDiffStats` 抛出 `java.nio.file.AccessDeniedException`，
  报错路径以包名 `sso` 结尾——那是**目录**，不是 `.java` 文件。
  Windows 上对目录调 `Files.readAllBytes` 必然抛该异常（目录不是可读流）。
- **根因**：`file_writer` 执行 `action=delete` 且 `path` 指向目录时，
  `ToolExecutor.resolveFilePath` 把目录当成「待修改文件」传进快照链；
  备份阶段 `Files.copy(目录)` 只建出空目录，后续 diff 统计或回滚读它就会炸。
  由于 `SnapshotService` 相关方法是 `synchronized`，反复抛异常还会拖慢整条快照链。
- **修复（三处防线）**：
  - `SnapshotService.createSnapshot` 入口拦截：存在且非普通文件直接返回 `null`，
    不建快照目录、不写 metadata 与索引；
  - `SnapshotService.computeDiffStats` 全部改用 `Files.isRegularFile`，
    并为历史脏数据（旧版本已落盘的目录条目）加 warn 跳过分支；
  - `ToolExecutor.resolveFilePath` 对「存在且非普通文件」返回 `null`，从源头拦住。

### ✅ 自检

- 新增 `LogServiceSearchTest`（6 用例）：page=1 返回最新一页、total 为真实命中数、
  page=2 比 page=1 更旧、相邻页首尾衔接无重叠无缺口、大页尺寸窗口完整、小窗口仍取最新。
- 新增 `SnapshotDirGuardTest`（6 用例）：目录不建快照、不留快照子目录、不写索引、
  普通文件正常建快照（防误伤）、`computeDiffStats` 传目录不抛异常、
  目录内普通文件仍可正常建快照。
- `ContextBuilderTrimTest`（5 用例）全绿，工具循环裁剪 396191 → 114601 tokens（降 71%，一次到位）。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.3.0
- `frontend/package.json` + `package-lock.json`
- `frontend-v3/package.json` + `package-lock.json`
- `electron/package.json`（`version` + `code-craft-3.3.0.jar`）+ `package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION`
- README 中英徽章、8 个文档头、4 个打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **需要重启后端**（Java 侧改动涉及流式解析、上下文裁剪、日志搜索、快照服务）。
- **未改数据库结构**，无数据迁移，可直接覆盖安装。
- 前端有改动，建议强刷（`Ctrl+F5`）避免旧资源缓存。

---

## [3.2.0] - 2026-11-16

> **长任务稳定性版本**：修复「长任务跑到中途莫名其妙断连」的根因（SSE 静默期没有心跳，
> 被链路中间层按空闲连接回收），并修掉上下文裁剪的两处静默失效——线上曾出现
> 「估算 429388 tokens 超限、执行裁剪、结果移除了 0 条消息」的死循环形态。
> 同时引入 **Provider 级上下文上限**，火山方舟这类「窗口远大于全局配置」的平台不再被
> 全局 30 万阈值误裁。
> 本版**需要重启后端 + 强刷前端**；未改数据库结构，无数据迁移，可覆盖安装。

### 🔥 修复：SSE 全链路无心跳，长任务静默期被中间层掐断（P0，断连根因）

- **症状**：长任务跑到一半突然「断连」，前端不报任何错误、后端 `agent_task`
  全部 `status=completed`、`error_message` 全为 `null`；同一会话在 DeepSeek 官方偶尔出现，
  在火山方舟上明显更频繁。
- **根因链**：
  1. SSE 是「上游有事件才有字节流动」的推模型。工具执行期间（跑构建、读大文件、
     等子 Agent）上游可能长时间无事件，`SseBatchUtil.batch()` 下游**一个字节都不发**；
  2. 实测轮内静默间隔可达 **125.8 / 96.1 / 90.4 秒**，静默 ≥30 秒的占比 **59 / 525**；
  3. 链路中间层（代理 / 防火墙 / 反代 / 系统连接跟踪表）普遍有 **30~60 秒**的空闲连接
     判定，越过即把连接当死连接回收；
  4. 前端 `reader.read()` 直接拿到 `done`，`readSseStream` 按正常收尾 `yield complete`。
     于是表现为「流悄悄结束了」，既不报错也无提示；
  5. 火山更频繁，是因为其首字节延迟更长（实测 TTFB 最大 23.5s、均 1146ms），
     静默窗口更容易触顶。
- **修复**：`SseBatchUtil` 增加静默期心跳保活：
  - 独立 `Flux.interval` 分支与事件流 **merge**，不并入 50ms 批窗口——
    并入会在静默期被攒住（`bufferTimeout` 靠「首元素到达」启动计时，
    静默期没有事件唤醒它，心跳反而发不出去）；
  - 静默超过 **15 秒**才补发一次心跳，密集输出期不产生多余帧；
  - 用「1 秒轻量 tick + 静默判定」而非直接 `interval(15s)`：
    后者会让上游正常结束后的收尾最长拖满一个 15 秒周期、连接悬挂不关；
  - 心跳载荷取单键 JSON `{"event":"heartbeat"}` 而**非 SSE 注释行**：
    输出会经 `ServerSentEvent.builder` 编码，Spring 给 payload 每个换行补 `data: ` 前缀，
    注释行会被编码成 `data: : keepalive`——非合法 JSON，前端会走「非 JSON 当正文」
    分支把文字渲染进界面。
- **前端配套**：`sse-client.ts` 显式丢弃心跳帧。虽然后端载荷已能自然落到
  `return null`，但那是「单键 JSON 恰好无 sessionId」的**巧合**；
  将来若给心跳加 `ts` / `seq` 字段，巧合会破、心跳就可能被当正文渲染。
  显式判断把这件事变成契约。

### 🔥 修复：上下文裁剪删 0 条的死循环（P0，与断连叠加放大）

- **症状**：日志中连续出现
  `上下文滑动窗口丢弃：估算 429388 tokens (上限 300000)` →
  `上下文丢弃完成：移除了 0 条消息，剩余 273 条，估算 429388 tokens`，
  10:57 前后连续重复 10 次无进展；请求体一路涨到 **74 万字符**。
- **根因**：`findProtectStart` 在「user 轮次不足 `protectRounds`」时返回 `1`
  （工具循环里常见只有 1 条 user 消息）。而裁剪循环 `idx` 固定从 `1` 起跳、
  条件为 `idx < lastIdx`（`lastIdx = 1`）——**恒不成立，一条都删不掉**。
  这不是"裁了但没效果"，是裁剪压根没执行。
- **修复**：`trimToTokenBudget` 增加**降级链**——保护带裁完仍超限则逐级缩小
  （`protectRounds` → `1` → `0`），每级复用同一套裁剪循环并打印降级日志。
- **同时加固（重要）**：降级到 0 轮时**绝不能删掉最后一条 user 提问及其之后的当前轮次**。
  可裁区上界改为同时满足两个约束并取**较严**的那个：
  `min(保护带起点, 最后一条 user 的下标)`。
  初版实现取 `max`，自检实测出「62 条裁到 2 条且 user 提问消失」——模型会失去回答对象，
  比上下文超限严重得多。该约束已固化为自检用例。

### ✨ 新增：Provider 级上下文上限（避免「一刀切」误裁）

- **背景**：此前 `max-context-tokens` 是全局单值，所有 Provider 共用。
  但实际窗口差异很大：DeepSeek 官方 1M、火山方舟 Coding Plan **1049K**、
  部分中转站 / 小模型仅 128K~256K。用全局 30 万管火山会「提前误裁」——
  裁剪本身会改变消息前缀导致缓存失效（单价差约 50 倍），还让请求体反复变大、拉长 prefill。
- **实现**：`DeepSeekConfig` 新增 `providerMaxContextTokens` 覆盖表与
  `resolveMaxContextTokens(providerCode)`；未配置的 Provider 回落全局值（保持既有行为）。
  三处「按上限做压缩/裁剪决策」的位置全部改为走该方法：
  `ContextBuilder.trimToTokenBudget`、`CompactionService.decide / asyncPrecompress`、
  `DeepSeekServiceImpl` 工具循环守卫（`resolveProviderCode` 从请求体 `_llmClient` 取实际 Provider）。
- **配置**（`application.yml`）：

```yaml
deepseek:
  max-context-tokens: 300000
  provider-max-context-tokens:
    huoshan: 400000
```

- **取值原则（不要用满）**：请求体越大 → prefill 越久 → 首字节延迟越长 →
  长任务静默窗口越长 → 越容易被中间层掐断。火山只用到窗口的约 38%，属刻意保守。
  实测请求体撑到 74 万字符时首字节延迟已到 17~23 秒。

### 🔧 文档补充：火山费用中心 V4 签名两个易错点

- `docs/LLM_PROVIDER_SYSTEM.md` 新增 4.5.1 节：
  - **`content-type` 不参与签名**：官方 `@volcengine/openapi` 把它列入 `unsignableHeaders`
    （该头可能被 HTTP 客户端改写，参与签名会出现「本地算的签名 ≠ 实际发出的请求」）。
    本项目 `SignedHeaders` 为 `host;x-content-sha256;x-date`；
  - **凭证缺失与凭证错误必须分清**：附 `InvalidAccessKey` / `SignatureDoesNotMatch` /
    `InvalidSecretToken` / `InvalidCredential` 四个返回码的语义对照表。
    关键判据：`SignatureDoesNotMatch` 意味着 **AK 存在**、错在 SK 或签名头。

### 🧪 新增自检（防止这两类"静默失效"回归）

- `SseBatchUtilHeartbeatTest`（4 用例）：静默期必须发心跳 / 密集期不发心跳 /
  上游结束快速收敛（实测 108ms）/ 心跳载荷可被前端安全忽略；
- `ContextBuilderTrimTest`（5 用例）：多轮超限必须真正裁剪 / 2 轮边界降级裁剪 /
  单 user 工具循环**必须保留 user 提问** / 未超限不裁剪 /
  工具循环裁剪必须显著降 token（实测 `396191 → 108945`，从"截断 0 条"变为"摘要化 28 条、截断 2 条"）。
- 回归：`VolcengineAdapterTest` **46 passed, 0 failed**；`mvn compile` 通过；
  两套前端 `typecheck` 0 error。

### 🔢 版本号同步

- `pom.xml` 项目版本 → 3.2.0
- `frontend/package.json` + `package-lock.json`
- `frontend-v3/package.json` + `package-lock.json`
- `electron/package.json`（版本 + `code-craft-3.2.0.jar` 引用）
- `electron/package-lock.json`
- `CodeCraftMcpServer.SERVER_VERSION`
- 8 个技术文档头（版本 + 更新日期）、README 双徽章、4 处打包命令示例、FAQ 版本示例

### ⬆️ 升级说明

- **后端需重启**（`SseBatchUtil` / `ContextBuilder` / `DeepSeekConfig` / `CompactionService` /
  `DeepSeekServiceImpl` 均有改动）；
- **前端需强刷**（`sse-client.ts` 改动）；
- **无数据库结构变更**，无需数据迁移，可直接覆盖安装；
- 新增配置项 `deepseek.provider-max-context-tokens` **可选**：
  不配置则全部回落 `max-context-tokens`（与 3.1.7 行为一致）。

---

## [3.1.7] - 2026-11-15

> **TTFT 性能回归修复版**：修掉「开启均衡（balanced）上下文模式后，长会话首个 token
> 从 ≤10 秒退化到 20~30 秒」的根因。同时修复 v3 前端重新登录后部分页面仍显示「未登录」
> 的问题，以及若干裸 `fetch` 通道的 401 无出口问题。
> 本版**需要重启后端 + 强刷前端**；未改数据库结构，无数据迁移。
> ⚠️ **行为变更**：`balanced / compact` 下模型在本轮工具循环中不再看到自己的历史思考文字
> （`tool_calls` 与工具结果仍在上下文里）；若需要最强推理衔接，请把上下文模式切到 `full`。

### 🔥 修复：跨轮前缀缓存全量失效导致首 token 变慢（P0，TTFT 回归根因）

- **症状**：`context-mode` 切到 `balanced` 之后，长会话里首个 token 等待时间从 10 秒以内
  涨到 20~30 秒。`llm_token_usage` 中可见同一 `turn` 内相邻请求缓存命中 97%+，
  但**跨轮的第一条请求反复出现 `cache_hit_tokens = 0`**，未命中量约等于上一整轮工具循环
  产生的内容量。
- **根因**：DeepSeek 的上下文缓存按「请求前缀」命中 —— 本次 prompt 必须是某次已缓存请求
  prompt 的**真前缀**才能复用。而 assistant 消息的 `reasoning_content` 被两条路径给出了
  **不同取值**：
  - 生成它的那一轮 → `ReasoningPassback.putCurrentTurn()` **无条件真实思考链**；
  - 下一轮从 DB 重建 → `putHistory(balanced)` **空串占位**。

  同一条消息两种形态 → 下一次请求不再以上一次为前缀 → 从差异点起全部按「未命中输入」
  重新 prefill。历史越长、工具循环内容越多，重新 prefill 的量就越大。
  多个 `turn` 首条命中 96%~99% 而非 0%，是因为那些轮模型没返回思考内容（DB 中
  `reasoning` 为空），两条路径恰好都写空串。
- **修复**：确立唯一口径 —— **同一条消息在任何一次请求里必须得到相同的
  `reasoning_content` 值**：
  - `putCurrentTurn` 增加 `ContextMode` 参数，与 `putHistory` 共用同一套判定
    （`full` → 真实值；`balanced` / `compact` → 空串占位）；
  - 新增 `ReasoningPassback.modeOf(apiRequest)`，从请求内部字段 `_contextMode` 解析
    **本次请求实际生效**的模式（优先级：请求参数 > `sys_config` > 全局配置 > 默认）；
    旧实现有一处用全局配置默认值，与历史组装用的请求生效值不一致，会产出第三种形态；
  - `DeepSeekServiceImpl` 中 5 处 `putCurrentTurn` 与 1 处 `putHistory` 全部改为传入
    请求生效模式；原两参 `putCurrentTurn` 标记 `@Deprecated`（等价 `full`，仅供既有测试
    与显式 full 语义使用）。
- **验证方法**：按 `turn_id` 分组取 `MIN(id)` 作为该轮首条请求，
  修复前会出现 `cache_hit_tokens = 0`，修复后应稳定在 90%+。

### 🔥 修复：同步压缩阻塞在首 token 之前（P0，叠加放大器）

- **症状**：上下文长度刚好卡在压缩阈值附近时，每一条请求都先卡十几秒才发出。
- **根因**：`max-context-tokens` 从 100 万降到 30 万后，压缩阈值 0.85 对应 **25.5 万 token**；
  而 `ContextBuilder` 里的**同步**压缩路径没有任何防抖（`asyncPrecompress` 有 120 秒防抖，
  同步路径没有）。于是「每轮每条请求」都会先阻塞调一次 LLM（`blockingChat` 最长 60 秒）
  去做一次「压缩收益为负」的无用压缩。
- **修复**：`CompactionService.isRecentlyCompacted()` 改为 public，同步压缩前先过同一道防抖；
  刚压缩过就跳过，直接使用现有上下文。两条压缩路径共用同一个间隔常量，避免互相踩踏。

### 🐛 修复：v3 重新登录后部分页面仍显示「未登录」（P1）

- **症状**：后端重启导致 token 失效 → 跳登录页 → 重新登录成功后进入「模型配置」页，
  列表处一直写着「加载失败：未登录，请先登录」，必须手点页面「刷新」才恢复。
  技能 / MCP / 定时任务 / 文件 / 数据库 / P2P / 用户 / 菜单权限等页面同样会卡住。
- **根因**：各页数据加载闸门是「列表为空 **且没有 error** 才请求」，把 `error` 当成了
  「已加载」标记；而 `App.vue`（SPA 根组件）从不卸载，各页 `error` 是长生命周期 ref。
  一次 401 写入「未登录 / 登录已过期」后，**重新登录成功没有任何人清它**，
  闸门便永久跳过加载。
- **修复**：`http-client` 新增 `onLoginSuccess()` 事件（判定「拿到非空且与上次不同的新
  token」触发，重复写入同一 token 不触发），并在会话切换时重置 401 去重窗口；
  `App.vue` 订阅后统一清空 16 个页面残留的鉴权类错误（**保留列表数据**，避免闪空态），
  下次进入页面即自动重新拉取。

### 🐛 修复：裸 fetch 通道的 401 没有统一出口（P2）

- 上传附件（`/deepseek/upload`）、补充需求（`/deepseek/supplement`）、
  文件上传 / 云端列表 / 预览 / 下载（`/files*`）、项目原文件读取（`/project/raw`）
  使用原生 `fetch`（FormData 与二进制流必须如此），绕过了 `request` / `authFetch`
  的自动 401 处理。token 过期时只报「上传失败」/「文件加载失败（401）」，
  既不提示重新登录也不跳登录页。
- 新增 `reportUnauthorizedOn401(response)` 作为裸 fetch 通道的 401 统一出口
  （行为与 `request` 内部一致：清 token → 提示 → 跳登录页），6 处调用点全覆盖。

### ✅ 测试

- `ReasoningPassbackTest`：28 项 → **36 项**（新增 8 项），专门锁死
  「同一消息在当前轮与历史两条路径下的取值必须一致」这一缓存命中前提，
  以及 `modeOf` 的解析与缺省回落。
- `mvn compile` / `mvn test-compile` 通过，36 项断言全绿。

---

## [3.1.6] - 2026-11-15

> **重构复查版**：对 v3.1.5 的 Provider 能力档案统一重构做三轮系统复查，
> 修掉 **18 处**遗留缺陷 —— 共同特征是「**编译不报错、测试不失败、只在用户那儿暴露**」。
> 其中 6 处属于同一病根：**同一个判定被写了多份**。
> 本版**需要重启后端**；未改数据库结构，无数据迁移。

### 🔥 修复：解析入口系统性遗漏（P0，8 个消费方全中）

- **症状**：Provider 的 `code` 起成 `volcengine-custom` / `deepseek-official`
  这类「平台名在前缀」的名字后，出现一串连锁故障：
  适配器回退 `DeepSeekClient`（对话 400）、认证头退回 `Authorization+Bearer`
  （mimo / anthropic 必然 401）、计费判成 `TOKEN`（套餐制被估出虚假金额）、
  视觉拿不到模型级规则（纯文本模型发图被**静默丢弃**）。
- **根因**：8 个消费方各自照抄同一段逻辑
  `if (template == null) template = code;` + `findByTemplate(template)`，
  **全都只做精确匹配**。而后端创建 Provider 时会把 `requestTemplate` 自动填成 `code`，
  于是这类命名必然落空。
- **修复**：新增 `ProviderRegistry.resolveFor(template, code, baseUrl)` 作为**唯一解析入口**，
  优先级：① template 精确 → ② template 前缀 → ③ code 前缀 → ④ baseUrl 域名兜底。
  8 个消费方（`LLMClientManager` / `LLMWebClientManager` / `TokenUsageParser` /
  `ProviderCapabilityService` / `ProviderBalanceService` / 两个搜索客户端 /
  `DeepSeekServiceImpl`）全部改用。

### 🔥 修复：展示层与执行层「各算一遍」（P0，3 处）

界面上说「能查余额 / 能发图 / 上传云端」，点下去却是另一回事 ——
两边都"正常"，只是算的不是同一件事：

| 能力 | 展示层原用 | 执行层实际用 | 分歧后果 |
|---|---|---|---|
| 余额 | `profile.getBalance()`（名字优先） | `balanceCandidateFor`（平台顺序优先） | `template=deepseek` + 火山域名：界面说查 DeepSeek，实际查**火山账户** |
| 视觉 | `config.getRequestTemplate()` 原始串 | 档案主名 | `code=my-gateway` + 火山域名：界面说能发图，一发被**拦截** |
| Files API | `resolveFor`（带前缀兜底） | `findByTemplate`（严格） | `deepseek-custom`：界面说**上传云端**，实际只存本地 |

- **修复**：展示层不再自己算，直接调用执行层的函数。
  Files API 那处**刻意保持严格**——它决定「文件是否外发到第三方云端」，
  口径必须更严：宁可显示「仅本地」（保守但真实），
  也不显示一个不会发生的数据外发承诺。

### 🔥 修复：`default ->` 把「代码没写完」伪装成「正常结论」（2 处）

- **症状**：新增 `SearchProvider` / `BalanceProvider` 枚举值后，界面显示
  「平台官方接口暂未接入判断」—— 既不报错、也不像故障，没人会去查。
- **修复**：删掉 `default`、显式写 `case NONE`、末尾加 `throw IllegalStateException`
  并附带确切枚举名。漏补 case 从「静默降级」变为「立刻抛异常」。
- **配套**：`FilesApiClientManager` / `LLMClientManager` 的 `switch` 本就无 `default`，
  新增枚举直接**编译报错**；`AdapterType` 用枚举而非字符串类名，拼错编译期就挂。

### 🔥 修复：前端能力缓存串台 + 降级丢数据

- **串台**：实例级与模板级档案塞进同一个 `Map`，实例档案同时按 `code` 与 `template` 建索引。
  同一模板下有多个 Provider 时，`template` 键被**最后写入的实例覆盖** ——
  新建 Provider（只拿得到 template）会显示**别人那台实例的凭证状态**。
  已拆成两个 Map：实例级只按 `code`、模板级只按 `template`，串台在结构上不可能发生。
- **丢数据**：`refreshCapabilities` 用 `Promise.all`，旧后端缺 `/templates` 时
  连 `/capabilities` 的**成功数据也一起丢弃**。已改为两个请求分别 try。

### 🐛 其他修复

- **`NullPointerException`**：`LLMClientManager` 里纯日志分支
  `"custom".equalsIgnoreCase(template.trim())`，当 `template` 为 null 时
  **打挂整个对话链路**。已改为判档案。
- **`Map.copyOf` 丢失插入顺序**：`ModelCapabilityRules.configure` 用 `Map.copyOf` 存 yml 规则，
  返回无序 Map，而 `findVisionRule` 的前缀匹配**依赖遍历顺序**（注释里写了「顺序必须保持」）。
  当前平台名无互相为前缀，尚未触发；已改为 `LinkedHashMap`。
- **`matches()` 大小写不一致**：`allNames()` 会 `toLowerCase`，`matches()` 却没有。
  已对齐。
- **前端本地兜底 4 处与后端不一致**（自称「逐条对齐」）：
  火山缺 Key 判 `off`（应为 `warn`，否则用户以为「火山不能搜索」）、
  AK/SK 用「任一存在」而非「成对」（只配 AK 显示可查询、点下去必失败）、
  身份判定全用精确匹配（`volcengine-custom` 漏掉火山规则 → `v4-pro` 显示可发图）、
  MiniMax 少认 `searchApiKey`。
- **`searchHint` 文案 bug**：`warn` 分支无条件说「Base URL 不是 api.deepseek.com」。
  火山 / 千问 / MiniMax 缺 Key 也走 `warn` 后，火山用户会莫名看到一段 DeepSeek 的说明。
  已改为按平台给文案。
- **前端 `label` / `iconTone` 未用后端返回值**：后端档案已返回 `display.label` / `display.iconTone`，
  前端却用本地硬编码 map → 新增平台显示英文 template 名、配色为空。
  已改为「后端优先，本地兜底」。

### 🛡️ 为「方便新增适配器」加的守卫

- **`stripImageDetail` / `blockFormat` 是「描述性声明」**：这两个档案字段**不驱动运行时**
  （实际转换在各适配器内），原先赋值 11 次却零读取 —— 新人照文档设置后以为生效，
  实际发图 400。已在字段注释写清原因，并新增**双向包含断言**
  （`AdapterVisionMatrixTest.declaredVsActualStripDetail`）：
  档案声明集合 ⇄ 已实际验证集合，不一致即测试失败。
- **`matchesAnyPrefix` 统一前缀语义**：前端身份判定原先全是精确比较，
  后端却是 `startsWith`。已统一。

### 📚 文档

- `docs/LLM_PROVIDER_SYSTEM.md` 新增 **9.1-H 节「新增 Provider 到底要改几处」**（实测清单）：
  仅加平台 = **1 个文件**；加搜索 / 余额 = 4 个；全新适配器 = 再加 2 个；**前端恒为 0**。
  并逐项标注「漏了会怎样」（编译报错 / 测试失败 / 异常 / 静默降级）。
- `LLMClient` 类头 A5 补「解析入口只允许一处」，新增 A6 条。
- `SearchTool` 类头修正已过时的前端判据位置，补「配套还要改两处」。
- `ProviderRegistry` 为 7 个模板级便捷方法加**职责边界警告**：
  它们是精确匹配（不带兜底），生产代码必须用 `resolveFor`。

### ✅ 验证

```
mvn compile / test-compile          ✓
npm run typecheck                   ✓
npm run build                       ✓

ProviderRegistrySelfCheck          237
ProviderResolveConsistencyTest      67  ← 新增（含 F 组「展示==执行」19 项）
ProviderCapabilityServiceTest      132
AuthHeaderEquivalenceTest          107
AdapterVisionMatrixTest             55  ← +2（交叉守卫）
BalanceDispatchEquivalenceTest      46
PricingModeGuardTest                27
TokenUsageMultiProtocolTest         40
DeepSeekToolsApiClientTest          26
SearchToolLogicTest                 16
──────────────────────────────────────
合计                               753 项，0 失败
```

---

## [3.1.5] - 2026-11-15

> **架构级重构**：把「一个 Provider 有哪些能力」从**散落 4 处的副本**收敛为**后端唯一真相源**，
> 并顺带修掉 3 个一直在悄悄生效的 Bug。
> 本版**需要重启后端**；未改数据库结构，无数据迁移。

### 🔥 修复：视觉拦截对自定义 code 完全失效（P0，真 Bug）

- **症状**：把火山方舟配成 `code=huoshan` + `requestTemplate=volcengine`
  后，默认模型换成 `deepseek-v4-pro` / `v4-flash`（纯文本）**再发图不会被拦截**，
  图片被**静默丢弃**，日志里没有任何痕迹。
- **根因**：视觉规则按**平台名**注册（`deepseek` / `volcengine,ark,doubao` / `qwen,dashscope,tongyi`），
  但拦截点 `DeepSeekServiceImpl` 传的是**用户自起的 `providerCode`**：
  `findVisionRule("huoshan")` 精确不中、前缀也不中 → 返回 `null`
  → 落到全局 `defaultAllowVision=true` → 判定「支持」→ 放行。
- **修复**：改为「**模板优先、编码兜底、双键取严**」。
  `requestTemplate` 是平台真相源，`code` 反映拦截点的实际入参，两者任一判不支持即拦截 ——
  宁可多拦一次并提示换模型，也不要让图片被无声丢弃。

### 🔥 修复：套餐制 Provider 被按 token 估出「不存在的花费」（P0，真 Bug）

- **症状**：火山 Coding Plan（`/api/coding/v3`）按**次数套餐**计费，
  但成本看板按 DeepSeek 的 token 单价算出金额 —— 实测 267 次请求 / 5396 万 token
  被估出 **7.595619 元**从未真实发生的花费。
- **根因**：计费模型里**只有「按 token」一种形态**，没有「套餐 / 次数制」的概念，
  「没配单价」与「不该按 token 估价」被混为一谈。
- **修复**：新增 `PricingMode`（`TOKEN` / `QUOTA` / `FREE`）：
  - `QUOTA`（火山 `/api/coding`、MiniMax Coding Plan）→ **永不估算金额**，只展示 token 与缓存命中率；
  - `FREE`（Ollama 本地）→ 无费用；
  - `TOKEN` → 才按单价估算。
  闸门**优先级高于全局兜底价**：兜底价是「未知模型按此价估算」，不是「连套餐制也要估」的授权。

### 🔥 修复：4 条适配器漂移（配了别名 → 对话走错协议）

- **症状**：Provider 配 `template=xiaomi-mimo` / `minimax-cn` / `minimax-intl` / `minimax-global` 时，
  能力按档案判（说能发图、能查余额），**对话却跑 DeepSeek 协议**。
- **根因**：`ProviderRegistry` 声明了这些别名，但 `LLMClientManager.createClient` 的 `switch`
  只写了 `case "mimo"` / `case "minimax"`，别名**掉进 `default` 被当成 `DeepSeekClient`**。
  编译通过、测试通过，只在用户那儿暴露。
- **修复**：把**适配器类型收进档案**（`ProviderProfile.adapterType`），
  `createClient` 改为查表分派 —— 「别名」与「实现」同源，**这类漂移在结构上不可能再发生**。
  自检项 `A2 适配器漂移条数` 从 4 变成 **0**，并新增断言「曾漂移的 4 个别名必须解析到正确适配器」。
  顺带修掉 `xiaomi-mimo` 的认证头错误：旧实现给它 `Authorization: Bearer`，
  而 MiMo 要求 `api-key: <裸 key>`。

### ✨ 新增：Provider 能力接口（前端从「推导者」降级为「展示者」）

| 接口 | 用途 |
|------|------|
| `GET /api/llm-providers/capabilities` | 全部 Provider 的批量能力（含**凭证成对判定**） |
| `GET /api/llm-providers/templates` | 全部内置模板档案（新建下拉 + 无凭证预览） |
| `GET /api/llm-providers/{code}/capabilities` | 单个 Provider（精确到「缺 secretKey」） |

- 返回 `state`（`on` / `warn` / `off`）+ `label` + `hint` + `platformSupported` + `missingKeys` + `pricingMode`。
- **刻意不做 admin 校验**：返回内容不含任何密钥值（`missingKeys` 只给**字段名**），
  而聊天页也需要它判断「当前 Provider 能否发图」；若加 admin 校验，
  非管理员会看到「能力未知」，反而退化成前端猜 —— 那正是本版要消灭的第四份副本。
- 前端 `provider-capability.ts` 的 350 行推导（`VISION_TEMPLATES` + 5 段正则 + `deriveCapabilities`）
  降级为**离线兜底**（后端不可达时才用），`ProviderEditDrawer` 的 `TEMPLATE_OPTIONS`
  改为从 `/templates` 拉取 —— **新增模板时前端无需改动**。

### ♻️ 重构：能力规则 4 份副本 → 1 份

| 能力 | 重构前散落在 | 现在 |
|------|--------------|------|
| 视觉 | ① `ModelCapabilityConfig` 代码默认值 ② `application.yml` 的 `vision-rules` ③ `ModelCapabilityRules` 的 if 链 ④ 前端 `VISION_TEMPLATES` + `visionStateOf()` | `ProviderRegistry` 的 `vision`（yml 降为**可选覆盖层**） |
| Files API | `FilesApiClientManager.createClient` 的 switch | 档案的 `filesApi` |
| 联网搜索 | `SearchTool` 的 if 优先级链 + 3 个 ApiClient 各自的身份判定 | 档案的 `search` |
| 余额 | `ProviderBalanceService` 的 5 个 `isXxx` + baseUrl host 兜底猜测 | 档案的 `balance` + `BALANCE_DETECTION_ORDER` |
| 适配器选型 / 认证头 | `LLMClientManager.createClient` 的 switch + `LLMWebClientManager` 的**两个** switch | 档案的 `adapterType` + `auth` |

- **认证头两个 switch 合并为一个 `auth` 声明**：旧实现里「改头名没改前缀」会发出
  `x-api-key: Bearer sk-xxx`（多一个 Bearer），「改前缀没改头名」会发出
  `Authorization: sk-xxx`（少 Bearer），两者都表现为 401 但看不出原因。
  合并后一处声明两个字段必须成对出现，结构上不可能只改一半。
- 刻意保留一处历史行为：`ollama` / `custom` 前缀为 null 时**发送裸 key**（不是不发送）。
  旧注释写的是「跳过认证头」，但代码实际是发裸 key —— 本版按**代码行为**固化，
  并写进 `AuthHeaderEquivalenceTest` 断言，避免有人「按注释修 bug」造成行为变更。

### 🧪 测试

新增/更新 9 个 main 方法式测试，共 **681 项断言全绿**：

| 测试 | 断言数 |
|------|--------|
| `ProviderRegistrySelfCheck` | 237 |
| `ProviderCapabilityServiceTest`（新） | 129 |
| `AuthHeaderEquivalenceTest`（新） | 107 |
| `AdapterVisionMatrixTest` | 53 |
| `BalanceDispatchEquivalenceTest`（新） | 46 |
| `TokenUsageMultiProtocolTest` | 40 |
| `PricingModeGuardTest`（新） | 27 |
| `DeepSeekToolsApiClientTest` | 26 |
| `SearchToolLogicTest` | 16 |

其中 `BalanceDispatchEquivalenceTest` 的「顺序陷阱」组保留了 6 个反直觉的既成事实
（如 `template=deepseek` + 火山域名 → 走火山余额），确保迁移不「顺手优化」。

### 📄 文档

- `LLMClient` 类头 A/B/C/D 四组清单重写：A5 从「加 `case`」改为「在 `ProviderRegistry` 登记档案」，
  C 组从「副本必须同步」改为「唯一真相源对照表」。
- `docs/LLM_PROVIDER_SYSTEM.md` 第 9.1 节（新增 Provider SOP）同步改写，
  并明确标注：**不要再**在 `createClient` 加 `case`、**不要**在 `LLMWebClientManager` 加认证头分支 ——
  两处已改为查表分派，加了不生效，只会让「声明」与「行为」重新分裂成两份。

### ⚠️ 升级提示

- **必须重启后端**：`ProviderRegistry` 是纯静态类，运行中的 JVM 不会重新加载。
- 旧版前端（`frontend/`）的 `BALANCE_TEMPLATES` 等遗留副本**已标注废弃**，未做改造 ——
  旧版没有能力接口消费层，改了也只是又多一份必然漂移的副本。

---

## [3.1.4] - 2026-11-14

> **3.1.3 的补丁版**：修复上一版**降级分支写了却永远不生效**的严重缺陷。
> 3.1.3 号称「火山等未适配 Files API 的 Provider 也能传图、不上传云端」，
> 但因前置的 `getFirstProviderCode()` 兜底猜测未删除，`client == null` 这个分支**从未被执行**——
> 用户在火山方舟下粘贴的图片，实际仍被静默上传到 **DeepSeek 官网云端文件库**。
> 本版把「猜 Provider」这一模式从全部 5 个入口彻底移除，并修复余额显示的「张冠李戴」。
> 纯后端 + 前端逻辑修复，**需重启后端**；未改数据库结构。

### 🔥 图片上传：删掉「猜 Provider」（P0，真 Bug）

- **症状**：用户在火山方舟（`volcengine`）下粘贴图片，数据库记录为
  `provider_code=deepseek`、`storage_type=cloud`、`file_id=file-api-xxx` ——
  图片被送进了**用户并未选择的平台**的云端文件库，既消耗别家额度，也是数据外发。
- **根因（3.1.3 的实际漏洞）**：降级逻辑的**前置条件**没处理干净。3.1.3 的代码是：

  ```java
  // 3.1.3 —— 降级分支写了，但永远进不去
  String code = (providerCode == null || providerCode.isBlank())
          ? llmClientManager.getFirstProviderCode()   // ← 猜成 id 最小的 Provider（deepseek）
          : providerCode;
  FilesApiClient client = filesApiClientManager.resolveClientByCode(code);
  if (client == null) {                 // ← resolve("deepseek") 返回非 null，此分支死代码
      return uploadLocalImage(...);
  }
  ```

  前端 `uploadFileAsset()` 当时**没有传 `providerCode`**，于是后端一路猜到 `deepseek`，
  `resolveClientByCode("deepseek")` 返回可用的 DeepSeek 客户端，降级分支形同虚设。
- **修复原则**：「**用户没选，就不许替他选**」。上传图片是把用户文件送出本机的操作，
  口径必须比对话更严。
  - `upload`：未指定 Provider → **直接本地存储**（`storage_type=local`、`file_id=null`），不触碰任何云端；
  - `uploadLocalBytes`（截图工具等内部入口）：同样不再猜，未指定即本地存储；
  - 明确指定但未适配 Files API（如火山 `volcengine`）→ 仍是本地存储 + base64 注入。
- **顺带修掉 3 处同类隐患**（这几个更危险，都是「猜错 Provider」直接作用在账户级数据上）：
  - `listCloudFiles`：云端文件是**账号级视图**，猜错会把别家平台的文件当成自己的列出来。
    现要求必须显式指定 Provider，否则明确报错而不是默默查错账号；
  - `deleteCloudFile`：**删除不可逆**，猜错 Provider 会删到别家平台的文件。同样要求显式指定；
  - `supported()`：能力判断兜底成 `deepseek` 时界面显示「支持 Files API」，
    而用户实际在用火山（不支持）—— 这正是「界面说能传、实际传不了/传错平台」的源头。
    未指定时返回 `supported=false` + `providerCode=null`。
- **前端配套**（`frontend-v3/src/App.vue`）：
  - 新增 `chatUploadProviderCode`，只取「用户明确切换过的」或「Agent 明确绑定的 Provider」，
    **不含 `isDefault` / 列表首个兜底**（本项目两个 Provider 的 `is_default` 都是 0，
    兜底结果就是 id 最小的 deepseek）；
  - 粘贴（`source: 'paste'`）与上传（`source: 'upload'`）两处都补传该值；
  - 云端文件查询同样改为显式传 Provider，未确定时提示「请先选择 Provider」。
- **验证要点**：重启后粘贴图片，`file_asset` 应出现 `storage_type=local`、`file_id=null`。

### 💰 余额显示：切换 Provider 后不再「张冠李戴」

- **症状**：切到火山方舟后，界面上显示的仍是 **DeepSeek 官网的余额**。
- **两个独立原因，缺一不可**：
  1. **火山余额本身查不到**：`huoshan` 的扩展配置为空（`extra_config={}`），
     而火山余额走费用中心 `QueryBalanceAcct`，需要**账号级 AccessKey/SecretKey + V4 签名**
     （与方舟 API Key 是两套凭据）→ 必然抛异常；
  2. **前端把旧值留在了界面上**：原逻辑 `catch` 后一律「保留上一次成功值」，
     于是切 Provider + 查询失败 = 界面一直挂着一个**属于另一个 Provider** 的数字。
- **修复**：区分两种情况——
  - 同一个 Provider 查询失败（网络抖动 / 后端临时不可用）→ **保留旧值**，避免余额凭空消失；
  - **切换了 Provider 之后失败** → **清空**，绝不显示别家的数字。
- **注意**：该接口查的是**账户资金余额**，不是 Coding Plan 套餐剩余次数；
  想看套餐额度请到火山控制台。缺 AK/SK 时正确行为是「显示为空」而非报错弹窗。

### 🔧 升级说明

- **需重启后端**（配置与逻辑变更）；未改数据库结构，无需迁移。
- 桌面端需重新打包（jar 内含新逻辑与新前端 `v3-static`）。
- 存量脏数据：此前被传到 DeepSeek 云端的图片（`file_asset` 中 `storage_type=cloud`
  且 `provider_code=deepseek` 的记录）不会自动迁移，如不需要可在文件页删除。
- 想看到火山余额需自行配置：Provider「扩展配置」填入账号级 `accessKey` / `secretKey`。

---

## [3.1.3] - 2026-11-14

> 视觉与稳定性修复版：修掉三处**「界面与后端都说不行、其实早就行」**的能力误判
>（火山方舟的 `deepseek-v4.1-flash` 明明是原生多模态，却被 `deepseek` 前缀一刀切拦下），
> 打通「Provider 没适配 Files API 就完全不能传图」的死路，并针对**长上下文场景的两个断连根因**
> 动刀：响应头超时 120s 太短、上下文上限配成 100 万导致压缩永不触发。
> 后端 + 两套前端 + Electron 均有改动，**需重启后端**；未改数据库结构。

### 🖼️ 视觉能力判定：三处误判修复（核心）

- **火山方舟（真 Bug，用户可感知）**：旧规则 `exclude-prefixes: ["deepseek"]` 把**所有** `deepseek*` 模型
  一刀切判为不支持图像。但方舟上的 deepseek 系早已分化 —— 实测（直接向 `/api/coding/v3` 发 `image_url` 块）：

  | 模型 | `prompt_tokens` | 结论 |
  |---|---|---|
  | `deepseek-v4.1-flash` | 35 → **224**（图片真实计入） | ✅ 支持 |
  | `deepseek-v4.1-flash-economy` | **218**（图片真实计入） | ✅ 支持 |
  | `deepseek-v4-pro` | 仅 **86**，模型自称「没有图片提供」 | ❌ 纯文本 |
  | `deepseek-v4-flash` | 图片被静默丢弃 | ❌ 纯文本 |

  改为「版本号 include + 纯文本支 exclude」：`include: ["vision","-vl","vlm","v4.1"]`、
  `exclude: ["v4-pro","v4-flash"]`。⚠️ `excludeKeywords` **优先于** `includeKeywords`，
  且 `v4.1-flash` 不含连续子串 `v4-flash`，因此 economy 版不会被误伤。
- **通义千问**：旧规则「`qwen-plus` / `qwen-max` 一律纯文本」已随模型换代失效 ——
  百炼文档明确写「推荐模型：`qwen3.7-plus`（**支持图片理解**）、`qwen3.6-plus`（**支持图片理解**）」。
  补 `plus` 关键词；`qwen-max` 各代差异大，**不确认就不放行**，避免「界面说能发图、实际图片被丢弃」。
- **小米 MiMo**：补 `omni` 关键词（全模态系列此前被漏判为纯文本）。
- **前后端双重防线同步**：`ModelCapabilityConfig`（后端拦截，`DeepSeekServiceImpl` 抛
  `IllegalStateException`）与 `frontend-v3/src/utils/provider-capability.ts`（界面三态）
  必须逐条对齐，任一侧漏改都会出现「界面显示可发图、后端直接报错」或反之。本次两处同步修改。

### 📤 图片上传：Provider 未适配 Files API 时不再死路

- **背景**：`FileAssetService.upload` 与 `uploadLocalBytes` 在 `FilesApiClientManager` 返回 `null` 时
  **直接抛异常**（「当前 Provider 不支持 Files API，无法上传」）。而 Files API 只适配了
  `deepseek` / `anthropic` 两个模板 —— 也就是说用**火山方舟**（`volcengine`）或第三方兼容中转时，
  **图片根本传不上去**，多模态能力形同虚设。
- **修复**：新增 `uploadLocalImage`，两个上传入口在 `client == null` 时降级为**仅本地存储**
  （`storage_type=local`、`file_id=null`），**不向任何平台的云端文件库上传用户图片**。
  注入阶段由 `FileVisionService.buildFileBlocks` 的 `supportsFiles=false` 分支读取本地副本转
  base64 `image_url` 块（沿用原有 32MB 总大小上限与块缓存）。
- **删除安全**：`delete()` 已有 `isLocalStorage` 分支，local 资产跳过远端调用，不会产生孤儿远端文件。
- **`uploadLocalBytes` 同步改造**：`remote` 改为可空，补偿删除、`storageType` 判定、日志均按
  是否真正上传过云端分支处理（截图工具等内部入口同样受益）。

### ⏱️ 长上下文断连修复（两条根因）

- **根因一：`responseTimeout` 120s 太短**（`LLMWebClientManager`）。该参数管的是
  **「发出请求 → 收到响应头」**，不是流式整体时长。上下文涨到十几万～几十万 token 时，
  共享套餐池（如火山 Coding Plan）仅 prefill + 排队就常超过 2 分钟，Netty 直接掐断连接，
  前端表现为「莫名其妙断连」（日志多为 `PrematureCloseException` / `ReadTimeout`）。改为 **300s**，
  与 `AbstractLLMClient.DEFAULT_STREAM_TIMEOUT` 对齐；响应头到达后由业务层流式超时接管。
- **根因二：`max-context-tokens: 1000000` 让压缩永不触发**。压缩阈值是按本值算百分比的
  （`0.7` / `0.85` / `0.95`），配 100 万意味着 **70 万 token 才预警、85 万才压缩**。
  实际表现是上下文一路涨、压缩机制从未启动，直到每次请求都要上传数 MB JSON 而次次断开。
  改为 **300000**（约 21 万预警 / 25.5 万压缩 / 28.5 万丢弃），与主流编码 Agent 单请求的
  实际承载能力匹配。⚠️ 该值是「**可稳定跑通的上限**」，不是模型窗口上限。

### 🔧 升级说明

- **需重启后端**（配置项与常量变更）；未改数据库结构，无需迁移。
- 桌面端需重新打包（jar 内含新逻辑）。
- 若此前因图片上传报错而改用别家 Provider，升级后可切回火山方舟并正常发图。
- 长上下文场景建议**开新会话**验证：旧会话已积累的超大上下文不会因上限下调而自动缩水。

---

## [3.1.2] - 2026-11-14

> 成本治理版本（二）：上一版把成本变成看得见的数字，这一版是对着数字动刀。
> 用真实用量定位到两个**纯浪费**来源：**压缩口径错误导致每轮都重写前缀**（缓存反复全量失效），
> 以及**工具结果体积失控**（单条上限 3 万字符，且历史组装期还在回溯改写）。
> 后端改动，**需重启后端**；桌面端需重新打包。未改数据库结构。

### 🔥 缓存失效根因修复（P0）

- **背景**：实测 6 轮对话 777 次请求，累计输入 1.158 亿 token、成本 ¥5.62，命中率 97.87% 看似很高，
  但其中 **15 次压缩里有 9 次造成全量未命中**，约 130 万 miss token（≈¥1.3）纯属浪费。
- **根因**：异步预压缩的 token 估算**直接累加 DB 全量原文，忽略已落库的压缩记录**。
  历史压缩后原文仍留在库里，于是估算值只增不减（实测 ratio≈1.65 恒定超过 0.60 阈值），
  每次请求返回都触发一次压缩 → `replaceWithCompaction` 把 `messages[1..N]` 整段换掉 → 前缀全变 → 缓存全失效。
- **修复**：估算改为「应用压缩记录 → 转 API 消息 → 估算」三步，与实际请求口径对齐（ratio 从 1.65 降到 0.05）。
- **配套四条硬约束**：
  - TOOL 消息的 API `content` 取自 `reasoning` 字段（工具结果），**不受 `stripHistoryReasoning` 影响** ——
    把它当思考链剥掉会严重低估上下文（工具结果才是大头）；
  - 传给 `compact()` 的列表必须与内部「重查库 + 应用压缩记录」的列表**逐条对齐**，否则会压错区间；
  - 压缩收益为负（摘要 ≥ 原文）直接跳过，不落库、不改前缀；
  - 新增最小压缩间隔 **120 秒**，保证压缩后至少几轮能吃到缓存。
- **验证**：`CompactionEstimateTest` 25 项断言，含用真实数据形态复现「ratio 恒超阈值」。

### 📉 工具结果体积预算（P1）

- **背景**：实测 1624 条工具结果共 466 万字符，是上下文增量的绝对大头。原实现单条上限 3 万字符，
  且阈值在 `ToolExecutor` 与 `ContextBuilder` 各写一套 —— 规则一旦漂移就变成「落库 A 形态、发给 LLM B 形态」。
- **统一收口**：新增 `util/ToolResultBudget` + `tool.result-budget.*` 配置，**按工具分级**设预算
  （`file_explorer` / `web_fetch` / `browser` 16000 字符；`command` / `web_search` / `execute_sql` / `http_request` 10000；
  其余 12000，`<= 0` 表示不限制）。分级依据是**信息密度**：日志 90% 是噪声，代码是刚需，用同一把尺子裁等于两头都错。
- **写入前执行一次**：由 `ToolExecutor#buildToolMessages` 调用，**落库与发 LLM 是同一份内容**；
  `ContextBuilder` 的超限兜底复用同一实例，只对存量超预算数据生效一次，不做二次改写。
- **幂等**：截断文本带 `…[CC-TRUNC]` 标记，已带标记的内容原样返回 —— 这是「前缀缓存可持续命中」的前提。
- **带续读指引**：截断处附「如何取回被省略部分」（`file_explorer` 的 `start_line`/`end_line`、
  `command` 的 `action=logs`、`execute_sql` 的 `WHERE`/`LIMIT`）。**静默砍掉一半内容**
  会让模型基于残缺信息下结论或盲目重跑同一工具，那比不截断更贵。
- **应急开关**：`tool.result-budget.enabled: false` 一键回退全部行为。

### 🔍 grep 输出：修 Bug + 窗口合并

- **行号 Bug（真 Bug）**：旧实现给 `contextBefore` / `contextAfter` 的**每一行都写同一个行号**，
  输出形如「5 - A / 5 - B / 5 - C」。模型按行号去读文件会读到**错的位置**，
  而输出看起来又像「大量重复内容」，掩盖了真实原因。现改为逐行递增的真实行号。
- **窗口重叠合并**：每个命中都带 `context_lines` 行上下文，命中密集时相邻窗口大量重叠，
  同一行被重复输出 2~5 次。实测 `file_explorer` 结果占总量的 68%，其中 grep 类 **286 条共 149 万字符**，
  超过一半是窗口重叠造成的重复。现按文件分组用「行号 → 内容」去重后输出，不连续处插 `...`。
- **收益特点**：在**源头**减半且**不丢信息**（被去掉的本就是重复行），因此不会增加轮次 ——
  这正是它比「事后截断」更划算的原因。

### 📊 预期效果与观测方式

- 工具结果体积：存量数据按新预算可再省约 **28 万字符（6%）**；新产生的 grep 结果因窗口合并可再降 **30~50%**，
  综合约 **10~15%** 的上下文输入。
- 更关键的是**停止失血**：压缩不再每轮触发，缓存命中率从「反复全量失效」回到「稳定命中」。
- 观测：成本看板看 `cache_miss_tokens` 是否从「每次几万~十几万」降到「几百~几千」；
  日志出现 `工具结果超预算已裁剪: tool=...` 即为预算生效。
- **验证**：新增 `ToolResultBudgetTest`（34 项断言）、`GrepMergeTest`（20 项断言），覆盖幂等性、分工具预算、
  续读指引、行号正确性与窗口合并边界。

### 🔧 升级说明

- **需重启后端**（新增配置项与 Bean）；未改数据库结构，无需迁移。
- 桌面端需重新打包（jar 内含新逻辑）。

---

## [3.1.1] - 2026-11-14

> 计量与定位版本：把「成本可见」从 DeepSeek 一家扩展到**全部 Provider**（三套 usage 协议 + 按 Provider 精确开启），
> 未配单价的模型一律**不估算**（显示「—」，而不是错误的 ¥0）；给「新增 Provider」立了一份 A/B/C/D 四组适配清单，
> 把「能聊天但成本全空 / 发图 400 / 余额按钮灰掉」这类**静默缺功能**挡在合并之前；聊天里的文件路径与网址改为
> **可点击直达**，右侧文件树与 Git 面板新增搜索框。后端 + 两套前端 + Electron 均有改动，**需重启后端**；
> 桌面端需重新打包（新增两个系统打开相关 IPC）。

### 💱 多 Provider 用量采集与计价（核心）

- **背景**：v3.0.9 的成本看板其实只对 DeepSeek 真实可用——其他 Provider 的流式响应里根本没有 usage，
  于是「成本可见」在最需要它的地方（按量计费更贵的第三方模型）反而是空白。根因是**协议不同**：
  有的在单帧返回、有的必须请求时开启开关、有的把输入输出拆在两帧里。
- **协议分派**：`LLMClient` 新增 `getUsageProtocol()`（`openai` / `anthropic` / `ollama`）与 `getRequestHeaders()`；
  `AbstractLLMClient` 新增 `requiresStreamUsageOption()` + `buildIncludeUsageOption()`；`TokenUsageParser` 重写为
  `parseFrame(previous, sseData, protocol)`，支持**跨帧合并**（返回 `UsageSnapshot`，新增 `cacheCreationTokens`、
  `protocol` 字段）。
- **各 Provider 实测行为与对策**：
  - DeepSeek：默认返回 usage（含 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`），行为不变；
  - OpenAI 官方（`gpt` 模板）：早已开启 `stream_options.include_usage`，本次改为走统一钩子，不再在适配器里重复拼装；
  - 千问 / 百炼、火山方舟、MiniMax、MiMo：**默认都不返回**，必须显式开启 `stream_options.include_usage` —— 四处全部补齐；
  - Anthropic：格式完全不同（`message_start` 只给输入侧、`message_delta` 给输出侧），走 `anthropic` 协议跨帧合并；
  - Ollama：NDJSON 收尾行 `done=true` 携带 `prompt_eval_count` / `eval_count`，走 `ollama` 协议；
  - `openai` 通用模板（第三方中转站 / vLLM）：**刻意不下发** `stream_options` —— 严格校验未知字段的网关会直接 400，
    代价是这类服务多数拿不到 usage。**宁可没有数字，也不能把主链路打挂**。
- **计价策略（行为变更，重要）**：未在 `llm.pricing.models` 配单价的模型**一律不估算**，不再回退到默认价。
  历史实现给 GPT / Claude 按 DeepSeek 的价算，**错误的成本比没有成本更危险**——它会让人误以为「换个模型也不贵」。
  - `TokenPricingConfig.resolve()` 未匹配返回 `null`；`PriceItem` 新增 `cacheWriteInput`（Anthropic 缓存写入）。
  - `LlmTokenUsage.costEstimate` 改为可空 `Double`；三个汇总 VO 新增 `pricedRequestCount`，
    `TokenUsageSummaryVO` 新增 `breakdownAvailable`；Mapper 聚合 SQL 统一补 `COUNT(cost_estimate)`。
  - 前端新增 `isCostAvailable()`：无定价时金额段整块不渲染（显示「—」），费用结构三段条按 `breakdownAvailable` 隐藏。
- **Anthropic 语义修正**：`input_tokens` **不含**缓存读写，总输入 = `input_tokens` + `cache_read` + `cache_creation`；
  缓存写入单独按 `cache-write-input` 计价。顺带补上主链路缺失的 `anthropic-version` 请求头（缺了直接 400）。
- **采集补全**：**无工具纯聊天路径**原先完全没有 usage 采集（只有工具循环在记账），本次补齐采集 + `doFinally` 落库。
- **验证**：新增 `TokenUsageMultiProtocolTest`（40 项断言），覆盖三套协议的帧解析、跨帧合并、缓存字段语义、缺价返回 null。

### 📘 新增 Provider 适配清单（可维护性）

- **背景**：每接一个 Provider 要改的地方散在十几处，最坑的是**漏了不报错**——对话能跑通，但成本看板没金额、
  发图 400、余额按钮灰掉、联网只剩 Bing。这类问题不会在开发时暴露，只会在用户那里暴露。
- **`LLMClient` 类头重写为「适配唯一总入口」**，按后果分四组：A 必写（不做跑不通）、B 能力开关（不做不报错、
  静默缺功能）、C 散点速查（真正要改的代码在另外 4 个类里）、D 前端与测试（决定用户看不看得到）。
- **`AbstractLLMClient` 类头**补「钩子总表」（默认值 / 何时覆写 / 漏了会怎样）+ 9 个适配器 × 6 个钩子的现状矩阵。
- **`LLMClientManager.createClient`** 加「注册三件事」注释（case 分支、选型表补行并写清「不要用于」、前端下拉）。
- **反向引用到散点**：`FilesApiClientManager`（B6 文件上传）、`SearchTool`（B7 联网搜索）、
  `ProviderBalanceService`（B8 余额查询）、`ModelCapabilityRules`（B5 图片 / B9 输出上限）各自类头标注所属清单项。
- **前端**：`provider-capability.ts` 头部列出「必同步 5 处」；`ProviderEditDrawer.vue` 的模板下拉补上遗漏的 **MiMo**。
- **文档**：`docs/LLM_PROVIDER_SYSTEM(_EN).md` 第 9.1 节重写为完整 SOP（含提交前 9 项自检清单）。
  **代码注释与文档不一致时以代码为准**——注释在改代码的人眼前，文档不在。

### 🔍 文件搜索与路径直达

- **后端新增 `GET /api/project/search`**（`root` / `keyword` / `limit`）：忽略大小写匹配文件名或相对路径，
  排序四档优先级（文件名全等 > 文件名前缀 > 文件名包含 > 仅路径包含），同级按路径长度升序；
  复用既有排除规则（`node_modules` / `target` / `.git` / 静态资源前缀等），扫描上限 50000 文件，
  默认返回 100 条（最多 300），关键字超 200 字符截断。
- **两套前端文件树都加了搜索框**：v3 为 250ms 防抖 + 递增序号丢弃过期响应 + Esc 清空，结果显示「文件名 + 所在目录」，
  点击后**先退出搜索再打开**（否则搜索视图会一直盖住文件树，表现为「点了没反应」）；
  经典版走后端全量搜索，点击复用既有 `openLocalFileTab` 链路。
- **为什么不前端过滤**：v3 文件树按目录懒加载、经典版树深上限 10 层，本地过滤必然漏掉深层文件，
  表现为「有时搜得到、有时搜不到」——这比搜不到更糟。
- **Git 面板用本地过滤**：变更数量通常几十个，无需请求，且不能改变「全部暂存 / 取消暂存」的全量语义
  （显示 `N/M` 计数区分「工作区干净」与「无匹配」）。
- **路径可点击**：v3 `utils/file-path.ts` 重写为**三级解析**——绝对路径直接读 → `/project/resolve` 后缀匹配
  （容忍 LLM 只给出路径片段）→ `/project/search` 按文件名兜底；兼容去行号 `:12`、包裹引号 / 反引号 / 尖括号、
  `.../` 与 `…/` 省略前缀、`file://`。
- **Markdown 渲染**：`renderFileList` 给「看起来像路径」的行加 `data-cc-path` 可点击链接（清单里的说明文字不套链接，
  避免点到不存在的文件）；行内代码中的 http(s) 网址还原为外链。
- **Electron 新增 IPC**：`shell:openPath` / `shell:showItemInFolder`，主进程校验**必须是已存在的绝对路径**，
  `shell.openPath` 返回空串才算成功；渲染进程新增 `utils/shell.ts` 统一封装；`CodeEditor` 增加 `actions` 具名插槽，
  文件视图接入「用默认程序打开」「在文件夹中显示」（带失败 toast 兜底）。
  **安全边界**：路径来自 LLM 输出，不能把 `shell.openPath` 当万能打开器。
- **验证**：新增 `ProjectSearchTest`（15 项断言），覆盖四档优先级、大小写、三类排除目录、空 / null / 根不存在、limit 收敛。

### 🎨 成本看板可发现性与主题适配

- **经典版菜单点不到（Bug）**：`sys_menu` id=19 写成 `menu_type='SETTING'`，而经典版侧栏只请求
  `LINK / AI / TOOL / SYSTEM / MANAGE` 五组 —— 页面和路由都在，分组却永远不渲染。
  `schema.sql` 改为 `SYSTEM`，并附**幂等迁移 UPDATE**（带 `name='成本看板'` 条件，避免 id 被复用或管理员手工调整被覆盖）；
  旧版 `Layout.vue` 的 `iconMap` 补 `BarChartOutlined`。
- **暗色 / 背景图下配色错乱（Bug）**：两套页面都引用了**不存在的变量**（`--bg-1` / `--bg-2` / `--primary`），
  永远走 fallback 写死色。v3 改用 `--card` / `--bg-sub` / `--card-2` / `--border-strong` / `--accent` / `--ok` / `--warn` / `--danger`，
  骨架统一为 `.cc-page-scroll → .cc-page-inner → .cc-page-head`；经典版改用 `--bg-card` / `--bg-hover` / `--text-*` / `--border` / `--accent`，
  并补 `html.has-app-bg` 下的毛玻璃与暗色覆盖。
- v3 新增 `i-chart` 图标，成本看板菜单图标由 `i-log` 改为 `i-chart`（原来用的是日志图标，语义不符）。

### 🧹 输入区信息分层（体验调整）

- 经典版聊天输入区：余额 / 上下文 / 会话用量从 footer 移到输入框上方**独立一行**——原来与发送、附件等 5 个控件
  挤在 `flex-wrap: nowrap` 的行里，窗口一窄就被压变形。
- 两套前端统一为「**上下文 → 余额 → 消耗 ¥x · N tokens → 缓存 x%**」，11px、圆点分隔、数值加粗。
- 术语由「会话」改为「**消耗**」：会话是范围，消耗是金额，前者容易被理解成「会话数」。
- 余额标签保留按 Provider 区分（额度 / 余额），避免 MiniMax 这类 quota 型 Provider 显示错误文案。
- 清理 `.balance-chip` / `.context-tokens` / `.session-usage` 三套已无引用的遗留样式。

### ✅ 验证

- 后端 `mvn -q compile -DskipTests` 通过。
- v3 `npx vue-tsc --noEmit -p tsconfig.app.json` 零错误、`npm run build` 通过。
- 经典版 typecheck 无新增错误、`npm run build` 通过。
- 单测：`TokenUsageMultiProtocolTest` 40/40、`ProjectSearchTest` 15/15。

### 📚 文档与版本号

- 版本号同步：`pom.xml`（产物 `code-craft-3.1.1.jar`）、经典版 / v3 前端 `package.json` + lock、
  Electron `version` 与 `extraResources` jar 路径、MCP Server 握手版本 `CodeCraftMcpServer.SERVER_VERSION`。
- `ARCHITECTURE(_EN)` 第 15 条扩展为「usage 三协议 + 未配单价不估算」；
  `LLM_PROVIDER_SYSTEM(_EN)` 第 9.1 节重写为新增 Provider 完整 SOP。
- 8 份深度文档版本头、`DEV_QUICKREF` / `RELEASE_CHECKLIST(_EN)` 打包命令示例、`FAQ` 版本示例、
  `README(_EN)` 徽章 —— 统一到 v3.1.1。
- 顺手清理 `ARCHITECTURE(_EN).md` 首部累积的 73 / 66 个重复 BOM（历史编辑残留，不影响渲染但污染 diff）。

### ⬆️ 升级说明

- **后端需重启**：新增 `/api/project/search` 接口；成本采集与计价逻辑变更。
- **桌面端需重新打包 / 重新安装**：新增两个系统打开相关 IPC（旧包上对应按钮自动隐藏，不会报错）。
- **金额显示变化（预期内）**：只有 DeepSeek 内置了单价。切到 GPT / Claude / 千问 / 火山 / MiniMax 等 Provider 时，
  聊天页与成本看板金额显示「—」属**设计行为**（不是采集失败）。需要看金额就按 `application.yml → llm.pricing.models`
  里的注释模板填写实际单价；**不要**为了「让数字显示出来」而填一个 `default` 兜底价。
- **老库无需手工改表**：成本看板菜单类型由启动时执行的 `schema.sql` 幂等 UPDATE 自动修正。
- 无破坏性配置变更。

---

## [3.1.0] - 2026-11-14

> 成本可见性版本：v3.0.9 让成本「事后可查」，本版让它「边用边可见」——每条 AI 回复下方**常显**
> 本轮成本 / 缓存命中率 / token，输入区底部显示整会话累计。配套后端 `turn_id` 按轮归并、
> 删除会话级联清理账单，并把「上下文模式」入口从设置页收敛到聊天页模型选择器。
> 后端 + 两套前端均有改动，**需重启后端**（新增 `turn_id` 列与 `/api/token-usage/turns` 接口）；桌面端需重新打包。

### 💰 每轮对话成本展示（新增）

- **背景**：v3.0.9 的成本看板是「事后查阅」——聊天过程中看不到本轮花了多少，也就谈不上「边用边省」。
  更关键的是，工具循环会把**一次提问**拆成多次 API 请求（编译失败自动重试、多步文件编辑常见 5~10 次），
  只按 `iteration` 记录时用户看到的是一串碎片数字，而不是直觉里的「这一轮多少钱」。
- **归并口径**：前端每次提问生成 `turnId`（与代码快照、「重新生成」的删除范围复用同一个 id），随
  `ChatRequest.turnId` 下发 → 后端存入 `llm_token_usage.turn_id` → 按 `turn_id` 聚合工具循环内的多次请求。
  **存量数据不参与**：v3.0.9 及更早的记录没有 `turn_id`，硬塞进某一轮会让该轮成本虚高，故只计入会话 / 时间维度汇总。
- **后端实现**：
  - `schema.sql`：`llm_token_usage` 新增 `turn_id VARCHAR(64)` 与 `idx_usage_turn (conversation_id, turn_id)`；
    附 H2 `ADD COLUMN IF NOT EXISTS` 与 MySQL 兜底两条迁移语句（老库自动升级，无需手工改表）。
  - `LlmTokenUsageMapper`：新增 `summarizeByTurn`（`GROUP BY turn_id`，`ORDER BY MIN(id)` 保证按发生顺序返回）、
    `deleteByConversationId`、`insertWithoutTurn`。
  - `TokenUsageParser.toEntity()` 增加 `turnId` 参数；`DeepSeekServiceImpl.persistTokenUsageSafely()` 取其
    `apiRequest._turnId`（与快照 / 重新生成同源），启动时补一次 `turn_id` 列兜底。
  - **降级路径**：带 `turn_id` 的 insert 失败会自动改为不带该列写入——老库迁移失败时宁可失去「按轮展示」，
    也不能让整条成本记账静默中断（成本看板会变成空表）。
  - 新增 `TokenUsageTurnVO` + `TokenUsageService.turnUsage()`（含 `cacheHitRate` 计算）+
    `GET /api/token-usage/turns?conversationId=`。
- **前端（两套同步：`frontend/`、`frontend-v3/`）**：
  - 每条 AI 回复底部**常显**「本轮 ¥x · 缓存 x% · x tokens」；工具循环发起多次请求时追加轮次徽章
    （hover 可见「共发起 N 次请求，已合并计入本轮」）。刻意不做 hover 显隐——成本要能被看见才会被优化。
  - 输入区底部新增「会话 ¥x · 缓存 x% · x tokens」，与既有「上下文 X」并排但**语义不同**：
    前者是「这个会话历史累计烧了多少」，后者是「这一屏要送多少」。
  - 布局上用量在左、操作按钮在右：按钮 hover 渐显时只向右挤占空白，不会把数字来回推动；
    外层 `min-height` 预留行高，避免有无用量时消息高度抖动。
  - `App.vue` 统一拉取与分发（`sessionUsageMap` / `turnUsageMap`），加载历史消息与每轮结束后各刷一次；
    删除会话、草稿转正式会话时同步清理与迁移缓存，避免数字张冠李戴。
  - 匹配不到 `turnId` 时整块用量不渲染（而不是显示「¥0」），不给用户假信息。
- **写入时序处理**：后端在 Flux `doFinally` 里才落库，前端收到 done 事件时该记录可能还没写入 →
  采用「立即拉一次 + 800ms 延时补拉一次」，补拉仅在「这一轮仍无数据」时触发，不产生无谓请求。

### 🗑️ 删除会话时级联清理成本记录（行为变更）

- **背景**：成本看板按会话分组，原设计「刻意不加外键，会话删除后历史账单仍应保留」用于长期核算；
  但实际使用中，删除会话后看板会留下 `会话#N` 幽灵条目——点不进去（会话已不存在），也无从判断是谁的。
- **变更**：`ConversationServiceImpl.deleteConversation()` 显式调用 `deleteByConversationId()`
  （独立 try/catch，清理失败不影响会话删除本身）；`SystemController` 的「重置所有数据」表清单
  加入 `llm_token_usage`。
- **影响**：删除会话 = 同时删除该会话的成本明细，跨会话的时间 / 模型维度汇总不再包含已删除会话的消耗。
  需要长期成本审计的话，请在删除前先导出。

### ⚙️ 上下文模式入口收敛（体验调整）

- 设置页移除「本机偏好 → 上下文模式」整块——它与聊天页模型选择器写的是同一份 `settingsStore.contextMode`，
  两处入口容易让人误以为可以按页分别设置，实际互相覆盖。
- 唯一入口改为聊天页「模型选择」弹层（`ChatModelPicker.vue`），三档 `full` / `balanced` / `compact` 与后端
  `ContextMode` 枚举同源；聊天页底部的「⚡ 精简」徽章仍随 `compact` 显示，口径不变。

### ✅ 验证

- 后端 `mvn -q compile -DskipTests` 通过。
- v3 `npx vue-tsc --noEmit -p tsconfig.app.json` 零错误；经典版 typecheck 无新增错误。
- H2 实测 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 可用；开发库确认 `turn_id` 列已就绪。

### 📚 文档与版本号

- 版本号同步：后端 `pom.xml`（产物 `code-craft-3.1.0.jar`）、经典版 / v3 前端 `package.json` + lock、
  Electron `version` 与 `extraResources` jar 路径、MCP Server 握手版本 `CodeCraftMcpServer.SERVER_VERSION`。
- 文档：README(_EN) 版本徽章；ARCHITECTURE(_EN) 新增「按轮成本归并」关键约定；TOOL_SYSTEM(_EN) /
  DEEPSEEK_SERVICE_IMPL(_EN) / LLM_PROVIDER_SYSTEM(_EN) 版本标注；DEV_QUICKREF / RELEASE_CHECKLIST(_EN)
  打包命令示例；FAQ 版本示例 —— 统一到 v3.1.0。

### ⬆️ 升级说明

- **后端需重启**：启动时会自动为老库补 `turn_id` 列（失败则降级为不按轮展示，不影响对话）。
- **桌面端需重新打包 / 重新安装**：安装包内嵌的 jar 已更新（新增接口与 DDL）。
- **存量会话**：v3.0.9 及更早产生的成本记录没有 `turn_id`，历史消息下方不会出现「本轮」用量，
  但会话级汇总（输入区底部）仍会包含它们。
- 无配置项变更，无需迁移 `application.yml`。

---

## [3.0.9] - 2026-11-13（2026-11-14 重新构建）

> 成本治理 + 长任务体验双主线版：四项成本/上下文治理（上下文模式三档、思考档位归一、能力声明配置化、Token 成本看板）+
> 一项架构级执行模型改造（长耗时命令后台化，编译/装依赖/下载不再被超时砍断）+
> 真终端 PTY（Electron 端从「模拟终端」升级为真实 PTY）+ 两个用户可感知问题修复（下载无法取消、v3 登录态失效）+ 工具合并瘦身。
> 后端 + 两套前端 + Electron 均有改动，**需重启后端**；桌面端需重新打包（新增 node-pty 原生依赖）。
>
> ⚠️ **2026-11-14 重新构建**：修复本版引入的 **thinking 模式 400 回归**
> （`The reasoning_content in the thinking mode must be passed back to the API.`）—— 任意上下文模式
> （full / balanced / compact）下只要触发工具调用，续跑请求即失败，新建会话亦无法规避；详见下方「紧急修复」小节。
> 安装包已按修复后代码重新产出，**版本号保持 3.0.9**，请下载本页最新资产。

### 🐛 紧急修复：thinking 模式 reasoning_content 缺失导致 400

- **现象**：v3.0.9 起，DeepSeek thinking 模式下只要模型发起工具调用（`tool_calls`），
  续跑请求即返回 400：`The reasoning_content in the thinking mode must be passed back to the API.`；
  切换 full / compact 模式、新建会话均无法规避。
- **根因（三层叠加，解释了「切模式 / 换 UI / 新窗口 / 关思考都不管用」）**：
  1. **认知错误**：v3.0.9 依据「官方文档称该字段属 Beta、仅用于 Chat Prefix Completion」判定
     「普通多轮无需回传」，于是 `balanced` 直接**不下发该字段**。但 DeepSeek 对该字段是**强校验**：
     任一 assistant 消息缺少 `reasoning_content` 即 400 —— **且不看思考开关**，因为模型本身是思考模型。
  2. **逻辑错误**：原判定写成 `reasoning != null && !reasoning.isEmpty() && !strip`（**与**关系），
     因此即使 `full` 模式，只要某轮模型没返回思考内容（长工具链后段模型常只返回裸 `tool_call`），
     字段依旧被跳过 → 同样 400；`compact` 模式更是直接 `remove("reasoning_content")` 无条件删除 →
     必然复发。
  3. **关闭思考场景必炸**：关闭思考时模型天然产不出思考内容，字段永远是空的。
     v3.0.8 靠「回传历史 reasoning」还能给字段兜住，v3.0.9 一旦剥离就露馅；
     新会话没有历史可回传，于是「新旧会话、开关思考、切换模式」四种组合全部报错。
- **修复（关键认知：字段必存在，只有「值」可优化）**：
  - 新增 `util/ReasoningPassback` 全局收口回传规则：**字段始终下发**，只按场景决定值 ——
    本轮工具循环内用**真实思考内容**（保持思维链连续），历史消息按模式取
    **真实值（full）/ 空串占位（balanced、compact）**。
  - 实测与业界实现一致：**空串可通过校验**，token 成本仅 3~5 个，比回传整段思考链便宜三个数量级。
  - `ContextBuilder` 历史组装与 `compactHistoryMessages` 改为「置空不删字段」。
  - `DeepSeekServiceImpl` 6 处、`AgentForkManager` 1 处工具循环消息统一改走 `ReasoningPassback`。
  - **发送出口兜底（本次真正生效的关键）**：`LLMClient` 新增
    `ensureReasoningContent(requestBody)`，`AbstractLLMClient` 实现为「按 Provider 能力补齐空串、
    copy-on-write 不覆盖真实值」。DeepSeek / MiniMax / Qwen / Volcengine / MiMo 开启；
    OpenAI 官方 / Anthropic / Ollama 不注入未知字段。
    > ⚠️ 踩坑点：主链路与工具循环续跑都是 `webClient.post().bodyValue(requestBody)` **直接发送**，
    > **不经过** `buildRequestBody` / `streamChat`。只把兜底挂在适配器基类上等于没生效，
    > 必须在 `DeepSeekServiceImpl` 的两处真实发送点显式调用（无工具路径 + 工具循环 `handleStreamingPhase`）。
  - `ContextBuilder` 返回前增加兜底扫描：压缩 / 裁剪 / 摘要化等路径漏写也不会再触发 400。
  - `LLMErrorFormatter` 对含 `reasoning_content` 的 400 给出精准提示（不再笼统显示「请求体格式错误」）。
- **验证**：`ReasoningPassbackTest`（28 项断言）+ `ReasoningPaddingTest`（25 项断言）全绿；
  `mvn compile` 通过。

### 🧩 上下文模式三档（新增）

- **背景**：此前上下文治理只有「是否剔除历史 reasoning」一个布尔开关，无法在「省 token」与「保前缀缓存」之间取舍——粗暴压缩历史会让前缀缓存 O(历史) 全量失效（实测缓存命中 98% 时，压缩带来的回本周期长达 100~150 轮）。
- **新机制**：`ContextMode` 枚举三档——`full`（全量，调试用）/ `balanced`（默认：只剔 reasoning，工具结果原样保留）/ `compact`（激进：摘要工具结果，会致一次缓存失效，适合超长会话）。
- **关键设计**：`balanced` 在**构建阶段就不写 reasoning**，而非事后裁剪——前缀天然稳定，每轮零额外失效；`compact` 才改写工具结果（明确告知会触发一次缓存重建）。
- **实现**：`ContextBuilder` 按模式分流；`DeepSeekServiceImpl` / `AgentForkManager` / `CompactionService` 统一走枚举；`DeepSeekConfig.resolveContextMode()` 兼容旧的 `stripHistoryReasoning` 配置（自动映射到 full / balanced），老配置无需迁移。

### 💰 Token 成本可观测（新增）

- **背景**：此前只有「感觉费 token」，没有任何量化依据——不知道钱花在哪个模型、哪类任务、输入还是输出。
- **新机制**：`llm_token_usage` 表记录每次调用的 prompt / completion / cached / reasoning token，配合 `llm.pricing` 计价配置算出金额；两套前端各增「成本看板」页（按月/按模型/按会话聚合）。
- **采集精度**：SSE 采集点放在 `doOnNext`（**避开 `takeWhile` 截断**，否则末尾 usage 帧会被吞），落库放在 `doFinally`（正常结束/异常/取消都能落账）。
- **配置化计价**：`TokenPricingConfig` 管理各模型单价（含缓存命中价），新增模型只改 `application.yml`。
- **实测结论**：缓存命中率 98% 时，缓存命中单价仅 0.025 元/百万，只占总成本约 13%——真正的成本大头在**输出侧**（reasoning 双重计费 9 元/百万）与**新增侧**（工具结果体积），据此定下了本版的方向。

### 🧠 思考档位统一归一化（修复 + 重构）

- **真 Bug**：MiniMax 适配器把 `thinking_low` 误判为 disabled——`startsWith("thinking")` 粗判 + 大小写敏感的档位匹配，导致低档位思考被静默关闭。
- **根因**：各适配器各自解析档位字符串，每新增一档就得改一批适配器，漏一个就是静默降级。
- **重构**：`AbstractLLMClient.normalizeThinkingMode()` 统一归一为 `none / low / medium / high / max` 五档，8 个适配器（DeepSeek / MiniMax / Gpt / Anthropic / MiMo / Qwen / Volcengine / Ollama）只处理规范值；未知档位回退到 `medium` 并 WARN，不再静默变 disabled。

### ⚙️ 模型能力声明配置化（重构）

- **根因**：推理模型前缀、effort 支持、输出上限、视觉能力此前硬编码在 `GptClient` / `OpenAIClient` / `FileVisionService` 里，新增模型必须改代码发版。
- **重构**：新增 `ModelCapabilityRules` + `ModelCapabilityConfig`，`llm.model-capabilities` 配置推理模型前缀（含 `gpt-6`）、`effort-none/minimal` 支持、输出 token 上限、视觉能力规则；三处调用方改为查规则表。
- **收益**：新增模型只改 `application.yml`，不动代码。

### 🔒 前缀缓存保护（新增）

- **背景**：DeepSeek 前缀缓存命中价仅为未命中的 1/40，但**任何 system 或 tools 定义的变化都会让整个前缀失效**——这类回退没有报错、只有账单变贵，极难察觉。
- **新机制**：`ContextFingerprint` 在每轮请求前比对 `system + tools` 的哈希指纹，一旦变化立即 WARN 并打印差异摘要，把「静默变贵」变成「日志可见」。

### 🧰 工具定义瘦身（重构）

- **GitTool**：`git_status` / `git_diff` / `git_log` / `git_add` / `git_commit` / `git_push` 六个工具合并为 `git`（按 action 分发，纯委托不改行为），`normalizeToolNames()` 兼容旧工具名，历史会话与自定义 Agent 无需迁移。
- **DesktopTool**：`screenshot` + `desktop_control` 合并为 `desktop`（2→1）。
- **收益**：工具定义（每轮都进 system prompt）体积下降，且不与缓存前缀打架。
- **配套**：`ToolExecutor.validateRequiredParams()` 必填参数前置校验（缺参直接返回可读错误，不再把参数错误当业务异常烧一轮 token）；系统提示词新增 15 行「工具选择边界表」，明确同类工具的分工。

### 🖥 真终端 PTY（新增，Electron）

- **背景**：此前终端是「模拟终端」——每次回车都走一次 `/project/exec` 同步 HTTP，无法跑交互式程序（vim / top / 脚手架向导），输出靠轮询拼接。
- **新机制**：`electron/pty-manager.js` 基于 `node-pty` 实现真实伪终端（会话池上限 8、create / write / resize / kill 全生命周期、**输出只回推创建者**避免多窗口串流、渲染进程销毁自动回收、cwd 路径 clamp 校验）。
- **链路**：`preload.js` 暴露 `electronAPI.pty`；`main.js` 接入 `initPtyManager()` 并在 `before-quit` 调 `disposeAllSessions()`（否则残留 shell 会拖住退出）；`package.json` 补 `node-pty` 依赖 + `files` + `asarUnpack`（原生模块不能进 asar）。
- **前端**：两套 `TerminalPanel.vue` 双模式——Electron 走 PTY（含 `fit` 同步 resize、切目录 kill 重开、退出后按键重连），浏览器降级回 `/project/exec`（PTY 不可用不白屏）。
- **验证**：Electron 33 实测 `PTY_RESULT:OK pid=9980`；`node-pty` 1.1.0 走 N-API，**无需 electron-rebuild**。
- **注意**：`node-pty` 自带 win32 / darwin prebuilds，**无 linux prebuild**——Linux 打包机需具备 node-gyp 工具链。

### ⏳ 长耗时命令后台化（新增，执行模型改造）

- **背景**：`command(action=exec)` 此前是「同步阻塞直到超时被杀」——`mvn package` 这类命令默认 120s 上限，跑到第 2 分钟被强制终止，**前面几分钟的依赖下载/编译全部白费**；期间整个 Agent 停摆，模型不能读文件、不能分析代码；失败后只能从头再跑一次。
- **新执行模型**：exec 启动进程后**前台最多等 20 秒**（编译/依赖/构建类命令识别为 10 秒），超时**不杀进程**，转后台任务继续跑，立刻把控制权还给模型：
  ```
  命令耗时超过 10 秒，已转后台继续执行（进程不会中断）
  任务 ID：7　已运行：10s　状态：运行中
  当前输出（最后 15 行）：...
  【下一步】logs 看进度 / wait 阻塞等 / stop 中止
  ```
- **新增 `action=wait`**：阻塞等待任务结束（默认 300s / 最大 600s），结束返回完整输出 + 退出码，未结束返回尾部并可再次 wait——长构建可分多段等，中间穿插别的活。
- **去重保护**：同工作目录 + 同命令已在运行时不重复启动，直接返回「已在运行（任务 #N，已运行 45s），不要重复执行」——否则模型会重复下发，两个 mvn 抢 `.m2` 锁 / 爆内存 / 产物互相覆盖。
- **任务池分离**：一次性长任务（上限 10）与长期服务（上限 5）分开计数，20 分钟的 mvn 不再占掉 `npm run dev` 的槽位。
- **输出治理**：转后台只给尾部 15 行；`logs` 默认尾部 50 行（`tail=0` 取全量）；**失败时自动抽取关键错误行**（`[ERROR]` / `Caused by` / `BUILD FAILURE` / `npm ERR` 等）再附尾部 30 行——避免 1MB 截断恰好切掉中间的关键报错。
- **结果可回看**：任务结束后输出**保留 30 分钟**（原为 5 分钟，且会被 `cleanupFinishedServices()` 直接删掉），期间随时可 logs / wait 回看。
- **可回退**：`command.exec.background-after-seconds`（默认 20）设为 `0` 或负数即完整回退旧的「同步阻塞 + 超时终止」行为。
- **配套**：`CommandTool.getDescription()` 重写（讲清「转后台不是失败」「不要重复执行」「用 wait/logs 跟进」）；系统提示词新增《长耗时命令（编译/装依赖/下载/构建）》7 条规则。
- **验证**：`CommandToolBackgroundTest` 13 项断言全绿（短命令零变化 / 长命令转后台不杀进程 / 去重拦截 / wait 拿到退出码 / list 可见）。

### 🖱 下载无法取消（修复）

- **现象**：点击「取消下载」后 UI 显示已取消，但后台仍在重试下载，重试耗尽后才真正停下——期间网络与磁盘持续被占用。
- **真因**：`update-manager.js` 的取消只是置了一个标记，`for` 重试循环从不检查它，只有全部重试失败后才退出。
- **修复**：引入 `cancelRequested` 令牌 + `CANCELLED` 错误码，重试循环每轮先检查令牌、命中立即终止且**不再重试**；`VersionCard.vue` 把「成功 / 失败 / 已取消」三终态分开提示（此前取消被渲染成失败）；经典版 `SystemSettingView.vue` 同步补 `cancelled` 分支与 loading 兜底。

### 🔐 v3 登录态失效（修复）

- **现象**：v3 在 token 被服务端吊销后，页面仍停留在业务界面，接口持续 401 但没有任何跳转。
- **真因**：`http-client.ts` 的 `fireUnauthorized()` 依赖外部注册的 handler，handler 尚未注册（如页面初始化早期）时直接 return，401 被静默吞掉；且路由 `afterEach` 没有兜底复查。
- **修复**：`fireUnauthorized` 在 handler 缺失时改为 `location.hash` 兜底跳转登录；`router/index.ts` 的 `afterEach` 增加漏网校验（`isTokenRevoked()` 仍为真且处在业务页则强制回登录）。

### 🏗️ CI 构建失败（修复）

- **现象**：v3.0.9 推送 tag 后 `build-windows` 与两个 `build-macos` job 均在「Electron 打包」步骤失败（`build-linux` 正常），`create-release` 被跳过，没有产出任何安装包。
- **真因**：`electron-builder` 默认开启 `npmRebuild`，会对生产依赖里的原生模块执行 `@electron/rebuild`。本轮新增的 `node-pty` 因此被 `node-gyp` 强制重编译，而构建机没有可用的 Visual Studio / 编译链，直接失败：
  `preparing moduleName=node-pty arch=x64` → `Error: Could not find any Visual Studio installation to use` → `node-gyp failed to rebuild '.../node_modules/node-pty'`。
- **修复**：`electron/package.json` 的 `build` 字段新增 `"npmRebuild": false`。`node-pty` 1.1.0 基于 N-API（ABI 跨 Electron 版本稳定），且自带 `prebuilds/win32-x64`、`prebuilds/win32-arm64`、`prebuilds/darwin-x64`、`prebuilds/darwin-arm64` 预编译产物，**无需针对 Electron 重建**；Linux 无 prebuild，仍由 `npm install` 阶段的 node-gyp 就地编译。
- **加固**：workflow 的 Linux 依赖步骤显式安装 `build-essential` + `python3`（node-pty 无 linux prebuild，必须依赖本机编译工具链）；Windows / macOS 打包步骤改为 `set -o pipefail` + `tee` 落日志，并新增 `if: failure()` 的日志 artifact 上传——GitHub Actions 的日志页未登录不可见，避免后续排查再次受阻。
- **验证**：把 v3.0.9 tag 干净克隆到临时目录，补入 Maven 产物与 jlink JRE 后按 CI 原命令复跑：日志显示 `skipped dependencies rebuild  reason=npmRebuild is set to false`，成功产出 `CodeCraft Setup 3.0.9.exe`，且 `app.asar.unpacked/node_modules/node-pty/prebuilds/win32-x64/pty.node` 已正确解包。

### 🔢 版本号同步

- 后端：`3.0.8` → `3.0.9`（`pom.xml`，产物 `code-craft-3.0.9.jar`）
- 经典版前端：`3.0.8` → `3.0.9`（`frontend/package.json` + lock）
- v3 前端：`3.0.8` → `3.0.9`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.9` / `code-craft-3.0.9.jar`
- MCP Server 握手版本：`3.0.8` → `3.0.9`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注与更新日期（内容同步本轮变更）、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例，统一到 v3.0.9

### ⬆️ 升级说明

- **含后端改动**：需重启后端服务（上下文构建、LLM 适配器、工具层、成本采集均有变更）；两套前端 dev 模式热更新即生效，打包版需重新构建。
- **新增数据库表**：`llm_token_usage`（`schema.sql` 已含建表语句，启动自动创建）；`sys_menu` 新增成本看板菜单（id=19）。
- **桌面端需重新打包**：新增 `node-pty` 原生依赖并配置了 `asarUnpack`，旧安装包不包含该模块；Electron 33 + node-pty 1.1.0（N-API）无需 electron-rebuild。
- **配置项变更**（全部有默认值，不配也能跑）：
  - `deepseek.context-mode`：`full` / `balanced`（默认）/ `compact`；旧 `stripHistoryReasoning` 自动映射
  - `llm.pricing.*`：各模型单价（含缓存命中价）
  - `llm.model-capabilities.*`：推理模型前缀、effort 支持、输出上限、视觉能力
  - `command.exec.background-after-seconds`（默认 20）/ `background-after-build-seconds`（默认 10）：设为 `0` 可回退旧的超时终止行为
- **行为变化提醒**：`command(action=exec)` 超时**不再杀进程**，而是转后台；`exec` 的 `timeout` 参数语义改为「仅对 wait 生效」。依赖「超时即中止」的脚本请显式改用 `command action=stop`。

---

## [3.0.8] - 2026-11-11

> 修复与自动化版：四个用户可感知的问题修复（v3 切换供应商无效、v3 新会话附件失效、会话列表不按活跃排序、任务清单编号跨轮续号）+
> 一项自动化增强（项目知识库任务结束自动维护，`.codecraft/` 从「要模型主动建」变为「写盘任务结束自动建/更新」）。
> 后端 + v3 前端改动，需重启后端生效；v3 前端打包版需重新构建。

### 🔧 v3 切换模型供应商无效（修复）

- **现象与真因**：v3 聊天页切换 Provider 后，请求仍发往旧端点（DeepSeek 报 `The supported API model names are deepseek-flash, deepseek-v4-pro, but you passed GLM-5.3-Flash`）——模型名切过去了、端点没切。根因是「前端只写 code、后端只认 id」的字段错位：v3 的 `onChatModelApply` 应用时只写回 `providerCode` 漏传 `providerId`，写回成功后请求不再携带动态 code，后端 `DeepSeekServiceImpl` 解析链的 Agent 绑定层**只认 `providerId`**，于是按残留的旧 providerId 选中旧端点、配上新模型名。
- **修复一（前端）**：`frontend-v3/src/App.vue` 的 `onChatModelApply` 按 code 反查 Provider id，与 `providerCode` 成对写回（对齐经典版 `saveRuntime` 与 `onAgentSave` 的既有约定）。
- **修复二（写入侧）**：`AgentConfigServiceImpl.updateRuntime()` 收到 `providerCode` 时以它为语义源同步 `providerId`——只传 code 按 code 反查 id（查不到清空绑定），空串视为「跟随默认」清空 providerId，从根上杜绝两字段错位。
- **修复三（读取侧兜底）**：`DeepSeekServiceImpl` Agent 绑定解析改为 **providerCode 优先（须已启用）> providerId > 第一个可用**——code 是 UI 每次切换都写的语义源，优先按 code 选型可自动纠正历史脏数据，此前切换「失败」的 Agent 无需重新配置即自愈。

### 🖼 v3 新会话输入框附件失效（修复）

- **现象**：新会话页面上传文件无任何反馈、粘贴截图完全无效；但会话进行中一切正常。
- **真因**：`ChatView.vue` 存在两个 `ChatComposer` 实例（空状态分支 / 会话中分支），空状态分支漏绑三个绑定——`@paste`（粘贴事件根本到不了处理函数）、`:attachments`（上传成功后附件 chips 永不渲染，看起来像失败）、`@remove-attachment`。上传链路本身（隐藏 file input → Files API）是通的且不依赖会话 id。
- **修复**：空状态 `ChatComposer` 补齐三个绑定，与会话中分支完全对齐；纯模板层修复（props/emits 原本就已声明）。

### 📚 项目知识库任务结束自动维护（新增）

- **背景**：v3.0.7 的设计铁律是「绝不自动创建任何目录」，`.codecraft/` 完全依赖模型显式调用 `project_map action=bootstrap`——而系统提示写的是「默认不建」，绝大多数任务都不会触发，知识库形同虚设。
- **新机制**：本轮对话出现写类工具（`file_writer` / `execute_sql`）时，工具循环结束后**异步执行一次增量 bootstrap**——`.codecraft/` 不存在则自动创建，已存在则只更新变化模块（幂等：无变化零写入，保留 annotate 手工区）。纯聊天轮次零开销。
- **实现**：`ProjectContextProvider` 新增脏标记（按会话）与 `maintainAfterTask()`（boundedElastic 异步、同 root 防重入、全链路 try-catch 不影响对话）；`DeepSeekServiceImpl` 两处挂钩——工具结果汇聚点 `appendToolResultMessages` 检测写类工具置脏，工具循环结束 defer 触发维护；子 Agent 收集分支因独立会话 ID 不置脏而强制维护一次。
- **开关**：`codecraft.project-context.auto-maintain`（默认开），关闭后回退「仅模型显式调用才落盘」的旧行为。

### 📋 会话列表按最新活跃排序（修复）

- **真因**：`ConversationMapper` 四个列表查询全按 `ORDER BY created_at DESC`（创建时间）——老会话刚聊完也停在创建时的位置；且 v3 前端只在**新建会话首轮**结束后刷新侧栏，老会话每轮结束根本不刷新。
- **修复**：四个查询改为 `ORDER BY updated_at DESC`（每轮对话开始 `ContextBuilder` 都会刷新 updated_at，数据源本就正确）；前端放宽为**每轮对话结束后都刷新**侧栏，最新活跃会话实时置顶（对齐经典版行为）；顺带清理 `wasNewSession` 死变量。

### 🔢 任务清单编号规范（修复）

- **现象**：每轮对话新建任务清单时编号接着上一轮编（T1-T4 → T5-T8 → T9-T11），看似「续上」旧清单。
- **真因**：数据本就按会话隔离、`create` 是整体替换（每轮一个新清单成立），但任务 id 由模型自行填写、工具端不校验——上一轮完成后模型参考上下文里的历史编号接着编。
- **修复**：`TaskManagerTool` 新增 `validateTaskIds` 强制编号恰好为 `T1..Tn` 连续（不缺号、不重号——顺带补上此前缺失的重复 id 校验，重号会导致 `complete` 误标记；乱序允许，分组与依赖才决定顺序），不合规返回带纠正示例的错误引导模型重置 id 与 `depends_on` 后重试；工具描述与参数说明同步注明「每次 create 都是全新清单，编号从 T1 开始」。

### 🔢 版本号同步

- 后端：`3.0.7` → `3.0.8`（`pom.xml`，产物 `code-craft-3.0.8.jar`）
- 经典版前端：`3.0.7` → `3.0.8`（`frontend/package.json` + lock）
- v3 前端：`3.0.7` → `3.0.8`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.8` / `code-craft-3.0.8.jar`
- MCP Server 握手版本：`3.0.7` → `3.0.8`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注与更新日期（内容同步本轮五项变更）、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例，统一到 v3.0.8

### ⬆️ 升级说明

- **含后端改动**：需重启后端服务（`AgentConfigServiceImpl` / `DeepSeekServiceImpl` / `TaskManagerTool` / `ConversationMapper` 均有变更）；v3 前端打包版需重新构建，dev 模式热更新即生效。
- **无数据库结构变更**：`llm_provider` / `agent_config` / `conversation` 表结构不变；此前切换供应商「失败」的 Agent 无需重新配置——providerCode 优先的选型会自动纠正 providerId 残留。
- 项目知识库自动维护默认开启，不想让 AI 自动建库可在 `application.yml` 把 `codecraft.project-context.auto-maintain` 置 `false`。

---

## [3.0.7] - 2026-10-21

> 能力扩张版：三项「让模型真会干活」的能力同时上线 —— 项目知识库（模型能读懂项目结构与团队约定）、
> 浏览器自动化（模型能真的操作网页，而不只是截屏猜坐标）、踩坑经验检索重构（踩过的坑真能被召回）。
> 工具总数 27 → 29，新增 `project_map` 与 `browser` 两个工具，含后端、Electron 主进程与两套前端改动，
> 需覆盖安装并让后端随客户端一起重启。

### 🗺 项目知识库 .codecraft/（新增）

- **解决的问题**：模型进新项目要从零 grep 摸索「代码在哪、这模块干什么」，十几轮工具调用才能定位；团队约定（构建命令、禁区）散落在对话里，换会话就丢。
- **目录契约**：项目根新增 `.codecraft/`，只有 4 类文件 —— `INDEX.md`（模块索引，给人看）、`map/<slug>.md`（模块详情：职责 + 代表符号 + 依赖方）、`rules.md`（团队约定，key-value 条目）、`.state.json`（机读状态：git head、文件指纹、stale 标记、规则命中数）。整目录进 `.gitignore`，定位为**本地会话加速层**，不产生 diff 噪音。
- **双模式自适应**：代码文件占比 ≥10% 走**代码模式**（按文件数阈值聚类出模块，抽模块内代表符号）；低于 10% 走**文档模式**（按 Markdown 标题层级输出文件清单 + 章节），纯需求 / 文档目录同样能建库。
- **5 个动作**：`project_map` 提供 `status`（HEAD 变了没、哪些模块 stale）/ `bootstrap`（建库或增量重建）/ `read`（按模块或关键词取详情）/ `annotate`（模型给模块写一句话职责）/ `rule`（团队约定的增删改查）。
- **增量重建**：二次 `bootstrap` 先比对文件指纹，**无变化零写入**（不重写 map / INDEX / state，避免在 Git 仓库里刷无意义 diff）；有变化只重建 stale 模块（文件数变化 / mtime 更新 / 新增 / 详情缺失），复用旧模块状态，并清理已消失模块的详情文件。
- **3 个注入点**（统一在 `ProjectContextProvider`，只读、绝不自动建目录）：① 会话常驻摘要 ≤1100 字符（技术栈 + 构建命令 + 模块目录表）；② 写文件成功后按所属模块追加 ≤120 字符提示；③ 技能加载时追加 `skill.*` 项目专属规则 ≤800 字符。
- **规则有闸门**：key 前缀白名单 16 类（`build.` / `style.` / `arch.` / `avoid.` / `flow.` / `domain.` / `link.` / `know.` / `risk.` / `test.` / `deploy.` / `ops.` / `req.` / `doc.` / `skill.`），非白名单拒写；单条 ≤400 字符、活跃规则 ≤60 条；`update` 不传 `evidence` 时自动沿用旧证据、命中数不清零。
- **实测**（CodeCraft 自身 718 个文件）：聚出 30 个模块，常驻摘要 669 字符，全量建库 2.7s；文档模式 4 篇需求文档 253ms 出清单。19/19 验证用例通过（首次建库、二次零写入且 mtime 全未变、改 1 个文件只重建 1 个模块、annotate 语义在增量后保留、规则证据与命中数保留、技能规则按预算截断）。

### 🌐 浏览器自动化 browser（新增）

- **补齐能力断层**：原有 `screen_capture` / `desktop_control` 是 AWT Robot 像素级操作（截图 + 猜坐标），`web_fetch` / `web_search` 只读正文，三者之间缺「DOM 语义级」这一层 —— 网页交互只能靠数格子点坐标。
- **驱动通道**：新增 `electron/browser-bridge.js`（约 900 行），在 Electron 主进程起一个只绑 `127.0.0.1` 的 HTTP 服务，把 Java 侧的 `{method, params}` 直接转发给 `webContents.debugger.sendCommand`。**这是关键取舍**：`webContents.debugger` 本身就是 CDP 客户端，Java 侧只需一个 HTTP 转发，不必自己实现 WebSocket（省约 300 行）；也不引入 Playwright / Selenium —— 那要额外下载浏览器，与 214MB 安装包的体积约束冲突。
- **握手发现**：bridge 启动后写 `<userData>/runtime/browser-bridge.json`（port + token + pid），Java 侧按三级候选探测（`-Dapp.userdata`、其父目录、项目根），请求带 `x-cc-token` 鉴权；`BrowserBridgeClient` 对回环地址强制 `NO_PROXY`（否则全局代理会把 `127.0.0.1` 请求吞掉）。
- **19 个动作**：`open` / `navigate` / `snapshot` / `click` / `fill` / `select` / `press` / `hover` / `scroll` / `wait` / `screenshot` / `console` / `eval` / `tab` / `show` / `hide` / `close` / `status` / `list`。
- **`snapshot` 是省 token 的核心**：不返回 HTML，而是注入采集脚本（`resources/browser/snapshot.js`，约 435 行）输出紧凑文本，按「可交互 + 可见 + 有语义文本」过滤，通常 20~60 个元素 + `e1..eN` 引用号 —— 比截图省一个数量级，模型也不用猜坐标。
- **穿透与降级**：递归遍历 open shadowRoot（4 层）与同源 iframe（3 层），iframe 内元素标 `[iframe内]`，坐标按执行时的 `frameElement` **动态**累加（用快照旧值会点偏，因为点击前会滚动宿主）；跨域 iframe 读不到内部 DOM，则把 iframe 本身作为占位元素输出，支持 `click ref=e5 rx=0.7 ry=0.3` 按比例点进去。
- **真实事件而非模拟赋值**：`click` 走 `Input.dispatchMouseEvent` 两段式（先 `scrollIntoViewIfNeeded`，失败回退 `el.click()`）；`fill` 走 `focus` + `select` + `Input.insertText` 三段式，校验 `value` 不符再回退原生 setter + `input` / `change` 事件；`press` 用 `dispatchKeyEvent` 而非 `insertText`（后者不触发 keydown，站点收不到回车提交）。
- **多标签**：同一会话下每个标签是一个独立窗口、共享同一 session partition（登录态按项目根走），`/cdp` `/show` `/close` `/events` 均支持 `tabId`；事件游标按 `conversationId::tabId` 隔离，否则新标签的报错会被旧标签的游标整段吞掉。
- **权限与安全**：新增 `OperationCategory.BROWSER`，`@ToolPermission(highRisk = true)`，首次授权后按会话级「全部同意」免打扰；`snapshot` / `status` / `list` / `console` / `wait` / `screenshot` 与 `tab op=list` 走 `SideEffectFreePreflight` 免授权。安全兜底保留 5 条：密码框值恒输出 `***`、只放行 http/https（`file:` 能绕开 `PathSecurityChecker`）、下载默认 deny、`eval` 默认拒绝需显式开启、全量审计。

### 🖥 浏览器状态可视化（两套前端）

- 新增 `BrowserSessionController` 三个接口：`GET /api/browser/sessions`（标签列表 + 活跃标签 + 可用性）、`POST .../show`（显示 / 隐藏）、`POST .../close`（单关或全关）；bridge 不可用时返回 `available=false` 而不报错。
- v3：聊天页顶栏新增地球图标入口（仅有标签时渲染 —— 常驻空按钮会让人以为功能坏了）+ 下拉面板（标签列表 / 显示 / 关闭 / 全部关闭）。
- 经典版：聊天区顶部新增 `.ai-browser-bar` 状态条 + 展开面板，走普通流布局不遮挡消息，含暗色主题适配。
- 轮询策略：4 秒一次，仅在本会话有标签时运行，标签归零即停，非 `/chat` 路由跳过；`tool_call_start` 出现 `browser` 时立即刷新一次，不等下一次轮询。

### 🔎 踩坑经验检索重构

- **先体检再动手**：通读整条链路（`doSearch` → 精确码 / 模糊召回 → 参数过滤 → 排序 → 输出）后定位到 4 个结构性缺陷，而不是按清单逐点修补。
- **缺陷 1：环境兼容分压过相关度**。原 `sortByEnvCompat` 把环境兼容（3 档离散分）当最终排序键，一条「同环境但完全不相关」的经验能压掉「异环境但高度相关」的经验。改为**加权总分**：错误码精确 3.0 / 参数一致 2.0 / 相关度 2.5 / 环境兼容 0.8 / 历史有效率 0.5 / 本项目 0.4 / 新鲜度 0.3 / 命中量 0.2 —— 环境从「一票否决」降为轻量加分。
- **缺陷 2：五字段平权、无词频抑制**。原 `countMatchedWords` 对 symptom / root_cause / solution / keywords / goal 一视同仁，`失败` `error` 这类高频词能把无关条目拉上来。改为**字段权重 × IDF × 覆盖率**：symptom 3.0 / keywords 2.5 / goal 2.0 / root_cause 1.5 / solution 1.0；IDF 用**候选集内部统计**（候选通常 1~20 条，零额外 SQL，压制效果与全库 IDF 同量级）。
- **缺陷 3：查询侧无同义扩展**。中文描述查英文经验（「端口占用」vs `address already in use`）直接漏召。新增 `QueryAliasExpander` + `application.yml` 约 50 组人工白名单别名，在拆词后做查询侧扩展；用人工表而非 LLM 生成，是为了同一查询每次扩展出同一批词，可复现、可 A/B。
- **缺陷 4：精确码命中即短路**。命中 1 条就停，结果数不足 Top3 也不再补。改为命中数 < 3 时用 symptom 前 N 词补跑模糊，按 id 合并去重。
- **可解释性**：`LessonRanker` 输出 `reasons`，每条结果附「为何召回」（最多 4 项，如「错误码精确命中」「symptom 命中 3 个词」「参数一致」），让模型能判断这条经验到底适不适用。
- **可回滚**：`lesson.ranker.enabled` / `lesson.query-alias.enabled` 两个开关关闭后行为与改造前完全等价。
- **实测**：对照集排序由 `[502, 503, 501]` 变为 `[501, 502, 503]`；20 条候选排序 < 3ms、200 条 < 30ms、20 条 × 6 万字解法 < 20ms；30/30 验证用例通过。

### 🧩 工具注册与提示词

- 工具总数 27 → 29：新增 `project_map`（`@ToolPermission(category = READ, isPathSensitive = true)`）与 `browser`（`BROWSER` / `highRisk`）；`OperationCategory` 新增 `BROWSER`，`ToolRegistryController` 补中文分类标签。
- `application.yml` 新增两块配置：`codecraft.project-context.*`（约 30 项：注入预算 / 扫描限制 / 模块划分阈值 / 注入开关）与 `codecraft.browser.*`（域名白名单 / 视口 / 超时 / 截图 / 控制台事件上限）。
- `prompts/code_agent_prompt.txt` 新增「项目知识库」「浏览器自动化」两段及对应工具白名单，`deepseek.tool-groups` 同步加 `project_map` / `browser`；经验检索段补「为何召回必须读」的约定。
- `docs/TOOL_SYSTEM.md` 新增 8.6 浏览器自动化、8.7 项目知识库、8.5.4 经验排序三节；`docs/LESSON_GROWTH_OPTIMIZATION.md` 新增 5.5.4 节；README / ARCHITECTURE / MCP_SYSTEM / BUILD_AND_RUN 的工具数量与目录树同步更新。

### 🩹 审查阶段修正

- `BrowserBridgeClient.handshakeCandidates()` 从 2 个候选扩到 3 个（补 `-Dapp.userdata` 父目录），修掉 dev 模式下 bridge 写 `<项目根>/runtime/`、Java 却找 `<target>/runtime/` 的路径不一致；打包模式零回归。
- `ProjectMapService.rule()` 的 `update` 分支新增 `evidenceGiven` / `evidenceInherited` 判定，修掉「模型自己填了 evidence 也提示沿用原证据」的错误文案。
- `ProjectMapTool` 的 `module` / `op` 参数描述纠正为实际必填关系；`BrowserTool` 的 `ref` 描述改为「click / fill / select / hover 必填，scroll 可选」。
- `application.yml` 补 `budget-doc-list` 与 `doc-mode-code-ratio` 两个漏声明的配置项；握手文件注释补 dev / 打包双路径与 Java 三级探测顺序。

### 🔢 版本号同步

- 后端：`3.0.6` → `3.0.7`（`pom.xml`，产物 `code-craft-3.0.7.jar`）
- 经典版前端：`3.0.6` → `3.0.7`（`frontend/package.json` + lock）
- v3 前端：`3.0.6` → `3.0.7`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.7` / `code-craft-3.0.7.jar`
- MCP Server 握手版本：`3.0.6` → `3.0.7`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注与更新日期、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例，统一到 v3.0.7

### ⬆️ 升级说明

- 本版**含后端与 Electron 主进程改动**：仅替换前端静态文件不够，需覆盖安装；安装后请**完全退出客户端再启动**（`Ctrl+R` 加载不到 `browser-bridge.js`），并让后端随客户端一起重启。
- 升级后首次使用 `browser` 工具会自动拉起受控浏览器窗口；若 `action=status` 报「未检测到客户端」，说明当前是纯 Web 访问，或后端未随之重启。
- 项目知识库**不会自动创建**：需模型在任务中主动调用 `project_map action=bootstrap`（也可以直接在对话里说「先把项目索引建一下」）。建库后 `.codecraft/` 出现在项目根，已默认 gitignore。
- 经验检索的两个开关默认开启；若排序异常，可在 `application.yml` 把 `lesson.ranker.enabled` / `lesson.query-alias.enabled` 置 `false` 回退旧行为。

## [3.0.6] - 2026-10-20

> 稳定版：把三个「坏起来很难受」的缺陷修掉（登录过期却不跳登录页、技能命中标签显示字面 `xxx`、聊天正文层次感缺失），
> 并新增全站统一动效层 `motion.css`，让 v3 的动效回到旧版水准且可集中调参。纯前端改动，无后端逻辑与数据库变更。

### 🔐 登录过期不跳登录页（修复）

- **真因是两层叠加**：① `getStoredToken()` 在自身 token 为空时会回退读经典版（v2）的 `localStorage.user.token` —— 两版共用同一 `origin`（`http://localhost:8084`），`clearAuthToken()` 只清了 `codecraft.token`，路由守卫 `hasToken()` 又读到旧 token，把用户从 `/login` 弹回 `/chat`，形成死循环；② `router.replace()` 在并发导航被取消时是 **resolve 一个 `NavigationFailure`** 而非 reject，原来的 `.catch()` 根本接不到。
- **判废标记**：`http-client.ts` 新增 `codecraft.token.revoked` 标记与内存副本 `revokedToken`，`clearAuthToken()` 写入、`setAuthToken(非空)` 清除；`getStoredToken()` / `readLegacyToken()` 一旦判废直接返回空串，彻底斩断跨版本兜底读取。标记写 `localStorage` 而非仅内存，刷新页面后依然生效。
- **导航兜底**：`router/index.ts` 的未授权回调改为 `router.replace(target).then(failure => { if (failure) fallback() }).catch(fallback)`，`fallback()` 直接改 `window.location.hash`；`notifyUnauthorized()` 在 handler 尚未注册时也走 hash 兜底。另加 800ms 去重，避免并发 401 连发多次跳转。
- **主动触发**：`App.vue` 的 `consumeChatStream` catch 命中 `HTTP 401` / `登录已过期` 时主动调用 `notifyUnauthorized()`，不再只是静默中断。

### 🏷 技能命中标签显示字面 xxx（修复）

- **真因**：`parseSkillHitFromToolOutput()` 对所有工具输出无条件扫正则，而本项目源码、文档里到处是示例文本 `【技能详情】xxx (ID:12)`。AI 读源码 / 文档时被误判成真实命中，标签就显示成了 `xxx`。
- **四道闸门**：① 新增 `isPlausibleSkillName()` 白名单校验，挡掉 `xxx` / `技能名` / `示例` / 文件名 / 路径 / 代码片段，放行 `#数字` 形式；② `parseSkillHitFromToolOutput` 改用行首锚定正则 `LINE_RE`（`^【技能详情】...$`，兼容全角括号与截断），不再全文任意位置命中；③ 调用侧先按 `tr.toolName` 过滤（为空或 `skill` 才解析）；④ 展示层 `ChatMessage.vue` 再兜底过滤一次。
- **配套**：`parseSkillHitsFromSummaries` 同样加白名单校验；`chat-mapper.ts` 的 `skillTexts` 先按工具名过滤再 `collectSkillHits`。

### 🎨 聊天正文层次感对齐旧版

- `.cc-md` 正文 `color` 由 `--text-1` 改为 `--text-2`，`h1`~`h6`、`strong`、`em` 提亮到 `--text-1`，恢复旧版「标题近白、正文白灰」的层次。
- 段落间距 `--sp-3` → `--sp-2`，`li::marker` 由 `--text-3` 提到 `--text-2`；引用块左边框改为 accent 55% 混合色，引用内 `strong` 提亮。
- 表格表头 `color: --text-1` + `font-weight: 700` + `--border-strong` 下边框；行内 `code` 去掉 1px 边框，暗色下底色 `--border-strong`、文字 `#b7a6ff`。
- 用户气泡正文改 `--text-1`，并新增 `.cc-msg-me__bubble .cc-md / strong / h1..h4` 全量提亮；新增 `.cc-msg-ai__name`（`--text-2` / 600）与 `.cc-msg-ai__status`（`--text-3`），替换 `ChatMessage.vue` 里原先的内联 style。

### ✨ 全站动效统一层（新增 motion.css）

- 旧版 `frontend/` 有 28+ 个 `@keyframes`，v3 改造前只有 9 个且散落在 `app.css` 6 个章节，几乎没有页面切换、浮层弹出、卡片入场、hover 反馈。
- 新增 `frontend-v3/assets/motion.css`（约 575 行）作为**全站动画唯一真相源**：§1 动效 Token（`--dur-1..4` = 120/200/260/340ms、`--ease-out` / `--ease-in-out` / `--ease-spring`、`--stagger-step: 26ms`）；§2 22 个统一 `cc-` 前缀的 keyframes 库；§12 一组可复用工具类 `.cc-anim-fade / up / down / pop / rise` 与 `.cc-anim-stagger`（容器级自动错峰前 8 项）。
- `app.css` 原有 9 个 `@keyframes` 全部迁入 `motion.css`（原位置留指针注释），`app.css` 现在 `@keyframes` 数量为 0；两处分散的 `prefers-reduced-motion` 降级块合并到 `motion.css §11`。引入顺序：`tokens.css` → `app.css` → `motion.css`。
- **已挂载**：路由页面淡入上浮 8px（`.cc-chat` 仅淡入不位移，内含 fixed 抽屉避免错位）；侧栏 `grid-template-columns` 260ms 平滑收放；一级导航左滑进、会话列表淡入上浮且前 8 项错峰 26ms；管理页 7 类卡片入场 + 前 6 张错峰 + hover 抬升 2px；12 类列表行淡入；技能命中标签回弹；思考折叠体 / 工具卡展开体 4px 上浮；审批停靠区滑入；附件菜单 / 优化状态条 / 6 处下拉浮层 / 任务面板 / 子 Agent 面板缩放弹出（按触发方向设 `transform-origin`）；灯箱、空状态 logo 浮动 + 主色光晕、下载进度条流光、按钮按下 `scale(.96)`、开关 spring 缓动。
- **刻意不加**：聊天消息项位移动画（虚拟滚动下消息项被复用时动画会反复重播、视觉错位，沿用旧版结论）；列表行 hover 抬升（密集列表视觉噪点）。
- **降级双入口**：`prefers-reduced-motion` 与 `html[data-motion="reduced"]` 统一把时长压到 0.01ms，为后续外观页开关预留钩子。
- **收益**：调手感只需改 `motion.css §1` 的 6 个 token，不必再翻 `app.css` 55 个 section。

### 🔢 版本号同步

- 后端：`3.0.5` → `3.0.6`（`pom.xml`，产物 `code-craft-3.0.6.jar`）
- 经典版前端：`3.0.5` → `3.0.6`（`frontend/package.json` + lock）
- v3 前端：`3.0.5` → `3.0.6`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.6` / `code-craft-3.0.6.jar`
- MCP Server 握手版本：`3.0.5` → `3.0.6`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例统一到 v3.0.6

### ⬆️ 升级说明

- 本版**无后端逻辑变更、无数据库结构变更**，全部为 v3 前端（Vue / CSS）修复与动效增强：覆盖安装后重载窗口（`Ctrl+R`）即可生效。
- 动效若觉得过快 / 过慢，改 `frontend-v3/assets/motion.css` 顶部 §1 的 `--dur-*` 与 `--stagger-step` 即可全局生效。
- 系统「减少动态效果」偏好已自动尊重；也可手动给 `<html>` 加 `data-motion="reduced"` 全局关闭动画。

## [3.0.5] - 2026-10-20

> 细节打磨版：把上一版遗留的四项「看着别扭、点不开」的界面问题逐个修掉 ——
> 会话列表「⋯」菜单终于能点开（含开了窗口背景图的情况），
> 审批 / 询问停靠区不再整行泛黄，暗色模式下停止按钮不再是刺眼纯白，
> 聊天正文换回与旧版一致的字体、字号与行高。纯前端改动，无后端逻辑与数据库变更。

### 🖱 会话「⋯」菜单点不开（修复）

- **两个叠加原因**：① 开启「窗口背景图」后 `.cc-shell.has-bg .cc-sidebar` 的 `backdrop-filter` 会让 `position: fixed` 的后代以侧栏为包含块，菜单坐标错乱后被裁掉；② 列表底部的会话向下展开时会超出视口。
- **单例 + Teleport**：菜单从「每个会话各挂一份」改为整个列表共用一个实例，并用 `<Teleport to="body">` 挂到 `body`，彻底脱离侧栏的 `filter` / `backdrop-filter` 包含块。之所以不做逐项 Teleport，是为了避免上百条会话各挂一个容器。
- **视口翻转与边界夹取**：`toggleConvMenu` 按 `CONV_MENU_W = 132` / `CONV_MENU_H = 118` 计算，空间不足时向上翻转，左右越界自动夹取；`onMounted` 监听 `window resize` 自动收起，避免菜单悬空留在原地。

### 🎨 审批 / 询问停靠区去黄

- `.cc-approval-dock` 背景由 `linear-gradient(..., var(--warn-lt))` 改为 `transparent`，`border-top` 改为 `var(--border)`；`.is-clarification` 同步改为中性色。
- 语义色收进卡片内部（`.cc-approval`）与脉冲圆点：整条停靠栏不再泛黄压住对话区，权限 / 询问的区别仍一眼可辨。

### 🌙 暗色模式下停止按钮不再是纯白

- `.cc-stop-btn` 补上 `border` 与过渡；新增 `html[data-theme="dark"] .cc-stop-btn` 覆盖（`background: var(--card-2)`、`color: var(--text-1)`、`border-color: var(--border-strong)`），hover 仍切 `--danger`。
- `ChatComposer.vue` 的停止图标 `stroke: #fff` 改为 `stroke: currentColor`，跟随按钮文字色，暗色下不再是硬编码白块。

### ✍ 聊天字体对齐旧版

- `tokens.css` 新增 `--font-chat` / `--fs-chat: 14px` / `--lh-chat: 1.75`（`Avenir, Helvetica, Arial, "Microsoft YaHei", "PingFang SC", sans-serif`），`.cc-msg-ai__body` / `.cc-msg-me__bubble` / `.cc-md` 三处改用该 token（原为 14.5px / 1.78）。
- 改动只作用于消息区，不动全站 `--font` / `--fs-md`，避免影响管理页表格与表单的可读性；思考区 `.cc-proc__text.cc-md` 因选择器更具体仍保持 12.5px，字体族跟随统一。

### 🔢 版本号同步

- 后端：`3.0.4` → `3.0.5`（`pom.xml`，产物 `code-craft-3.0.5.jar`）
- 经典版前端：`3.0.4` → `3.0.5`（`frontend/package.json` + lock）
- v3 前端：`3.0.4` → `3.0.5`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.5` / `code-craft-3.0.5.jar`
- MCP Server 握手版本：`3.0.4` → `3.0.5`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例统一到 v3.0.5

### ⬆️ 升级说明

- 本版**无后端逻辑变更、无数据库结构变更**，全部为前端样式与交互修复：覆盖安装后重载窗口即可生效。
- 若侧栏菜单仍点不开，请确认旧进程已完全退出（含托盘）后再启动，避免 Electron 复用旧的静态资源缓存。

## [3.0.4] - 2026-10-20

> 体验修复版：把「检测到新版本却提示安装旧包」和「回复里的文件链接点了没反应」两个老大难挖到根上，
> 给提示词优化补上加载反馈与撤销，审批 / 询问卡片改为固定停靠不再被长会话顶出视野，
> 询问支持直接点选 1./2./3. 方向，技能标签显示真实技能名，
> 发版流程的 Release 正文改为自动带上本版变更。版本号统一到 3.0.4，无数据库结构变更。

### 🔄 自动更新：彻底修掉「装旧包」

- **主进程兜底**：新增 `invalidateStaleDownload(targetVersion)`，`checkForUpdates()` 检测到最新版本后、以及 `publicState()` 输出前核对 `downloadState.version`，与最新版本不一致即作废并 `emitDownloadState()` 广播。此前 `downloadState` 跨检查保留，先下过 3.0.1 再发布 3.0.2 时，「安装」按钮仍指向旧包。
- **启动恢复加闸门**：`initUpdateManager()` 原先只判断「安装包文件存在」就恢复「已下载」，现在必须同时满足 `compareVersion(latestInfo.version, app.getVersion()) > 0` 且文件大小匹配。
- **安装前再校验**：`installDownloaded()` 增加版本闸门，本地包版本与最新版本不一致时直接抛错「请重新下载」，宁可让用户重下也不装错包。
- **前端双保险**：`VersionCard` 新增 `versionFromFileName()`（正则 `/(\d+\.\d+\.\d+)/`，避免把 `.exe` 当成预发布后缀）、`downloadVersion`、`staleDownload`，按钮文案改为「重新下载 vX」，并在下方提示版本不一致。

### 🔗 文件链接打不开（修复）

- **根因**：v3 工作区标签把消息里的**相对路径**原样传给 `GET /api/project/read`，后端按 `user.dir`（Electron 下为 jar 所在目录）拼接，读到的自然不是用户期望的文件；旧版 `openLocalFileTab` 是「先拼绝对路径 + 失败后走 `/api/project/resolve` 后缀匹配兜底」，v3 迁移时丢了这一层。
- **新增 `utils/file-path.ts`**：`normalizeFsPath` / `isAbsoluteFsPath` / `joinFsPath` / `absolutizePath` / `resolveFileTarget` / `describeMissingFile`。`resolveFileTarget()` 返回 `{ raw, path, found, candidates, viaResolve }`，先按工作目录拼绝对路径，读不到再走后端 resolve 兜底并回传候选列表。
- **点击时解析**：`ChatView.onStreamClick` 改为 async，先解析再把结果交给 `workspace.openTab` / `openFilePreview`；`openFilePreview` 签名放宽为 `(target: string | ResolvedFileTarget, kind)`。解析放在点击时而非标签内部，避免同一文件的相对 / 绝对路径开成两个标签（`tabKeyOf` 以 `payload.path` 为 key）。
- **工作区视图**：`WorkspaceFileView` 新增 `root` prop 与 `resolvedPath`，读写都用解析后的路径；`WorkspaceTabView` 透传 `:root="root"`，图片 `loadImage` 同样解析。
- **纯文件名链接**：`markdown.ts` 的 `isLocalFilePath(p, allowBareName = false)` 支持识别纯文件名（命中 `FILE_EXT_STRICT_RE`），仅在显式链接 / 图片场景放开（`allowBareName=true`），正文扫描仍要求含路径分隔符，避免误伤普通文字。
- **错误可读**：读取失败的提示带上「工作目录 + 候选路径 + 后端原始 message」；`app.css` 的 `.cc-wsv__state-text` / `.cc-fileview__state.is-error` 加 `white-space: pre-line; word-break: break-all`，长路径不再被截成一行。

### ✨ 提示词优化：加载反馈 + 可撤销

- **前端请求层**：`api/chat.ts` 的 `optimizePrompt` 返回结构化 `OptimizePromptResult { ok, optimized, error, elapsedMs, model }`，不再静默回落原文；加 30 秒 `AbortController` 兜底（后端 15 秒 + 传输余量）；401 走 `notifyUnauthorized` 跳登录；区分「非 JSON 响应（后端版本过旧）/ 网络断开 / 超时」三类文案。
- **加载反馈**：✨ 按钮变旋转圆环 spinner（`aria-busy`）并禁用防连点；输入框上方新增状态条「AI 正在优化提示词 · 已等待 Ns」，每秒 +1、60 秒停止累加；优化期间 textarea 用 `readonly` 且禁止发送（用 `readonly` 而非 `disabled`，保留正常配色）。
- **可撤销**：`ChatView` 持有 `optimizeUndo` 快照，`optimizeUndoAvailable` 仅在「当前草稿仍等于优化结果」时为真；`applyOptimizedDraft()` / `undoOptimize()` 通过 `defineExpose` 暴露；发送或切换会话即清空快照，避免把旧会话的撤销串到新会话。
- **结局细分**：`App.vue` 的 `onOptimize` 重写为防重入 + `toast.loading` → success / error / info / warning 四态，带耗时与字数统计；检测到会话已切换或草稿被改动就不覆盖；草稿超过 `OPTIMIZE_MAX_INPUT = 6000` 直接引导拆段（模型 `max_tokens` 装不下，等 10 秒只会拿到被截断的更差结果）。
- **后端**：`PromptOptimizeService` 新增 `OptimizeResult` record，失败原因细分（模板缺失 / 无 Provider / 超时 / 空结果 / 调用异常）；`MAX_TOKENS` 500 → 800（默认模型可能是推理模型，500 容易被思考 token 吃满导致 content 为空）；`PromptOptimizeController` 改用 `LinkedHashMap` 输出（`Map.of` 不允许 null 值），保持裸结构向后兼容旧前端。

### 📌 审批 / 询问卡片固定停靠

- 卡片原先内联在消息列表末尾，长会话里要一路下拉才能看到 —— 等于「AI 停了在等你，你却不知道」。改为从消息流移出，固定在输入框上方的 `.cc-approval-dock`：`flex: 0 1 auto` + `min-height: 0` + `overflow: hidden`，小窗口下可收缩，不顶掉输入框。
- 停靠条按 `askType` 显示「等你授权 / 等你回答 / 等你确认继续」并带脉冲指示；卡片主体 `max-height: min(46vh, 460px)` 内部滚动，长 diff 也能看全。
- 新增 `watch(() => props.approval)`：用户本来就在底部时自动滚到最新（不打断正在翻历史的人），并提供「回到最新上下文」按钮。

### 🗂 询问支持直接点选方向

- 新增 `utils/ask-options.ts`：`parseAskOptions(markdown)` 从问题文本解析 `1. / 2. / 1、/ (1)`、`A.`、`-` 三类编号项，剔除围栏代码块、归一化字面量 `\n`、去掉 markdown 装饰。
- **误判防护**：至少 2 项、最多 6 项、平均长度 ≤90 字、原始块 ≤400 字才启用；解析失败自动退化为原来的文本框。
- `ChatApproval` 的 clarification 分支新增选项按钮区，点一下即以原编号作答（`${mark}. ${plain}`），`picking` 防重复提交。
- 后端 `AskClarificationTool` 的问题参数说明强化：多方向时必须用 `1./2./3.` 编号逐行列出 —— 前端零协议变更，只提升命中率。

### 🎯 技能命中显示真实技能名

- 标签文案由 `🎯 {name}` 改为 `🎯 命中技能：{name}`；`hitSkills` 展示前清洗 `**`、前导符号，`#12` 这类裸 ID 转成「技能 #12」并去重。
- `utils/skill-hit.ts`：新增 `cleanSkillName()`；`parseSkillHitsFromSummaries` 的正则由 `(.+)$` 改为 `/🎯\s*命中技能\s*[:：]?\s*\*{0,2}\s*([^\n]+)/` —— 原来摘要尾部带 `\n\n` 会导致整条匹配失败，这正是「标签只显示 XXX」的直接原因。
- `App.vue` 新增 `skillNameMap` / `ensureSkillNameMap()`：按需拉一次技能列表建立 `#id → name` 映射（失败不重试），`setConvMessages()` 套用 `withSkillNames()`，`watch(skillNameMap)` 回填已渲染的历史消息；映射不到时显示「技能 #12」而非裸数字。

### 📝 发版流程：Release 正文自动带变更

- `.github/workflows/release.yml` 的 Release 正文此前是写死的模板，只有下载说明和 macOS 提示，**不含本版实际改动**（Release 页面看起来「没有更新说明」就是这里）。现在 create-release 阶段 checkout 代码并用 `awk` 从 `CHANGELOG.md` 抽取当前版本段落拼进正文；抽取失败时回退为指向 CHANGELOG 的链接。
- 顺手修掉 Windows 安装包文件名写错的问题：正文写的是 `CodeCraft-Setup-x.y.z.exe`，实际资产是 `CodeCraft.Setup.x.y.z.exe`。

### 🧾 版本号同步

- 后端：`3.0.3` → `3.0.4`（`pom.xml`，产物 `code-craft-3.0.4.jar`）
- 经典版前端：`3.0.3` → `3.0.4`（`frontend/package.json` + lock）
- v3 前端：`3.0.3` → `3.0.4`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.4` / `code-craft-3.0.4.jar`
- MCP Server 握手版本：`3.0.3` → `3.0.4`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例统一到 v3.0.4

### ⚠️ 升级说明

- 此版本**无数据库结构变更**，但有后端改动（提示词优化返回结构、询问提示词），需**覆盖安装**新包；仅替换前端静态文件不够。
- 自动更新的主进程修复必须随安装包更新才生效：3.0.3 及更早版本的主进程仍是旧逻辑，若本地躺着一个旧安装包，仍可能提示安装旧包 —— 升级到 3.0.4 后恢复正常。
- 文件链接解析依赖后端 `/api/project/read` 与 `/api/project/resolve`，工作目录以当前设置为准（会话覆盖 > Agent 工作目录 > 全局设置）；若链接仍打不开，请把提示里的候选路径一起反馈。

---

## [3.0.3] - 2026-10-20

> 稳定性修复版：定位并解决「展开长思考直接卡死」的真凶（markdown 本地路径识别正则的 O(n²) 回溯），
> 补齐聊天记录分页加载的加载中 / 失败 / 滚动锚点反馈，登录态失效改为直接跳登录页，
> 用户消息补上附件气泡，并修掉自动更新「装错旧包」的版本比对缺陷。
> 版本号统一到 3.0.3，无后端接口与数据结构变更。

### ⚡ 长思考展开卡死（真因修复：正则回溯）

上一版把卡死归因于「渲染频率过高」，只加了节流与长度上限 —— 治标。本轮定位到真凶并彻底修掉：

- **真因**：`utils/markdown.ts` 的本地路径识别正则 `PATH_RE` 用 `[^...]*[\\/]` 匹配「任意长度前缀 + 斜杠」，遇到**一整段没有斜杠的长文本**（大段中文思考几乎不出现路径）会在每个位置反复回溯，退化成 O(n²)。实测纯中文文本：2 千字 51ms、8 千字 774ms、1.6 万字 2721ms、3.2 万字 **10697ms**，6 万字约 40 秒 —— 这就是「一点展开就死」的观感来源。
- **为什么只在展开时炸**：历史思考默认懒渲染（`p.html` 为空），展开瞬间才走 `processHtml` 同步跑 markdown + 路径识别，卡顿全部集中在点击展开那一刻。
- **修复**：改为两段式线性匹配 —— `PATH_CANDIDATE_RE` 按空白切词（O(n)），再用 `^` 锚定的 `PATH_WORD_RE` 校验单个片段；`splitTextByFilePath` 增加「不含 `\` 或 `/` 直接跳过」预检。修复后 3.2 万字 **1ms**、16 万字 5ms、7.6 万字含 6000 条路径 46ms；10 组行为一致性用例（Windows 绝对路径 / 相对路径 / 多路径同行 / URL 排除 / 纯文件名不误判 / 尾部标点）全部通过。
- **高亮降级**：`highlight.js` 是同步 CPU 活（3000 行 Java 实测 328ms），新增 2 万字阈值 —— 思考区 `PROC_HIGHLIGHT_LIMIT`、历史正文 `HISTORY_HIGHLIGHT_LIMIT` 超限即跳过高亮；流式渲染上限 `FLUSH_RENDER_LIMIT` 由 12 万字收紧到 2 万字。
- 上一版的优化继续保留：历史思考懒渲染、折叠态零 DOM（`v-if`）、流式 1.5 万字 / 单条 6 万字分段、Token 估算按消息指纹缓存。

### 🐞 聊天记录分页加载

- **加载态与失败态**：`ChatView` 新增 `loading` / `loadingMore` / `loadError` 三个 prop。切换会话时不再先闪一下「开始新对话」空态（分页请求未返回，被误判成空会话）；加载失败也不再伪装成空会话，改为错误图标 + 原因 + 「重试」按钮（`reload` 事件）。
- **顶部加载反馈**：滚动到顶触发的更早历史请求，占位块由「上方还有 N 条消息」换成「正在加载更早的聊天记录…」，不再出现「滚到顶没反应」。
- **滚动锚点补偿**：`requestMore()` 先记录 `scrollHeight` / `scrollTop`，数据到达后在 `nextTick` 用高度差回算 `scrollTop`，把视口钉回用户正在看的那条消息；再用 `requestAnimationFrame` 二次校正（markdown / 代码高亮会在下一帧继续撑高，只补一次会偏几十像素）。本地窗口展开 `showEarlier()` 同样补第二帧，且仅在偏离 >2px 时修正，避免抖动。`prependAnchor` 声明提前到 watch 之前，规避 TDZ 报错。
- 分页游标按会话隔离（`cursorMap` / `hasMoreMap`），快速切会话时旧请求不再覆盖新会话游标；`chatLoadError` 与 `chatError` 拆开，前者专用于分页失败，后者承载发送失败 / 任务中断。

### 🔐 登录态失效直接跳登录

- `utils/http-client.ts` 新增 `notifyUnauthorized(reason)`：清 token 并触发路由注册的未授权回调，统一跳登录页。
- `utils/sse-client.ts` 的 401 分支原先只 `clearAuthToken()`（界面停在原地、后续请求继续 401），改为调用 `notifyUnauthorized('登录已过期，请重新登录')`。
- `App.vue` 的流式消费 `catch` 识别到 `HTTP 401` / `登录已过期` 时直接返回，不再额外弹「发送失败」，避免登录页上残留红色错误提示。

### 📎 用户消息附件展示

- 用户气泡新增附件区：图片显示 48×48 缩略图（点击开灯箱放大），文档显示文件 chip（点击下载；`id=0` 的旧临时通道附件给出「无法下载」提示）。
- `ChatMessage` 导出 `MessageFileAsset` 类型，`ChatMessageModel` 增加 `fileAssets`；`App.vue` 新增 `loadMessageFileRefs()` —— 走 `GET /api/files?conversationId=X&withRefs=true`，按 `file_reference.messageId` 分组挂到对应消息，图片再异步取 `preview` blob 补缩略图；发送时直接挂载本轮附件，无需等下一次刷新。

### 🔄 自动更新：安装包版本比对

- `VersionCard` 的 `downloadReady` 不再只看 `status === 'done'`，改为比对「已下载包版本 === 检测到的最新版本」（比较前去掉 `v` 前缀）。
- 检测到新版本时若本地躺着**另一个版本**的安装包，直接作废下载状态并提示「发现新版本，需要重新下载」。修掉「先下过 3.0.1、之后发布 3.0.2，界面却把『安装 3.0.1』摆在最前面」的坑。
- 老版本主进程不回 `download.version` 时按「不匹配」处理，宁可让用户重下，也不装错包。

### 🧾 版本号同步

- 后端：`3.0.2` → `3.0.3`（`pom.xml`，产物 `code-craft-3.0.3.jar`）
- 经典版前端：`3.0.2` → `3.0.3`（`frontend/package.json` + lock）
- v3 前端：`3.0.2` → `3.0.3`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.3` / `code-craft-3.0.3.jar`
- MCP Server 握手版本：`3.0.2` → `3.0.3`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例统一到 v3.0.3

### ⚠️ 升级说明

- 此版本**无后端接口与数据库结构变更**，替换安装包即可；已装 3.0.2 的用户可直接覆盖安装。
- 卡死修复与附件展示都是前端改动，需随包更新：桌面端加载的是 jar 内 `v3-static/` 构建产物，仅刷新页面不一定生效。

---

## [3.0.2] - 2026-10-20

> 修复版：集中处理 v3 聊天页的一批体验缺陷（顶部配色割裂、余额不刷新、切换工作目录无效、
> 长思考卡死页面），补齐经典版已有、v3 缺失的「技能命中标签」与「Git 提交历史」，
> 并修掉 Provider 切换的语义漂移。版本号统一到 3.0.2，无后端接口与数据结构变更。

### 🔧 v3 聊天页修复

- **余额每轮刷新**：此前只在进入聊天页 / 切 Provider 时查询，一轮对话结束不刷新，数字永远停在上一次。现在 `onSend` 的 `finally` 里强制重查（含后台会话与手动停止）；查询失败时**保留上一次成功值**，不再把余额清空成空白。
- **切换工作目录真正生效**：文件树里换目录原先只改面板自己的 `localRoot`（注释还写着「不改 Agent 配置」），聊天请求仍用旧 `workDir`。现在切目录会弹确认框：
  - 「设为工作目录」→ 写入会话级覆盖 + `settingsStore.projectRoot`，并 `PUT /agent-configs/{id}/runtime` 写回 Agent，随后刷新列表 —— AI 文件操作、Git、终端、消息内相对路径解析全部跟随；
  - 「仅浏览」→ 只改文件树范围。
  - `chatProjectRoot` 的优先级同时改为「会话覆盖 ＞ Agent 配置 ＞ settingsStore 持久化兜底」，与经典版一致。
- **顶部区域与主区颜色统一**：壁纸模式下 `.cc-header`（含「文件 / Git / 终端」那条）从 62% 卡色改为透明，直接复用主列纸面；Electron 顶部 32px 标题栏右段复刻页面的两层叠加（`--bg-solid` 50% + 纸面 40%）。此前深色主题下 62% 卡色叠纸面比主区暗约 30%，顶部看起来是一条割裂的深色横带。
- **设置补全生效**：
  - 窗口整体透明度接入 `electronAPI.setWindowOpacity`（此前只改 store 数字，窗口纹丝不动）；
  - 界面密度写入 `<html data-density>` 并在 `tokens.css` 覆盖 `--sp-*` / `--header-h`（此前是只改本地 ref 的「演示控件」）；
  - 切主题时补上 `electronAPI.setTheme`，右上角窗口按钮配色跟随切换。
- **Provider 切换语义修正**：只在用户确实改了绑定时才写回 `providerCode`。此前只改执行模式也会把「跟随默认 Provider」固化成某个具体 code，之后换默认 Provider 不再跟随。

### ⚡ 聊天记录加载优化（长思考不再卡死）

- **历史思考懒渲染**：`chat-mapper` 不再对每条消息跑 `renderMarkdown(thinking)`（一条几万字 × 十几条 = 几十万字同步解析，是「打开老会话卡死」的主因），只保留原文，由 `ChatMessage` 在展开时按需渲染并按原文缓存。
- **折叠态零 DOM**：思考区从「内容常驻 DOM + CSS `display:none`」改为 `v-if="procOpen"`，与经典版 `getThinkingVisible` 行为对齐，折叠时不再挂几千个隐藏节点。
- **超长思考分段**：单条思考默认只渲染尾部 6 万字，更早内容由「渲染完整思考（共 N 字）」按钮显式加载；流式期间超过 3 万字不再逐帧渲染，收尾超过 12 万字跳过完整渲染（省掉 highlight.js 的秒级开销）。
- **上下文 Token 估算缓存**：按「消息 id + 内容长度指纹」缓存单条估算值，翻页 / 切会话只重算变化的消息，不再每次遍历全部已加载消息的全文。

### 🎯 新增：技能命中标签

- AI 消息的思考区横栏显示 `🎯 技能名` 胶囊，标识**本轮真正执行过**的技能（只认 `skill action=load`，不是「技能目录里匹配到」）；即使没有思考过程也能单独显示。
- 数据来自两条通道：`tool_call_start.summaries` 的「🎯 命中技能：xxx」（主通道）＋ 工具输出里的「【技能详情】xxx (ID:n)」（兜底通道）。
- **历史回放**：刷新 / 切换会话后从工具输出原文反解，老会话里执行过的技能同样能显示。
- 解析规则集中在新增的 `frontend-v3/src/utils/skill-hit.ts`，实时流与历史回放共用。

### 📜 新增：Git 提交历史面板

- Git 面板折叠区新增「提交历史」：解析 `GET /api/git/log` 返回的 `git log --oneline` 文本，展示「短哈希（等宽主色）+ 提交信息」。
- 点击整行复制完整 40 位哈希；「加载更多」每次 +20 条；**提交成功后自动展开并刷新**，刚提交的那条立刻可见；面板刷新（提交 / 拉取 / 回滚）时历史区若展开会一起更新。

### 🧾 版本号同步

- 后端：`3.0.1` → `3.0.2`（`pom.xml`，产物 `code-craft-3.0.2.jar`）
- 经典版前端：`3.0.1` → `3.0.2`（`frontend/package.json` + lock）
- v3 前端：`3.0.1` → `3.0.2`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.2` / `code-craft-3.0.2.jar`
- MCP Server 握手版本：`3.0.1` → `3.0.2`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例、FAQ 版本示例统一到 v3.0.2

### ⚠️ 升级说明

- 此版本**无后端接口与数据库结构变更**，替换安装包即可；已装 3.0.1 的用户可直接覆盖安装。
- 前端改动需随包更新：桌面端加载的是 jar 内 `v3-static/` 的构建产物，仅刷新页面不一定生效。

---

## [3.0.1] - 2026-10-19

> 修订版：v3 前端的「背景图」从只作用于三个外壳容器，扩展为**全站透明化**（L21）；
> 桌面端顶部 32px 让位区改为与页面同色自绘（L22）；版本号统一到 3.0.1，
> 并修复 `scripts/sync-version.sh` 的版本提取缺陷。此版本无后端接口与数据结构变更。

### 🖼 背景图全站透明（v3）

- **问题**：开启「背景图」后只有侧栏 / 顶栏 / 主内容区三个外壳容器半透明，消息气泡、输入框、卡片、列表行、抽屉仍是实色卡面，层层叠加把壁纸完全盖住 —— 用户观感是「设了背景图但看不见」。
- **改法（改 token 而非逐个改组件）**：`tokens.css` 拆出 5 个不透明基色（`--bg-solid` / `--bg-sub-solid` / `--card-solid` / `--card-2-solid` / `--accent-soft-solid`），原语义变量改为引用基色，**无背景图时行为逐像素不变**；`app.css` §37 在 `.cc-shell.has-bg` 下用 `color-mix()` 把这 5 个表面色统一改写为半透明，一次性覆盖全站 283 处背景引用，日后新增组件也不会漏。
- **四级层次**（壁纸 → 内容纸面 → 卡片 → 浮层）：外壳 62%~74% + 毛玻璃；内容纸面 40%（亮色 30%）；卡片 64%（亮色 58%）；抽屉 94%、弹层 / 下拉 90%、代码与日志 / diff / 编辑器等密集阅读区 96%。
- **背景图明暗校正**：`.cc-app-bg::after` 在深色主题压暗 60%、亮色主题提亮 32%，避免「亮壁纸 + 浅色文字」读不清；校正强度随不透明度一起变淡。
- **回退与例外**：`@supports not (color-mix)` 时退回实色；Toast / Confirm / Prompt / Lightbox 因 Teleport 到 `body`、不在 `.cc-shell` 内，天然保持实色（最上层提示必须清晰）。
- **验证**：18 个页面 × 明暗 2 套 = 36 次自动审计，大面积不透明元素命中 **0**。

### 🪟 桌面端顶部 32px 与页面同色（v3）

- **问题**：`titleBarStyle: 'hidden'` 下 v3 用 `.cc-shell { padding-top: 32px }` 为窗口控制按钮让位，这 32px 只剩裸壁纸层，而下方侧栏是 74% 卡色 + 毛玻璃、顶栏 62%，合成后比顶部亮一档 —— 视觉上像「壳的一块贴在上面」。
- **改法**：顶部改由前端两段式自绘 —— `.cc-titlebar__side` 对齐侧栏层，`.cc-titlebar__main` 对齐顶栏层（且叠两层：先用 `--bg-solid` 40% / 30% 垫底，再压 62% 卡色，与页面 `.cc-chat__main` + `.cc-header` 的合成顺序一致）；折叠侧栏（`cc-sidebar-collapsed`）、登录页（`is-plain`）、无壁纸等场景各有分支；`<html>` 新增 `cc-has-bg` / `cc-sidebar-collapsed` 类，供壳外的标题栏读取状态。
- **壳层保持中性**：`electron/main.js` 的 `TITLE_BAR_THEME.color` 仍为全透明，只负责切换窗口控制按钮的符号色（本次仅补充注释说明设计意图）；经典版沿用自身 `.electron-titlebar-drag` + 32px 遮罩色带。两版顶部各画各的，互不迁就对方配色。
- **验证**：Electron 真窗口 `capturePage` 取像素，深 / 亮 × 有 / 无壁纸 × 折叠 × 登录页共 7 个场景，顶部与下方页面差值 ≤ 8 级（壁纸纵向渐变内的正常差异）。

### 🔧 工具链

- **修复 `scripts/sync-version.sh` 版本提取失效**：脚本此前按 `<artifactId>codecraft</artifactId>` 查找，而 `pom.xml` 里实际是 `<artifactId>code-craft</artifactId>`（带连字符），无参运行必然报「无法从 pom.xml 提取版本号」。已修正匹配串，并 `tr -d '\r'` 避免 CRLF 尾巴混入版本号。
- **扩展同步范围**：从「仅 `electron/package.json`」扩到 electron / frontend / frontend-v3 三套 `package.json` 及其 `package-lock.json` 的顶层 `version`（lock 只改根节点前两处，不触碰 `node_modules` 里的依赖版本）。
- **写明手工同步项**：Java 常量 `CodeCraftMcpServer.SERVER_VERSION`、README 版本徽章、docs 版本标注无法由脚本安全覆盖，脚本末尾会提示。

### 🧾 版本号同步

- 后端：`3.0.0` → `3.0.1`（`pom.xml`，产物 `code-craft-3.0.1.jar`）
- 经典版前端：`3.0.0` → `3.0.1`（`frontend/package.json` + lock）
- v3 前端：`3.0.0` → `3.0.1`（`frontend-v3/package.json` + lock）
- Electron：版本与 `extraResources` jar 路径 → `3.0.1` / `code-craft-3.0.1.jar`
- MCP Server 握手版本：`3.0.0` → `3.0.1`（`CodeCraftMcpServer.SERVER_VERSION`）
- 文档：README(_EN) 版本徽章、ARCHITECTURE / DEEPSEEK_SERVICE_IMPL / LLM_PROVIDER_SYSTEM / TOOL_SYSTEM(_EN) 版本标注、DEV_QUICKREF / RELEASE_CHECKLIST(_EN) 打包命令示例统一到 v3.0.1

### ⚠️ 升级说明

- 此版本**无后端接口与数据库结构变更**，替换安装包即可；已装 3.0.0 的用户可直接覆盖安装。
- 背景图与顶部配色均为纯前端样式改动，不重新打包时刷新页面 **不一定**能生效 —— 桌面端加载的是 jar 内 `v3-static/` 的构建产物，需随包更新。

---

## [3.0.0] - 2026-10-19

> 大版本：全新 v3 前端（`frontend-v3/`）正式落地，与经典版（`frontend/`）**并存可切换**，
> 补齐「版本与更新」页（F06），版本号统一到 3.0.0。
>
> **本次重建追加（同版本号覆盖发布）**：慢机器启动优化（P2P 异步化 + JVM/Spring 调优 + AppCDS 类归档）、
> 导入备份上限 64MB → 500MB。业务功能与首次构建一致。

### ⚡ 启动性能（针对慢磁盘 / 低配机器）

以一份真实慢机器日志（`Started ... in 68.281 seconds`）为准逐段优化：

- **P2P 移出启动关键路径**：`ConnectionManager.start()` 从 `@Bean` 工厂方法改为 `ApplicationReadyEvent` 之后异步执行。
  此前 TLS 初始化 + Netty 绑端口（实测 3.3s + 5.3s）全部计入 Spring 容器刷新，主界面必须等它跑完才能打开。
  现在 Tomcat 先对外服务，P2P 就绪前后 `/api/p2p/status` 返回 `running=false`。
- **移除两个适得其反的 JVM 参数**：`-XX:TieredStopAtLevel=1`（Spring 启动期大量反射/注解解析停在 C1 反而更慢）、
  `-XX:+UseSerialGC`（JDK 17 ergonomics 会按机器规格选 GC，显式指定在 8G 机器上限制了 G1）。
- **关闭 SpringDoc**：`--springdoc.api-docs.enabled=false`、`--springdoc.swagger-ui.enabled=false`，桌面端不需要 OpenAPI 建模。
- **Hikari `minimum-idle` 5 → 1**：启动少开 4 个 H2 连接（每个都要打开库文件）。
- **H2 连接串增加 `WRITE_DELAY=2000`**：把 schema.sql 100+ 条 DDL 触发的 fsync 合并，慢磁盘上省数秒。
  代价：进程被强杀时最多丢最后 2 秒写入。
- **新增 AppCDS 类归档（自动、自适应）**：首次启动 20 秒后由后台预热进程生成 `runtime/cds/codecraft.jsa`，
  后续启动用 `-XX:SharedArchiveFile` 直接内存映射复用。实测同机 **7.12s → 5.54s（-22%）**；
  jar 指纹变化自动重建；归档异常时 `-Xshare:auto` 静默回退，不影响启动。
  为此后端新增 `--app.cds-warmup=true`（跑完启动流程即正常退出，供 Electron 产归档）。

### 📦 导入备份上限 64MB → 500MB

- `spring.servlet.multipart.max-file-size` 64MB → **500MB**，`max-request-size` → 520MB，
  `server.tomcat.max-swallow-size` 70MB → 520MB（保证超限时客户端能收到错误响应而非连接重置）。
- 后端 `/api/system/import-data` 改用 `file.getInputStream()` 流式解析，避免 500MB 文件被 `getBytes()` 再复制一份进堆。
- 前端（v3 `DataImportCard` + 经典版 `SystemSettingView`）同步放开校验并提示「单文件 ≤500MB」；
  超限时保留文件信息并明确告知超出多少，而不是静默失败。
- **导入失败提示细化**：上传中断时不再笼统提示「请确认后端服务已启动」，改为「文件多大 + 可能原因
  （旧版 64MB 上限 / 后端异常退出）」。

### 🛠 其它

- `docs/FAQ.md` 新增 Q20（启动慢的分阶段拆解 + 5 项优化 + 硬件排查顺序）。

### 🎨 新增：v3 前端（`frontend-v3/`，服务在 `/v3/`，默认入口）

- **技术栈重写**：Vue 3.5 + Vite 8 + TypeScript + Pinia（3 store）+ hash 路由；零 UI 组件库依赖，全部自研 `cc-` 设计系统（1px 描边卡片、线性图标、hover 才显操作、深浅主题）。
- **19 个页面全部对接真实接口**（无一处 DEMO 数据）：对话、智能体、技能、MCP、定时任务、踩坑经验、数据库连接、文件管理、运行日志、数据管理、设备互联、用户管理、菜单权限、模型配置、外观设置、设置聚合、个人信息、版本与更新、登录。
- **聊天页能力补齐**：markdown + highlight.js 渲染（含 `diff` 红绿着色、filelist / cmd 卡片）、工具结果语义化卡片、思考过程独立滚动跟随、图片灯箱（缩放/拖拽/多图）、审批三形态（权限 / 澄清 / 迭代确认 +10/+20/+50）、任务清单（优先级 / 依赖阻塞）、快照级「回滚本轮改动」、重新生成（真删重发）、补充需求注入、上下文 Token 统计、`full` / `compact` 上下文模式与 Provider 运行时切换、多会话并行流（切页不中断后台流）、子 Agent 面板（读 `sub_agent_log` 轮询）、P2P 聊天与文件传输、右侧工作区（多标签 + 分屏 + 内嵌网页 + 代码编辑器 + Git 客户端 + xterm 终端）。
- **静态预览 `preview.html`**：与 Vue 应用同构的纯静态原型（保留在开发机，未纳入本仓库）。
- **启动时隐形登录已移除**：v3 改为真实登录页（`/login`），支持退出登录、改密后自动重登、401 统一回跳并保留原地址。

### 🔄 新增：版本与更新页（F06）

- **新增页面 `/about`**（左侧「设置 → 系统配置」入口）：页面头 + 版本矩阵（客户端版本 / 后端版本 / 构建时间 / 运行环境 / 最近检查 / 后端 PID）+ 更新卡片 + 更新设置 + 更新策略说明。
- **新增后端接口 `GET /api/system/version`**：返回应用名、版本、构建时间、Java 版本、OS、PID；**不做管理员校验**（不含敏感数据，普通用户也需可见）。版本号由 `application.yml` 的 `app.version` 经 Maven 资源过滤注入（`@project.version@`），未替换时回退内置常量。
- **复用现有 Electron 更新链路**（`electron/update-manager.js` + `preload.js` 的 `electronAPI.update`，零改动）：检查更新（GitHub Releases，6 小时缓存 + 手动 30s 最小间隔）、带进度条下载（镜像加速 + 失败回退直连）、一键安装（Windows 安装器 / macOS dmg / Linux 定位）、打开发布页。
- **浏览器环境安全降级**：无 `electronAPI` 时只显示只读版本卡片 + 一行说明，更新设置区退化为只读文案，点「检查更新」给 toast 提示而非报错。
- **两处刻意设计**：① 客户端版本与后端版本分列（只换 jar 不重装客户端时能看出不一致，不强行合并）；② Release 说明按 markdown 渲染（`html:false` 防 XSS，外链自动 `target=_blank`）。
- **数据清理联动**：「重置所有数据」成功后自动调 `clearLocalData()`，清掉升级前备份 / 已下载安装包 / 检测缓存，让「清空」是彻底清空（浏览器环境无副作用）。

### 🔧 后端

- 新增 `GET /api/system/version`（见上）。
- `application.yml` 新增 `app.version` / `app.build-time`（Maven 资源过滤注入）。
- `SystemController` 注入 `@Value` 配置并新增版本解析兜底（占位符未替换时回退 `3.0.0`）。
- 版本号同步：`pom.xml`、`CodeCraftMcpServer.SERVER_VERSION`（MCP 握手版本）。

### 🖥️ 桌面端

- `electron/package.json` 版本与随包 jar 路径同步到 `code-craft-3.0.0.jar`。
- 沿用上一版修复的 macOS 生命周期处理（单实例锁、关窗不杀后端、`activate` 重建窗口与自愈重试、最小应用菜单恢复 `Cmd+Q`）。
- **修复：覆盖安装后仍启动失败（重要）**。`electron/installer.nsh` 的 `customInit` 现在按「`CodeCraft.exe` 优雅退出（触发 `before-quit` → `stopBackend`）→ 强制结束 `CodeCraft.exe` → 兜底清理命令行含 `codecraft.jar` 的 `java.exe`」三步释放文件句柄。此前安装器不结束任何进程：`codecraft.jar` / `app.asar` 被占用时 NSIS **静默跳过写入**却照样弹「安装完成」，结果装完仍运行旧 jar，残留 java 还占着 8084 与 H2 文件锁，新客户端启动即报「后端服务启动失败：后端服务启动超时」。清理只精确匹配 `java.exe` 且命令行必须含 `codecraft.jar`，不会误杀 IDEA 等其它 JVM。
- **修复：启动自愈**。`main.js` 新增后端代际探测（`none` / `current` / `legacy` / `foreign`）：遇到旧版遗留后端先结束再启动；端口被非 CodeCraft 程序占用时直接报出占用者 PID，不再盲目 spawn 一个必然卡死的新 java；启动超时文案附带端口占用者与后端日志路径。另新增 `scripts/fix-startup.bat`，可一键结束残留进程，供已装坏的用户自救。
- **修复：启动失败时看不到任何依据**。java 子进程的 stdout / stderr 此前只写 `console.log`，而打包后的 Windows GUI 应用没有控制台，等于**直接丢弃** —— 于是「java 根本没起来」这类问题在用户侧表现为「8084 没被占用、版本号也对、日志里空无一物」，完全无从排查。现在子进程输出原样落盘到 `runtime/logs/backend-console.log`（文件开头记录平台 / 应用版本 / Java 路径 / JAR 路径 / 工作目录），启动失败的原生弹窗直接附带最后 30 行；`findJava()` 的三级回退（内置 JRE → `JAVA_HOME` → 系统 `PATH`）也会逐步写入该日志；启动前还会先跑一次 `java -version` 预检，Java 跑不起来时直接给出可读原因。
- **修复：启动慢的机器被误判为启动失败**。日志显示后端确实在推进，只是每推进一步要几十秒：`18:52:20` 启动 → `18:53:01` Tomcat 初始化（41s）→ `18:53:39` 解析器注册（79s）→ `18:53:56` 仍未走完（96s+）。原实现等 90 秒就判超时，把「正在启动」当成了「启动失败」（用户看到的日志恰好停在中途）。现改为最长等待 300 秒，超过 60 秒时启动画面提示「首次启动较慢」，进度条停在 92% 不再冲顶以免误导；进程真正退出时仍会立即失败。
- **修复：后端代际探测误判导致「后端根本没启动」却报启动超时（关键缺陷）**。`detectBackendGeneration()` 的兜底分支把「`/v3/` 探测异常（超时 / 5xx / 连接重置）」当成「已是新版后端」，于是 `startBackend()` 直接 `return true` 且**不 spawn 任何 java 进程**，随后 `waitForBackend()` 对着一个没人服务的端口干等满 90 秒并报「后端服务启动超时」。这是 2.x 完全没有的代码路径（2.x 无条件直接启动后端），正好解释「2.0.2 秒开、3.0.0 启动超时」而与应用数据量、磁盘、杀软无关。现改为：探测异常时用 TCP 探测端口 —— 有人监听按 `legacy` 结束可疑进程后重启自己的后端，无人监听按 `none` 正常启动。实测 6 种现场（`/v3/` 500、`/v3/` 挂起、`/v3/` 200、`/v3/` 404、外部程序占端口、端口空闲）判定全部正确。
- **修复：就绪探测可能永久挂起，或把「慢」判成「失败」**。① `waitForBackend()` 的 `http.get` 原先没有请求超时，Tomcat 已 listen 但 Spring 尚在初始化时请求会挂起不返回，轮询再也不被调度、Promise 永久 pending，用户只能看到启动画面一直转圈；现改为单轮 3 秒超时，到点 `destroy` 进入下一轮。② 原先固定轮询次数用尽即判失败，现改为「后端 30 秒内仍有输出就自动续期」，只有「长时间零输出 + 端口不响应」才判失败 —— 真正卡死与单纯慢被彻底区分开。
- **修复：探测地址与页面加载地址由 `localhost` 改为 `127.0.0.1`**。Windows 上 `localhost` 可能优先解析到 `::1`（IPv6），而后端 Tomcat 绑定 IPv4；一旦解析到 `::1`，会出现「TCP 探测（用 `127.0.0.1`）说端口有人监听、HTTP 探测（用 `localhost`）说完全没响应」的自相矛盾判定，进而把正常机器误判成 `legacy` 或空等满超时。
- **修正文档中的错误归因**：此前把「启动慢」统一归因于杀毒软件实时扫描。经实测，同一台机器上 v3.0.0 与 v2.0.4 的空库冷启动分别为 **7.8 秒 / 8.0 秒**（后端 jar 并无退化），且无杀软环境同样会触发上述超时。`docs/FAQ.md` Q19 已改写为四个现场（旧 jar / java 未启动 / 真慢 / 探测误判），并把杀软从「根因」降级为「仅在确实装了时的可能因素」。

### 🧾 版本号同步

- 后端：`2.0.4` → `3.0.0`（`pom.xml`，产物 `code-craft-3.0.0.jar`）
- 经典版前端：`2.0.4` → `3.0.0`（`frontend/package.json` + lock）
- v3 前端：`3.0.0`（`frontend-v3/package.json`，本次保持一致）
- Electron：`2.0.4` → `3.0.0`（`package.json` + lock + `extraResources` jar 路径）
- MCP Server 握手版本：`2.0.4` → `3.0.0`
- 文档：README(_EN) / BUILD_AND_RUN(_EN) / CHANGELOG(_EN) / docs（ARCHITECTURE、DEEPSEEK_SERVICE_IMPL、LLM_PROVIDER_SYSTEM、DEV_QUICKREF、RELEASE_CHECKLIST）统一到 v3.0.0

---

## [2.0.4] - 2026-10-18

### 🎯 新增：GPT（OpenAI 官方专精）适配器 + 适配器选型注释

- **新增 `GptClient`**（模板 `gpt`，别名 `gpt-official` / `chatgpt`），与 `openai`（公共契约）职责严格分离：
  - 输出上限统一 `max_completion_tokens`（普通 8192 / 推理 16384；post-build 注入的 `max_tokens` 自动转换）——`max_tokens` 已废弃，GPT-5 / o 系列会直接 400；
  - `reasoning_effort` 按模型精细下发：GPT-5 支持 `none`/`minimal`，o 系列不支持 `none`（non-thinking 时不下发）；`thinking→medium`、`thinking_max→high`，也支持直接传 `low`/`high`/`xhigh`；
  - 推理模型、或开启推理（effort≠none）时自动移除 `temperature`（官方会拒绝）；
  - 流式自动带 `stream_options: {"include_usage": true}`；
  - 端点默认 `https://api.openai.com/v1/chat/completions`，支持官方代理与自定义完整路径。
- **`OpenAIClient` 回归纯公共契约**：移除「是否官方域名」分支，不再下发 `max_tokens` / `reasoning_effort` / `stream_options`（第三方兼容服务支持度不一，易 400），仅保留协议级处理（端点自适应、推理模型切 `max_completion_tokens`、模型名最后一段归一）。
- **维护性：所有适配器补「适用范围（选型指引）」注释**
  - 9 个适配器类（DeepSeek / 火山方舟 / 千问 / GPT / OpenAI / Anthropic / Ollama / MiMo / MiniMax）Javadoc 顶部统一增加「适用 / 不适用 / 专属能力」三段式说明；
  - `LLMClientManager` 类注释新增「模板选型总览」表（模板 → 适配器 → 适用对象 → 不要用于），新增 Provider 时先看这张表；
  - 文档 `LLM_PROVIDER_SYSTEM.md(+EN)` 新增 4.9（GPT vs OpenAI 分工）与 4.10（适配器选型总表）。
- **前端**：请求模板下拉新增「GPT（OpenAI 官方 / o 系列专用）」，与「OpenAI（公共契约：任意 OpenAI 兼容服务）」并列区分；模板标签区分 `GPT（官方）` / `OpenAI 兼容`，GPT 使用独立图标 💠 与配色。

### 🌋 新增：火山引擎（方舟）Provider 适配（对话 / 图片识别 / 余额 / 联网搜索抓取）

- **对话适配器 `VolcengineArkClient`**（模板 `volcengine`，别名 `ark` / `doubao`）：
  - 端点解析兼容 `baseUrl` 带 / 不带 `/api/v3`、带尾斜杠、已含完整 `chat/completions`（代理网关）四种写法，统一产出绝对 URL，避免 WebClient baseUrl 路径被覆盖导致 404；
  - `thinkingMode` 映射方舟 `thinking.type`：`non-thinking → disabled`、`thinking / thinking_max → enabled`、`auto → auto`；
  - `reasoning_effort`（low/high/max）仅对 `deepseek-v4` 系列下发，其他模型不下发，规避 `400 InvalidParameter`。
- **图片识别（视觉理解）**：
  - `FileVisionService.supportsVisionModel` 增加火山分支：方舟上的 `deepseek-*` 文本模型前置拦截并给出明确提示，`doubao-seed` / `vision` / `-vl` 系列放行；
  - `VolcengineArkClient` 自动把 OpenAI 风格的 `image_url.detail=auto` 归一为 `high`（方舟取值域仅 low/high/xhigh）；
  - 火山不在 Files API 白名单内 → 图片自动走 base64 `image_url` 块降级路径，无需额外配置。
- **余额查询**：新增 `VolcengineBillingClient`（费用中心 OpenAPI `QueryBalanceAcct`，火山 V4 签名：HMAC-SHA256 + Credential Scope + X-Date/X-Content-Sha256），`ProviderBalanceService` 扩展为多平台（DeepSeek + 火山），返回可用余额/现金余额/信控额度/冻结/欠费/账号 ID；VO 增加对应可选字段，前端悬浮提示按平台显示不同语义。
- **联网搜索**：新增 `VolcengineSearchApiClient`（豆包搜索 Custom 版 `POST https://open.feedcoopapi.com/search_api/web_search`）：
  - `web_search` 工具在「当前 Provider 为火山 + 已配置联网搜索 Key」时优先调用官方接口，返回结构化结果（Title/Url/Summary/Content/SiteName/PublishTime/AuthInfoLevel），失败或无结果自动回退 Bing/百度链路；
  - `web_fetch` 工具新增「联网搜索正文」通道（URL 域名 + 路径关键词检索并命中同 URL 正文，best-effort），未命中回退 Jina Reader / 本地抓取；
  - 能力判定基于「请求模板 + 实际域名 + 凭证有效性」，与用户自定义 providerCode 无关；搜索 Key 支持 `extraConfig.searchApiKey` 或环境变量 `VOLCENGINE_SEARCH_API_KEY` / `WEB_SEARCH_API_KEY`。
- **数据模型**：`llm_provider` 新增可选列 `extra_config`（TEXT，平台增强凭证 JSON），建表语句 + 启动自动 `ALTER TABLE` 兼容旧库；`LLMProviderController` 列表接口对 `accessKey/secretKey/searchApiKey/apiKey/token` 掩码返回，提交含掩码视为「未修改」，空串视为清空。

### 🔧 优化：存量适配器对齐最新接口行为

- **`OpenAIClient`**：推理模型判定从「o1/o3 前缀」扩展为 o1/o3/o4 与 gpt-5 系列；默认输出上限 4096 → 8192，推理模型走 `max_completion_tokens`（16384）；
- **`AnthropicClient`**：修复扩展思考模式下的参数冲突——思考开启时 `max_tokens`（16000）严格大于 `budget_tokens`（8000），并移除 `temperature`（Anthropic 要求思考模式 temperature=1，自定义值会 400）；普通模式上限 4096 → 8192；
- **`OllamaClient`**：不再无条件剔除 `tools`（Ollama 0.4+ 已支持 function calling），本地模型恢复工具能力；新增 `think=true` 映射（0.6+ 推理模型）；`temperature` 写入 `options` 时改为合并已有选项而非覆盖。

### 🔎 补充：MiniMax / 小米 MiMo 平台能力适配

调研结论：**MiniMax 的搜索、图片理解、套餐额度都有官方接口可接；MiMo 只有图片理解与（Cookie 方式的）余额；两家都没有公开的网页抓取 API。**

- **新增 `MiniMaxCodingPlanClient`**（Bearer Coding Plan Key，区域 host 跟随 baseUrl：`api.minimaxi.com` / `api.minimax.io`，可用 `extraConfig.apiHost` 覆盖）：
  - 联网搜索：`POST /v1/coding_plan/search`（`{"q":"..."}`）→ 解析 `organic[title/link/snippet/date]`；`web_search` 工具在该通道可用时优先调用，失败/无结果自动回退 Bing/百度；
  - 图片理解：`POST /v1/coding_plan/vlm`（prompt + image_url，支持公网 URL 与 base64，JPEG/PNG/WebP）；
  - 套餐额度：`GET /v1/token_plan/remains` → 剩余百分比 / 剩余次数 / 重置时间（注意 `current_interval_usage_count` 实为「剩余」，解析优先用 `remaining_percent`）。
- **`ProviderBalanceService` 扩展为四平台**：DeepSeek（余额）、火山引擎（余额）、MiniMax（`balanceType=quota` 套餐额度）、小米 MiMo（控制台 Cookie 余额 `platform.xiaomimimo.com/api/v1/balance`，约 24h 有效，失效给明确提示）。
- **`ProviderBalanceVO` 增加 `balanceType` 与额度字段**（`remainingPercent` / `remainingCount` / `totalCount` / `resetAt` / `planName`），前端聊天页额度型展示为「💰 额度 67%」，悬浮提示含套餐名、次数与重置时间。
- **图片识别兼容**：`FileVisionService.supportsVisionModel` 增加 MiMo（v2.5 系列）与 MiniMax（M3 / VL / abab-vl，M2.x 为纯文本）判定；`AbstractLLMClient.stripImageDetail` 供 MiMo / MiniMax 适配器剔除协议未声明的 `image_url.detail` 字段，避免严格校验 400。
- **不做的部分（如实说明）**：MiniMax 无官方网页抓取接口，MiMo 无官方搜索/抓取接口 → `web_fetch` 继续走 Jina 与本地抓取；MiMo 现金余额官方未开放 OpenAPI，只能靠控制台 Cookie。

### 🐱 新增：通义千问（阿里云百炼 DashScope / QwenCloud）适配器

- **新增 `QwenClient`**（模板 `qwen`，别名 `dashscope` / `tongyi`）：OpenAI 兼容协议 + Qwen 扩展参数
  - 端点自适应：`https://dashscope.aliyuncs.com` → 自动补 `/compatible-mode/v1/chat/completions`；工作空间专属域名 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` 直接可用；已写全 `/chat/completions` 的中转站地址原样使用（绝对 URL，避免 baseUrl 路径被覆盖导致 404）
  - 思考模式：`thinkingMode` → `enable_thinking`（`non-thinking=false`、`thinking / thinking_max=true`）+ `thinking_budget`（thinking_max 默认 8192，可用 `extraConfig.thinkingBudget` 覆盖；空模式不下发，交给模型默认策略）
  - 对话级联网：`extraConfig.enableSearch=true` → 请求体下发 `enable_search: true` + `search_options`（DashScope 不支持 OpenAI 的 `web_search_options`）
  - 图片：自动剔除 DashScope 未声明的 `image_url.detail` 字段
- **新增 `AliyunBillingClient`**（余额查询）：阿里云费用中心 `QueryAccountBalance`（BssOpenApi 2017-12-14），自实现 **RPC 风格 HMAC-SHA1 签名**（参数排序 → percentEncode → StringToSign → Base64(HMAC-SHA1(SK+"&", ...))），返回可用余额 / 现金余额 / 信用额度 / 币种；凭证走 `extraConfig.accessKey` / `secretKey`（与百炼 API Key 是两套）。
- **`FileVisionService`** 增加 Qwen 视觉判定：Qwen-VL / Qwen3-VL / Omni 系列放行，`qwen-plus` / `qwen-max` 等纯文本模型前置拦截。
- **`ProviderBalanceService` 扩展为五平台**：DeepSeek / 火山引擎 / MiniMax（额度）/ MiMo（Cookie）/ 通义千问（阿里云余额）。

### 🔌 重构：OpenAI / Anthropic 升级为「公共契约」适配器

> 目标：用户通过这两个模板可以接入**任意符合协议规范的服务**（官方、Azure、OpenRouter 与中转站、vLLM / LM Studio / One-API 自建网关、GLM / DeepSeek / MiniMax 的 `/anthropic` 兼容层）。

- **`OpenAIClient`**：
  - 端点智能解析（裸域名补 `/v1`、已含 `/v1` 不重复、完整 URL 原样使用，统一绝对 URL）；
  - **参数分级下发**：只有官方 `api.openai.com` 才下发 `max_tokens`（8192）与 `reasoning_effort`；第三方兼容服务不下发，避免各家上限/支持度差异导致 400；
  - 模型名归一（`openai/gpt-4o`、`azure/o3-mini` 按最后一段判定），推理模型仍自动切 `max_completion_tokens` 并移除 `temperature`，不会因中转站命名误判；
  - 清理其他适配器残留的内部标记字段。
- **`AnthropicClient`**：
  - 端点智能解析（裸域名 → `/v1/messages`、`/anthropic` 兼容层 → `/anthropic/v1/messages`、完整 URL 原样使用）；
  - **图片块协议转换（新增能力）**：内部统一的 OpenAI 风格 `image_url` 块自动转成 Anthropic `image/source`（`data:` URL → `base64` + `media_type`，http(s) → `url`），让 Claude 兼容服务也能正常识图；
  - `thinking` **只在开启时下发**（`non-thinking` 不发送该字段，规避兼容层 400）；`max_tokens` 支持 `extraConfig.maxTokens` 覆盖，思考模式自动放宽到 `budget + 8192` 满足 `max_tokens > budget_tokens`；
  - 工具转换修正通配符类型问题（`parameters` / `description` 缺省值安全处理），并保留 `tool_choice` 移除策略。

### 🖥️ 前端

- Provider 管理页（`ProviderConfigView` / `ConfigView`）：请求模板下拉新增「火山方舟（豆包 / OpenAI 兼容）」「通义千问（阿里云百炼 / OpenAI 兼容）」，OpenAI / Anthropic 选项标注为「公共契约（任意兼容服务）」；新增「扩展配置」JSON 输入（带合法性校验）；模板标签/配色/图标补充 `volcengine` / `ark` / `doubao` / `qwen` / `dashscope` / `tongyi`；扩展配置提示汇总各平台凭证（火山 AK/SK + searchApiKey、通义 AK/SK + enableSearch、MiniMax codingPlanKey、MiMo mimoCookie、Anthropic maxTokens）；
- 聊天页余额展示：从「仅 DeepSeek」扩展为 DeepSeek + 火山引擎 + MiniMax + MiMo + 通义千问（`BALANCE_TEMPLATES`），支持 `quota` 类型（额度百分比）与各平台差异化悬浮提示（通义显示可用余额/现金余额/信用额度）。

### ✅ 测试

- 新增 `VolcengineAdapterTest`（32 断言）：端点解析 4 例、thinking 映射 5 例、reasoning_effort 3 例、多模态 detail 归一、V4 签名结构与确定性 6 例、联网搜索解析 5 例、URL 关键词 3 例、vision 能力判定 5 例；
- 新增 `MiniMaxAdapterTest`（29 断言）：搜索解析 9 例、图片理解 3 例、额度解析 6 例、区域 host 4 例、视觉判定 6 例；
- 新增 `QwenAdapterTest`（58 断言）：千问端点/思考/联网/图片剔除、OpenAI 公共契约边界、Anthropic 端点与图片协议转换、阿里云 RPC 签名、千问视觉判定；
- 新增 `GptAdapterTest`（31 断言）：GPT 端点、reasoning_effort 全档位映射、max_completion_tokens、temperature 移除、stream_options、注入 max_tokens 转换；
- `SearchToolLogicTest`（16 断言）、`DeepSeekToolsApiClientTest`（26 断言）回归通过。

### 🔖 版本号同步（2.0.3 → 2.0.4）

- 后端：`2.0.3` → `2.0.4`（pom.xml）
- 前端：`2.0.3` → `2.0.4`（package.json + package-lock.json）
- Electron：`2.0.3` → `2.0.4`（package.json + package-lock.json，打包 jar 路径同步 `code-craft-2.0.4.jar`）
- MCP Server 握手版本：`2.0.3` → `2.0.4`（`CodeCraftMcpServer.SERVER_VERSION`）
- 打包产物：`code-craft-2.0.3.jar` → `code-craft-2.0.4.jar`
- 说明文档：README(_EN) / BUILD_AND_RUN(_EN) / CHANGELOG(_EN) / docs（ARCHITECTURE、DEEPSEEK_SERVICE_IMPL、LLM_PROVIDER_SYSTEM、DEV_QUICKREF、RELEASE_CHECKLIST 等）同步至 v2.0.4，平台支持列表补充 GPT / 通义千问 / 火山方舟

---

## [2.0.3] - 2026-10-17

### 💬 修复：聊天记录渲染重叠 / 位置错乱 / 超长会话卡顿

- **后端新增「按轮」游标分页接口** `GET /conversation/{conversationId}/messages/page?beforeId=&rounds=`：
  - 以 `role=USER`（排除 `desktop_inject` 注入消息）为「轮」边界，边界只落在用户提问上，`processMessageGroups` 不会跨页聚合；
  - 默认返回最近 5 轮，`rounds` 上限 50；响应含 `messages`（id 升序）+ `hasMore` + `nextBeforeId`；
  - 游标为 `beforeId`（上一页最早消息 id），避免 offset 分页在新消息写入后错位。
- **前端改为懒加载**：首屏只取最近 5 轮 → 上滑到顶部（`scrollTop ≤ 80`）自动加载更早 5 轮 → `nextBeforeId` 持续向前翻；本地新建会话（`local-*`）迁移真实 ID 时同步迁移分页状态。
- **加载历史后阅读位置不漂移**：`preserveScrollAfterPrepend` 改为双向补偿（`delta !== 0` 即补偿），并加入用户滚动检测（偏差 > 48px 视为用户主动滚动，放弃补偿）。
- **渲染重叠 / 位置错误修复**：
  - 虚拟列表按会话强制重建（`:key="currentConversationId"`）；消息 id 全局唯一（`user-` / `assistant-` / `reconnect-` + `localMsgSeq`），并保留重复 id 兜底；
  - 移除消息入场动画与 `transition: all`（高度测量期动画会让动态列表估算错位）；`min-item-size` 80 → 120；
  - 新增 `v-track-msg-size` 指令 + `ResizeObserver`：内容真实高度变化（代码块渲染 / 图片加载 / 折叠展开）后 120ms 节流、差异超过 1.5px 才触发 `forceUpdate()` 自愈，修正滚动条抖动与条目错位；
  - 切换会话 / 切换 Agent 恢复快照后强制贴底（`scrollToBottomForce`：token 取消 + 逐帧贴底 + 高度稳定 3 帧判定），修复切回 Agent 后列表停在顶部。
- **历史加载不再被「拽到底部」**：新增 `lastHistoryPrependAt`（500ms 窗口）标记，避免「加载历史 → 消息数变化 → watcher 误判为新消息 → 强制贴底」。
- **`loadingMoreMessages` 按会话隔离**（`loadingMoreConvIds`）：并行加载多个会话历史不再互相阻塞，顶部「加载中」提示不再串台。
- **多智能体并行状态修复**：
  - `hasRunningTask` / `hasActiveStream` 统一覆盖四类运行态（前台流 `sendingStates` / 后台流 `backgroundStreams` / 重连中 `reconnectingConvIds` / 活跃任务 `activeTaskMap`）——切走智能体再切回，「补充需求」「结束当前任务」按钮与输入框 loading 均能恢复；
  - `onStreamComplete` 按「本流」`abortController` 精确移除后台流条目（同一会话可并存多条后台流，原按 `convId` 全量移除会摘掉仍在运行的流，导致停止按钮失效）；
  - 后台任务完成通知的 Agent 名称/头像改为按「本流归属 agentConfigId」精确取（原取当前选中 Agent，多智能体并行时头像/名字货不对板）。

### 🧠 修复：工具调用序列孤立导致的接口 400（会话永久卡死）

- **新增 `ToolCallSequenceSanitizer`（幂等 / 纯内存 / copy-on-write / 不改数据库）**，在「唯一请求出口」统一净化，取代历史上「只删 `assistant.tool_calls`」的局部修复（那样会让已落库的 tool 消息变成孤立 tool 消息，接口同样 400，反复修不好）：
  - 丢弃孤立 `tool` 消息（同轮内找不到 id 匹配、尚未被消费的 tool_call）；
  - 逐个剔除「无结果」的 `tool_call`；全部无结果时清空 `tool_calls`（assistant 退化为普通文本，内容保留）；
  - 丢弃清洗后既无 `content` 又无 `tool_calls` 的空 assistant 消息；
  - 剔除无 id / id 为 `"null"` / 重复 id 的非法调用项。
- **接入全部请求出口**：`ContextBuilder`（消息转换后 + 压缩/裁剪后各净化一次）、`AbstractLLMClient` / `AnthropicClient` / `OllamaClient` 的请求体构造；`ContextBuilder` 同时移除旧的孤立 tool_calls 局部修复逻辑。
- **写入侧加固**：`DeepSeekServiceImpl.appendToolResultMessages` 为本批次「已声明但没有拿到结果」的 tool_call 补一条「【工具未执行】」占位 tool 消息（任务被取消 / 工具返回 null / 结果构建异常场景），从源头避免序列缺口。
- **回归测试**：新增 `ToolCallSequenceSanitizerTest`（36 断言，覆盖孤立 tool、缺失结果、非法 id、重复 id、幂等性）。

### 🤖 优化：评委机制不再硬终止，改由用户决策

- **评委否决（reject）/ 响应解析失败 / 评委调用异常且本地判定无进展 / 累计扩容达 `MAX_JUDGE_GRANTED_ITERATIONS=100` 上限** → 全部改为通过 `ask_user` 询问用户（`askType=iteration_confirm`），不再直接终止任务。
- **新增「任务迭代确认」面板**：展示触发原因、评委理由、当前进度、当前迭代（`n / max`）与累计扩容（`x / 100`），提供「继续执行 +10 / +20 / +50 次」与「结束任务」；等待超时 5 分钟按「结束任务」处理并清理待处理问题，避免悬挂。
- 页面刷新 / 任务重连后，按问题文本前缀 `【任务迭代确认】` 还原面板类型，不会退化成普通追问。

### 🔄 新增：桌面端自动更新（基于 GitHub Releases）

- **新增 `electron/update-manager.js`**：检测 GitHub Releases（`/releases/latest`，6 小时结果缓存 + 手动检查最小间隔 30 秒，规避未认证 API 60 次/小时限流）→ 页面内带进度下载（镜像前缀加速，失败自动回退直连）→ 分平台安装（Windows 启动安装器并退出；macOS 打开 dmg 引导拖拽；Linux 在文件管理器中定位 AppImage）。
- **新增 `electron/update-utils.js`**：语义化版本比较（支持 `v` 前缀 / 预发布）与发布资产按「平台 + 架构」匹配，可单独单测。
- **升级不丢数据（三层保护）**：
  - 后端工作目录（cwd）由安装目录迁至 `userData/runtime`，含老数据自动迁移、`legacy-backup` 恢复、迁移失败回退；
  - 升级前停止后端进程，并把 `userData/runtime/data` 备份到 `userData/backups`；
  - 新增 `electron/installer.nsh`（NSIS `customInit`）：首次升级前备份旧数据目录，避免覆盖安装清库。
- **前端**：新增 `frontend/src/utils/update.ts`（IPC 封装 + 分平台引导文案）；`SystemSettingView` 新增「版本与更新」卡片（当前版本 / 最新版本 / 检查更新 / 下载进度 / 一键安装 / 启动时自动检查开关 / 打开发布页，浏览器环境展示降级提示）；`Layout.vue` 启动 15 秒后自动检查，发现新版本弹出通知并可跳转。
- **本地数据重置联动**：`clearLocalData` 一并清理 `backups` / `legacy-backup` / 安装包 / 更新缓存。
- **preload 新增**：`update.*`（getState / check / download / cancelDownload / install / openReleasePage / getSettings / setSettings / onAvailable / onDownloadProgress）与 `clearLocalData`。

### 🌐 新增：DeepSeek 官方 web_search / web_fetch 接口

- **新增 `DeepSeekToolsApiClient`**：当 Provider 的 `baseUrl` 主机为 `api.deepseek.com` 且已配置 API Key 时自动启用（第三方中转 / 自建兼容服务不启用，避免误发请求），复用 `LLMWebClientManager` 的认证头、连接池与重试策略：
  - `POST /v1/tools/search`：query / count / need_content 等参数，解析 `results[]`（title / url / snippet / summary / content / site_name / date / authority / rerank_score）；
  - `POST /v1/tools/fetch`：url → title + Markdown 正文。
- **`SearchTool` / `WebFetchTool` 改为「官方优先 + 失败回退」**：官方接口异常 / 超时 / 非官方 Provider 时自动回退原链路（Bing/百度多源抓取、Jina/本地抓取），保证工具始终可用；`WebFetchTool` 官方通道仅对公网地址启用。
- **`AgentForkManager`**：子 Agent 补 `ToolContext.setProviderCode(...)`，保证子 Agent 内同样按 Provider 正确判定。
- **回归测试**：新增 `DeepSeekToolsApiClientTest`（26 断言，main 方法风格）。

### 🏷️ 版本号

- 后端：`2.0.2` → `2.0.3`（pom.xml）
- 前端：`2.0.2` → `2.0.3`（package.json + package-lock.json）
- Electron：`2.0.2` → `2.0.3`（package.json + package-lock.json，打包 jar 路径同步 `code-craft-2.0.3.jar`）
- MCP Server 握手版本：`2.0.2` → `2.0.3`
- 打包产物：`code-craft-2.0.2.jar` → `code-craft-2.0.3.jar`
- 说明文档：README(_EN) / BUILD_AND_RUN(_EN) / CHANGELOG(_EN) / docs（ARCHITECTURE、DEEPSEEK_SERVICE_IMPL、TOOL_SYSTEM、LLM_PROVIDER_SYSTEM、DEV_QUICKREF、RELEASE_CHECKLIST 等）同步至 v2.0.3

---

## [2.0.2] - 2026-09-24

### 🎨 新增：外观设置（全局背景图 / 窗口透明度）

- **侧边栏底部新增「外观设置」抽屉**（AppearanceSettingsDrawer）：上传全局背景图（≤10MB）后整个应用（侧边栏 / 内容区 / 标题栏）透出背景图；提供「背景不透明度 / 背景模糊 / 窗口整体透明度」三滑块与「恢复默认」
- **后端**：新增 BackgroundStore（文件存至 `data/backgrounds/`）与 AppearanceController——`POST /api/appearance/background`（需登录）上传并返回 URL；`GET /api/appearance/file/{name}` 免鉴权供 CSS/img 引用
- **前端**：App.vue 新增全局背景层（fixed 铺满），style.css 新增 `html.has-app-bg` 半透明规则链（根容器透明 + 业务页半透明面板）；外观设置持久化（bgImageUrl / bgOpacity / bgBlur / windowOpacity）
- **Electron**：preload 暴露 `setWindowOpacity` → 主进程 `mainWindow.setOpacity()`（clamp 0.2~1，异常静默）——「窗口整体透明度」对含标题栏的整个窗口生效

### 🖥️ 桌面端：透明自定义标题栏 + 启动画面（Splash）

- **透明标题栏**：标题栏覆盖色改为全透明——标题栏区域直接露出网页内容（开启背景图时随应用背景变化）；窗口控制按钮符号色随深浅主题切换
- **启动画面**：新增 `electron/loading.html` 与 Splash 窗口（居中悬浮、无边框透明、鼠标穿透、不抢焦点、最短展示 600ms）：
  - 进度双通道：Spring Boot 启动日志锚点（Starting / Tomcat initialized / Tomcat started / Started）跳跃推进 + 时间维度平滑兜底
  - 就绪后进度补满 + 页面淡出动画衔接主窗口；后端进程提前退出 → 立即失败（展示错误原因 + 原生弹窗，不再空等轮询超时）
- **preload 新增 `openExternal`**：应用内网页标签页的「在浏览器打开」按钮调起系统默认浏览器

### 🧩 重构：技能系统改为「渐进式披露」（Progressive Disclosure）

- **新增 SkillCatalogBuilder（L1 目录层）**：把全部活跃技能压缩为「技能目录」（名称 / ID / 置信度 / 触发词 / 一句话描述）动态注入 system prompt，LLM 始终拥有全量技能全局视野；目录受 3500 字符预算约束，超出按置信度降序截断
- **技能说明按需加载（L2 层）**：LLM 通过 `skill action=load` 读取完整使用说明——从「BM25 自动匹配注入」改为「目录常驻 + LLM 主动加载」，不再有隐形匹配过程
- **移除 SkillIndexer / SkillMatcher**（BM25 + 触发词匹配）与 SSE 事件 `skill_match`；前端「思考过程」改为展示「命中技能」标签（LLM 实际执行 skill load 时动态追加）

### 🌐 优化：网络工具（搜索源 / 缓存 / 反爬 / Jina / 代理自适应）

- **web_search 搜索源升级**：多搜索源优先级——有代理时 Bing 全球 → Bing 中国 → 百度；无代理（国内直连兜底）仅保留国内可达源；新增搜索结果缓存（TTL 300s / 最多 100 条）；低质量域名（如百家号）排序降权
- **web_fetch 升级为「Jina 优先 + 本地降级」**：本机有可用代理时优先走 Jina Reader 渲染抓取（反爬 / JS 渲染页友好）；Jina 不可用（429 配额受限进入冷却）或本机无代理时自动降级本地抓取
- **本地抓取加固**：UA 池按域名稳定选择 + 失败换 UA 重试（403/429/503/5xx/超时/反爬拦截页识别，429 优先遵循 Retry-After）
- **代理策略重构为「自动探测」**：`network-tool.proxy.mode` = auto（默认）/ always / never；AUTO 模式依次探测候选端口（Clash 7890 / Clash Verge 7897 / socks 7891 / v2rayN 10809）+ 出网验证（识别「端口在监听但代理已失效」）+ 分向缓存（走代理 300s / 直连 30s，中途启动代理可快速自适应）
- **废弃 `web-fetch.proxy`**（WebFetchConfig 删除）：所有网络工具代理统一由 `network-tool.proxy` 管理；新增 ProxyResolver 组件与单元测试

### 💾 新增：数据管理（备份导出 / 导入）

- **「数据管理」页新增「导出备份 / 导入备份」**：以「表级快照」JSON 导出 7 个类别（LLM 配置 / 智能体 / 技能 / 聊天记录 / 踩坑经验 / MCP 服务器 / 数据库连接，共 10 张表）；导入按主键 UPSERT 合并（同 id 覆盖、新 id 追加，不清空现有数据），并自动修复自增序列
- **后端**：新增 DataTransferService；SystemController 新增 `/data-stats`、`/export-data`、`/import-data` 端点（管理员）；导入完成后自动刷新 LLM Provider 缓存（新导入配置立即生效）；单行失败不中断（返回新增/更新/失败统计）
- **安全提示**：备份文件含 API Key、数据库密码密文等敏感信息（用于重装 / 跨设备迁移后免重新配置），前端已提示妥善保管

### 🗄️ 增强：数据库连接（管理页「执行 SQL」）

- **「数据库连接」页新增「执行 SQL」抽屉**（SqlExecutorDrawer）：选连接 → 输入 SQL（Ctrl+Enter 执行）→ 结果表格化展示——多条语句按顺序执行、单条错误独立定位、最大行数可选（100~1000）、展示执行语句数 / 总耗时 / 影响行数 / 截断提示
- **新增 SqlExecutor 核心组件**（管理页与 AI 工具 `execute_sql` 共用）：双档检查策略——严格模式（AI 工具：单语句 + 白名单 + 5000 字符）与宽松模式（管理页：多语句 + 20000 字符）；单条 SELECT 无 LIMIT 自动追加；语句级 30s 超时
- **API**：新增 `POST /api/db-connections/{id}/execute`；前端通用请求支持自定义超时（长耗时 SQL 场景）

### 🖼️ 新增：头像上传（用户 / 智能体）

- **支持上传本地头像图片**（≤5MB，png/jpg/jpeg/gif/webp/bmp）：新增 AvatarController + AvatarStore（文件存至 `data/avatars/`）；`GET /api/avatar/file/{name}` 免鉴权供 img 标签直接引用
- **前端**：新增 AvatarUploader 组件；个人资料页、智能体配置页、聊天头像展示接入（图片头像 / 默认 emoji 双形态）
- **数据库**：`agent_config.avatar` 列扩容 VARCHAR(20) → VARCHAR(255)（存储本地 URL；老库启动时自动迁移）

### 📎 新增：应用内查看（文件 / 图片 / 链接标签页）

- **AI 回复中的项目文件路径、图片、外链可直接点击**，在应用内以标签页打开（可随时关闭）：
  - Markdown 渲染管线扩展：图片语法 / 链接语法 / 行内代码 / 纯文本中的本地路径自动转为可点击链接（文件芯片 / 图片芯片）
  - 本地文件 → 文件标签页；本地图片 → 图片标签页（blob 加载）；http(s) 外链 → 内嵌 `<webview>` 网页标签页（支持刷新与「在浏览器打开」）
- **后端**：ProjectService 新增 `resolveFilePath`（不完整路径 → 项目内候选解析，精确匹配在前、最多 50 个、排除 node_modules 等黑名单）与 `readFileBytes`；ProjectController 新增 `GET /api/project/resolve`、`GET /api/project/raw`（按扩展名推断 Content-Type）
- **提示词**：核心 Agent 提示词新增「内容引用规范」与「应用内查看」说明（相对路径写法 / 图片语法 / 外链格式）

### ⚠️ 增强：LLM 接口错误友好提示（task_error）

- **新增 LLMErrorFormatter**：将 LLM API 底层异常按 DeepSeek 官方错误码表（400 / 401 / 402 / 422 / 429 / 500 / 503 + 上下文超长特判 + 网络类异常）转为「标题 + 原因 + 解决建议」中文提示
- **统一任务级错误出口**（工具循环异常 / 无工具直连）下发 `task_error` SSE 事件；错误信息同步写入 `agent_task.error_message`
- **任务中断恢复**：任务状态接口附带「最近一次失败任务」摘要 → 页面刷新后恢复聊天区错误详情 + 输入区「任务中断」横幅与「一键继续」入口；AgentEventBus 新增身份校验发射（错误事件只发本任务通道，避免误发新任务流）
- **P2P Agent** 同步处理 `task_error` 事件（远程任务携带友好失败原因）

### 🗂️ 优化：侧边栏菜单结构（4 组重排）

- **菜单收敛为 4 组 + 底部管理**：「对话」/「AI 能力」（智能体、技能、MCP 服务器、定时任务、踩坑经验）/「数据与工具」（数据库连接、文件管理、设备互联）/「系统」（模型配置、运行日志、数据管理）+ 底部（个人信息、用户管理、菜单权限管理）
- **命名优化**：「协作」→「设备互联」、「配置」→「模型配置」
- **前端**：Layout.vue 改为 MENU_GROUPS 配置化渲染（空组自动隐藏）+ 组间并行加载；MenuPermissionView 与 api/menu.ts 同步 5 类菜单枚举
- **数据库**：schema.sql 追加幂等迁移（分组 / 排序 / 命名条件式 UPDATE + 普通用户「AI 助手」断链修复）；新增 SchemaMenuMigrationTest 回归测试

### 🛠️ 修复与调整

- **全新库初始化修复**：`agent_config` 表内联索引改为 H2 兼容的 `CREATE INDEX IF NOT EXISTS` 写法（原索引名与 conversation 表重名导致全新库 agent_config 建表失败）
- **提示词**：核心 Agent 提示词精简（移除冗余语言强制段）；聊天流式状态条背景透明化（与背景图融为一体）
- **应用内查看修复**（点击 LLM 输出的文件 / 图片链接）：
  - 修复「标签已打开但内容空白 / 图片一直加载中」：tab 对象改用 `reactive` 包装——原实现入列后对原始对象直接异步赋值，不触发 Vue 响应式更新
  - 路径拼接统一为正斜杠：原 `workDir + '/' + path` 会拼出 `C:\dir/sub/file` 混合形式（显示观感差、跨入口去重不可靠）；文件树双击 / Git 操作 / 授权面板的同类拼接一并统一，tab 去重做规范化比较
  - 修复纯文件名（如 `README.md` / `main.py`）被 linkify 误识别为 http 链接（`.md` / `.py` 恰为有效 TLD）——现还原为本地文件链接（走「点击打开文件」逻辑；真域名 `example.com` 不受影响）
  - 文件编辑器 / 图片 / 网页标签适配背景图透明化：深色编辑器降为半透明 + 毛玻璃（头部 / 行号区 / 高亮层去底），图片 / 网页标签工具栏半透明

### 🏷️ 版本号

- 后端：`2.0.1` → `2.0.2`（pom.xml）
- 前端：`2.0.1` → `2.0.2`（package.json + package-lock.json）
- Electron：`2.0.1` → `2.0.2`（打包 jar 路径同步 `code-craft-2.0.2.jar`）
- MCP Server 握手版本：`2.0.1` → `2.0.2`
- 打包产物：`code-craft-2.0.1.jar` → `code-craft-2.0.2.jar`
- 说明文档：README(_EN) / BUILD_AND_RUN(_EN) / docs（ARCHITECTURE、DEEPSEEK_SERVICE_IMPL、TOOL_SYSTEM、LLM_PROVIDER_SYSTEM、DEV_QUICKREF、RELEASE_CHECKLIST 等）同步至 v2.0.2

---

## [2.0.1] - 2026-09-22

### 🔧 增强：Git 工具家族功能补齐（AI 工具 / REST / 前端 UI 三层同步）

- **新增 3 个 Git 工具（内置工具数 24 → 27）**：`git_stash`（储藏管理 save/list/pop/drop）、`git_tag`（标签管理 list/create/delete/push）、`git_remote`（远程仓库管理 list/add/set_url/remove）
- **`git_submit` 扩展 6 个 action**：pull（拉取并合并）、fetch（获取远程更新）、unstage（取消暂存）、restore（还原工作区改动）、reset（回退提交，--hard 必须 confirm=true）、revert（撤销指定提交）；修复推送被拒提示「请先 git pull」但系统无 pull 工具的死循环文案
- **`git_query` 新增 show action**：查看某次提交详情或某文件在该提交中的改动（ref/file/stat 参数，超长输出自动截断）
- **`git_branch` 新增 merge action**：将指定分支合并到当前分支（no_ff/message 参数）；create 支持 switch=true 创建并切换；修复切换失败提示「必要时 stash」但系统无 stash 工具的死循环文案
- **权限注解统一**：`git_query` / `git_submit` 补充 `@ToolPermission`（写类工具 affectsData=true，manual 模式审批；此前 git_submit 无注解不受审批）
- **REST 层**：GitController 新增 22 个端点（pull / push / fetch / stash×4 / merge / reset / revert / branches / branch×3 / tags / tag×3 / remotes / remote×2）；/restore 支持 all 参数
- **前端**：GitSidebar 新增同步按钮（拉取/推送/获取）、储藏/分支/标签折叠区、文件还原按钮；修复「取消暂存」误调 /add 接口的 bug；`api/git.ts` 新增 18+ API 函数；CodeAssistantView 工具标签映射同步
- **配置与文档**：application.yml tool-groups 与核心提示词工具名单更新为 6 个 Git 工具；README(_EN) / BUILD_AND_RUN(_EN) / docs（TOOL_SYSTEM(_EN)、ARCHITECTURE(_EN)、MCP_SYSTEM）同步（内置工具数 24 → 27）

### 🐛 修复：子 Agent 重复调用检测「误杀」正常批处理（F3）

- **根因**：fork 循环（AgentForkManager）的重复检测判据仅比对「第一个工具调用的工具名」，连续读取不同文件 / 搜索不同关键词等正常批处理被误判为死循环；且终止处置为零日志 break、状态保持初始值（completed）——表现为「子 Agent 静默提前停止且无任何痕迹可查」
- **修复**：①判据对齐主循环 ToolLoopManager（「相同工具 + 相同关键参数」才计次，全量遍历所有工具调用），删除本地残缺实现；②重复检测终止补 log.warn + `completionSuspicious=true`（复用 ⚠️ 核验通道）；③评委终止 / 用户拒绝 / API 解析失败三处退出路径统一补「疑似未完成」标记（退出路径收口）；④`ToolLoopManager.extractToolKey` 的 file_explorer/file_writer 签名纳入 `pattern`（grep/glob 关键词）——连续搜索不同关键词不再误判
- **验证**：新增回归测试 SubAgentRepeatDetectTest（8 用例，含「连续读不同文件不误杀 / 真死循环仍检测」双向断言），8/8 通过

### 🐛 修复：file_explorer 的 grep 无法搜索 .log 等非白名单文本类型文件

- **根因**：grep 按 `TEXT_EXTENSIONS` 白名单过滤文件，`.log`（及 .csv/.diff/.mjs 等常见文本类型）不在名单中被静默跳过；且显式传入 `include` 也无法绕过——日志文件内容永远搜不到（与文件大小无关，372KB 小日志同样受影响；read 无此限制，行为割裂易误判「文件里没有该内容」）
- **修复**：①白名单扩容（.mjs/.cjs/.log/.csv/.tsv/.diff/.patch/.rst/.adoc/.jsonl/.ndjson/.lock）；②新增 include 逃生通道（显式指定类型时不再套用默认白名单；二进制拦截保留）；③0 命中提示新增「特殊后缀用 include 显式指定」建议
- **验证**：新增回归测试 FileExplorerGrepLogTest（6 用例，红绿对照：修复前 2/6 → 修复后 6/6 通过）

### 🏷️ 版本号

- 后端：`2.0.0` → `2.0.1`（pom.xml）
- 前端：`2.0.0` → `2.0.1`（package.json + package-lock.json）
- Electron：`2.0.0` → `2.0.1`（打包 jar 路径同步 `code-craft-2.0.1.jar`）
- MCP Server 握手版本：`2.0.0` → `2.0.1`
- 打包产物：`code-craft-2.0.0.jar` → `code-craft-2.0.1.jar`
- 说明文档：BUILD_AND_RUN(_EN) / docs（ARCHITECTURE、DEEPSEEK_SERVICE_IMPL、LLM_PROVIDER_SYSTEM、TOOL_SYSTEM、DEV_QUICKREF、RELEASE_CHECKLIST 等）同步至 v2.0.1

---

## [2.0.0] - 2026-09-16

### 🖼️ 新增：图像理解与文件管理（Vision / Files API）

- **粘贴/上传图片直通 LLM**：聊天中 Ctrl+V 粘贴或上传图片（≤64MB），经云端 Files API 以 file_id 引用注入 user 消息（不支持 Files 的 Provider 自动 base64 降级）；支持多图与历史消息图片回显
- **Files API 适配体系**：新增 FilesApiClient 抽象层（FilesApiClient 接口 + AbstractFilesApiClient + DeepSeek/Anthropic 双实现 + FilesApiClientManager），仿 LLMClient 适配器模式按 Provider 路由
- **文件资产模块**：新增 file_asset / file_reference 两表与 FileAssetController / FileAssetMapper / FileReferenceMapper / service/files（FileAssetService / FileAssetStore / FileVisionService）；文件永久保存（不再传 expires_after），引用归零时级联删除（含 orphan 补偿重试）
- **独立文件管理页**（SETTING 菜单）：上传 / 列表 / 重命名 / 删除 / 预览、「引用到会话」双入口；粘贴大图自动压缩
- **文档附件统一（M7）**：storage_type 区分 cloud（图片走云端 Files API）/ local（PDF/Word/Excel/文本存本地）；LLM 读取文档统一走 chat_attachment 的 read_by_file_asset 通道
- **前端**：聊天页图片消息组件与隐私提示、FileManageView 页面、file-asset API 模块

### 🪟 新增：桌面自动化（Computer Use）

- **新增 `screen_capture` 工具（第 23 个）**：截屏 → 分辨率标准化 → 自动上传 Files API → 以 user 消息注入上下文；截图叠加 10×10 网格 + 四边三级刻度（25/50/100）辅助坐标读数；支持 region 局部放大（放大镜）与 mark 准星标记（估坐标 → 带标记截图 → 目测校正 → 操作）
- **新增 `desktop_control` 工具（第 24 个）**：桌面键鼠模拟（mouse_move / mouse_click / mouse_drag / mouse_scroll / key / type / screen_size），单次支持 1~20 个批量动作（也兼容单动作）；坐标采用 0~1000 归一化协议（基于最近一次截图基准）
- **安全机制**：DESKTOP 高危权限分类（授权弹窗展示动作摘要）+ 坐标准星预检闸门（点击/拖拽前未校验坐标时先返回带准星的校验图，预检轮经 SideEffectFreePreflight 免弹窗）+ 红线约束（禁止密码/支付类输入）
- **跨平台**：DesktopPlatformAdapter 平台适配层（Windows / macOS / Linux）；application.yml 新增 desktop 配置块（capture / control）

### ⚡ 优化：流式渲染性能（stream-render-perf M1~M5）

- **前端渲染**：分段稳定渲染、更新管线去冗余、巨型消息瘦身、流式降级渲染（超大消息流式期间降级为纯文本，结束后恢复 Markdown）
- **后端 SSE 聚合推送（M5）**：SseBatchUtil 按输出边界（50ms / 32 条）批量合并推送，前端零改动

### 💰 新增：Provider 余额查询

- 新增 ProviderBalanceController / ProviderBalanceService（对接 DeepSeek `/user/balance`）；聊天页进入与每轮对话结束后自动刷新余额展示

### 🛠️ 修复与增强

- **LLM 网络重试增强**：LLMWebClientManager 网络异常自动重试与认证归属修复
- **全局异常处理**：新增 GlobalExceptionHandler 统一兜底
- **权限管道增强**：OperationCategory 新增 DESKTOP 分类；新增 SideEffectFreePreflight（无副作用预检豁免）与 ToolPermissionLevel
- **数据库连接**：连接测试修复
- **智能体互调增强**：`agent_invoke` 工具描述动态注入「可用智能体清单」（委托方智能体可自主发现可用目标，推荐用 ID 调用）

### 🏷️ 版本号

- 后端：`1.1.8` → `2.0.0`（pom.xml）
- 前端：`1.1.8` → `2.0.0`
- Electron：`1.1.8` → `2.0.0`（打包 jar 路径同步 `code-craft-2.0.0.jar`）
- MCP Server 握手版本：`1.1.8` → `2.0.0`
- 打包产物：`code-craft-1.1.8.jar` → `code-craft-2.0.0.jar`
- 说明文档：README(_EN) / BUILD_AND_RUN(_EN) / docs（ARCHITECTURE、TOOL_SYSTEM、MCP_SYSTEM 等）同步至 v2.0.0（内置工具数 22 → 24）

---

## [1.1.8] - 2026-09-14

### 🤝 新增：智能体互相调用（Agent-to-Agent Invocation）

- **新增 `agent_invoke` 内置工具（第 22 个）**：智能体 A 在执行中委托另一个智能体 B（agent_config 实例）——B 以自身身份（角色/模型/工作目录/工具集）在独立会话中执行；支持 `create_session`（新建会话）/ `continue_session`（按会话 ID 继续）/ `poll`（查结果）/ `cancel`（取消）
- **委托即授权**：manual 模式下批准一次 `agent_invoke` 即视为显式委托授权，B 执行期间免弹窗；信任沿调用链传递（环检测 + 深度 ≤ 3 防死循环）；A 停止任务级联取消 B 的委托任务
- **透明可审计**：B 的委托会话出现在其会话列表（名称带「[A 委托]」前缀）；agent_task 增加 caller 溯源；委托结果与【工具轨迹摘要】如实返回
- **接入**：MCP 暴露白名单新增 `agent_invoke`；核心 Agent 提示词新增「智能体互调」使用规则；新增 `docs/AGENT_INVOKE_DESIGN.md` 设计文档

### 🗄️ 新增：外部数据库连接（数据管理）

- **「数据库连接」管理页**（SETTING 菜单）：配置 MySQL / PostgreSQL / H2 外部业务库；密码 AES-256-GCM 加密存储（复用 P2P CryptoHelper）、API 不回显明文；支持测试连接、启用/停用
- **`execute_sql` 支持 `connection` 参数**：不传连 CodeCraft 系统库（向后兼容）；传连接名称/id 连外部业务库，查询结果标注目标库防混淆
- **后端**：新增 `db_connection` 表、DbConnectionController / Mapper / Entity、service/dbconnection（DbConnectionService / DbConnectionManager / CredentialCipher）；pom 新增 mysql-connector-j、postgresql 驱动
- **前端**：新增 DbConnectionView 页面与 db-connection API

### 🚦 优化：LLM 限流与重试（P4）

- **全局并发限流**：按 DeepSeek 官方并发模型（v4-pro 500 / v4-flash 2500，账号粒度）重构为单一全局并发信号量（移除 QPS 令牌桶与 per-provider 分桶），占满时排队等待，超时报「Provider 繁忙」
- **429/5xx 指数退避重试**：最多 3 次尝试（1s → 2s → 4s，封顶 10s）；流式安全——仅响应头阶段错误重试，不重复流式数据

### 📚 优化：经验库检索（P1）

- **错误码别名归一化**：新增 canonical-aliases 映射（如 CMD_ENCODING_MISMATCH → CMD_ENCODING），同义错误码统一为 canonical 形态，防同坑多码碎片
- **检索增强**：`lesson` 工具 keyword 支持空格分隔多关键词模糊匹配；`error_code` 可传原始报错文本，系统自动归一化对齐

### 🖥️ 优化：运行日志页

- 支持**选中复制**与**导出**日志

### 🛠️ 修复与调整

- **桌面端启动脚本**：start.bat / start.sh 动态扫描 `target` 下最新 `code-craft-*.jar`（消除 JAR 名版本写死漂移，与 main.js 逻辑一致）
- **前端**：切回智能体后输入框锁定修复；会话恢复、菜单图标（数据库连接）等配套调整
- **MCP Server 握手版本修复**：`1.1.6`（1.1.7 升级遗漏）→ `1.1.8`

### 🏷️ 版本号

- 后端：`1.1.7` → `1.1.8`
- 前端：`1.1.7` → `1.1.8`
- Electron：`1.1.7` → `1.1.8`
- MCP Server 握手版本：`1.1.6` → `1.1.8`（修复 1.1.7 遗漏）
- 打包产物：`code-craft-1.1.7.jar` → `code-craft-1.1.8.jar`
- 说明文档：README(_EN) / BUILD_AND_RUN(_EN) / docs（TOOL_SYSTEM、ARCHITECTURE、MCP_SYSTEM 等）同步至 v1.1.8

---

## [1.1.7] - 2026-08-18

### 🌐 新增 MiniMax LLM Provider

- **新增 MiniMaxClient**：支持 MiniMax API（OpenAI 兼容协议），覆盖 M3 旗舰 / M2.7 / M2.5 / M2.1 / M2 系列模型
- **thinking 模式适配**：`non-thinking` → `thinking.type=disabled`，`thinking` → `enabled`，`thinking_max` → `adaptive`（M3 自适应深度思考；M2.x 系列服务端忽略 disabled）
- **输出参数兼容**：`max_tokens` 自动转 `max_completion_tokens`（MiniMax 已弃用 max_tokens），默认 65536
- **reasoning_split 开关**：将思考内容拆分到 `reasoning_content` 字段，避免 `<think>` 标签混入正文，与 AbstractLLMClient 默认 delta.reasoning_content 解析天然契合
- **认证**：Authorization: Bearer $MINIMAX_API_KEY（与 DeepSeek / OpenAI 一致，复用 getAuthHeader / getAuthHeaderPrefix 路由）
- **路由接入**：LLMClientManager 新增 `case "minimax"` → 实例化 MiniMaxClient；LLMWebClientManager 认证头注释同步补充 minimax（与 deepseek/openai 共用 Authorization + Bearer）
- **前端选项**：ConfigView / ProviderConfigView 的 Provider 下拉新增 `minimax`（紫色主题色 #4a36e0，emoji 🔮）；templateLabel / providerEmoji / templateStyle 全套同步
- **文档同步**：README.md / README_EN.md / docs/ARCHITECTURE(_EN).md / BUILD_AND_RUN(_EN).md / docs/LLM_PROVIDER_SYSTEM(_EN).md / docs/MCP_SYSTEM.md 等共 7 份文档同步新增 MiniMax，Provider 实现数 5 → 6
- **关键词**：项目根 README 关键词列表新增 `minimax`

### 🏷️ 版本号

- 后端：`1.1.6` → `1.1.7`
- 前端：`1.1.6` → `1.1.7`
- Electron：`1.1.6` → `1.1.7`
- MCP Server 握手版本：`1.1.6` → `1.1.7`
- 打包产物：`code-craft-1.1.6.jar` → `code-craft-1.1.7.jar`

---

## [1.1.6] - 2026-08-12

### 📚 成长体系：经验库进化（P0 归一化 + P1 弯路通道 + P2 环境感知）

- **P0 错误归一化管线**：新增「错误码字典」（application.yml 配置，正则匹配报错文本 → 稳定短码 + 错误类别）；规则未命中时由 LLM 语义归一化兜底（`LessonNormalizerService`，异步队列 + `lesson_norm_cache` 缓存，命中缓存免调 LLM）
- **P1 弯路经验通道（DETOUR）**：经验类型分型（FAILURE 失败经验 / DETOUR 弯路经验），弯路经验按任务目标（goal）维度指纹去重；任务启动时自动检索弯路经验并注入「弯路预警」system 提示（会话级去重）；LLM 最终回复中的【方案取舍】块自动解析入库；信号扫描（用户否定/纠正、LLM 自述换方案、工具序列辅助）+ LLM 判定异步提炼（C3）
- **P2 环境感知检索**：记录经验时自动附加操作系统环境（env_params），检索时「通用经验 > 同环境经验 > 异环境经验」排序降权，不误杀通用经验
- **P2 规则自学习表**：新增 `lesson_rule` 表沉淀「报错文本片段 → 类别 + 短码」规则（LLM 归一化成功时自动沉淀，唯一索引防并发重复），提取时规则表优先命中；看板按容量上限淘汰「候选且从未被提取命中」的最旧规则（替代时间淘汰）
- **管理界面**：经验列表新增「类型」列与过滤（失败/弯路），详情展示类型与目标，统计看板新增「弯路经验」卡片；看板新增归一化指标（规则命中/LLM 调用/缓存命中/回写）与规则统计（总数/候选/转正/使用度）
- **lesson 工具**：search 新增 type 参数（FAILURE / DETOUR 过滤），示例补充弯路经验检索用法
- **核心 Agent 提示词精简重构**：大幅精简冗余说明（工具清单等以 function 定义为准），新增 P1 规划期约定——「新任务规划方案前先查弯路经验」与【方案取舍】块输出规范（自动入库 type=detour）
- **数据库**：lesson 表新增 type / goal / env_params 列（H2 兼容 ALTER TABLE ADD COLUMN IF NOT EXISTS），新增 lesson_norm_cache、lesson_rule 两张表及索引

### 🏷️ 版本号

- 后端：`1.1.5` → `1.1.6`
- 前端：`1.1.5` → `1.1.6`
- Electron：`1.1.5` → `1.1.6`
- MCP Server 握手版本：`1.1.5` → `1.1.6`
- 打包产物：`code-craft-1.1.5.jar` → `code-craft-1.1.6.jar`

---

## [1.1.5] - 2026-08-11

### 📚 成长体系：踩坑经验库（Lesson）

- **新增 `lesson` 内置工具**：search（检索经验）/ record（记录经验）/ complete（补全草稿）/ feedback（反馈验证）/ list（巡检经验库）五合一，按项目隔离沉淀失败经验
- **自动捕获**：ToolExecutor 工具执行失败时自动入库草稿（source=auto），LLM 主动记录（source=llm）、人工沉淀（source=manual）、对话级复盘（source=review）多来源汇聚
- **被动注入**：工具失败后自动检索经验库，向 LLM 注入「解法提示 + 补全引导」（会话级去重、零常驻成本、不影响主流程）
- **验证闭环**：F3 追踪机制自动反馈——注入解法后会话结束未再犯同样错误 → 自动判有效；再次失败 → 自动判无效；经验被有效复用 2 次自动转正，连续失败 5 次自动隐藏
- **对话级复盘（C2）**：工具循环结束后异步调 LLM 提炼根因/解法，友好提示不抛异常也能捕获经验
- **管理界面**：新增「踩坑经验」管理页（/lesson-manage，菜单 SETTING 组），支持分页浏览、详情、人工编辑补全、反馈验证、删除、成长看板统计（总数/草稿/有效/隐藏/命中/成功率）
- **核心 Agent 提示词**：内置工具清单 20 → 21，新增「成长体系闭环」最高优先级规则（先查经验 → 用经验 → 记经验 → 评经验）

### 🔧 构建脚本修复

- **sync-version.bat**：`setlocal DisableDelayedExpansion` 修复 build.bat 调用链下 `!j.build` 等延迟展开吞字符导致的 "Expected ')', got '='" 报错

### 🏷️ 版本号

- 后端：`1.1.4` → `1.1.5`
- 前端：`1.1.4` → `1.1.5`
- Electron：`1.1.4` → `1.1.5`
- MCP Server 握手版本：`1.1.4` → `1.1.5`
- 打包产物：`code-craft-1.1.4.jar` → `code-craft-1.1.5.jar`

---

## [1.1.4] - 2026-08-10

### 🛰️ MCP 双向功能（Model Context Protocol）

- **MCP Server**：将内置工具暴露为 MCP 服务，供 Claude Desktop / Cursor 等外部客户端调用（Streamable HTTP 传输 + 可选 X-API-Key 认证 + 白名单控制）
- **MCP Client**：连接外部 MCP Server，将外部工具动态注册到 ToolRegistry 供 LLM 调用（http/stdio 双传输、自定义请求头、连接失败不阻塞启动）
- **MCP 服务器管理工具**：新增 `mcp_server_manager` 内置工具，可在聊天窗口直接创建/删除/连接/断开/刷新 MCP 服务器，无需打开配置页面
- **动态权限档位**：MCP 外部工具按 SAFE/DATA/HIGH_RISK 三档映射权限元数据，断开连接时自动注销工具与权限
- **前后端管理界面**：新增 MCP 服务器配置页（/mcp-config），支持 CRUD、连接状态三态展示、工具数统计

### ✨ 提示词系统优化

- **重构核心 Agent 系统提示词**（code_agent_prompt.txt）：工具清单修正为 20 个内置工具、补充 MCP 外部工具说明、新增工作目录/执行模式/上下文压缩/快照保护等系统行为说明
- **重构评委提示词**（judge_prompt.txt）：迭代限制动态化（不再写死 50 次）、细化 extend/reject 评估维度、明确 JSON 输出格式与中文字段要求
- **增强提示词优化器**（prompt_optimize.txt）：新增代码需求改写规则（保留代码标识符原样）、500 字长度控制、明确无需优化的场景

### 🔧 修复与优化

- **application.yml** 工具列表补充 `mcp_server_manager`，与代码实际注册的 20 个内置工具保持一致
- **.gitignore** 排除文件化任务规划工作文件（task_plan.md / findings.md / progress.md / .planning/）

### 🏷️ 版本号

- 后端：`1.1.3` → `1.1.4`
- 前端：`1.1.3` → `1.1.4`
- Electron：`1.1.3` → `1.1.4`
- MCP Server 握手版本：`1.1.3` → `1.1.4`
- 打包产物：`code-craft-1.1.3.jar` → `code-craft-1.1.4.jar`

---

## [1.1.3] - 2026-07-02

### 🌐 多 LLM Provider 支持系统

- **多 Provider 支持**：支持 DeepSeek、OpenAI、Anthropic、Ollama、MiMo 等主流 LLM 平台
- **动态切换**：前端运行时可随时切换 Provider，无需重启服务
- **Agent 绑定**：每个 Agent 可以绑定特定的 Provider 和模型
- **统一接口**：所有 Provider 通过统一的 `LLMClient` 接口调用，屏蔽平台差异
- **热刷新**：Provider 配置变更后自动刷新客户端缓存

### 🏗️ 核心架构变更

- **新增 LLMClient 抽象层**：统一接口适配 DeepSeek/OpenAI/Anthropic/Ollama/MiMo
- **新增 LLMClientManager**：核心管理器，负责 Provider 注册、路由和缓存
- **新增 6 个 Provider 实现**：DeepSeekClient、OpenAIClient、AnthropicClient、OllamaClient、MiMoClient、AbstractLLMClient
- **新增 LLMProviderController**：REST API 控制器，支持 Provider CRUD 操作
- **新增 llm_provider 数据库表**：存储 Provider 配置信息

### 🎨 前端 UI 增强

- **新增 Provider 切换 UI**：CodeAssistantView 支持运行时切换 Provider
- **动态模型列表**：根据当前 Provider 动态刷新模型列表
- **无 Provider 引导界面**：当没有配置任何 Provider 时，显示引导界面
- **Agent 配置增强**：AgentConfigView 新增 Provider 绑定配置

### 🔧 修复与优化

- **工具调用参数累积中断时保存部分内容**
- **评委扩展计数器内存泄漏防护**
- **子 Agent 超时统计**
- **NullNode 处理修复**

### 🏷️ 版本号

- 后端：`1.1.2` → `1.1.3`
- 前端：`1.1.2` → `1.1.3`
- Electron：`1.1.2` → `1.1.3`
- 打包产物：`code-craft-1.1.2.jar` → `code-craft-1.1.3.jar`

### 📦 新增依赖

- 后端：`LLMClientManager`、`LLMClient` 接口及 6 个实现类
- 前端：`llm-provider.ts` API 接口、`ProviderConfigView.vue` 页面

---

## [1.1.2] - 2026-07-01

### 🌡️ Agent 采样温度（Temperature）全链路支持

- **数据库层**：`agent_config` 表新增 `temperature` 列（DOUBLE，默认 0.3），`AgentConfigServiceImpl` 启动时自动建表 / ALTER TABLE 兼容旧表升级
- **实体层**：`AgentConfig` 新增 `temperature` 字段，MyBatis Mapper 全量 SQL 同步新增该字段的读写
- **API 层**：`ChatRequest` / `ForkAgentRequest` 新增 `temperature` 参数，前端可在发送消息或 fork 子 Agent 时指定
- **服务层**：`DeepSeekServiceImpl` 支持三级优先级（前端传入 > Agent 配置 > 默认 0.3），`ToolContext` 新增 `temperature` ThreadLocal 全程传递
- **子 Agent**：`AgentForkManager` 子 Agent 执行使用配置温度值，`AgentTool` fork 时自动传递当前 Agent 的 temperature
- **前端 UI**：`AgentConfigView` 新增采样温度滑块（0~2，步长 0.1），刻度标记 + 实时数值显示，`agent-config.ts` API 接口同步新增字段

### 🐚 Shell 自动发现与多 Shell 支持

- **智能 Shell 发现**：`CommandUtils` 重构，从硬编码 `cmd`/`sh` 改为自动检测最佳 Shell
  - Windows：pwsh → powershell → Git Bash → cmd（按优先级依次尝试）
  - Unix/Linux：`$SHELL` 环境变量 → zsh → bash → sh
- **新增 `ShellDiscoveryService`**：统一 Shell 发现与命令包装服务，`ProjectBuildService` 构建/运行/执行命令全部通过该服务包装
- **工作目录切换**：Shell 包装命令支持 `cd` 前置（cmd: `cd /d`、pwsh: `Set-Location`、bash: `cd &&`），确保命令在正确目录执行
- **Git Bash 路径检测**：自动搜索 Program Files / LocalAppData 下的 Git Bash 安装路径，覆盖多种安装场景

### 🖥️ 前端终端面板独立化（xterm.js）

- **新增 `TerminalPanel.vue`**：独立终端组件，集成到右侧工具栏「终端」标签页
- **引入 xterm.js**：`@xterm/xterm` (6.0.0) + `@xterm/addon-fit` (0.11.0)，替代旧的手写 HTML 终端
- **CodeAssistantView 重构**：移除旧的终端 Tab（Shell 风格手写终端 + 运行日志混排），精简为仅「运行日志」面板
- **日志 xterm 终端**：运行日志使用 xterm.js 渲染，支持明暗主题自适应（Pinia settingsStore + 系统 `prefers-color-scheme` 媒体查询双监听）
- **日志面板优化**：独立拖拽调整高度、清屏 `xterm.clear()`、增量同步 `writeln`（避免全量重绘）

### 📂 目录浏览器增强

- **DirectoryEntry 新增 `directory` 属性**：区分目录与文件，前端可据此显示不同图标
- **`listChildren` 新增 `includeFiles` 参数**：支持按需包含文件列表（默认仅目录），用于终端 Tab 补全等场景
- **排序优化**：目录优先，再按名称字母排序（目录在前，文件在后）
- **前端 API 适配**：`project.ts` 新增 `includeFiles` 查询参数、`DirectoryEntry` 接口新增 `directory` 字段

### 🔧 修复与优化

- **artifactId 修正**：`pom.xml` 中 `artifactId` 从 `codecraft` 修正为 `code-craft`（与 jar 文件名 `code-craft-{version}.jar` 保持一致）
- **默认 Agent 名称**：从「编码助手」更名为「AI 助手」
- **前端文本对齐修复**：消息/代码/日志/工具结果等多处 CSS 新增 `text-align: left`，修复暗色主题下文本异常居中的问题
- **暗色主题控制台样式清理**：移除已废弃的 `.console-panel` / `.console-tab-bar` 暗色样式代码

### 🏷️ 版本号

- 后端：`1.1.1` → `1.1.2`
- 前端：`1.1.1` → `1.1.2`
- Electron：`1.1.1` → `1.1.2`
- 打包产物：`code-craft-1.1.1.jar` → `code-craft-1.1.2.jar`

### 📦 新增依赖

- 前端：`@xterm/xterm` ^6.0.0、`@xterm/addon-fit` ^0.11.0
- 后端：`ShellDiscoveryService` 新服务类

### 📄 新增文件

- `frontend/src/components/TerminalPanel.vue` — 独立终端面板（xterm.js）
- `src/main/java/com/example/agentdeepseek/service/ShellDiscoveryService.java` — Shell 自动发现服务

---

## [1.1.1] - 2026-06-10

### 🎉 工具系统重大重构（Tool Unification）

- **工具合并统一（30 工具 → 19 工具）**：将原有 30+ 个独立工具合并为 19 个统一工具，采用 `action` 参数区分操作模式，大幅降低 LLM 工具选择混淆度
  - **文件操作**：`read_file`/`glob_files`/`grep_search`/`read_project_tree` → `file_explorer`（action: read/glob/grep/tree）
  - **文件编辑**：`write_file`/`edit_file`/`delete_file` → `file_writer`（action: write/edit/delete）
  - **命令执行**：`run_command`/`run_server`/`service_control` → `command`（action: exec/start/list/logs/stop）
  - **Git 操作**：`git_status`/`git_diff`/`git_log` → `git_query`；`git_add`/`git_commit`/`git_push` → `git_submit`
  - **Agent 协作**：`fork_agent`/`collect_agent`/`inspect_agent` → `agent`（action: fork/collect/inspect）
  - **技能管理**：`manage_skill`/`report_skill_result` → `skill`（action: create/update/delete/list/report）
- **新增工具**：`chat_attachment`（PDF/Word/Excel 附件按需读取）、`schedule_task`（定时任务增删改查启停）
- **Action 参数铁律**：12 个带 action 参数的工具缺一不可，系统提示词首屏醒目提醒
- **智能 action 参数补齐（Smart Action Fix）**：`ToolExecutor` 新增智能补齐引擎，LLM 忘记传 action 时根据已有参数自动推断（如 `file_path` → `read`、`content` → `write`），覆盖 12 个工具全覆盖推断逻辑；无法推断时返回精确修复指导

### 📎 附件系统（ChatAttachment）

- **PDF/Word/Excel 解析支持**：新增 PDFBox（3.0.2）+ POI（5.2.5）依赖，支持 PDF、Word(.docx)、Excel(.xlsx/.xls) 文件的文本提取
- **附件上传改为暂存模式**：前端上传不再拼接内容到消息，改为返回 `attachmentId`；LLM 通过 `chat_attachment` 工具按需读取（节省 Token）
- **附件图标按类型区分**：PDF 📕、Word 📘、Excel 📊、图片 🖼️、文本 📄
- **配置项**：`chat-attachment.store.temp-dir` / `chat-attachment.parse.pdf-max-pages` 等

### 🚀 连接池与性能优化

- **WebClient 连接池**：新增 `ConnectionProvider("deepseek-pool")`，空闲 40s 超时主动关闭连接（比服务端 ~60s 短，防止死连接）、后台 20s 清理、最大存活 5 分钟强制轮换
- **HTTP/2 优先**：`HttpProtocol.H2, HTTP11` 配置，HTTP/2 下 401 不关闭连接
- **连接预热**：启动完成后异步预热 DeepSeek API 的 HTTPS 连接（DNS+TCP+TLS），消除首次用户请求 10~20s 延迟。优先从数据库 `sys_config` 读取 API Key，回退配置文件
- **性能诊断日志**：记录每次 LLM 请求的 TTFB（首字节到达时间）和 requestBodySize

### ⚡ 工具异步执行 + 加载动画

- **工具调用异步化**：`DeepSeekServiceImpl` 中工具执行从同步改为 `Mono.fromCallable` + `boundedElastic` 线程池，避免阻塞 Netty 事件循环
- **SSE 事件流优化**：先发 `tool_call_start` 事件（含工具名 + 操作摘要）→ 前端渲染紫色加载卡片 → 工具完成后替换为结果卡片
- **前端 pending 卡片**：紫色脉冲边框 + 进度条滑动 + 跳动圆点动画，暗色主题适配，pending 卡片始终展开不响应折叠
- **操作摘要显示**：`OperationDetailGenerator` 新增 action 网关分发，支持 `command`（exec/start/logs/stop）、`file_writer`（write/edit/delete）、`git_submit`（add/commit/push）等所有统一工具

### 🖥️ 前端多项改进

- **AgentPanel.vue**：工具事件新增 `action` 字段显示（绿色标签），修复无 action 且无 filePath 时的空状态显示
- **CodeAssistantView.vue**：附件系统完整重写（`attachmentId` 模式、`type` 字段替换 `image`/`language`）、工具调用 pending 加载卡片、工具结果卡片增强（操作类型标签如「file_explorer · 搜索内容」+ 错误检测仅检查第一行防止误判）、附件图标按类型区分、`attachmentIds` 传递到后端
- **Layout.vue**：主题切换跳过 auto 循环（亮→暗→亮 直接切换），移除 `ThemeMode` 类型导入冗余
- **RightToolbar.vue**：drawer 面板 `overflow: hidden` → `overflow-y: auto` 修复内容不可滚动问题
- **sse-client.ts**：新增 `tool_call_start` 事件类型和 `ToolCallStartData` 接口
- **chat.ts**：`ChatRequest`/`StreamChatOptions` 新增 `attachmentIds` 字段，`FileUploadResult` 返回 `attachmentId` 和 `type`
- **暗色主题**：pending 卡片、action 标签、tr-toggle 等多组件暗色主题适配

### 👥 子Agent 并行度大幅提升

- **最大并发 5 → 20**：`AgentForkManager.MAX_CONCURRENT_AGENTS` 提升至 20
- **线程池重构**：`corePoolSize` 从 2 升到 20（= MAX），`LinkedBlockingQueue(20)` → `SynchronousQueue`（无缓冲，核心线程满直接 CallerRuns 策略）
- **线程命名**：子Agent 线程统一命名为 `sub-agent-{id}`，便于日志排查
- **批量收集**：新增 `batchCollectAgents()` 方法，一次调用收集所有子Agent（并行等待 + 整体超时保护），推荐在 agent tool 中使用 `action=batch_collect`

### 🏠 API Key 数据库化

- **配置存储迁移**：API Key 从前端「配置」页面设置，存储到 `sys_config` 表（`deepseek_api_key`），不再从环境变量或 `application-local.yml` 读取
- **向后兼容**：预热和运行时优先查数据库，数据库无记录则回退到配置文件

### 📝 系统提示词全面升级

- **语言强制指令**：移到提示词最前并升级为「最高优先级指令」，5 条子规则覆盖思考/回复/代码/工具返回/优先级
- **工具清单表格**：新增「可用工具清单」19 工具完整表格（工具名 + 典型用法 + 说明）
- **Action 参数铁律**：醒目提醒框 + 记忆口诀 + 12 工具自检清单
- **多Agent 协作**：更新为 action 模式（fork → batch_collect/collect → inspect），推荐 batch_collect 一次性收集
- **所有工具引用名称**：提示词中所有旧工具名统一替换为新工具名

### 🐛 修复

- **DeepSeekAnalyzer 括号匹配修复**：`extractJsonFromText()` 改用 `findMatchingBrace()` 栈计数器精确匹配 `{}` 对，解决嵌套 JSON 场景下首尾截取跨越多个 JSON 块的问题
- **DeepSeekAnalyzer non-thinking 回退**：评委调用不传 `thinking_mode` 时显式设 `"non-thinking"`，防止 `deepseek-v4-pro` 等模型默认启用 thinking 导致 `content=""` 提取 JSON 失败
- **ScheduleTaskServiceImpl**：默认 `max_execute_count` 从 0（不限）改为 100（安全上限）

### 🗑️ 删除文件

- 旧工具实现全部删除（19 个文件）：`ReadFileTool`、`WriteFileTool`、`EditFileTool`、`DeleteFileTool`、`GlobFilesTool`、`GrepSearchTool`、`ReadProjectTreeTool`、`RunCommandTool`、`RunServerTool`、`ServiceControlTool`、`GitStatusTool`、`GitDiffTool`、`GitLogTool`、`GitAddTool`、`GitCommitTool`、`GitPushTool`、`ForkAgentTool`、`CollectAgentTool`、`InspectAgentTool`、`ManageSkillTool`、`ReportSkillResultTool`
- 配置文件：`DeleteFileConfig` → 重命名为 `EditFileConfig`（适配 `file_writer` 体系）
- `OperationCategory` 枚举注释全局更新（旧工具名 → 新工具名）

### 🏷️ 版本号

- 后端：`1.1.0` → `1.1.1`
- 前端：`1.1.0` → `1.1.1`
- Electron：`1.1.0` → `1.1.1`
- 打包产物：`CodeCraft-Setup-1.1.0.exe` → `CodeCraft-Setup-1.1.1.exe`

### 📝 文档

- `README.md`：工具名更新（`delete_file` → `file_writer action=delete`）、子Agent 并发数更新（5→20）
- `BUILD_AND_RUN.md` 版本号同步更新至 `1.1.1`
- `CHANGELOG.md` 新增 `1.1.1` 版本条目（本文档）

---

## [1.1.0] - 2026-06-04

### 🎉 新增功能

- **右侧工具栏（RightToolbar）**：全新的右侧图标工具栏，集成文件树、Git 面板、Agent 运行面板，竖排按钮栏一键切换，面板宽度可拖拽调整
- **分屏布局（SplitLayout）**：支持拖拽分屏的通用布局组件，可将文件树/Git 等面板拖拽到上/下/左/右四个方向分屏展示，拖拽文件到热区自动切换分屏模式
- **HEAD 版本文件查看**：新增 `git show HEAD:file` API（`/api/git/show`），支持查看任意文件在 HEAD 提交中的原始内容，用于对比/还原参考
- **按块还原改动（Hunk Restore）**：新增 `git apply --reverse` API（`/api/git/restore-hunks`），支持按 diff hunk 选择性还原文件改动，而非全量 `git restore`
- **快照文件内容对比**：新增快照文件内容读取 API（`/api/snapshots/file-content`），支持将工作区文件与历史快照版本进行 diff 对比

### 🔧 修复优化

- **Git 状态查询重构**：两步法精准区分已跟踪/未跟踪文件——`git diff --name-only HEAD`（已跟踪）+ `ls-files --others --exclude-standard`（未跟踪），彻底解决 `git status --porcelain` 因 autocrlf 将全部文件误报为"已修改"的问题
- **Git stderr/stdout 分离**：`GitCommandExecutor` 不再合并错误流（`redirectErrorStream=false`），避免 CRLF warning 混入正常输出导致 diff 解析失败，同时防止 stderr 管道堵塞
- **HOME 环境变量注入**：Git 子进程继承 `HOME`/`USERPROFILE` 环境变量，确保全局 `.gitconfig` 配置（`core.autocrlf` 等）在子进程中生效
- **Git diff 基准修正**：`git diff` 始终以 `HEAD` 为基准对比，展示工作区/暂存区 vs 最新提交的真实差异
- **权限审批增强**：手动模式下审批事件（`ask_user`）携带工具详情（`toolName`/`filePath`/`fullDetail`），用户可在弹窗中了解即将执行的具体操作
- **变更文件过滤**：Git 状态查询自动排除构建产物/快照/日志等目录（`target/`、`node_modules/`、`snapshots/` 等），避免噪音干扰
- **Agent 面板暗色模式适配**：`AgentPanel.vue` 完整适配暗色主题，事件列表/技能标签/滚动条全面暗色化
- **多组件暗色模式优化**：`AgentSelector`、`DiffView`、`FileEditor`、`FileTree`、`GitSidebar`、`SkillList`、`TreeNode` 等多组件暗色主题完善

### 🛠️ 技术架构

- **GitCommandExecutor 重构**：新增 `executeWithStdin()` 方法（支持 `git apply --reverse` 等需 stdin 的命令），stdout/stderr 独立线程消费防止管道死锁
- **GitController 重构**：状态查询逻辑提取为 `appendTrackedChanges()` / `appendUntrackedFiles()` / `isExcludedPath()` 三个私有方法，新增 `show` 和 `restore-hunks` 两个接口
- **SnapshotService 扩展**：新增 `getSnapshotFileContent()` 方法，支持按 sessionId + filePath 检索快照文件内容
- **ToolLoopManager 扩展**：`createAskUserEvent()` 新增 `toolName`/`filePath`/`fullDetail` 参数
- **DeepSeekServiceImpl 增强**：权限审批流程集成工具详情传递，审批弹窗展示更丰富的操作信息

### 🏷️ 版本号

- 后端：`1.0.9` → `1.1.0`
- 前端：`1.0.9` → `1.1.0`
- Electron：`1.0.9` → `1.1.0`
- 打包产物：`CodeCraft-Setup-1.0.9.exe` → `CodeCraft-Setup-1.1.0.exe`

### 📝 文档

- `BUILD_AND_RUN.md` 版本号同步更新至 `1.1.0`
- `CHANGELOG.md` 新增 `1.1.0` 版本条目（本文档）

---

## [1.0.9] - 2026-06-03

### 🎉 新增功能

- **上下文模式（Context Mode）**：全新的双策略上下文注入系统，支持全量/精简两种模式，大幅优化 Token 消耗
  - **全量模式（Full）**：所有历史消息完整注入，LLM 拥有完整上下文
  - **精简模式（Compact）**：非本轮对话的工具调用结果仅保留成功/失败摘要，推理过程（reasoning_content）移除，Token 消耗预计降低 90%+
  - 精简模式下 LLM 可使用 `query_tool_history` 工具按需查询历史工具调用的完整细节
  - 配置持久化到服务端 `sys_config` 表，运行时在聊天输入区底部快速切换
- **QueryToolHistory 新工具**：`query_tool_history` 工具支持按 tool_name、limit、message_id 查询历史工具调用的完整原始结果，精简模式下按需检索，全量模式下也可用于深入排查
- **上下文 Token 可视化**：聊天输入区底部实时显示当前上下文 Token 总数，精简模式时附紫色「⚡ 精简」标记并应用精确折扣算法
- **配置页上下文模式管理**：新增"上下文模式"配置卡片，全量/精简双选 radio 按钮 + 详细使用说明

### 🔧 修复优化

- **输入框发送后不清空**：修复 AI 助手消息发送后输入框残留内容的问题（根因：Ant Design Vue `a-textarea` 在 `disabled` 状态下同一渲染周期不响应 `v-model` 变更，改为 `await nextTick()` 后再禁用 + 兜底清空逻辑）
- **服务端口修正**：修正服务端口为 8084（alignment 统一）
- **application.yml 配置同步**：v1.0.9 配置项完善

### 🛠️ 技术架构

- **ContextBuilder 重构**：新增 `buildMessagesFromHistory(conversationId, contextMode)` 双模式消息构建方法，`compactHistoryMessages()` 精简核心逻辑（工具结果摘要化 + reasoning 移除 + token 估算），`buildCompactModeInstruction()` 注入智能提示指令
- **DeepSeekAnalyzer 增强**：集成紧凑上下文模式支持，优化分析器与 ContextBuilder 协作
- **AgentForkManager 适配**：子 Agent 创建时正确传播上下文模式配置
- **DeepSeekServiceImpl 重构**：消息构建管线集成 ContextBuilder，统一上下文管理
- **TokenEstimator**：新增 Token 估算工具类，支持消息列表级 Token 预估
- **配置层扩展**：`DeepSeekConfig` 新增 `contextMode` 字段，`ChatRequest` 新增 `contextMode` 参数

### 🗄️ 数据库变更

- `sys_config` 表新增 `context_mode` 配置项（full/compact，默认 full）

### 🏷️ 版本号

- 后端：`1.0.8` → `1.0.9`
- 前端：`1.0.5` → `1.0.9`
- Electron：`1.0.8` → `1.0.9`
- 打包产物：`CodeCraft-Setup-1.0.8.exe` → `CodeCraft-Setup-1.0.9.exe`

### 📝 文档

- `README.md` 更新项目特性描述
- `BUILD_AND_RUN.md` 版本号同步更新

---

## [1.0.8] - 2026-06-02

### 🎉 新增功能

- **P2P 图片/文件传输**：支持在聊天中发送图片和文件
  - 图片≤20MB 缩略图预览 + 点击放大查看，>20MB 自动降级为文件传输
  - 文件最大支持 2GB，分块传输（1MB/块），TLS 加密通道
  - 文件完整落盘存储（`data/p2p/received/{peerId}/{transferId}/`），数据库只存元信息
  - 接收方收到图片自动生成缩略图，文件点击直接打开本地目录
  - `p2p_chat_message` 表新增 7 个文件元信息字段（`file_name`/`file_size`/`mime_type`/`file_category`/`transfer_id`/`file_status`/`local_path`）
  - 新增 `FileTransferHandler` + `FileTransferManager` 独立管理文件传输协议
- **输入区增强**：支持拖拽发送、Ctrl+V 粘贴图片、📎按钮选择文件
- **P2P 节点管理完善**：离线节点支持重连/删除，Agent 模式下对方离线自动禁用

### 🔧 修复优化

- **P2P 面板多项修复**：reactive 未导入、Token 丢失（FormData 请求 + img 标签）、Desktop HeadlessException、文件名丢失（String.format 的 % 冲突）、发送方图片闪烁
- **http-client 修复**：FormData 请求自动跳过 Content-Type 设置，headers 显式合并防止 token 丢失
- **TokenAuthenticationFilter**：修正认证过滤器逻辑

### 📝 文档

- **README.md 精简重构**：去除冗余描述，改为核心功能概览 + 关键特性摘要，阅读体验更简洁

### 🗄️ 数据库变更

- `p2p_chat_message` 表新增 `file_name`/`file_size`/`mime_type`/`file_category`/`transfer_id`/`file_status`/`local_path` 字段
- `P2pChatService` 新增 `saveFileMessage()` 和 `findByTransferId()` 方法

---

## [1.0.7] - 2026-05-31

### 🎉 新增功能

- **提示词优化**：输入框新增 ✨ 优化按钮，使用 LLM 自动改写用户消息使其更清晰明确
  - 后端 `PromptOptimizeService` + `PromptOptimizeController`，使用 DeepSeek Flash 非流式调用
  - 前端点击后锁定输入框 + loading 动画，优化结果自动覆盖原始文本
  - 优化模板聚焦于「识别模糊指代、明确操作意图、保持原意」
- **补充需求（中途注入）**：AI 执行过程中可随时补充需求，消息注入到正在运行的 ToolLoop
  - 新增 `SupplementStore` 消息队列
  - 前端 `supplementRequest()` API + 后端 `/api/deepseek/supplement` 接口

### 🎨 UI 优化

- **发送面板重构**：统一按钮组风格（附件/优化/发送按钮不再各玩各的），下拉选择器使用 emoji 图标（🛡️执行 ⚡模型 💡思考），圆角对齐 Bento 设计语言，暗色主题完整适配

### 🔧 修复优化

- **优化按钮 loading 修复**：改为手动切换 LoadingOutlined，解决 Ant Design `:loading` 导致的溢出问题
- **提示词模板修正**：添加「不要回答用户问题」硬约束，防止优化助手把自己当成对话角色

---

## [1.0.6] - 2026-05-30

### 🎉 新增功能

- **P2P 消息方向感知**：前端根据 `direction` 字段自动区分「我发出的」和「对方发出的」消息
- **P2P Agent 调用 loading 动画**：调用方发起 Agent 调用后显示「AI 正在思考中...」加载状态
- **P2P Markdown 渲染**：Agent 响应内容支持完整 Markdown 渲染（代码高亮/表格/列表等）
- **会话自动重建**：当用户清除聊天记录后，P2P 映射自动检测并重建新会话，避免 AI 响应为空

### 🔧 修复优化

- **JSON 解析改进**：`P2pController` 改用 Jackson ObjectMapper 完整解析，替换原有的字符串查找方式
- **P2P 暗色模式适配**：loading 动画、消息气泡、发送按钮等组件暗色主题适配
- **消息气泡间距修复**：修复 chat 消息气泡间距不一致的问题

### 📝 文档系统

- **新增 `docs/` 文档目录**：架构全景图、工具系统、核心引擎、P2P 系统、快照系统、开发速查、发布清单、常见问题（8 份）
- **新增 `README` 文档导航表格**：按读者角色推荐对应文档
- **新增 `BUILD_AND_RUN` 懒人通道**：一键脚本打包说明

### 🤖 CI / 自动化

- **新增 `.github/workflows/release.yml`**：推送 `v*` tag 自动触发 Win/Mac/Linux 三平台构建 + Release 发布
- **新增 `scripts/` 目录**：一键构建脚本（`build.bat` / `build.sh`）+ 版本同步脚本

---

## [1.0.5] - 2026-05-28

### 🎉 新增功能

- **P2P 远程协作**：全新的点对点远程 Agent 调用系统，支持设备间安全直连通信
  - Netty TCP Server/Client 实现高性能异步网络通信
  - Protobuf 高效二进制序列化（消息协议）
  - TLS 自签名证书加密通信（BouncyCastle）
  - 二维码扫码配对（ZXing），免手动输入地址
  - Agent 授权共享（双向授权管理面板）
  - P2P 聊天消息完整留存与查询
- **P2P 前端面板**：`P2pPanel.vue` 组件，支持连接状态展示、消息收发、授权管理
- **P2P 菜单项**：左侧设置菜单新增「P2P连接」入口

### 🎨 UI 优化

- **侧边栏全面重构**：Layout.vue 升级为 Bento 卡片风格，CSS 变量体系 + 微交互动画
- **主题切换功能**：支持亮色/暗色/自动三种模式，侧边栏底部一键切换
- **菜单项溢出提示**：菜单项新增 title 属性，折叠状态悬停可查看完整名称
- **折叠动画优化**：侧边栏折叠/展开过渡更流畅（cubic-bezier 缓动）

### 🔧 修复优化

- **Electron 缓存清理**：覆盖安装后自动清除 HTTP 缓存，解决旧缓存导致页面白屏问题
- **Electron 防火墙管理**：启动时自动注册 P2P 端口防火墙规则，退出时自动清理
- **消息气泡间距修复**：虚拟滚动器中消息项改用 `padding-bottom` 替代 `margin-bottom`，解决气泡紧贴问题
- **暗色模式发送按钮**：disabled 状态下背景色适配暗色主题，不再显示为浅色
- **思考过程头部布局优化**：技能标签溢出处理改进，`flex-wrap: nowrap` + 缩小最大宽度
- **技能列表暗色适配**：SkillList 组件全面适配暗色模式（卡片、文本、标签等）
- **技能管理 TransitionGroup**：添加 `tag="div"` 和 `min-height: 0` 修复过渡动画布局问题

### 🗄️ 数据库变更

- 新增 `p2p_chat_message` 表：P2P 聊天记录存储
- 新增 `p2p_agent_authorization` 表：P2P Agent 授权记录
- 新增 `p2p_agent_conversation` 表：P2P Agent 会话映射
- 新增菜单项：P2P连接（菜单 ID=13，SETTING 类型）

### 🏷️ 版本号

- 后端：`1.0.4` → `1.0.5`
- 前端：`1.0.4` → `1.0.5`
- Electron：`1.0.4` → `1.0.5`
- 打包产物：`CodeCraft-Setup-1.0.4.exe` → `CodeCraft-Setup-1.0.5.exe`

### 📄 文档

- `README.md` 新增 P2P 远程协作功能说明
- `BUILD_AND_RUN.md` 版本号同步更新
- 所有 `.md` 文档版本号同步更新至 `1.0.5`

---

## [1.0.4] - 2026-05-26

### 🏷️ 版本号

- 后端：`1.0.3` → `1.0.4`
- 前端：`1.0.1` → `1.0.4`
- Electron：`1.0.3` → `1.0.4`
- 打包产物：`CodeCraft-Setup-1.0.3.exe` → `CodeCraft-Setup-1.0.4.exe`

### 📄 文档

- 所有 `.md` 文档版本号同步更新至 `1.0.4`

---

## [1.0.3] - 2026-05-25

### 🎉 新增功能

- **Agent 选择器展开/收起**：AgentSelector 组件默认只显示前 3 个 Agent，超过 3 个时显示"展开全部"按钮，避免拥堵
- **技能列表按置信度排序**：SkillList 技能列表按置信度降序排列，置信度相同按创建时间升序，最高效的技能优先展示
- **定时任务按 Agent 过滤**：ScheduleTaskView 新增 Agent 过滤下拉框，可快速筛选指定 Agent 的定时任务
- **技能标签溢出处理**：CodeAssistantView 中技能匹配标签增加 title 属性和溢出省略样式，长技能名不会撑破布局

### 🔧 修复优化

- **主内容区域滚动修复**：Layout.vue 中 main-content 的 `overflow` 从 `hidden` 改为 `overflow-y: auto`，修复某些场景下内容无法滚动的问题
- **技能匹配强制截断**：SkillMatcher.java 增加强制截断逻辑，确保返回的技能不会超过 TOP_K 限制，防止保底逻辑导致技能数超限

### 🏷️ 版本号

- 后端：`1.0.2` → `1.0.3`
- Electron：`1.0.2` → `1.0.3`
- 打包产物：`CodeCraft-Setup-1.0.2.exe` → `CodeCraft-Setup-1.0.3.exe`

---



## [1.0.2] - 2026-05-25

### 🎉 新增功能

- **工具选择区按类型分组**：Agent 管理和技能管理的工具选择区改为折叠面板按分类分组显示，每个工具附带功能描述和高危标记 ⚠️
- **后端工具注册表 API**：新增 `GET /api/tools/registry` 接口，返回所有工具的按分类分组信息（含描述和风险等级）
- **多 Agent 后台流式切换**：切换 Agent 时后台流式继续运行，切回时自动恢复流式状态和中断按钮，支持多个 Agent 同时并行流式

### 🔧 修复优化

- **工作目录检查**：用户未设置工作目录时发送消息会提示，不会默认使用项目根目录
- **文件 Tab 栏加载状态**：修复"加载中..."字样在加载完成后不隐藏的问题
- **文件树默认显示**：修复未选择工作目录时文件树默认显示项目根目录的问题
- **流式停止按钮**：修复点击停止按钮无法取消后端任务的问题（cancelTask 未调用）
- **中断流式后消息空白**：修复点击停止后已输出的一半消息丢失变为空白的问题
- **停止按钮需点两次**：修复因 `checkAndReconnect` 重连导致的第一下停止无效的问题
- **agent_task 建表 SQL**：修复 H2 数据库不兼容 `ENGINE=InnoDB` 语法导致任务记录无法创建的问题
- **工具选择区样式同步**：Agent 管理和技能管理的工具选择区样式完全统一

### 🗄️ 数据库变更

- `schema.sql` 新增 `agent_task` 表建表语句（含 `pending_question_uuid` 和 `pending_question_text` 字段及索引）
- 修复 `agent_task` CREATE TABLE 动态 SQL 的 H2 兼容性

### 🏷️ 版本号

- 后端：`1.0.1` → `1.0.2`
- 前端：`1.0.1` → `1.0.2`
- Electron：`1.0.1` → `1.0.2`
- 打包产物：`CodeCraft-Setup-1.0.1.exe` → `CodeCraft-Setup-1.0.2.exe`

---

## [1.0.1] - 2026-05-24

### 🎉 新增功能

- **Agent 配置管理**：支持创建和管理多个自定义 AI Agent，每个 Agent 可独立配置系统提示词、可用工具集、AI 模型（如 deepseek-v4-flash）、思考模式（non-thinking / thinking / thinking_max）和执行模式（自动 / 手动）
- **Agent 选择器**：聊天界面顶部新增 AgentSelector 组件，可快速切换不同的 AI Agent
- **技能管理独立页面**：新增 `/skill-manage` 路由和 SkillManageView，支持对技能进行可视化增删改查，技能支持绑定到特定 Agent 配置
- **会话级工作目录**：Conversation 实体新增 `workDir` 字段，会话可绑定独立的工作目录
- **Agent 配置关联**：会话、技能、定时任务均新增 `agentConfigId` 字段，支持绑定到指定的 Agent 配置

### 🔧 改进优化

- **前端菜单重组**：左侧菜单栏改为分组布局（"聊天"组 + "设置"组），LINK 类型菜单归入聊天组，SETTING 类型菜单归入设置组
- **菜单项名称优化**："编码助手" → "AI 助手"
- **前端 API 层统一**：Skill API 从原生的 `fetch` 迁移到统一的 `request` 工具，接口更加规范
- **Chat API 扩展**：`ChatRequest` 和 `StreamChatOptions` 新增 `agentConfigId` 可选参数
- **Conversation API 扩展**：`getConversationList` 支持按 `agentConfigId` 过滤会话
- **ScheduleTask API 扩展**：`ScheduleTaskItem` 和 `CreateTaskParams` 新增 `agentConfigId` 字段
- **前端构建脚本优化**：`build` 命令移除 `vue-tsc -b` 前置检查，新增独立的 `typecheck` 命令

### 🗄️ 数据库变更

- 新增 `agent_config` 表：存储自定义 Agent 配置，含初始化内置"编码助手"Agent（ID=1，不可修改/删除）
- `conversation` 表新增 `agent_config_id` 和 `work_dir` 字段
- `skill` 表新增 `agent_config_id` 字段（null=全局技能）
- `schedule_task` 表新增 `agent_config_id` 字段
- 新增菜单项：Agent 管理（`/agent-config`）、技能管理（`/skill-manage`）

### 🏷️ 版本号

- 后端：`0.0.1-SNAPSHOT` → `1.0.1`
- 前端：`0.0.0` → `1.0.1`
- Electron：`1.0.0` → `1.0.1`
- 打包产物：`CodeCraft-Setup-1.0.0.exe` → `CodeCraft-Setup-1.0.1.exe`

### 📄 文档

- `README.md` 更新功能描述和项目结构
- `BUILD_AND_RUN.md` 更新版本号引用
- 新增 `CHANGELOG.md`（本文档）

---

## [1.0.0] - 2026-05-19

### 首次正式发布

- 🤖 AI Agent 多工具调用（30+ 工具：读写文件、搜索代码、执行命令、Git 操作、API 调用等）
- 🧩 任务拆解与并行子 Agent 执行
- 🔄 自动纠错与重试机制
- 🎯 技能系统：支持创建和管理可复用技能
- 👨‍💻 编码辅助：代码生成、文件浏览编辑、代码搜索、Diff 对比、文件快照
- 🔧 项目管理：Maven 构建、Git 集成、项目结构分析
- 🗄️ H2 嵌入式数据库（零配置）+ Caffeine 内置缓存
- 🖥️ Electron 桌面应用（Windows/macOS/Linux），内置 JRE 无需安装 Java
- 🔐 用户认证与权限管理（Token 鉴权、角色权限、菜单权限）
- ⏰ 定时任务调度（Cron 表达式 + 一次性任务）
- 📝 系统日志查看
- 🌐 内置网络代理支持
