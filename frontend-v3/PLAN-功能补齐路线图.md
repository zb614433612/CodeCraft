# CodeCraft v3 功能补齐实施计划（18 项 / 8 阶段）

> 版本：v1.0（初稿）
> 适用工程：`C:\Users\61443\Desktop\codecraft`（v3 前端，Vue 3 + Vite）
> 后端工程：`E:\zbcode\testDemo\code-craft`（Spring Boot，端口 8084）
> 参考实现：旧前端 `E:\zbcode\testDemo\code-craft\frontend\src`（v2，功能完整但技术栈旧）
>
> **范围说明**：本计划只覆盖「旧版有、v3 缺」的 18 项能力，**不含**登录页与个人信息页
> （v3 早期刻意砍掉登录、导致这两块缺失，属另一条线）。这两块已在 **L19** 单独补齐，
> 见 `README.md`「十九、L19 登录页与个人信息」：显式登录页（`views/LoginView.vue`）、
> 个人信息页（`views/ProfileView.vue`）、侧栏账号菜单、路由守卫恢复「登录态 + 角色」两层判定。

---

## 一、背景与目标

v3 前端已完成 L0–L18 的迁移与增强（工程装配、数据层、反馈层、路由权限、16 个管理页真实接线、聊天页 SSE/多会话并行/滚动跟随/模型弹层、侧栏折叠与按钮重做）。当前与旧版相比仍有 **18 项能力缺口**，本计划把它们拆成 8 个阶段逐批落地。

**三条铁律（沿用前序阶段约定）：**

1. **不虚构**：没有接口就不做假数据演示；没有数据源就不显示该字段。
2. **后端只增不改**：能不碰后端就不碰；必须新增接口时，只新增映射、不改既有契约（URL / method / 字段）。
3. **每阶段可独立验收**：`typecheck` 0 error + `build` 成功 + 浏览器断言通过 + README 更新 + 临时脚本清理。

---

## 二、功能清单与现状对照

| 编号 | 功能 | 现状 | 后端就绪度 | 参考实现 |
|---|---|---|---|---|
| F01 | P2P 点对点聊天 + 文件/图片传输 | ✅ 已实现（P5）：设备行「聊天」→ `P2pChatDrawer`（历史 + 2s 轮询 + 文本/文件/图片 + Agent 调用 + 授权） | ✅ 完整（`api/p2p.ts` 252 行：send/poll/history/delete/send-file/file-url/thumbnail/progress/open-dir） | `P2pPanel.vue` |
| F02 | 多标签工作区 | 无。右侧抽屉只有 files/git/term 三个固定 tab | — 纯前端 | `CodeAssistantView.vue`（tabs: chat/file/diff/web/image） |
| F03 | 分屏 | 无 | — 纯前端 | `SplitLayout.vue`（四象限拖拽） |
| F04 | 内嵌网页浏览 | 无 | — 前端 + Electron `webview` | `openWebTab()`（webview / iframe 降级） |
| F05 | 子 Agent 面板 | `App.vue` 对 `agent_event` **明确忽略**，无渲染 | ⚠️ **原判断有误**：`AgentForkManager` **不发** `agent_event`，数据在 `sub_agent_log` 表（本阶段已补只读 `GET /api/sub-agents`）；`delegate_progress` 确有 | `AgentPanel.vue` |
| F06 | 版本与更新（自动更新） | ✅ 已实现（P7）：独立页 `/about`（版本矩阵 + 检查更新 + 进度下载 + 安装 + 更新设置）；浏览器环境自动降级为只读 | ✅ 复用 `electron/update-manager.js`（零改动）+ 新增只读 `GET /api/system/version` | `utils/update.ts` + `SystemSettingView` 版本区 |
| F07 | 头像上传（用户管理） | `api/avatar.ts` 的 `uploadAvatar` **零调用** | ✅ `POST /api/avatar`（multipart，本地存储）+ `PUT /api/user/update` | `AvatarUploader.vue` |
| F08 | 消息列表虚拟滚动 | 全量 `v-for` 渲染，长会话（会话 2 有 1 万+ 条）卡顿 | — 纯前端 | `DynamicScroller`（vue-virtual-scroller） |
| F09 | 改动明细 diff 查看 | `ChangeReview.vue` 只有文件清单 + 回滚按钮，**无 diff** | ✅ `GET /api/git/diff`、`GET /api/snapshots/file-content` | `DiffView.vue` |
| F10 | 逐行/逐块撤销（hunk 级） | 无 | ✅ `POST /api/git/restore-hunks`（git apply --reverse） | `DiffView.vue`（LCS + patch） |
| F11 | 代码编辑器能力 | `ProjectFileTree.vue` 用纯 `<textarea>`（无高亮/行号/查找） | — 纯前端（highlight.js 已装） | `FileEditor.vue` |
| F12 | 任务清单优先级与依赖 | `TaskItem` 只有 `{id,title,status}`，丢弃 `priority` / `depends_on` | ✅ `TaskManagerTool` 输出的 JSON 已含这些字段 | `CodeAssistantView` 任务看板 |
| F13 | 工具结果语义化渲染 | 工具卡片只显示 `detail` 原文片段 | — 纯前端 | `extractActionFromToolContent()`（20+ 工具语义解析） |
| F14 | 附件图片缩略图 + 点击放大 | 附件只有文字 chip（名称 + 大小） | ✅ `getFilePreviewBlobUrl` | `openBigPreview()` |
| F15 | 未配置 Provider 全屏引导页 | 仅在 `/providers` 页显示内联错误条 | ✅ `GET /api/llm-providers` 空列表即可判定 | 旧版无（新增设计） |
| F16 | 右侧抽屉拖拽调整宽度 | `.cc-drawer` 固定 400px（行内 `--drawer-w` 覆盖） | — 纯前端 | `RightToolbar.vue` |
| F17 | 智能体管理头像上传 | 抽屉里有头像输入框，但无上传入口 | ✅ `POST /api/avatar` + `PUT /api/agent-configs/{id}` | `AvatarUploader.vue` |
| F18 | 智能体管理工具选择 | 有 chips UI，但工具列表是**写死的 prop 默认值**（`AgentEditDrawer.vue:268`） | ✅ `GET /api/tools`（工具注册表） | `AgentConfigView.vue` 工具选择 |

