> 🌐 English Version：[🇬🇧 CHANGELOG_EN](./CHANGELOG_EN.md)
# 更新日志

本文档记录 CodeCraft 项目的所有重要版本变更。

---

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