---

## 三、阶段划分总览

| 阶段 | 主题 | 包含功能 | 估算 | 可并行性 |
|---|---|---|---|---|
| **P0** | 地基与公共能力 ✅ | F16、F15 | 2 人日 | 独立 |
| **P1** | 聊天页体验增强 ✅ | F13、F12、F14、F08 | 5 人日 | 独立（F08 需最后做） |
| **P2** | 管理页补全（头像 / 工具）✅ | F07、F17、F18 | 3 人日 | 与 P1 可并行 |
| **P3** | 代码工作区底座 ✅ | F11、F09、F10 | 6 人日 | 依赖 P0（抽屉拖宽） |
| **P4** | 多标签 + 分屏 + 内嵌网页 ✅ | F02、F03、F04 | 6 人日 | 依赖 P3（编辑器） |
| **P5** | P2P 协作 ✅ | F01 | 6 人日 | 与 P3/P4 可并行 |
| **P6** | 子 Agent 面板 ✅ | F05 | 3 人日 | 依赖 P1（工具渲染） |
| **P7** | 桌面端版本与更新 ✅ | F06 | 4 人日 | 依赖桌面壳决策（已按方案 1 落地） |
| | **合计（18/18 完成）** | **18 项** | **≈35 人日** | |

**里程碑：**

- **M1（P0+P1）**＝ 7 人日：聊天页「看得爽、读得懂、不卡」
- **M2（P2+P3）**＝ 9 人日：管理页补齐 + 可看可改的代码工作区
- **M3（P4）**＝ 6 人日：真正的多标签分屏工作台
- **M4（P5+P6）**＝ 9 人日：协作与多 Agent 可视化
- **M5（P7）**＝ 4 人日：桌面客户端闭环

**推荐执行顺序：P0 → P1 ∥ P2 → P3 → P4 ∥ P5 → P6 → P7**

---

## 四、阶段详情

### 阶段 P0｜地基与公共能力（F16、F15）

**目标**：先建两个「被后续功能反复复用」的地基，避免后面每做一个面板都重复改宽度、每个页面都重复处理无 Provider。

#### F16 右侧抽屉拖拽调整宽度

- **设计要点**
  - 新建 `src/utils/drawer-resize.ts`：`useDrawerResize(key, { defaultWidth, min, max })`，返回 `width`、`onPointerDown`、`reset`。
  - 指针拖拽左边界（`pointerdown` → `setPointerCapture` → `pointermove` 计算 `clientX` 与右边界差值）。
  - 宽度写入 `localStorage`（键 `codecraft.drawerWidth.<key>`），双击分隔条复位到默认值。
  - 保留现有 `--drawer-w` CSS 变量机制，组件只改这个变量，不改 DOM 结构。
- **改造文件**：`assets/app.css`（`.cc-drawer` 增加可拖拽分隔条样式）、`src/views/ChatView.vue`、`GitChangePanel.vue`、`ProjectFileTree.vue`、`TerminalPanel.vue`、`ChangeReview.vue`。
- **验收**：拖拽 320→640px 流畅无抖动；刷新后宽度保持；双击复位；窄屏（<900px）自动禁用拖拽并强制全屏。

#### F15 未配置 Provider 全屏引导页

- **设计要点**
  - 新建 `src/components/ProviderGuide.vue`：全屏空态（大图标 + 标题「还没有可用的模型提供商」+ 说明 + 主按钮「去配置」+ 次按钮「刷新」）。
  - 判定来源：`providerList.length === 0 && !providerLoading`（`GET /api/llm-providers` 只返回 `enabled=1`，空列表即「无可用 Provider」）。
  - 管理员 → 「去配置」跳 `/providers`；普通用户 → 文案改为「请联系管理员配置模型提供商」，按钮置灰。
  - 挂载位置：`ChatView` 的对话区空态（`messages.length === 0` 时优先展示引导页，而不是「开始新对话」）。
- **改造文件**：新增 `ProviderGuide.vue`；`ChatView.vue`（空态分支）；`App.vue`（传 `providers` / `isAdmin`）。
- **验收**：库中 Provider 全部停用后进入 `/chat` 看到引导页；新增一个 Provider 后刷新恢复正常对话界面；非管理员看不到「去配置」跳转。

---

### 阶段 P1｜聊天页体验增强（F13、F12、F14、F08）

**目标**：把聊天页从「能用」做到「好用」——工具结果看得懂、任务清单有结构、图片点得开、万条消息不卡。

#### F13 工具结果语义化渲染

- **现状痛点**：`chat-mapper.ts` 把工具输出压成 `detail` 字符串截断 4000 字，全部原始 JSON/日志堆在一个灰框里。
- **设计要点**
  - 新建 `src/utils/tool-render.ts`：把工具输出归一为 `ToolRenderModel`，联合类型：
    `{ kind: 'file-write', path, linesAdded, linesDeleted, language }`
    `{ kind: 'file-read', path, lineRange }`
    `{ kind: 'command', cmd, exitCode, stdout, stderr, durationMs }`
    `{ kind: 'search', query, hits: [{file, line, text}] }`
    `{ kind: 'web', url, title, snippet }`
    `{ kind: 'db', sql, rows, columns }`
    `{ kind: 'git', subcommand, summary }`
    `{ kind: 'agent', agentName, action }`
    `{ kind: 'task', tasks }`
    `{ kind: 'raw', text }`（兜底）
  - 新建 `src/components/ToolResultCard.vue`：按 `kind` 渲染不同卡片（文件改动用 +N/-M 徽章、命令用等宽终端块 + 退出码色标、搜索用命中文件列表、网页用标题+域名+摘要、数据库用迷你表格）。
  - 迁移旧版 `extractActionFromToolContent()` 的启发式规则（20+ 工具名映射），补上 v3 新增的 MCP 工具（前缀 `mcp_` 走 `raw` + 可折叠 JSON）。
  - `ChatMessage.vue` 的工具区改为渲染 `ToolResultCard`；`raw` 类型保留「查看原始输出」折叠。
- **改造文件**：新增 `src/utils/tool-render.ts`、`ToolResultCard.vue`；`ChatMessage.vue`、`chat-mapper.ts`、`assets/app.css`（新增卡片样式节）。
- **验收**：`file_writer` → 显示「已写入 xxx.ts +42 -3」；`run_command` → 终端块 + 退出码 0/1 着色；`web_search` → 命中列表；未知工具 → 折叠原始 JSON 不报错。

#### F12 任务清单优先级与依赖

- **现状痛点**：`TaskItem = {id,title,status}`，后端 `TaskManagerTool` 输出的 `priority` / `depends_on` 被丢弃。
- **设计要点**
  - `chat-mapper.ts`：`TaskItem` 扩展 `priority?: 'HIGH'|'MEDIUM'|'LOW'`、`dependsOn?: string[]`、`failed?: boolean`。
  - 渲染：任务浮层（`ChatView` 的 `.cc-taskpop`）与 `ChatTaskPanel.vue` 同步改造 —— 优先级用左侧色条（HIGH 红 / MEDIUM 橙 / LOW 灰）、依赖用「等待 #3 #5」小字标签、被依赖任务未完成时状态显示「阻塞中」。
  - 统计徽章口径调整：`已完成/总数` 之外增加「阻塞 N」。
- **改造文件**：`chat-mapper.ts`、`ChatTaskPanel.vue`、`ChatView.vue`、`App.vue`（`restoreTasksFromMessages`）、`assets/app.css`。
- **验收**：构造含 `priority` / `depends_on` 的任务 JSON → 浮层显示色条与「等待」标签；无这些字段时降级为当前样式（不报错）。

#### F14 附件图片缩略图 + 点击放大

- **设计要点**
  - `ChatComposer.vue`：附件 chip 改为 48×48 缩略图（图片走 `getFilePreviewBlobUrl(id)`，文档保留图标 + 文件名），hover 显示「×」移除。
  - 新建 `src/components/ImageLightbox.vue`：全屏遮罩（`z-index: 190`，低于 Toast 200）、居中图片、`ESC` / 点击遮罩关闭、左右切换（多图）、滚轮缩放（1x–4x）、下载按钮。
  - 用户消息内的图片（若有）与 `FileDetailDrawer` 的预览也复用该 Lightbox。
- **改造文件**：新增 `ImageLightbox.vue`；`ChatComposer.vue`、`ChatView.vue`、`FilePickerDialog.vue`、`assets/app.css`。
- **验收**：附加图片后聊天输入区显示缩略图；点击 → lightbox 打开且可缩放/切换/ESC 关闭；文档附件仍显示文件图标。

#### F08 消息列表虚拟滚动

- **现状痛点**：会话 2 有 1 万+ 条消息，全量 `v-for` 渲染 DOM 数量爆炸。
- **技术选型**
  - **方案 A（推荐，自研）**：`src/utils/virtual-list.ts` 实现「测量 + 占位」窗口化 —— 只渲染视口上下各 5 屏的消息，未渲染项用最近测量高度占位；`ResizeObserver` 回写高度缓存；流式尾部消息强制真实渲染（不入虚拟池）。
  - 方案 B（备选）：引入 `vue-virtual-scroller@next` 的 `DynamicScroller`；缺点是该库维护停滞、与 Vue 3.5 兼容性需实测。
  - **决策：先做方案 A**，因为它必须与「流式自动贴底 / 折叠展开 / 触顶加载更早」三个既有逻辑共存，自研更可控。
- **与既有逻辑的兼容处理（关键风险）**
  - 流式期间：新消息/内容增长时跳过窗口化（`streaming` 状态直接全量渲染尾部 N 条）。
  - 折叠/展开（思考区、工具区）：展开后触发 `ResizeObserver` → 修正高度缓存与 `scrollTop` 补偿。
  - 触顶加载（`load-more`）：插入历史消息后按插入总高做 `scrollTop += delta`。
- **改造文件**：新增 `src/utils/virtual-list.ts`；`ChatView.vue`（消息区）、`assets/app.css`（占位块）。
- **验收**：1 万条消息会话首屏渲染 DOM 消息节点 < 60 个；滚动到顶触发分页且位置不跳；流式生成时贴底正常；折叠展开后位置正确。
- **建议**：本阶段**最后做**，因为它会牵动 P1 其它项的 DOM 结构。

---

### 阶段 P2｜管理页补全（F07、F17、F18）✅ 已完成

> 落地记录见 `README.md`「十四、P2 管理页补全」。实际实现与计划的差异：① 头像上传改为「只拿 URL 不写库」，
> 由所在抽屉的保存流程统一提交（取消即丢弃）；② 工具选项的兜底表改用**真实工具名快照**（计划里的
> 「退回当前写死列表」用的是假 key，会把 Agent 的工具全删）；③ 顺带修了两个真实缺陷 ——
> 前端 `toAgentRow` 解析 `tool_names` 用逗号切 JSON、后端 `update` 把 `"[]"` 原样写库导致 Agent 无工具。

**目标**：补齐头像上传与工具选择的真实数据源，去掉最后几处「写死」。

#### F07 头像上传（用户管理）+ F17 智能体管理头像上传

- **共享组件**：新建 `src/components/AvatarUploader.vue`
  - props：`modelValue`（URL）、`fallbackText`、`size`、`maxSizeMB`（默认 2MB）
  - 交互：点击选文件 → 客户端压缩 → `POST /api/avatar` → `emit('update:modelValue', url)`；hover 显示「更换 / 移除」；圆形裁切预览。
- **客户端压缩**：新建 `src/utils/image-compress.ts`（迁移旧版逻辑：阈值 1.5MB / 最长边 2048px，canvas 等比缩放，输出 jpeg 0.85）。
- **F07 落点**：`UserEditDrawer.vue` 头像字段；保存时随 `PUT /api/user/update` 提交 `avatar`。（管理他人头像需 admin，后端 `UserController` 已是 admin-only。）
- **F17 落点**：`AgentEditDrawer.vue` 头像字段；`AgentCard.vue` 头像展示改为 `<img>` + fallback（复用已有 `utils/avatar.ts` 的 `isImageAvatar` / `avatarFallback`）。
- **验收**：用户管理编辑弹窗上传头像 → 列表与侧栏用户头像同步更新；智能体抽屉上传 → 卡片与聊天页 AI 头像同步；超过 2MB 被压缩而非报错；`file://` 或后端不可用时给明确 toast。

#### F18 智能体管理工具选择

- **现状痛点**：`AgentEditDrawer.vue:268` 的 `toolOptions` 是写死的默认数组。
- **设计要点**
  - `App.vue` 在 `/agents` 加载时并行拉 `listTools()`（`src/api/tools.ts`），映射为 `{ key, label, group, description }`。
  - `AgentEditDrawer.vue` 工具区升级：按 `group` 分组（文件 / 命令 / 搜索 / 数据库 / 协作 / MCP）、顶部搜索框、每组「全选/反选」、底部「已选 N / 共 M」。
  - `allTools = true`（拥有全部工具）时，列表整体半透明禁用，与现有语义保持一致。
  - 后端不可用或返回空 → 退回当前写死列表并给一行灰字提示「未能加载工具注册表，展示默认列表」。
- **改造文件**：`AgentEditDrawer.vue`、`AgentListView.vue`（透传 `toolOptions`）、`App.vue`、`assets/app.css`。
- **验收**：工具项数量与 `GET /api/tools` 返回一致；搜索「git」只剩 git 相关；分组全选只影响本组；保存后回读一致。

---

### 阶段 P3｜代码工作区底座（F11、F09、F10）✅ 已完成

> 落地记录见 `README.md`「十五、P3 代码工作区底座」。实际实现与计划的差异：
> ① 编辑器用「高亮层 + 透明 textarea + 行号栏」三层结构，样式与 hljs 主题直接复用聊天代码块（不复制一套规则）；
> ② 快照视图的差异是**只读**的，撤销仍走整文件回滚（hunk 撤销依赖 git 索引，快照没有）；
> ③ 撤销单行不是「另造 hunk」，而是把同一 hunk 里其它变更行改写为上下文行 / 剔除后原样下发
> `git apply --reverse --unidiff-zero`（new 侧行号保持不变即可精确命中，真实 git 端到端 15/15）；
> ④ 顺带修了两个真实缺陷 —— `parseUnifiedDiff` 丢弃「无 `diff --git` 头」的裸 diff、
> 新建文件的快照差异永远打不开（后端对新建文件返回 null，前端却当失败）。

**目标**：把右侧「文件面板」从只读文本框升级为可用的代码查看/编辑 + 差异审查 + 精确撤销。这一阶段是 P4（多标签）的前置。

#### F11 代码编辑器能力

- **设计要点**
  - 新建 `src/components/CodeEditor.vue`：`<pre><code>`（highlight.js 着色）+ 透明 `<textarea>` 叠加（保留原生输入/IME/撤销栈）—— 「伪富文本」方案，避免引入 CodeMirror/Monaco（≈1MB+）。
  - 能力：行号栏、当前行高亮、Tab 缩进、自动缩进、`Ctrl+F` 页内查找（高亮 + 上/下跳转）、`Ctrl+S` 保存、只读/可编辑切换、语言按扩展名推断（复用 `utils/markdown.ts` 已注册的 28 种语言）。
  - 大文件保护：> 2MB 或 > 2 万行时降级为只读 + 提示「文件过大，仅支持查看」。
  - 未保存离开保护：`beforeunload` + 切换文件时确认。
- **改造文件**：新增 `CodeEditor.vue`、`src/utils/language-detect.ts`；`ProjectFileTree.vue`（替换 textarea）；`assets/app.css`。
- **验收**：打开 `.vue` / `.ts` / `.json` 正确着色；行号与内容对齐；`Ctrl+S` 落盘并 toast；>2MB 文件自动只读。

#### F09 改动明细 diff 查看

- **设计要点**
  - `src/utils/diff-hunk.ts`：LCS 行级 diff → 对齐行 + hunk 分组（上下文 3 行），输出 `Hunk[] { header, lines, patch }`。
  - 新建 `src/components/DiffViewer.vue`：双栏（HEAD / 工作区）与单栏（unified）切换、增删行着色、hunk 折叠/展开、上下滚动同步、`+A/-D` 统计。
  - 数据来源两条：
    - Git 变更：`GET /api/git/diff?file=`（工作区 vs HEAD）
    - 快照轮次：`GET /api/snapshots/file-content?snapshotId=&path=`（该轮备份 vs 当前）
  - 落点：`GitChangePanel.vue` 点文件名 → 打开 `DiffViewer`（替换现有内嵌纯文本预览）；`ChangeReview.vue` 增加「查看差异」。
- **验收**：修改一个文件后 diff 正确显示 +N/-M；双栏行号对齐；大文件（>5000 行 diff）不卡顿（分块渲染）。

#### F10 逐行 / 逐块撤销（hunk 级）

- **设计要点**
  - 在 `DiffViewer` 的每个 hunk 头部加「撤销此块」（`restore-hunk`）、每一行加「撤销此行」（`restore-line`）。
  - 实现：由 `diff-hunk.ts` 生成标准 unified patch 片段 → `POST /api/git/restore-hunks { projectRoot, file, hunks }`（后端 `git apply --reverse`）。
  - 前置校验：目标目录必须是 git 仓库（`GET /api/git/status` 的 `isRepo`），否则按钮置灰并提示「非 Git 仓库，仅支持整文件恢复」。
  - 撤销成功后局部重算 diff（不整页刷新），并 emit `changed` 让上层刷新改动统计。
  - 快照视图下的撤销走 `rollbackFile`（整文件），UI 上明确区分「撤销此块（Git）」与「回滚整文件（快照）」。
- **验收**：对一处新增行点「撤销此行」→ 文件内容恢复且 git diff 同步减少；撤销块后其它块不受影响；非 git 仓库时按钮禁用提示准确；失败时显示后端 `error` 原文（不吞错）。

---

### 阶段 P4｜多标签 + 分屏 + 内嵌网页（F02、F03、F04）✅ 已完成

> 落地记录见 `README.md`「十六、P4 多标签工作台」。实际实现与计划的差异：
> ① **标签类型去掉 `chat`**：聊天始终是左侧主列 —— 塞进标签会让「切走再切回」重建消息流与 SSE 状态，风险远大于收益；
> ② **标签全局共享、不按会话隔离**：工作台服务于「边聊边看代码」，切会话关掉正在看的文件反而碍事；
> ③ **状态隔离靠「全标签实例化 + v-show」**：只有 `term` 例外（xterm 在 `display:none` 下初始化会拿到 0 尺寸，改为仅激活时挂载）；
> ④ **禁嵌检测不做「精确判定」**：浏览器对跨域 `X-Frame-Options` 拒绝不抛任何事件，改为「4s 超时判定 + 常驻提示条」；
> ⑤ **窄屏（≤900px）工作区改浮层覆盖**：并排会把聊天压到 200px 以内，两栏都没法用；
> ⑥ 抽屉**完整保留**（`settings.workspaceMode` 切换），不是「降级为快捷入口」而是「两套并存」；
> ⑦ 额外打通两个打开入口：消息内文件路径 → 文件/图片标签、文件树点文件 → 新标签（`ProjectFileTree.externalOpen`）。

**目标**：把「右侧单抽屉」升级为可切换、可并排、可看网页的工作台。这是本计划工程量最大、也最影响日常使用的阶段。

#### F02 多标签工作区

- **设计要点**
  - 新建 `src/store/workspace.ts`（pinia + persist）：
    ```ts
    type TabKind = 'chat' | 'file' | 'diff' | 'web' | 'image' | 'term' | 'git'
    interface WorkspaceTab { id, kind, title, payload, dirty?: boolean, closable: boolean }
    ```
    状态：`tabs`、`activeTabId`、`layout`（single / split）。
  - 新建 `src/components/WorkspaceTabs.vue`：标签栏（图标 + 标题 + dirty 圆点 + 关闭）、`+` 新建菜单、拖拽排序、中键关闭、`Ctrl+W` 关闭当前、`Ctrl+Tab` 切换。
  - 内容区按 `kind` 动态渲染：`chat → ChatView`、`file → CodeEditor`、`diff → DiffViewer`、`web → WebFrame`、`image → ImageLightbox 内嵌版`、`term → TerminalPanel`、`git → GitChangePanel`。
  - 与现有 `ChatView` 右侧抽屉的关系：**抽屉降级为快捷入口**，点击后不再是抽屉而是打开对应 tab（保留旧行为开关，避免一次性砍掉）。
- **验收**：打开 3 个 tab 并切换，状态各自保持（未保存标记不丢）；关闭 dirty tab 有确认；刷新后 tab 恢复（persist）。

#### F03 分屏

- **设计要点**
  - 新建 `src/components/SplitPane.vue`：支持 `horizontal / vertical`、`ratio`（0.2–0.8）、拖拽分隔条、双击复位 50%、最小宽 280px、窗口 resize 自适应。
  - 布局扩展：`single`（单栏）→ `split-h`（左右）→ `split-v`（上下）→ `quad`（四象限，可选）。
  - `workspace.ts` 记录每个 pane 的 `activeTabId`；拖 tab 到另一个 pane 即移动。
- **验收**：左右分屏拖拽到 30/70 比例流畅；两个 pane 各自滚动互不影响；缩到最小时触发最小宽保护；切回单栏后 tab 不丢失。

#### F04 内嵌网页浏览

- **设计要点**
  - 新建 `src/components/WebFrame.vue`：地址栏（URL 输入 + 前进/后退/刷新/外部打开）、加载态、错误态。
  - 双通道：
    - Electron 环境（`window.electronAPI` 存在）→ `<webview>`（能力完整，可加载任意站点）
    - 浏览器环境 → `<iframe sandbox="allow-scripts allow-same-origin allow-forms">`；命中 `X-Frame-Options` / CSP 时显示「该站点禁止嵌入」+ 「在系统浏览器打开」按钮
  - 安全：地址栏仅接受 `http/https`；`file://`、`javascript:` 直接拒绝；默认不注入任何脚本。
- **验收**：输入 `https://example.com` 正常渲染；输入被禁嵌站点时给出降级提示（不白屏）；非法协议被拒绝并提示。

---

### 阶段 P5｜P2P 协作（F01）✅ 已完成

> 落地记录见 `README.md`「十七、P5 P2P 协作」。实际实现与计划的差异：
> ① **入口用抽屉而不是页内左右分栏**：1120px 页宽装两栏后设备行字段会被挤成多行，抽屉可拖到 1200px，宽度上限更高且零布局风险；
> ② **不做传输进度条**：后端 `send-file` 是同步接口（分块发完才返回），前端拿到 `transferId` 时传输已结束，进度恒为 100%，做进度条等于假数据，改为「发送中…」+ 完成 toast；
> ③ **不做已读/未读**：后端无回执字段，不虚构；
> ④ 额外落地：**历史 pending 恢复**（刷新后「AI 正在思考中」不丢）、**发送失败标红 + 重发**、**离线只读历史**、**授权抽屉叠层**；
> ⑤ 单机无法验证真实互发（TLS 通道需双机），验证覆盖「接口契约 + 失败分支 + 全部 UI 交互」。

**目标**：把 `/p2p` 从「设备管理页」升级为「可聊天、可传文件」的完整协作面板。

- **设计要点**
  - 入口：`P2pPeerRow.vue` 增加「聊天」按钮 → 打开 `P2pChatDrawer`；同时支持 `/p2p` 页内左右分栏模式（设备列表 + 会话区）。
  - 新建 `src/components/P2pChatPanel.vue`：
    - 轮询：`pollMessages(peerId)` 每 2s（页面不可见时暂停，`document.visibilityState`）；`getHistory` 首次加载历史。
    - 气泡：己方/对方左右分栏，支持文本 + 文件 + 图片三类；文件气泡显示文件名/大小/进度条/「打开目录」；图片气泡用 `getThumbnailUrl` 缩略 + 点击走 `ImageLightbox`（原图 `getFileUrl`）。
    - 发送文件：拖拽进面板 / 点击选择 → `sendFile()` → 轮询 `getTransferProgress()` 更新进度（分块完成百分比）。
    - 已读/未读与时间分组；`deleteMessages` 清空会话（二次确认）。
  - 新建 `src/components/P2pFileBubble.vue`：文件/图片气泡独立组件（含进度、失败重试）。
  - Agent 调用：展示 `getPeerAuthToMe` 的 Agent 列表，点选后 `invokeAgent(peerId, agentConfigId, message)`，结果作为特殊气泡渲染（与 F13 的工具卡片风格一致）。
- **依赖**：`api/p2p.ts` 全部就绪，**零后端改动**。
- **验收**：两台设备（本机 + 虚拟机或同机双实例）互发文本 1s 内显示；发送 5MB 图片显示缩略图与进度，点击放大；断网时消息失败有重试入口；清空历史后 `getHistory` 为空。
- **风险**：P2P 需要证书与端口互通，测试环境受限 → 至少保证「离线降级」（未连接时面板只读展示历史）。

---

### 阶段 P6｜子 Agent 面板（F05）✅ 已完成

> 落地记录见 `README.md`「十八、P6 子Agent面板」。**计划的最大前提被推翻**，实际实现与计划的差异：
> ① **`agent_event` 这条数据源不存在**：核查后端后确认 `AgentForkManager`（fork 子 Agent）**从不发任何 SSE 事件**，
>    它把子 Agent 结果以纯文本写进主 Agent 思考流；`AgentEventBus` 只发 `ask_user` / `tool_call_start` / `task_error`，
>    `AgentInvokeServiceImpl` 另发 `delegate_progress`。旧版 `AgentPanel.vue` 依赖的 `agent_event` 是死代码，面板永远为空。
>    改为读 `sub_agent_log` 表（fork 时 INSERT running、结束时 UPDATE），**这是唯一可靠数据源**。
> ② **不是零后端改动**：新增只读接口 `GET /api/sub-agents?conversationId=&turnId=`（`SubAgentController` + `SubAgentVO`
>    + `SubAgentLogMapper.selectMetaByConversationId` 轻量查询，不拉 `full_messages`）。不改任何既有契约。
> ③ **数据获取用「库 + 轮询」而非「收事件」**：有 running 记录时 3s 轮询一次（上限 200 次防后端残留 running 导致无限轮询），
>    全部终态自动停；切回会话时拉一次覆盖历史回放（计划原写「历史从消息中提取」，实际库里就是全量，不必解析消息）。
> ④ **委托调用（`delegate_progress`）单独分块**：跨智能体委托 A→B 与 fork 子 Agent 语义不同，面板里分两块展示，不混淆。
> ⑤ 落点只做 `.cc-runbar` 浮层，未做 P4 工作区 tab（可后续复用同一组件）。

**目标**：把后端已经产生的子 Agent 记录渲染出来，让「一个任务拆给多个 Agent 并行」这件事可见。

- **设计要点**
  - 新建 `src/utils/agent-event.ts`：把 SSE 的 `agent_event` / `delegate_progress` 归一为
    ```ts
    interface SubAgentRun { id, name, avatar?, task, status: 'running'|'done'|'failed', startedAt, endedAt?, output?, tokens?, tools: string[] }
    ```
  - `App.vue` 增加 `subAgentMap: Record<convKey, SubAgentRun[]>`（与会话 key 一致，天然支持多会话并行），`consumeChatStream` 中消费 `agent_event` 写入。
  - 新建 `src/components/SubAgentPanel.vue`：圆形头像 + 名称 + 状态点 + 任务摘要 + 耗时 + 工具次数；点击展开该子 Agent 的输出摘要；失败态红色描边 + 错误原因。
  - 落点：聊天页运行状态栏（`.cc-runbar`）增加「🤖 N 个子 Agent」按钮 → 弹出面板；也可作为 P4 工作区的一个 tab。
- **改造文件**：新增 `agent-event.ts`、`SubAgentPanel.vue`；`App.vue`、`ChatView.vue`、`chat-mapper.ts`（历史回放时从消息中提取）、`assets/app.css`。
- **验收**：触发一次带子 Agent 的任务 → 运行栏出现计数、面板列出子 Agent 与状态流转（running→done）；历史切回后仍能从消息回放；无事件时不显示入口。

---

### 阶段 P7｜桌面端版本与更新（F06）✅ 已完成

**目标**：补齐「版本信息 + 检查更新 + 下载进度 + 安装」，浏览器环境安全降级。

> ✅ **方案决策（已落地）**：选**方案 1** —— v3 产物已随 `codecraft.jar`（`src/main/resources/v3-static/`）
> 进入桌面客户端，Electron 主进程的 `electron/update-manager.js` 与 `preload.js` 的
> `electronAPI.update` **零改动复用**；额外补一个只读后端接口 `GET /api/system/version`，
> 让浏览器环境也能显示真实版本号。

- **设计要点（按方案 1）**
  - 迁移 `src/utils/update.ts`（`UpdateCheckResult` / `UpdateDownloadState` / `UpdateSettings` / `isUpdateSupported` / `describeInstallAction`）。
  - 新建 `src/components/VersionCard.vue`：当前版本、最新版本、发布时间、Release 说明（markdown 渲染）、「检查更新」「下载」「安装并重启」按钮、下载进度条（百分比 + 已下载/总量）。
  - 设置项：自动检查（启动后 3s 静默检查一次）、使用国内镜像、镜像前缀、备份保留数量。
  - 落点：`SettingsView.vue` 或独立 `/about` 页；`DataManageView.vue` 重置数据后可选调 `clearLocalAppData()`。
  - 浏览器环境：`isUpdateSupported() === false` → 卡片只显示版本号 + 一行灰字「桌面客户端内可使用自动更新」。
- **验收**：桌面客户端内检查更新能拿到 latest 版本号与 notes；下载进度条随 `download` 事件推进；安装按钮文案按平台正确（`describeInstallAction`）；浏览器内不报错且给出降级提示。

**实现差异（实际落地 vs 计划）：**

| 计划 | 实际实现 | 原因 |
|---|---|---|
| 落点「`SettingsView.vue` 或独立 `/about`」 | 独立页 `/about`（`AboutView.vue` + `VersionCard.vue`），并在「设置」聚合页登记入口 | 卡片内容多（版本矩阵 + Release 说明 + 进度条 + 设置），塞进聚合导航页会把「纯导航」变成「半功能页」 |
| 版本号只有 `app.getVersion()` | 客户端版本 + 后端版本**分列**，后端走新增的 `GET /api/system/version` | 浏览器环境没有 Electron；只换 jar 不重装客户端时两者会不一致，合并显示会掩盖问题 |
| 未提后端改动 | 新增只读 `GET /api/system/version`；`application.yml` 加 `app.version` / `app.build-time`（Maven 资源过滤注入）；`SystemController` 加占位符兜底 | 浏览器端要有真实版本号来源；资源过滤在 IDE 直跑时可能不生效，必须兜底 |
| 更新设置 4 项 | 同步 4 项（自动检查 / 国内镜像 / 镜像前缀 / 备份保留份数）+ 额外「清理升级缓存」按钮 | 主进程已提供 `clearLocalData()`，「重置数据」也复用它，给用户一个可见入口 |
| 未提 Release 说明渲染方式 | `renderMarkdown()` 渲染（`html:false` 防 XSS，外链 `target=_blank`） | Release 正文来自 GitHub，属外部内容 |
| 浏览器降级为「只显示版本号 + 一行灰字」 | 只读版本卡片 + 灰字提示 + 设置区退化为只读文案 + 点「检查更新」给 toast | 只读文案比「按钮点了没反应」清晰 |

**验证**：`typecheck` 0 error；`build` 377 modules；后端 `/api/system/version` 接口探针 **12/12**（含未登录 401、版本 3.0.0、构建时间已注入、无敏感字段、非 403）；浏览器 CDP **30/30**（浏览器降级 13 项 + mock Electron 桌面链路 17 项，覆盖检查更新 → 版本详情 → 进度条 → 安装按钮 → `update.install` 调用链）。

---

## 五、依赖关系

```
P0(F16 抽屉拖宽) ─┬─→ P3 ──→ P4
                  │
P0(F15 引导页)     │
                  ├─→ P1 ──→ P6（子 Agent 复用工具卡片样式）
P2（头像/工具）────┘
P5（P2P，独立，可与 P3/P4 并行）
P7（依赖桌面壳决策，可与 P5/P6 并行，但上线前必须收口）
```

**硬依赖（不能打乱）：**
- F09/F10 依赖 F11（编辑器与 diff 共用行模型）
- F02/F03 依赖 F11（file tab 需要编辑器）
- F08 必须排在 P1 其它项之后（窗口化会动消息 DOM 结构）
- F05 的卡片样式复用 F13

**软依赖（建议顺序）：**
- F14 的 Lightbox 被 F01（P2P 图片气泡）复用 → P5 前完成 P1
- F16 的拖宽被 P3 的 diff/编辑器面板复用 → P0 先行

---

## 六、跨阶段通用规范

1. **组件与样式命名**：`cc-` 前缀 + BEM；新样式按 `app.css` 现有节编号顺延（当前到第 41 节「聊天页运行状态栏」+ 第 42 节响应式，新功能从第 42 节起插入并顺延响应式节）。
2. **显隐一律用 `.is-open` 类**，不用 `v-show`（CSS 基础态常为 `display:none`，`v-show` 会被样式表覆盖 —— 见踩坑经验 216）。
3. **层级约定**：抽屉遮罩 60 < 抽屉 70 < 目录选择 80 < 文件选择 84 < 附件菜单 86 < 模型选择 90 < **ImageLightbox 190** < Toast 200 < Confirm 210。
4. **接口层**：只用 `src/api/*`，不裸写 `fetch`（`authFetch` 除外，用于 blob/SSE）；`request<T>` 返回 `ApiResponse<T>`，业务代码统一 `res.data`。
5. **不虚构数据**：拿不到数据就显示空态/错误态，不用 demo 常量顶替。
6. **静态预览 `preview.html` 同步策略**：只同步「结构性、可演示」的部分（如新增卡片外观、tab 栏），深度交互（虚拟滚动、hunk 撤销、P2P 轮询）**不同步**，在 README 说明即可。
7. **每阶段 DoD（完成定义）**：
   - `npm run typecheck` → 0 error
   - `npm run build` → 成功且无新增警告
   - 浏览器 CDP 断言脚本通过（结果写文件后读取，避免 GBK 乱码）
   - `README.md` 追加本阶段章节（改动清单 / 验证数据 / 踩坑）
   - 临时脚本（`_*.cjs` / `_*.txt`）全部删除，调试用 Edge 按 PID 关闭

---

## 七、风险登记表

| 风险 | 影响功能 | 等级 | 应对 |
|---|---|---|---|
| 虚拟滚动与流式贴底/折叠展开/触顶分页冲突 | F08 | 高 | 流式期间退出窗口化；`ResizeObserver` 回写高度；插入历史时补偿 `scrollTop` |
| hunk 撤销要求 git 仓库、且不适用于未跟踪/跨盘文件 | F10 | 中 | 前置 `isRepo` 校验；非 git 场景降级为整文件回滚并明确文案 |
| iframe 被 `X-Frame-Options` / CSP 拒绝 | F04 | 中 | Electron 用 `webview`；浏览器降级「在系统浏览器打开」 |
| 自动更新依赖 Electron 壳与发布流程 | F06 | 高 | 先做方案决策；未定前只交付「只读版本卡片」 |
| P2P 测试需双端网络互通 | F01 | 中 | 用同机双实例 + 离线降级；不阻塞其它阶段 |
| 子 Agent 事件字段与后端未完全对齐 | F05 | 中 | 先打日志抓真实事件体，再定 schema；字段缺失降级为「未知子 Agent」 |
| 伪富文本编辑器在超大文件下卡顿 | F11 | 中 | 2MB / 2 万行阈值只读；分块渲染 |
| 多标签重构影响聊天主链路 | F02/F03 | 高 | 抽屉与 tab 双模式并存，开关切换；每步 typecheck + 回归清单 |

---

## 八、附录 A：功能 → 阶段映射

| 阶段 | 功能编号 | 功能名称 |
|---|---|---|
| P0 | F16 | 右侧抽屉拖拽调整宽度 |
| P0 | F15 | 未配置 Provider 的全屏引导页 |
| P1 | F13 | 工具结果语义化渲染 |
| P1 | F12 | 任务清单优先级与依赖 |
| P1 | F14 | 附件图片缩略图 + 点击放大 |
| P1 | F08 | 消息列表虚拟滚动 |
| P2 | F07 | 头像上传（用户管理） |
| P2 | F17 | 智能体管理头像上传 |
| P2 | F18 | 智能体管理工具选择 |
| P3 | F11 | 代码编辑器能力 |
| P3 | F09 | 改动明细 diff 查看 |
| P3 | F10 | 逐行 / 逐块撤销（hunk 级） |
| P4 | F02 | 多标签工作区 |
| P4 | F03 | 分屏 |
| P4 | F04 | 内嵌网页浏览 |
| P5 | F01 | P2P 点对点聊天 + 文件/图片传输 |
| P6 | F05 | 子 Agent 面板 |
| P7 | F06 | 版本与更新（自动更新）✅ |

## 附录 B：可直接复用的现有资产

| 资产 | 位置 | 复用点 |
|---|---|---|
| P2P 全部接口 | `src/api/p2p.ts` | F01 零改造 |
| 头像上传接口 | `src/api/avatar.ts` | F07 / F17 |
| 工具注册表 | `src/api/tools.ts` | F18 |
| Git 全套接口（含 `restoreHunks`） | `src/api/git.ts` | F09 / F10 |
| 快照接口 | `src/api/snapshot.ts` | F09 / F10 |
| 项目读写 / 执行 | `src/api/project.ts` | F11 |
| markdown + hljs 渲染 | `src/utils/markdown.ts` | F11（语言注册）/ F13 |
| 头像判定工具 | `src/utils/avatar.ts` | F07 / F17 / F05 |
| Toast / Confirm | `src/utils/toast.ts`、`confirm.ts` | 全阶段 |
| 目录选择器 | `src/components/DirectoryPicker.vue` | F02（打开文件夹） |
| 终端（xterm） | `src/components/TerminalPanel.vue` | F02（term tab） |
| 旧版参考实现 | `frontend/src/components/*`（DiffView / FileEditor / SplitLayout / RightToolbar / AgentPanel / P2pPanel / AvatarUploader） | 全阶段（逻辑移植，UI 重写为 `cc-` 风格） |

## 附录 C：不在本计划范围

- 登录页（v3 已采用隐形登录 + 单管理员角色）
- 自动更新的服务端 / 发布流水线改造（仅消费现有 Release）
- 多用户权限细化（当前 admin / user 两级已够用）
- 移动端适配（仅需保证 900px 断点不破版）
