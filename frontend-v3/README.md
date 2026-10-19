# CodeCraft v3 前端（可运行工程 + 设计预览）

> Vue 3 + Vite 工程骨架（v3）+ 静态设计预览（`preview.html`）。
> 旧前端为唯一真相源，迁移按 L0→L5 分层推进；`/api/*` 契约保持稳定（L1.5 适配器增强只改后端内部实现，无接口变更）。
> 当前进度：**L0 装配 + L1 数据层（含收口验收）+ L1.5 适配器增强（火山 3 项 + 图片能力矩阵复核 + 联网搜索三态）+ L2 反馈层 + L3 路由权限 + L4 逐页接线（16/16 页面全部接真实接口）+ L5 收尾（`<router-view>` + 加载下沉）+ 聊天页渲染增强（Markdown / 思考与工具分离 / 文件路径跳转 / 会话重命名 / 滚动体验）+ L8 聊天页能力补齐（会话搜索 / 余额 / 导出 / 文件树 / Git / 终端 / 改动回滚 / 任务看板隔离）+ L9 深度补齐（定时任务立即执行 / 文件面板切目录 / Git 完整客户端 / xterm 真终端）+ L10 运行状态栏（状态耗时 / 补充需求 / 任务与改动常驻入口）+ L11 消息操作区三件套（复制 / 重新生成 / 回滚本轮改动）+ 上下文统计口径修正 + L12 多智能体并行流（会话分片状态 / 头像同步 / 背景图修复 / Agent 身份还原）+ L13 聊天页输入区补齐与遗留标记纠正（历史文件引用 / 粘贴任意文件 / 运行中禁输 / 任务清单解析修复 / 聊天页 Agent 切换器 / 多任务并行看板 / 遗留标记只忽略不中断）+ L14 回滚入口可见性修复（回滚按钮常驻可见 / 状态栏「改动 · N 轮」/ 快照按轮次标注提问 / 流后延时补匹配）+ L15 回滚全景修复（**跨盘/项目根外文件也能建快照** / 快照列表回填该轮提问 / 状态栏文案回归「改动」）+ L16 聊天页 Provider 切换与上下文模式（模型弹层补齐旧版两项缺失能力 / compact 上下文口径联动）+ L16 收尾：模型弹层视觉重构（统一 34px 控件高 / 自绘下拉箭头 / 整宽模型单选行 / 上下文双选卡片）+ L17 侧栏折叠修复与「更多」菜单收口（折叠态统一排版 / 折叠状态持久化 / 二级页面统一从「设置」进入）+ L18 侧栏「新建会话」按钮重做（改 `<button>` 恢复手型光标与键盘可达 / 1px 描边取代淡紫底 / 上下间距对齐）+ **P0 地基（抽屉拖宽 F16 + 未配置 Provider 引导页 F15）** + **P1 聊天页体验增强（工具结果语义化 F13 / 任务优先级与依赖 F12 / 图片灯箱 F14 / 消息列表窗口化 F08）** + **P2 管理页补全（头像上传 F07+F17 / 工具选择真实数据源 F18）** + **P3 代码工作区底座（代码编辑器 F11 / 差异查看 F09 / 逐块逐行撤销 F10）** + **P4 多标签工作台（多标签 F02 / 分屏 F03 / 内嵌网页 F04）** + **P5 P2P 协作（点对点聊天 F01 / 文件与图片传输 / Agent 远程调用）** + **P6 子Agent面板（读 `sub_agent_log` 表 + 3s 轮询 + 委托调用分块，新增只读 `GET /api/sub-agents`）** + **L19 登录页与个人信息（去掉隐形登录，恢复显式登录 / 路由守卫改造 / 侧栏账号菜单 / 修改密码闭环）** + **L20 两处交互修复（思考区内部滚动跟随 / 智能体工具自由全选·组选·单选）** + **P7 版本与更新（F06：独立页 `/about` + 只读 `GET /api/system/version`；桌面端复用 `electronAPI.update`，浏览器降级为只读卡片）** 全部完成（详见文末「七、工程装配与迁移进度」「十三、P1」「十四、P2」「十五、P3」「十六、P4」「十七、P5」「十八、P6」「十九、L19」「二十、L20」「二十一、P7」）。

## 一、怎么看效果

### A. 跑起来（真实 Vue 应用，推荐）

```bash
npm install     # 首次
npm run dev     # 开发服务器 → http://localhost:5173/
```

| 命令 | 作用 |
|------|------|
| `npm run dev` | 开发服务器（HMR） |
| `npm run build` | 生产构建到 `dist/`（资源为相对路径，Electron `file://` 可直接加载） |
| `npm run preview` | 本地预览构建产物 |
| `npm run typecheck` | 类型检查 |
| `npm run check:api` | API 契约检查：前端 api 调用 ⟷ 后端 Controller 映射（需 `CC_BACKEND_SRC`） |

> ⚠️ `typecheck` 必须带 `-p tsconfig.app.json`（脚本里已带）。仓库根 `tsconfig.json` 是 solution-style
> （`files: []` + references），直接跑 `vue-tsc --noEmit` 会 0.8 秒「空跑」、不检查任何文件却返回成功。

### B. 看静态设计预览（零依赖）

双击 `preview.html`（浏览器直接打开，不需要 `npm install`）。

顶部黑色条是**预览控制条**（不属于产品界面）：

| 控件 | 作用 |
|------|------|
| **聊天 / 智能体管理 / 技能管理 / MCP 服务器 / 定时任务 / 踩坑经验 / 数据库连接 / 文件管理 / 设备互联 / 模型配置 / 运行日志 / 数据管理 / 用户管理 / 菜单权限 / 外观设置 / 设置 / 个人信息 / 登录页** | 切换页面（页面级切换；「登录页」会隐藏外壳整屏显示） |
| 对话中 / 生成中 / 工具审批 / 空状态 / 未配置模型 | 切换聊天页状态（仅「聊天」页可用） |
| 工作区 / 抽屉 | 切换右侧面板形态：多标签工作台 ↔ 传统单抽屉（P4） |
| 「更多」面板 | 打开侧栏底部的二级菜单面板 |
| 侧栏折叠 | 演示折叠态 |
| 暗色 / 亮色 | 切换主题 |

> 侧栏点「智能体」/「技能」/「任务」/「设置」或「更多」里的「踩坑经验」「数据库连接」「文件管理」「设备互联」「模型配置（Provider）」「运行日志」「数据管理」「用户管理」「菜单权限」「外观设置」也会自动切到对应页面（已做演示联动）。
> 「任务」直接落到定时任务页（后端无独立 `/api/tasks` 接口，任务面板在聊天页内）；「设置」是各管理页的聚合导航页。

页面内可交互：

- 点「▸ 思考 12s · 3 个工具调用 · 2 个文件改动」→ 展开过程
- 点工具卡片（`file_writer`）→ 展开参数
- 点「任务清单」→ 展开/收起
- 点头部「文件 / Git / 终端」→ 右侧抽屉滑出（可切 tab、ESC 关闭）
- 点「更多」→ 二级菜单面板分组展示

## 二、目录结构

```
codecraft/
├── index.html                  # Vite 构建入口（<div id="app"> + /src/main.ts）
├── preview.html                # 静态设计预览（聊天 4 状态 + 15 个管理页 + 各页抽屉，零依赖）
├── package.json                # 依赖与脚本（vue 3.5 / vue-router 5 / vite 8）
├── vite.config.ts              # base './'（Electron file://）+ @ 别名 + webview 自定义元素
├── tsconfig.json               # solution-style（references → app / node）
├── tsconfig.app.json           # 应用侧 TS 配置（@/* → src/*，vite/client 类型）
├── tsconfig.node.json          # 构建脚本侧 TS 配置（仅 vite.config.ts）
├── .gitignore                  # node_modules / dist / 本地环境文件
├── assets/
│   ├── tokens.css              # 设计 token（颜色/圆角/间距/字号，亮+暗）
│   └── app.css                 # 组件样式（cc- 前缀 + BEM）
│                               # ↑ 两份 CSS 由 src/main.ts 直接 import：
│                               #   静态预览与 Vue 应用共用唯一真相源，改一处两边同时生效
├── dist/                       # 构建产物（npm run build 生成，已 gitignore）
├── scripts/                    
│   └── check-api-contract.cjs  # API 契约检查（前端 api 调用 ⟷ 后端 Controller 映射）
└── src/                        # 可直接拷进项目的 Vue 源码
    ├── main.ts                 # 应用引导（挂载前 await router.isReady()，避免首帧闪占位）
    ├── env.d.ts                # vite/client 类型引用（刻意不写 *.vue 通配声明）
    ├── App.vue                 # 入口示例（含 DEMO 数据 + 数据接缝注释）
    ├── menu.config.ts          # 菜单配置（一级导航 + 设置页分组 + 会话分组工具函数）
    ├── router/index.ts         # 路由骨架（菜单生成 + adminOnly 守卫 + document.title 同步）
    ├── utils/
    │   ├── http-client.ts      # 请求封装（显式登录 performLogin / 401 触发未授权回调 / token 管理 / 超时归一）
    │   ├── session.ts          # 身份会话（GET /user/current → user store；clearSession 供退出登录与 401 共用）
    │   ├── md5.ts              # RFC1321 MD5（登录二次加密，自带 UTF-8/代理对处理）
    │   ├── sse-client.ts       # SSE 流式解析（聊天增量消息）
    │   ├── tokenCalculator.ts  # token 估算（上下文用量显示）
    │   ├── provider-capability.ts # Provider 能力推导（模板+默认模型+扩展配置+Base URL → 图片三态/联网三态/余额）
    │   ├── log-line.ts         # 日志行解析（时间戳/级别/正文 + 续行判定 + 级别统计）
    │   ├── menu-permission.ts   # 菜单类型元数据 / 分组排序 / 类型色调
    │   ├── data-transfer.ts    # 数据类别定义 / 备份预览解析 / 备份文件名
    │   ├── toast.ts            # 全局轻提示（函数式单例 / 去重计数 / loading 常驻）
    │   ├── confirm.ts          # 全局确认框（Promise 化 / confirmDelete / 调用串行排队）
    │   ├── character-profile.ts # 角色性格 JSON 解析/序列化（9 字段对齐后端 CharacterPromptUtil）
    │   ├── markdown.ts         # Markdown 渲染（markdown-it + hljs 按需语言；完整版/流式轻量版；filelist·cmd·diff 自定义块）
    │   ├── prompt.ts           # 全局输入弹窗（Promise 化，会话重命名等场景）
    │   ├── chat-mapper.ts      # 聊天数据映射（历史消息→消息模型 / task_manager→任务清单 / ask_user→审批卡片）
    │   ├── avatar.ts             # 头像判定（isImageAvatar 与后端一致 / avatarFallback 首字兜底）
    │   ├── image-compress.ts     # 图片上传前压缩（阈值 1.5MB 或长边 2048px 才压；失败回退原图）
    │   ├── tool-catalog.ts       # 工具目录（注册表→分组选项 / parseToolNames / 内置真实工具快照）
    │   ├── language-detect.ts    # 文件→hljs 语言 / 语言展示名 / 大文件阈值（2MB·2 万行）/ 体积格式化
    │   ├── diff-hunk.ts          # diff 行模型（unified 解析 / 前端 LCS / hunk 与单行 patch / 双栏拆分）
    │   ├── p2p-chat.ts           # P2P 消息归一化（历史/轮询→统一模型）+ 文件大小·图标·日期分组·去重
    │   ├── tool-render.ts          # P1 工具输出归一化（工具名+原文 → ToolRenderModel 12 种 kind / 折叠态摘要）
    │   ├── task-list.ts            # P1 任务清单模型（priority / dependsOn / group / 阻塞判定 / 统计口径）
    │   ├── virtual-list.ts         # P1 消息窗口化（useTailWindow：尾部窗口 + 顶部占位块）
    │   ├── lightbox.ts             # P1 灯箱单例（openLightbox / closeLightbox，配合 LightboxHost）
    │   ├── agent-event.ts          # P6 子Agent归一化（sub_agent_log → SubAgentRun / 委托调用 / 允许 fork 判定）
    │   ├── update.ts             # F06 自动更新封装（electronAPI.update 类型 / 平台安装文案 / 字节与时间格式化）
    │   └── web-url.ts            # 内嵌网页地址归一化（协议白名单 / host:port 判定）
    ├── types/
    │   ├── pinia-persist.d.ts  # pinia 持久化插件类型增强
    │   ├── router-meta.d.ts    # RouteMeta（title / adminOnly）类型增强
    │   └── markdown-it-container.d.ts    # markdown-it-container 最小类型声明（该包不发 d.ts）
    ├── layouts/
    │   └── AppShell.vue        # 外壳：侧栏 + 主区 + 主题/折叠状态
    ├── components/
    │   ├── AppIcons.vue        # 图标库（内联 symbol）
    │   ├── AppSidebar.vue      # 侧栏（导航 / 会话列表 / 用户；折叠态只留图标，二级页面走「设置」）
    │   ├── ChatComposer.vue    # 输入区（附件 / 输入框 / 模型 / 发送·停止）
    │   ├── ChatMessage.vue     # 消息（user 气泡 / assistant 全宽 + 过程 + 工具卡）
    │   ├── ChatApproval.vue    # 工具审批卡片
    │   ├── ChatTaskPanel.vue   # 任务清单面板
    │   ├── ChatModelPicker.vue # 模型与运行模式弹层（模型 / 思考模式 / 执行模式，应用后写回 Agent 运行时）
    │   ├── ToastHost.vue       # 全局 Toast 渲染器（Teleport 到 body，读 utils/toast 状态）
    │   ├── ConfirmHost.vue     # 全局确认弹窗（Teleport，焦点陷阱 / ESC / 确认中 loading）
    │   ├── PromptHost.vue     # 全局输入弹窗（Teleport，Enter 确认 / ESC 取消 / 自动聚焦全选 / 空值校验）
    │   ├── AgentCard.vue       # 智能体卡片（头像/标签/描述/元信息 + 「更多」下拉菜单：设为默认/删除）
    │   ├── AgentEditDrawer.vue # 智能体新建·编辑抽屉（头像上传 / Provider·模型联动 / 性格 / 分组工具选择 / 工作目录）
    │   ├── SkillRow.vue        # 技能行（名称/作用域/状态/触发词/置信度）
    │   ├── SkillEditDrawer.vue # 技能新建·编辑抽屉（触发词/指令/工具集）
    │   ├── McpRow.vue          # MCP 服务器行（类型/状态/权限/工具数/失败原因）
    │   ├── McpEditDrawer.vue   # MCP 新建·编辑抽屉（http/stdio 双形态表单）
    │   ├── ScheduleTaskRow.vue # 定时任务行（调度描述/进度/上次执行）
    │   ├── ScheduleTaskEditDrawer.vue # 定时任务新建·编辑抽屉（cron/一次性）
    │   ├── LessonRow.vue       # 踩坑经验行（错误码/类别/状态/来源/解法/验证计数）
    │   ├── LessonEditDrawer.vue # 踩坑经验详情·补全抽屉（事实区 + 根因/解法/参数）
    │   ├── DbConnectionRow.vue  # 数据库连接行（类型配色/连接串/密码状态/启用开关）
    │   ├── DbConnectionEditDrawer.vue # 连接新建·编辑抽屉（类型/主机/库名/密码/启用）
    │   ├── DbExecuteDrawer.vue  # 执行 SQL 抽屉（多语句 + 多结果块渲染）
    │   ├── FileRow.vue          # 文件行（类型图标/显示名/文件名·MIME·体积/引用数）
    │   ├── FileDetailDrawer.vue # 文件详情·重命名抽屉（预览区 + 事实区 + 显示名）
    │   ├── CloudFilesDrawer.vue # 云端文件抽屉（已登记/仅云端 + 清理）
    │   ├── P2pPeerRow.vue       # 设备行（首字母头像/在线点/地址 · peerId/连接时间/备注）
    │   ├── P2pPeerDrawer.vue    # 设备详情·授权抽屉（事实区 + 备注 + 双向 Agent 授权）
    │   ├── P2pConnectDrawer.vue # 连接设备抽屉（连接串粘贴 + 二维码图片解析）
    │   ├── P2pSelfDrawer.vue    # 我的设备抽屉（二维码 + 连接串复制 + 本机信息）
    │   ├── P2pChatDrawer.vue    # P5 P2P 会话抽屉（历史 + 2s 轮询 + 发文本/传文件/调 Agent + 清空）
    │   ├── P2pChatBubble.vue    # P5 单条消息气泡（文本/图片/文件/Agent 调用/Agent 回复/系统通知）
    │   ├── P2pAuthDrawer.vue    # P5 Agent 授权抽屉（双向授权，chips 即时生效）
    │   ├── ProviderRow.vue      # 模型配置行（平台标识/模板/能力徽章/凭证状态/启用开关）
    │   ├── ProviderEditDrawer.vue # Provider 新建·编辑抽屉（模型列表/扩展配置/能力预览/余额）
    │   ├── LogRow.vue           # 日志行（行号/时间戳/级别徽章/正文，续行保留缩进）
    │   ├── DataExportCard.vue   # 导出备份卡片（7 类勾选 + 条数 + 导出按钮）
    │   ├── DataImportCard.vue   # 导入备份卡片（拖放选择 + 备份解析预览 + 逐表导入结果）
    │   ├── DataResetDrawer.vue  # 重置确认抽屉（输入「重置」二次确认）
    │   ├── UserRow.vue              # 用户行（头像 / 昵称·@账号 / 角色标签 / 状态 / 创建时间）
    │   ├── UserEditDrawer.vue      # 用户新建·编辑抽屉（头像上传 / 用户名 / 密码 / 角色 / 启用 / 联系方式）
    │   ├── MenuRow.vue             # 菜单行（名称 / 路由 / 权限码 + 编辑·删除）
    │   ├── MenuEditDrawer.vue      # 菜单新建·编辑抽屉（类型 / 路径 / 权限码 / 可见 / 启用）
    │   ├── RoleEditDrawer.vue      # 角色新建·编辑抽屉（名称 / 编码 / 描述 / 启用）
    │   ├── DirectoryPicker.vue    # 目录选择器（盘符/面包屑/手输路径；走 /api/project/drives + /children）
    │   ├── ProjectFileTree.vue    # 聊天抽屉·文件（目录懒加载 + CodeEditor 编辑保存 + 路径条切目录；/project/children|read|write）
    │   ├── GitChangePanel.vue     # 聊天抽屉·Git 完整客户端（同步/暂存/储藏/分支/标签/提交；diff 用 DiffViewer；/api/git/*）
    │   ├── TerminalPanel.vue      # 聊天抽屉·终端（xterm.js 真终端，行编辑 + cd + 历史；/project/exec，按需加载）
    │   ├── ChangeReview.vue       # 改动回滚面板（按文件 / 按快照 / 全量 + 只读差异查看；/api/snapshots/*）
    │   ├── FilePickerDialog.vue   # 历史文件选择器（文件库多选 + 图片缩略图 + 已附加去重；/api/files）
    │   ├── ChatAgentPicker.vue    # 聊天页 Agent 切换器（头像 / 模型 / 默认标记 / 名下运行中会话数）
    │   ├── CodeEditor.vue         # 代码编辑器（高亮层 + 透明 textarea + 行号 + 当前行 + 查找 + Ctrl+S；大文件只读）
    │   ├── DiffViewer.vue         # 差异查看器（单栏/双栏 + hunk 折叠 + 撤销块/撤销行；patch 交父级提交）
    │   ├── WorkspacePanel.vue    # P4 工作区外壳（布局切换 / 收起 / 切回抽屉 / 快捷键 / 拖宽）
    │   ├── WorkspacePane.vue     # P4 单栏容器（标签栏 + 新建菜单 + 内容区 + 关标签二次确认）
    │   ├── WorkspaceTabs.vue     # P4 标签栏（图标 / 标题 / dirty 点 / 中键关闭 / 拖拽排序 / 跨栏移动）
    │   ├── WorkspaceTabView.vue  # P4 标签内容分发（tree/file/diff/git/term/web/image）
    │   ├── WorkspaceFileView.vue # P4 文件标签（读写 + 未保存上报）
    │   ├── WorkspaceDiffView.vue # P4 差异标签（只读，复用 DiffViewer；/api/git/diff）
    │   ├── SplitPane.vue         # P4 分屏容器（横/纵 + fr 比例 + 最小宽保护 + 双击复位）
    │   ├── WebFrame.vue          # P4 内嵌网页（地址栏 / 前进后退刷新 / iframe+webview 双通道 / 禁嵌降级）
    │   ├── ToolResultCard.vue    # P1 工具结果卡片（按 kind 渲染文件改动 / 命令 / 搜索 / 网页 / 数据库 / git 等结构）
    │   ├── ImageLightbox.vue     # P1 图片灯箱（缩放 1x–4x / 拖拽 / 多图切换 / 下载 / ESC）
    │   ├── LightboxHost.vue      # P1 灯箱宿主（全局单例挂载，配合 utils/lightbox.ts）
    │   ├── SubAgentPanel.vue     # P6 子Agent面板（fork 记录 + 委托调用两块，状态/耗时/文件徽章/失败原因）
    │   ├── AvatarUploader.vue    # 头像上传器（v-model 受控 / 客户端压缩 / 上传中禁保存 / hover 移除）
    │   └── VersionCard.vue       # F06 版本与更新卡片（版本矩阵 / 检查更新 / 进度下载 / 安装 / Release notes）
    └── views/
        ├── ChatView.vue        # 聊天页（头部 + 消息流 + 输入区 + 工具抽屉）
        ├── AgentListView.vue   # 智能体管理页（页面头 + 搜索筛选 + 卡片网格）
        ├── SkillListView.vue   # 技能管理页（页面头 + 统计行 + 搜索筛选 + 行式列表）
        ├── McpListView.vue     # MCP 服务器页（状态统计 + 搜索筛选 + 行式列表）
        ├── ScheduleTaskListView.vue # 定时任务页（状态统计 + 搜索筛选 + 行式列表）
        ├── LessonListView.vue  # 踩坑经验页（内联统计 + 类型/状态/零命中筛选 + 归并面板）
        ├── DbConnectionListView.vue # 数据库连接页（内联统计 + 类型/状态筛选 + 行式列表 + 执行 SQL）
        ├── FileListView.vue    # 文件管理页（内联统计 + 存储/状态筛选 + 行式列表 + 分页 + 云端视图）
        ├── P2pListView.vue     # 设备互联页（本机身份卡 + 在线筛选 + 设备列表 + 四抽屉含聊天）
        ├── ProviderListView.vue # 模型配置页（内联统计 + 启用筛选 + 行式列表 + 编辑/余额抽屉）
        ├── LogListView.vue     # 运行日志页（实时/搜索双模式 + 级别筛选 + 等宽日志面板 + 导出）
        ├── DataManageView.vue  # 数据管理页（导出/导入/危险三卡片 + 重置确认抽屉）
        ├── UserListView.vue        # 用户管理页（内联统计 + 搜索/角色/状态筛选 + 行式列表 + 分页 + 编辑抽屉）
        ├── MenuPermissionView.vue  # 菜单权限页（左菜单分组管理 + 右角色菜单勾选 + 菜单/角色双抽屉）
        ├── AppearanceView.vue      # 外观设置页（主题三态 + 背景图上传/不透明度/模糊 + 窗口透明度）
        ├── ProfileView.vue         # 个人信息页（头像上传 / 昵称·邮箱·手机号 / 修改密码含改后自动重登）
        ├── LoginView.vue           # 登录页（全屏独立布局，不套 AppShell；两次 MD5 + 随机码）
        ├── SettingsView.vue        # 设置页（MORE_GROUPS 全部入口的聚合导航，二级页面唯一入口）
        └── AboutView.vue           # F06 版本与更新页（版本矩阵 + 更新设置 + 更新策略说明）
```

## 三、接入项目步骤

1. **样式**：把 `assets/tokens.css`、`assets/app.css` 复制到项目 `src/styles/`，
   在 `main.ts` 里引入（顺序不能反，app.css 依赖 tokens.css）：
   ```ts
   import './styles/tokens.css'
   import './styles/app.css'
   ```
2. **源码**：把 `src/` 下文件按同名目录拷入项目（`layouts/`、`components/`、`views/`、`menu.config.ts`、`router/index.ts`）。
   若项目已有 router，可直接把 `buildRoutes()` 的生成逻辑合并进去。
3. **数据层**：`src/api/*`（25 个模块）与 `src/store/*` 已就位，页面数据由 `App.vue` 的 `PAGE_BINDINGS`
   注入（`props` + `on` + `load`）；各页在自己的 `onMounted` 调用 `props.load?.()` 触发首次拉取。
   若要换成别的数据源，改 `PAGE_BINDINGS` 对应分支即可，页面组件本身不用动。
4. **图标**：目前用内联 symbol（`AppIcons.vue`）。若项目统一用 `@ant-design/icons-vue`，
   可逐步替换；自定义线性图标建议保留。

## 四、数据接缝清单（组件 → 接口）

| 组件 | 入参 | 对应接口 / 事件 |
|------|------|-----------------|
| `AppSidebar` | `groups`（会话分组） | 会话列表接口，用 `groupConversationsByTime()` 归类；搜索框对已加载列表做**本地过滤** |
| `ChatView` | `conversation` | 会话详情（标题 / 模型 / 执行模式 / 统计 / 余额）；`stats.changedFiles` 来自 `/snapshots/session-changes` |
| `ChatView` | `messages` | 消息列表（role / content / process / tools） |
| `ChatView` | `tasks` | 当前会话的 `task_manager` 清单（`taskMap[会话id]`，历史回放自消息内容） |
| `ChatView` | `approval` | `permission_request` / `ask_user` 事件 |
| `ChatView` | `isStreaming` | SSE 连接状态（同时驱动运行状态栏的状态文字与耗时计时） |
| `ChatView` | `supplementState` | 补充需求发送状态：`idle / sending / done / error`（父级持有，`done` 时组件清空并收起输入条） |
| `ChatView` | `emit('supplement')` | 「💡 补充需求」→ `supplementRequest()` → `POST /deepseek/supplement`（入队 `SupplementStore`，下一轮迭代消费） |
| `ChatView` | `emit('open-changes')` | 状态栏「改动 +A/-D」→ 打开 `ChangeReview`（原入口在消息流顶部概览条，现已常驻） |
| `ChatView` | `subAgents` / `delegateCalls` | `GET /api/sub-agents?conversationId=` 读 `sub_agent_log`（fork 记录，含 status/工具/技能/文件变更/失败原因）+ SSE `delegate_progress`（跨智能体委托心跳）；有 running 时 3s 轮询，全部终态自动停 |
| `ChatView` | `emit('open-subagent-session')` | 面板里「前往会话」→ `onOpenSubagentSession()`：目标会话可能属于另一个 Agent，先刷新列表再定位，仍找不到则明确提示 |
| `ChatView` | `emit('rollback')` | AI 消息操作区「回滚改动」→ `preview` 列文件 + 二次确认 → `POST /snapshots/rollback`（本轮快照由 `turnId` 匹配） |
| `ChatView` | `emit('regenerate')` | AI 消息操作区「重新生成」→ `DELETE /conversation/{id}/messages?turnId=` 真删该轮 → 原样重发 |
| `ChatView` | `#drawer` 插槽 | 由父级注入 `ProjectFileTree` / `GitChangePanel` / `TerminalPanel` |
| `ChatComposer` | `attachments` / `contextTokens` / `balance` | 上传接口 / 会话统计（**口径 = 正文 + 思考 + 工具返回**，前端字符估算，非后端 usage）/ `GET /llm-providers/{code}/balance` |
| `ProjectFileTree` | `root`（Agent workDir，默认值） | `GET /project/children`（懒加载）、`GET /project/read`、`POST /project/write`；点路径条可切到任意目录（`DirectoryPicker`），不改变 Agent 配置 |
| `GitChangePanel` | `root` | `/git/status`、`/git/diff`、`/git/branches`、`/git/tags`、`/git/stash`，写操作走 `add` / `unstage` / `restore` / `commit` / `pull` / `push` / `fetch` / `stash*` / `branch/*` / `merge` / `tag*` |
| `TerminalPanel` | `root` | `POST /project/exec`（**timeout 单位毫秒**，不传默认 30s）；xterm 只做渲染与行编辑，`cd` 在前端本地切换 |
| `ChangeReview` | `open` / `sessionId` | `/snapshots/session-changes`、`/snapshots/list`、`/snapshots/preview`、`/snapshots/rollback[-file|-session]` |
| `AgentListView` | `agents` | `GET /agent-configs/list`（含 isDefault / isBuiltin / toolsCount / workDir / providerCode / characterProfile） |
| `AgentListView` | `providers` | `GET /api/llm-providers` → 模型的联动数据源（每项含 `models` = `modelList` 解析结果） |
| `AgentEditDrawer` | `agent`（null=新建） | 传入已存在 Agent 即为编辑；`save` 事件回传表单数据 |
| `AgentEditDrawer` | `providers` / `models` / `toolGroups` | Provider 选项（模型联动）/ 兜底模型 / `GET /api/tools/registry` 分组工具（为空时回退 `utils/tool-catalog` 的内置快照） |
| `AgentEditDrawer` | `avatar` 字段 | `POST /api/avatar`（multipart）→ 拿到 `/api/avatar/file/xxx`，随 `save` 提交给 `PUT /agent-configs/{id}` |
| `UserEditDrawer` | `avatar` 字段 | `POST /api/avatar` → 随 `PUT /api/user/update` 提交；改的是自己时再 `refreshProfile()` 同步侧栏 |
| `SkillListView` | `skills` | `GET /skill/list`（含 confidence / usageCount / triggerWords / agentConfigId） |
| `SkillRow` | `skill` | 单条技能；`confidence >= 0.1` 为已激活，`< 0.1` 视为已隐藏（后端不再注入） |
| `SkillEditDrawer` | `skill`（null=新建） / `agents` / `toolGroups` | 技能编辑表单；`save` 回传 `triggerWords`（数组）与 `tools`（工具名数组） |
| `McpListView` | `servers` | `GET /mcp/list`（含 type / url / command / permissionLevel / enabled / status / toolCount） |
| `McpRow` | `server` | 单行服务器；`status` 为 `CONNECTED` / `DISABLED` / `FAILED`，失败时展示 `errorMessage` |
| `McpEditDrawer` | `server`（null=新建） | http / stdio 双形态表单；`headers` 留空表示保持原值（安全不回显） |
| `ScheduleTaskListView` | `tasks` | `GET /schedule-task/list`（含 cronExpression / executeTime / executeCount / maxExecuteCount / status） |
| `ScheduleTaskRow` | `task` | 单行任务；`status === 'ENABLED'` 为启用，否则整行降饱和；cron 会翻译成中文描述 |
| `ScheduleTaskEditDrawer` | `task`（null=新建） / `agents` | cron 与一次性二选一，保存时只提交对应字段（另一个为空） |
| `LessonListView` | `lessons` / `clusters` | `GET /lessons`（status/type/toolName/errorCode/zeroHit 筛选）+ `GET /lessons/clusters` |
| `LessonRow` | `lesson` | 单条经验；`status` 0=草稿 1=有效 2=隐藏，`type` FAILURE/DETOUR，无可用解法时显示「待补全」 |
| `LessonEditDrawer` | `lesson` / `save` | `PUT /lessons/{id}` 只提交非空字段；`paramsJson` 需为 JSON 数组 |
| `DbConnectionListView` | `connections` / `rowTestStates` / `editTestState` / `executeResult` | `GET /api/db-connections`；测试结果按连接 id 回填行内反馈 |
| `DbConnectionRow` | `db` / `testState` | 单行连接；`enabled` 1=启用 0=停用，`hasPassword` 为脱敏标记（不回显明文） |
| `DbConnectionEditDrawer` | `connection`（null=新建） | `POST /api/db-connections` / `PUT /api/db-connections/{id}`；`passwordPlain` 留空=不改密码 |
| `DbExecuteDrawer` | `connection` / `result` / `error` / `running` | `POST /api/db-connections/{id}/execute { sql, maxRows }`，返回 `SqlExecuteResult` 多结果块 |
| `FileListView` | `files` / `total` / `totalSize` / `page` / `pageSize` / `keyword` / `cloud` | `GET /api/files/page`（page/pageSize/keyword/status/storageType）+ `GET /api/files/cloud` |
| `FileRow` | `file` | 单条文件；`storageType` cloud/local，`status` active/deleted/orphan，`source` upload/paste/tool_capture |
| `FileDetailDrawer` | `file` / `previewSrc` | `PATCH /api/files/{id} { displayName }`；预览需 `fetch /api/files/{id}/preview` 带鉴权头取 blob |
| `CloudFilesDrawer` | `cloud` / `loading` | `DELETE /api/files/cloud/{fileId}` 清理「仅云端」孤儿；`truncated` 表示超出拉取上限 |
| `P2pListView` | `selfName` / `status` / `address` / `signaling` / `peers` / `grantedCounts` | `GET /api/p2p/status`、`/address`、`/peers`、`POST /api/p2p/qrcode` |
| `P2pPeerRow` | `peer` / `grantedCount` | 单个节点；`displayName` 由后端按「备注 > Agent 名 > 握手名 > 未知」计算 |
| `P2pPeerDrawer` | `peer` / `sentAgents` / `receivedAgents` / `availableAgents` | `PUT /peer/{peerId}/remark` + `POST /agent-auth/grant|cancel/{peerId}`（body `{ agentConfigIds:number[] }`） |
| `P2pConnectDrawer` | `connecting` / `parsedConnectionString` | `POST /api/p2p/connect`（10 秒超时）、`POST /api/p2p/qrcode/parse` |
| `P2pSelfDrawer` | `name` / `status` / `address` / `signaling` | `POST /api/p2p/name`、`POST /api/p2p/qrcode`（qrCodeBase64 兼容带/不带 data: 前缀） |
| `P2pChatDrawer` | `open` / `peer` / `myName` | **自包含**：`GET /messages/{peer}/history?offset&limit`（历史，ASC 最早的 1000 条）· `GET /messages/{peer}`（2 秒轮询增量）· `POST /send/{peer}` · `POST /agent/invoke/{peer}` · `POST /send-file/{peer}` · `POST /file/{peer}/{tid}/open-dir` · `DELETE /messages/{peer}` · `GET /agent-auth/received/{peer}` |
| `P2pChatBubble` | `message` / `peerId` / `peerInitial` / `myInitial` | 六类渲染；图片走 `GET /file/{peer}/{tid}/thumbnail`（失败降级原图→占位），点击开全局灯箱 |
| `P2pAuthDrawer` | `open` / `peer` | 双向授权：`GET /agent-auth/sent`、`GET /agent-auth/received/{peer}` + `POST /agent-auth/grant`、`POST /agent-auth/cancel/{peer}`（chips 即时生效，失败回滚） |
| `ProviderListView` | `providers` / `balance` / `balanceLoading` / `balanceError` | `GET /api/llm-providers`（管理员）；`emit save/delete/toggle` 对应 POST / PUT / DELETE |
| `ProviderRow` | `provider` | 单条 Provider；`apiKey` 与 `extraConfig` 密钥为 `****` 掩码（表示已配置），`is_default` 只读展示 |
| `ProviderEditDrawer` | `provider`（null=新建） / `balance` | `POST` / `PUT /api/llm-providers/{id}`；`modelList` 需 JSON 数组，`extraConfig` 需合法 JSON |
| `provider-capability` | `requestTemplate` / `code` / `extraConfig` / `defaultModel` / `baseUrl` | 推导图片识别 / 联网搜索 / 余额查询三项能力。图片识别与联网搜索都是**三态**（`on`/`warn`/`off`）：图片识别对齐 `FileVisionService.supportsVisionModel`；联网搜索的 `on` 要求平台官方接口就绪（火山 `searchApiKey` / 千问 `enableSearch` / MiniMax `codingPlanKey` / DeepSeek 免凭证但 Base URL 必须是 `api.deepseek.com`），DeepSeek 走第三方中转时降为 `warn`（只剩通用抓取）；余额对齐 `ProviderBalanceService` |
| `LogListView` | `lines` / `mode` / `total` / `availableDates` / `date` / `hasMore` | `GET /api/logs/tail`（实时，只读今天）· `GET /api/logs/search`（关键词/级别/日期）· `GET /api/logs/dates` |
| `LogRow` | `line` / `index` | 单行日志；tail 无后端行号时用展示序号，search 优先用后端 `lineNumber` 与 `fileName` |
| `log-line` | `text` | 解析时间戳 / 级别 / 正文与续行；级别判定与后端 `LogService` 的 `LEVEL_KEYWORDS` 一致（级别后必须是空白） |
| `DataManageView` | `stats` / `exporting` / `importing` / `importResult` / `resetting` / `lastExportAt` | `GET /api/system/data-stats`；`emit export / import / reset / refresh` 对应其余三个接口 |
| `DataExportCard` | `stats` / `exporting` | 7 类勾选（默认全选）；`emit('export', types)` → `GET /api/system/export-data?types=...` |
| `DataImportCard` | `importing` / `result` | `POST /api/system/import-data`（multipart，字段名 `file`）；前端先用 `parseBackupJson` 校验 app 标识并预览 |
| `DataResetDrawer` | `open` / `resetting` | `POST /api/system/reset-data`；必须手输「重置」才可确认，执行中禁止关闭 |
| `data-transfer` | `text` / `stats` | 7 类别定义（10 张表）、备份结构校验、类别行数汇总（= 各表之和），与后端 `DataTransferService` 对齐 |
| `UserListView` | `users` / `total` / `page` / `pageSize` / `selfId` | `GET /api/user/list?page&pageSize&search`（管理员；`password` 已脱敏）；`emit save/delete/toggle/refresh` 对应 POST / PUT / DELETE |
| `UserRow` | `user` / `selfId` | 单条用户；`status` 1=启用 0=禁用，`role` 为 `sys_role.code`；与 `selfId` 相同的那行禁用「删除 / 禁用」 |
| `UserEditDrawer` | `user`（null=新建） | `POST /api/user/create` / `PUT /api/user/update`；密码提交**明文**（后端 MD5），编辑时留空=不修改 |
| `MenuPermissionView` | `menus` / `roles` / `roleMenus` | `GET /api/menu/tree` + `GET /api/role/list` + `GET /api/role/menus/{roleId}`；`emit('save-assign')` → `POST /api/role/assign-menus`（全量覆盖） |
| `MenuRow` | `menu` | 单条菜单；`menuType` 六类（LINK/AI/TOOL/SYSTEM/MANAGE/SETTING），`visible` 0 时权限列显示「隐藏」 |
| `MenuEditDrawer` | `menu`（null=新建） | `POST /api/menu/create` / `PUT /api/menu/update`；`parentId`、`sortOrder` 由父级转数字 |
| `RoleEditDrawer` | `role`（null=新建） | `POST /api/role/create` / `PUT /api/role/update`；`code` 唯一且创建后不可改（用户表 role 存的就是它） |
| `menu-permission` | `menus` / `type` | 类型元数据与分组排序（对话 → AI → 工具 → 系统 → 管理 → 设置），与后端 `menu_type` 注释一致 |
| `AppearanceView` | `theme` / `bgImageUrl` / `bgOpacity` / `bgBlur` / `windowOpacity` / `uploading` | ✅ 已接：`POST /api/appearance/background`（multipart，字段名 `file`）→ `/api/appearance/file/<uuid>.<ext>`；主题/透明度/模糊存 localStorage（`settingsStore`），由 `AppShell` 落到 `<html data-theme>` 与 `.cc-app-bg` 背景层 |
| `SettingsView` | — | 纯导航页：入口取自 `menu.config.ts` 的 `MORE_GROUPS`，`emit('navigate', key)`，无后端接口 |

事件（`App.vue` 的 `PAGE_BINDINGS` 已全部接线，下表为「事件 → 真实接口」对照）：

```
send(text)         -> POST /conversation/{id}/send + SSE
stop()             -> 取消流 / 停止接口
approve/approve-all/reject -> 审批回执
attach()           -> 文件选择 + 上传
optimize()         -> 提示词优化
select-model()     -> 模型弹层（模型 / Provider / 思考模式 / 上下文模式）
paste(ev)          -> 截图粘贴上传
remove-attachment  -> 移除附件
copy / regenerate  -> 复制 / 重新生成
log-refresh        -> GET /api/logs/tail?maxLines=500&skip=0
log-search         -> GET /api/logs/search?keyword&level&date&page&size
log-load-more      -> GET /api/logs/tail?maxLines=500&skip={已加载行数}
log-export-file    -> GET /api/logs/download?date（后端拼接当日全部文件）
data-refresh       -> GET /api/system/data-stats
data-export        -> GET /api/system/export-data?types=...（附件下载）
data-import        -> POST /api/system/import-data（multipart，字段名 file）
data-reset         -> POST /api/system/reset-data（管理员，不可撤销）
user-search        -> GET /api/user/list?page=1&pageSize=10&search=...（角色/状态为本地筛选）
user-page-change   -> GET /api/user/list?page={page}&pageSize={pageSize}
user-save          -> POST /api/user/create（密码明文）/ PUT /api/user/update（密码留空=不改）
user-delete/toggle -> DELETE /api/user/delete/{id}（拒删自己）/ PUT /api/user/update（只提交 id+status）
menu-save/delete   -> POST /api/menu/create | PUT /api/menu/update | DELETE /api/menu/delete/{id}
role-save          -> POST /api/role/create | PUT /api/role/update
assign-menus       -> POST /api/role/assign-menus { roleId, menuIds }（全量覆盖）
look-upload-bg     -> POST /api/appearance/background（multipart，字段名 file）
look-reset         -> 恢复默认外观（背景图空 / 不透明度 0.5 / 模糊 0 / 窗口 1）
```

## 五、设计规范（后续页面请沿用）

**颜色**：只用 `tokens.css` 里的变量，禁止写死色值。
常用：`--bg` `--card` `--card-2` `--border` `--text-1..4` `--accent` `--accent-lt`。

**圆角**：`--r-sm(8)` 按钮/输入框 · `--r-md(10)` 卡片 · `--r-lg(14)` 输入容器/弹层 · `--r-xl(18)` 大图标。

**间距**：4 的倍数 —— `--sp-1(4)` `--sp-2(8)` `--sp-3(12)` `--sp-4(16)` `--sp-5(20)` `--sp-6(24)` `--sp-8(32)`。

**字号**：`--fs-xs(11.5)` 极弱信息 · `--fs-sm(12.5)` 辅助 · `--fs-base(14)` 正文 · `--fs-md(14.5)` 聊天正文 · `--fs-lg(16)` 区块标题 · `--fs-xl(20)` 空状态标题。

**硬性规则（避免视觉噪音）**：

1. 列表项 hover **只允许背景/颜色变化**，禁止 border / shadow / transform；
2. 阴影只用于浮层与弹层，常规容器一律 1px 描边；
3. 图标统一 16px、`stroke: currentColor`，选中态才用 accent；
4. 状态信息（token / 余额 / 改动行数）一律 11.5~12.5px 弱色，不占控件位；
5. 长内容（思考 / 工具调用 / 任务）默认折叠，一行摘要 + 可展开。

## 六、后续加页面怎么加

1. 在 `src/menu.config.ts` 里给 `PRIMARY_NAV`（≤5 项）或 `MORE_GROUPS` 增加一项；
2. 在 `src/router/index.ts` 的 `ROUTE_COMPONENTS` 里登记新页面组件路径；
3. 新页面沿用 `AppShell` 提供的侧栏，只写主区内容，样式复用 `assets/app.css` 的原子类；
4. 页面顶部建议统一 `<header class="cc-header">`，列表页用 `--card` 容器 + `--border` 描边（不要每项卡片化）。

> 当前已完成：
> **① 菜单布局**（一级 5 项 + 「更多」4 组二级页面）
> **② 聊天页面**（对话中 / 生成中 / 工具审批 / 空状态）
> **③ 智能体管理页**（页面头 + 搜索筛选 + 卡片网格 + 新建/编辑抽屉）
> **④ 技能管理页**（页面头 + 内联统计 + 搜索/状态/作用域筛选 + 行式列表 + 编辑抽屉）
> **⑤ MCP 服务器页**（内联统计 + 状态筛选 + 行式列表 + http/stdio 双形态抽屉）
> **⑥ 定时任务页**（内联统计 + 状态/Agent 筛选 + 行式列表 + cron/一次性抽屉）
> **⑦ 踩坑经验页**（内联统计 + 类型/状态/零命中筛选 + 行式列表 + 详情·补全抽屉 + 归并面板）
> **⑧ 数据库连接页**（内联统计 + 类型/状态筛选 + 行式列表 + 编辑抽屉 + 执行 SQL 多结果块抽屉）
> **⑨ 文件管理页**（内联统计 + 存储/状态筛选 + 行式列表 + 分页 + 详情·重命名抽屉 + 云端文件清理）
> **⑩ 设备互联页**（本机身份卡 + 在线筛选 + 设备列表 + 设备详情·授权 / 连接设备 / 我的二维码三抽屉）
> **⑪ 模型配置页**（内联统计 + 启用筛选 + Provider 行列表 + 编辑抽屉 + 余额/套餐额度查询，能力徽章按模板与扩展配置推导）
> **⑫ 运行日志页**（实时 tail / 历史检索双模式 + 级别即时过滤 + 等宽日志面板（续行保留栈帧缩进）+ 自动滚动 / 加载更早 / 行点击复制 + 导出当前视图与完整文件）
> **⑬ 数据管理页**（导出备份（7 类勾选 + 真实 JSON 下载）/ 导入备份（拖放解析 + 预览 + 逐表结果）/ 危险操作（输入「重置」二次确认））
> **⑭ 用户管理页**（内联统计 + 搜索 / 角色 / 状态筛选 + 行式列表（头像·角色标签·状态点·创建时间）+ 分页 + 新建·编辑抽屉；本人禁止删除与禁用）
> **⑮ 菜单权限页**（双栏：左「菜单项」按 6 类分组维护 + 右「角色权限」角色 chips + 菜单勾选 + 全量保存；菜单 / 角色双抽屉）
> **⑯ 外观设置页**（主题三态 + 背景图上传/移除 + 不透明度 0~100% + 模糊 0~20px + 窗口整体透明度，全部即时预览）
> **⑰ 设置页**（「更多」面板全部入口的聚合导航，按业务域分组，纯前端导航不虚构接口）
>
> **全部计划页面已完成**（一级导航 5 项 + 「更多」4 组 12 项）。`menu.config.ts` 里与一级导航重复的两项（`/agents/config`、`/skills/manage`）已从「更多」面板移除，并在路由层保留 redirect 兜底；「任务」直接指向 `/tasks/schedule`（后端无独立任务接口）。

## 七、工程装配与迁移进度

### L0 装配（已完成）

目标：让骨架从「只能看静态页面」变成「可运行、可类型检查、可构建」的工程基线。

新增/调整文件：

| 文件 | 作用 |
|------|------|
| `package.json` | 依赖与脚本；`vue ^3.5` / `vue-router ^5.4` / `vite ^8` / `vue-tsc ^3` |
| `vite.config.ts` | `base: './'`（Electron `file://`）、`@` 别名（对齐旧项目 `@/api/...` 写法）、`webview` 自定义元素、`server.host: true` |
| `tsconfig.json` / `tsconfig.app.json` / `tsconfig.node.json` | 与旧项目同构；app 侧 `@/*` → `src/*` |
| `index.html` | 新的 Vite 入口（`#app` + `/src/main.ts`） |
| `preview.html` | 原静态设计预览页（由 `index.html` 改名，内容未动，仍零依赖可开） |
| `src/main.ts` | 应用引导：`createApp` → `use(router)` → `await router.isReady()` → `mount('#app')` |
| `src/env.d.ts` | 仅引 `vite/client`；**刻意不写 `declare module '*.vue'`**，否则所有组件 props 退化为 `any`，typecheck 形同虚设 |
| `src/App.vue` | `activeKey` 由 `ref('/chat')` 改为与 `route.path` 双向绑定的 `computed` |
| `.gitignore` | `node_modules/` `dist/` 本地环境文件 |

**路由打通**：原骨架用 `ref('/chat')` + `v-if` 切页面，地址栏完全不参与 —— 刷新回首页、无法用 URL 直达
`/logs`、`/users`，`router/index.ts` 的路由表形同虚设。改为 `computed` 双向绑定后，
`activeKey.value = '/logs'` 等价于 `router.push('/logs')`，App.vue 内原有的
`onSelectMenu` / `onSettingsNavigate` / `onAgentSelect` 赋值写法**无需改动**，
同时获得浏览器前进/后退与 `#/logs` 直达能力。

### 装配过程中修掉的 3 个真问题

1. **`AgentEditDrawer.vue` 模板直接崩溃**（`npm run build` 报 `Unterminated string constant`）
   原文 `{{ '{{tools}}' }}`：Vue 插值解析遇到内层 `}}` 提前闭合，导致整个 SFC 编译失败。
   改为 `v-pre` 元素内写字面量 `{{tools}}`。这类错误 **HTML 静态检查、`vue-tsc` 都查不出**，只有真实编译才报。
2. **`typecheck` 是空跑**：`vue-tsc --noEmit` 在 solution-style `tsconfig.json` 下一律 0.8 秒通过、不检查任何文件。
   改为 `vue-tsc --noEmit -p tsconfig.app.json`（实测 6.2 秒），并用一个故意报错的探针文件验证**确能抓错**后才定稿。
3. **dev server 只监听 IPv6**：Vite 8 在 Windows 上默认只绑 `[::1]:5173`，`127.0.0.1` 与 Electron/局域网调试全部连不上。
   已在 `server.host: true` 固定。

### 验证基线（可复现）

```
npm run typecheck   → 0 error（55 个 .vue + 6 个 .ts 全量检查，非空跑）
npm run build       → 138 modules transformed，built in ~600ms
dist/index.html     → 资源为 ./assets/... 相对路径（Electron file:// 可直接加载）
产物              → JS 296.2 kB / CSS 77.4 kB（gzip 后 92.5 kB / 11.9 kB）
产物冒烟（16 项）   → HTML 挂载点、相对路径、16 个页面组件类名、图标库、15 个页面样式节、
                      暗色主题覆盖、900px 断点、无模板编译报错痕迹，全部 PASS
dev server          → /、/src/main.ts、/src/App.vue、AppShell.vue、ChatView.vue、/assets/app.css 均 200
```

### 已知项（留给后续层处理）

- ~~`INEFFECTIVE_DYNAMIC_IMPORT` 警告 ×16~~ → **L5 已消除（现为 0 条警告）**：`App.vue` 不再静态 import 任何
  `views/*`（只留 `import type`），页面切换改由 `<router-view>` 承担，路由懒加载真正生效（构建产物按页分包）。
- `App.vue` 约 3900 行：页面数据与事件仍集中在 `App.vue`（通过 `PAGE_BINDINGS` 注入各页），
  「加载时机」已下沉为各 View 的 `onMounted`。若要把 state + handler 也搬进页面组件，需逐页重构，
  属可选优化（见「L5 收尾」章节的边界说明）。
- `pinia` / `api/` / `store/` 已在 L1 落地；`src/api/` 25 个模块全部在用，L4 已逐页替换为真实接口。
- **Provider 停用开关为只读**：后端 `GET /api/llm-providers` 走 `selectAllEnabled()`，只返回 `enabled = 1` 的记录。
  一旦停用，该项立即从列表消失、`code` 又被占用无法重建 —— 属于不可撤销操作。因此行内开关与编辑抽屉里的开关都设为只读，
  等后端补上「查全部」能力（或加 `includeDisabled` 参数）后再放开。
- **生产 / Electron 下没有 Vite 代理**：`file://` 或静态托管时 `/api` 会指向自身，需要改为直连后端地址
  （如给 `http-client` 加可配置 `baseURL`）。开发期用代理即可，L4/L5 集成时处理。
- ~~**14 处 api 方法与后端不匹配**~~ → **已修复**：逐条取证后确认 13 条是契约脚本自身的假阳性，
  只有 1 条是真问题（`chat()` 直连不存在的 `/deepseek/chat`）。详见「L1 收口验收 · A=14 的真相」。
- ~~`src/api/logs.ts` 缺 `tail` 函数~~ —— **L4 已补齐** `tailLogs(maxLines, skip)`（见 L4 章节）。

### L1 数据层（已完成）

- `src/utils/md5.ts`（自研 RFC1321 实现，324 个用例对拍 Node `crypto` 全通过）
- `src/utils/http-client.ts`（隐形登录、401 自动重登重试、并发去重、FormData 适配、超时归一）
- `src/api/*.ts` 25 个（脚本批量搬入，3180 行）
- `src/store/*`（user / settings / file-ref）+ `src/types/pinia-persist.d.ts`
- `main.ts` 接入 `createPinia` + `pinia-plugin-persistedstate` + 启动预热 `ensureToken()`
- 验收：`npm run typecheck` 0 error、`npm run build` 146 modules

#### L1 收口验收（已完成）

目标是「数据层真能打到后端」，而不是「文件搬过来了」。做了四件事：

**1. 补上开发期代理**（`vite.config.ts`）

`http-client` 所有请求都以 `/api` 开头，没有代理时会打到 5173 自己身上、拿回 `index.html`。
现在 `/api` → `http://127.0.0.1:8084`（后端 `server.port` **是 8084，不是默认 8080**），
可用 `CC_BACKEND=http://192.168.1.9:8084 npm run dev` 覆盖。

**2. `/providers` 页切到真实接口**（首个真实数据页）

| 原 DEMO | 现在 |
| --- | --- |
| `providerList` 写死 5 条 | `listProviders()`，进入页面时拉一次；失败不自动重试，由「刷新」手动触发 |
| 保存只改内存 | `POST /api/llm-providers` / `PUT /api/llm-providers/{id}`，成功后重新拉列表 |
| 删除只改内存 | `DELETE /api/llm-providers/{id}` |
| 余额返回假数据 | `GET /api/llm-providers/{code}/balance`（实测 DeepSeek 返回真实余额） |
| 无失败反馈 | 页面头下方错误条（后端未启动时可见）+ 空状态不误报「还没有配置」 |

**3. 实测验证**（直连 8084 与经 5173 代理各跑一遍）

- 读链路：隐形登录 → 列表（密钥 `****` 掩码）→ 详情 → 余额，全部 200
- 写链路 **14/14 通过**：新增 → 列表脱敏（`apiKey` / `extraConfig` 密钥均 `****`）→ 重复 code 被拒（400）
  → 改名 → 停用 → 空 `apiKey` 不覆盖原值 → `extraConfig` 空串清空 → 停用项不出现在列表
  → 删除 → 重复删除 404 → 数据恢复原状（探针用临时 code，已清理，未污染业务数据）

**4. 新增契约检查脚本 `npm run check:api`**（`scripts/check-api-contract.cjs`）

把 `src/api/*.ts` 的 `(方法, 路径)` 与后端 Controller 映射逐条比对，退出码非 0 可进 CI：

```bash
CC_BACKEND_SRC=/path/to/src/main/java npm run check:api
```

首次运行的结论：前端 166 处调用 ｜ 后端 185 个映射 ｜ **A=14 B=42**。
逐条取证后发现这 14 条里**只有 1 条是真问题**，其余 13 条是脚本自身的假阳性。
修复后：**A=0 ｜ B=15**（剩余 B 全部是原生 `fetch` / `<img src>` / SSE 的有意直连）。

#### A=14 的真相：13 条假阳性 + 1 条真问题

脚本最初按「调用处往后固定截取 320 字符」找 `method: 'POST'`，结果把**下一个函数**的参数算了进来：

| 被误报的项 | 前端实际写法 | 真相 |
| --- | --- | --- |
| `POST /api/agent-configs/list` | `request<AgentConfig[]>('/agent-configs/list')` | 不传 method = GET，本来就对 |
| `POST /api/git/{branches,diff,log,remotes,tags}`（5 条） | `authFetch(\`/api/git/xxx?${params}\`)` | 不传 method = GET，本来就对 |
| `POST /api/lessons/clusters` | `request<LessonCluster[]>(...)` | 不传 method = GET，本来就对 |
| `PUT /api/lessons/stats` | `request<LessonStats>(...)` | 不传 method = GET，本来就对 |
| `POST /api/role/menus/{roleId}` | `request<number[]>(...)` | 不传 method = GET，本来就对 |
| `POST /api/schedule-task/list` | `request<ScheduleTaskItem[]>(...)` | 不传 method = GET，本来就对 |
| `POST /api/p2p/peers`<br>`POST /api/p2p/agent-auth/received/{peerId}`<br>`DELETE /api/p2p/messages/{peerId}/history` | `request<T>(...)` | 不传 method = GET，本来就对 |

`http-client` 的 `request(url, options = {})` 把 options 原样交给 `fetch`，**不传 method 就是 GET** ——
这 13 处前后端本来就是一致的，不需要改任何业务代码。

真正的问题只有 1 条：`chat()` 直连 `POST /api/deepseek/chat`，而后端 `DeepSeekController` 只有
`POST /chat/stream`（SSE），这个调用必然 404。

#### 修复内容

1. **`scripts/check-api-contract.cjs` 三处修正**（既消假阳性，也消假绿）

   | 修正 | 原问题 |
   | --- | --- |
   | method 判定改为**实参括号配对** | 固定截取 320 字符会跨到下一个函数 → 13 条假阳性 |
   | 支持**变量间接调用**回查字面量赋值 | `const url = '/logs/search' + qs; request(url)` 整段漏扫 |
   | 修**嵌套泛型**失配 | `request<Record<string, number>>(...)` 的 `>>` 让调用扫不到 |

2. **`src/api/chat.ts` 的 `chat()`**：改为消费 `streamChat` 聚合 `content` / `thinking` / `sessionId`，
   返回同样的一次性结果，不再直连不存在的端点。

> **覆盖率**：修正后识别 174 处调用（比原先多 8 处），因此 A=0 建立在完整扫描之上，不是漏扫造成的假绿。

### L1.5 适配器增强（已完成）

#### 1. 火山三处增强

后端 `service/llm` 三处增强（已提交单测 45/45 通过）：

| 增强 | 位置 | 说明 |
| --- | --- | --- |
| 图片解析粒度可配 | `VolcengineArkClient` | 新增 `extraConfig.imageDetail`（`low`/`high`/`xhigh`，缺省 `high`）；`detail=auto` 或缺失时按配置归一，非法值告警回落 |
| 思考档位白名单可配 | `VolcengineArkClient` | 新增 `reasoningEffortModels`（支持 JSON 数组或逗号分隔）、`reasoningEffortEnabled`（全模型下发）、`reasoningEffortHigh` / `reasoningEffortMax`（档位文案）；不再硬编码 `deepseek-v4` 前缀 |
| 联网搜索不再静默失效 | `VolcengineSearchApiClient` | 任何不可用原因（provider 未注册 / 非火山 / 缺 Key / 上下文缺失）都会在状态变化时落一条日志；上下文无 `providerCode` 时按「环境变量 → 唯一火山 Provider」两级兜底；非火山 Provider 走 debug 不刷 INFO |
| 配置读取与兜底支撑 | `LLMClientManager` | 新增 `getAllProviderConfigs()`（按 sortOrder 排序快照）与 `readExtraCsv()`（数组 / 逗号串双写法） |

前端同步：`ProviderEditDrawer.vue` 火山预设与提示文案补全新字段说明，`provider-capability.ts` / `ProviderRow.vue` 注释对齐（本步未改推导逻辑，模型级改造见下一小节）。

#### 2. 图片能力矩阵复核（T11）

**复核结论：8 个适配器对「内部统一的 OpenAI 风格图片块」各有各的协议处理，逐条核对如下。**

| 适配器 | 图片协议处理 | 结论 |
| --- | --- | --- |
| `VolcengineArkClient`（火山 / ark / doubao） | `image_url` 保留，`detail=auto` 归一为 `extraConfig.imageDetail`（缺省 `high`） | 已完备 |
| `OpenAIClient`（公共契约）/ `GptClient`（官方专精） | 原样透传 `image_url` 与 `detail` | 合规，**不剔除** `detail`（OpenAI 规范定义了该字段） |
| `QwenClient` / `MiniMaxClient` / `MiMoClient` | 保留 `image_url`，剔除协议未声明的 `detail` | 已完备 |
| `AnthropicClient` | `image_url` → `{"type":"image","source":{"type":"base64\|url"}}` | 已完备 |
| `DeepSeekClient` | 透传 `image_url`（另有 Files API 走 `file` 块） | 已完备 |
| `OllamaClient` | **原缺转换**：内部发的是 `content` 数组，而 Ollama `/api/chat` 要求 `messages[].images[]`（纯 base64，无 `data:` 前缀）→ 图片被服务端忽略、且不报错（静默失败） | **本次补齐** |

**Ollama 补齐（`OllamaClient.convertImageBlocksToImages`）：**

- `text` 块按顺序用 `\n` 合并回 `content` 字符串；`image_url` 块提取 base64 放入 `images[]`（`data:<mime>;base64,xxx` → `xxx`，裸 base64 原样）
- 远程 `http(s)` URL 跳过并计数（Ollama REST 不接受 URL）、`file` 块（Files API 句柄）跳过并计数；有跳过时打一条 WARN，不静默丢图
- 幂等：`content` 已是字符串的消息不再处理，重复调用不会二次转换

**前端 `provider-capability.ts` 改造：`vision` 从「平台级 boolean」升级为「模型级三态」。**

- `on` = 默认模型确定支持多模态；`warn` = 平台支持但模型未配置/非多模态（如 `deepseek-chat` / `qwen-plus` / `MiniMax-M2`）；`off` = 平台协议不支持（保留兜底，当前无平台命中）
- 规则与后端 `FileVisionService.supportsVisionModel` 对齐；判定时对 `code`（后端模型防线实际使用的键）与 `requestTemplate` 同时求值并**取更保守结论**，避免「自定义 code + 火山模板」这类配置漏报
- 兼容自定义前缀模板（`minimax-coding` / `openai-compatible` 等），不会被误判为 `off`
- `deriveCapabilities(template, code, extraConfig, model?)` 第 4 参为默认模型；`visionHint()` 给出三态各自的悬浮说明
- 页面呈现：行内徽章 `图片识别` / `图片识别看模型`；统计拆为「图片就绪」「图片待切换」；编辑抽屉能力预览随默认模型输入实时变化

**验证：** 新增 `AdapterVisionMatrixTest`（main 方法式，不依赖 JUnit/网络）**52/52 通过**，覆盖 Ollama 转换与边界、openai/gpt 透传、qwen/minimax/mimo 剔除 detail、Anthropic 双分支、`supportsVisionModel` 矩阵；已有 `VolcengineAdapterTest` 45/45 无回归；前端 `typecheck` 0 error、`build` 153 modules；规则脚本 41/41、页面结构自检 29/29（临时脚本已删除）。

#### 3. 联网搜索徽章三态（DeepSeek 漏判修复）

**问题**：`deriveCapabilities` 的 `search` 原本只有 `boolean`，且只认火山 / 千问 / MiniMax 三家凭证，DeepSeek 一律落到 `false`（灰徽章）。但后端 `SearchTool` 有第三条官方通道 —— `DeepSeekToolsApiClient` 的 `/v1/tools/search`，启用条件是「Provider 有 code + Base URL 主机严格等于 `api.deepseek.com` + API Key 有效」，**免额外凭证**。因此官方 DeepSeek 配置一直被误显示成「不支持联网搜索」。

**改造**：

| 改动点 | 说明 |
| --- | --- |
| `ProviderCapabilities.search` | `boolean` → `CapabilityState`（`on` / `warn` / `off`） |
| `deriveCapabilities(...)` | 新增第 5 参 `baseUrl` |
| 新增 `isDeepseekOfficialHost(baseUrl)` | 用 `new URL().hostname` 判域名，不用字符串包含（避免 `https://proxy.example.com/api.deepseek.com` 被误判为官方） |
| DeepSeek 分支 | 官方域名 → `on`；第三方中转 → `warn`（官方接口拿不到，只剩通用抓取） |
| 新增 `searchLabel()` / `searchHint()` | `warn` 时文案补「·通用抓取」，悬浮说明写清成因与「仍可正常联网」 |

**四个调用点同步**：`ProviderRow.vue`（行内徽章三态 + title）、`ProviderEditDrawer.vue`（能力预览随 Base URL 输入实时变化）、`ProviderListView.vue`（统计拆为「搜索就绪 / 搜索待启用」）、`preview.html`（`provCaps` 增参、`provCapsHtml` 支持 warn、Base URL 输入监听、统计同步）。

**验证（CDP 真实浏览器）**：真实 DeepSeek 行徽章为 `is-on`「联网搜索」，title「Base URL 指向 api.deepseek.com，DeepSeek 官方搜索接口免额外凭证可用」；统计「搜索就绪 1 / 搜索待启用 0」；抽屉里把 Base URL 改成 `https://proxy.example.com/v1` → 徽章立即变 `is-warn`「联网搜索·通用抓取」并给出该地址，改回官方域名 → 恢复 `is-on`。

### L2 反馈层（已完成）

自建 toast / confirm 替代 ant-design-vue 的 `message` / `modal.confirm`，零组件库依赖。

**新增 4 个文件：**

| 文件 | 作用 |
| --- | --- |
| `src/utils/toast.ts` | 函数式单例：`toast.success/error/warning/info/loading/update/dismiss`；同 type+文案 1.2s 内合并计数（防批量失败刷屏）、最多同时 4 条、loading 常驻 |
| `src/utils/confirm.ts` | Promise 化确认框：`confirm(options)` / `confirmDelete(what, name?, opts?)`；支持异步 `onOk`（抛错则保持打开）、多次调用自动排队 |
| `src/components/ToastHost.vue` | Teleport 到 body 的渲染器；图标按类型着色、`role=status/alert`、可关闭 |
| `src/components/ConfirmHost.vue` | Teleport 弹窗；`role=dialog` + `aria-modal`、危险操作默认聚焦「取消」、ESC 取消、Tab 焦点循环、确认中 loading |

**使用方式：**

```ts
import { toast } from '@/utils/toast'
import { confirm, confirmDelete } from '@/utils/confirm'

toast.success('保存成功')
toast.error('删除失败', { description: '数据库连接超时' })
const id = toast.loading('正在上传…')
toast.update(id, { type: 'success', message: '上传完成' })

if (!(await confirmDelete('智能体', 'AI 助手'))) return   // 取消则直接返回
await confirm({ title: '确认回滚', content: '将丢弃当前改动', type: 'danger', onOk: rollback })
```

**已接入的真实路径：**

| 位置 | 行为 |
| --- | --- |
| 12 个删除入口（智能体/技能/MCP/定时任务/踩坑经验/数据库连接/文件/云端文件/设备/用户/菜单/模型配置） | 统一走 `confirmDelete`，文案带对象名与不可撤销提示，取消则中断 |
| `/providers` 全链路（真实接口） | 保存/删除/启停/余额查询 成功→`toast.success`，失败→`toast.error` + 页面错误条同步 |
| 用户管理删除自己 | `toast.warning('不能删除当前登录账号')`（对齐后端 400 语义） |
| 数据管理重置、外观移除背景图/恢复默认 | 操作完成即提示 |
| `main.ts` 隐形登录预热失败 | ~~`toast.error('后端未连接，接口暂不可用')`~~ → **L19 已移除**：启动时不再静默登录，未登录由路由守卫跳登录页，错误就地显示在登录卡片里 |

**样式**：`app.css` 新增第 33 节（原响应式顺延为 34），类名 `.cc-toast-*` / `.cc-confirm-*`；浮层 z-index 约定 **抽屉遮罩 60 < Toast 200 < Confirm 210**；含 `@keyframes cc-spin` 与 `prefers-reduced-motion` 降级。
**图标**：新增 `i-close` / `i-alert` / `i-info`（`AppIcons.vue` 与 `preview.html` 双库同步，共 40 个；后补 `i-folder`）。

**验证**：`typecheck` 0 error、`build` 153 modules、自检脚本 34 项全 PASS；并用临时演示页在浏览器实测（Toast 四色 + loading→success、Confirm 遮罩/居中/按钮、ESC 与遮罩点击关闭）。

### L3 路由权限（已完成；L19 起改为「登录态 + 角色」两层）

> ⚠️ 本节的「无登录版」结论是 L3 当时的状态。**L19 恢复了显式登录页**，守卫改回两层判定
> （先看 `hasToken()`，再看 `meta.adminOnly`），`requiresAuth` 的语义以「未登录跳 `/login?redirect=`」
> 的形式回归。下面表格保留作历史记录，最新行为见「十九、L19 登录页与个人信息」。

砍掉登录页后，权限模型从「登录态 + 角色」收敛为**只有角色一维**：

| v2 守卫 | L3 时的处理 | 原因（L19 已回退） |
| --- | --- | --- |
| `requiresAuth`（未登录跳 `/login`） | ~~删除~~ → **L19 恢复** | 隐形登录保证任何 `/api` 请求前必有 token，不存在「未登录」状态 |
| `requiresGuest`（已登录禁入 `/login`） | ~~删除~~ → **L19 恢复** | 没有登录页 |
| `requiresAdmin` | **保留**，改由 `meta.adminOnly` 驱动 | 后端仍有 6 个 Controller 校验 `userRole === 'admin'` |

**新增 2 个文件：**

| 文件 | 作用 |
| --- | --- |
| `src/utils/session.ts` | 身份会话：`ensureIdentity()`（拉 `GET /api/user/current` 并写入 user store）、`peekIdentity()`（同步读快照）、`canAccess(adminOnly)`、`resetIdentity()`。含并发去重与**失败冷却 30s**（后端未启动时不会每次点管理页都干等 15s 超时） |
| `src/types/router-meta.d.ts` | 给 `RouteMeta` 补 `title` / `adminOnly` 声明，守卫与路由表共享同一份字段定义（写错字段名 typecheck 直接报错） |

**权限矩阵（`meta.adminOnly` 与 `MenuItem.adminOnly` 同源）：**

| 页面 | 需管理员 | 依据 |
| --- | --- | --- |
| `/users`、`/menu-permission` | ✅ | `UserController` / `MenuController` / `RoleController` 全部 `checkAdmin` |
| `/tasks/schedule` | ✅ | `ScheduleTaskController` 6 个端点全部 `checkAdmin` |
| `/data` | ✅ | `SystemController` 的 `data-stats` / `export` / `import` / `reset` 均 `checkAdmin` |
| `/providers` | ✅ | `LLMProviderController` 全部 `checkAdmin` |
| `/mcp`、`/databases` | ✅（**前端收口**） | 后端未做角色校验，但两页涉及 MCP 凭证与数据库明密文密码；前端允许**比后端更严格**，不允许更宽松 |
| `/chat`、`/agents`、`/skills`、`/lessons`、`/files`、`/logs`、`/p2p`、`/appearance`、`/settings` | — | 后端无角色校验；外观只影响本机观感 |

**守卫行为（两段式，命中快照零网络开销）：**

1. 先 `peekIdentity()` 同步判定 —— 身份快照随 user store 持久化，刷新后不闪变；
2. 快照缺失才 `ensureIdentity()` 拉一次；
3. 非管理员 → `toast.warning('权限不足，需要管理员权限')` + 回落 `/chat`；
4. **后端不可用时放行**（拿不到身份不阻断导航），由页面自身展示错误态 —— 否则一个 401 会让整站不可用。

**菜单同源过滤**：`AppSidebar` 只渲染一级导航（`PRIMARY_NAV`），`SettingsView` 渲染 `MORE_GROUPS` 全部入口，两边都按 `isAdmin` 过滤 `adminOnly` 项并剔除变空的分组；`AppShell` 透传 `is-admin`，`App.vue` 从 `userStore.isAdmin` 取值。（L17 起侧栏底部的「更多」入口已移除 —— 它与设置页完全同源，等于同一份菜单挂了两处入口。）

**顺带修掉的问题**：`http-client` 的 `extractErrorMessage` 增加 Spring 默认错误页识别 —— 后端 Controller 抛 `RuntimeException`（如 `checkAdmin`）时响应是 `{timestamp,status,error,path}` 而非 `ApiResponse`，原先会把整段 JSON 甩进 Toast，现在压成 `服务异常 HTTP 500 Internal Server Error（/api/...）`。

**验证：**

- `npm run typecheck` 0 error；`npm run build` 156 modules（较 L2 的 153 增加 session + user-admin + md5 链路）
- 后端接口探针 **10/10 通过**：admin 登录 → `/user/current` 返回 `role=admin`；创建 `l3probe`（role=user）→ `/user/current` 返回 `role=user`；同 token 访问 `/llm-providers` 被后端拒绝（500），访问 `/lessons`、`/files/supported` 正常；探针用户已清理
- 浏览器双向实测（临时跳板页注入 `localStorage` 凭据，验后已删除）：
  - **普通用户**：直捣 `/#/users` → 被弹回 `#/chat` + Toast「权限不足，需要管理员权限」；一级导航只剩 对话/智能体/技能/设置（「任务」消失）；「更多」hint 变 `经验 · 文件 · 日志`；左下用户名显示 `l3probe`
  - **管理员**：`/#/users` 正常进入，标签页标题 `用户管理 · CodeCraft`；5 项一级导航全在；hint 为 `MCP · 经验 · 数据库 · 日志`；用户名显示 `admin`

> 静态预览 `preview.html` 不体现权限（无路由、无登录态、菜单写死），权限只在 Vue 应用内生效。

### L4 逐页接线（已完成 · 16/16）

把 `App.vue` 里各页面的 DEMO 数据换成 `src/api/*` 真实调用。

**已接线的 16 个页面**

| 页面 | 接口 | 状态 |
|------|------|------|
| `/providers` 模型配置 | `GET/POST /api/llm-providers`、`GET /{code}/balance` 等 | ✅ L1 阶段打样 |
| `/logs` 运行日志 | `GET /api/logs/{dates,tail,search,download}` | ✅ L4 第一批 |
| `/agents` 智能体 | `GET/POST /api/agent-configs`、`PUT /{id}`、`PUT /{id}/runtime`、`PUT /{id}/default`、`DELETE /{id}` + `GET /api/llm-providers` | ✅ L4 第一批 |
| `/skills` 技能 | `GET /api/skills?userId=`、`POST`、`PUT /{id}`、`DELETE /{id}?userId=` | ✅ L4 第二批 |
| `/mcp` MCP 服务器 | `GET/POST /api/mcp/servers`、`PUT/DELETE /{id}`、`POST /{id}/{connect,disconnect,refresh}` | ✅ L4 第二批 |
| `/lessons` 踩坑经验 | `GET /api/lessons`、`GET /lessons/clusters`、`PUT /{id}`、`POST /{id}/feedback`、`POST /merge`、`DELETE /{id}` | ✅ L4 第二批 |
| `/databases` 数据库连接 | `GET/POST /api/db-connections`、`PUT/DELETE /{id}`、`POST /{id}/test`、`POST /test`、`POST /{id}/execute` | ✅ L4 第三批 |
| `/files` 文件管理 | `GET /api/files/page`、`POST /api/files`、`PATCH/DELETE /{id}`、`GET /{id}/preview`、`GET /api/files/cloud`、`DELETE /files/cloud/{fileId}` | ✅ L4 第三批 |
| `/tasks/schedule` 定时任务 | `GET /api/schedule-task/list`、`POST /create`、`PUT /update`、`DELETE /delete/{id}`、`POST /{enable,disable}/{id}` | ✅ L4 第四批 |
| `/p2p` 设备互联 | `GET /status,address,name,peers`、`POST /qrcode`、`POST /qrcode/parse`、`POST /connect`、`POST /{disconnect,reconnect}/{peerId}`、`DELETE /peer/{peerId}`、`PUT /peer/{peerId}/remark`、`GET/POST /agent-auth/*` | ✅ L4 第四批 |
| `/data` 数据管理 | `GET /api/system/data-stats`、`GET /export-data`、`POST /import-data`、`POST /reset-data` | ✅ L4 第四批 |
| `/users` 用户管理 | `GET /api/user/list`、`POST /create`、`PUT /update`、`DELETE /delete/{id}` | ✅ L4 第四批 |
| `/menu-permission` 菜单权限 | `GET /api/menu/tree`、`POST /menu/create`、`PUT /menu/update`、`DELETE /menu/delete/{id}`、`GET /api/role/list`、`GET /role/menus/{roleId}`、`POST /role/assign-menus`、`POST /role/create`、`PUT /role/update` | ✅ L4 第四批 |
| `/chat` 聊天页 | `GET /conversation/list`、`GET /conversation/{id}/messages/page`、`DELETE /conversation/{id}`、`POST /deepseek/chat/stream`(SSE)、`POST /deepseek/answer`、`GET /deepseek/task/{id}`、`GET /task/{id}/stream`、`POST /task/{id}/cancel`、`POST /deepseek/upload`、`POST /prompt/optimize` | ✅ L4 第五批 |
| `/appearance` 外观设置 | `POST /api/appearance/background`（multipart）→ `GET /api/appearance/file/{name}`（免鉴权静态） | ✅ L4 第六批 |
| `/settings` 设置 | —（纯前端导航聚合，入口取自 `menu.config.ts`） | ✅ 无接口需求 |

**L4 第一批（logs + agents）解决的问题**

1. **补 `tailLogs()`**：`src/api/logs.ts` 新增 `tailLogs(maxLines, skip)`，对齐 `GET /api/logs/tail`
   （只读今天、返回纯文本、从旧到新、**不含行号**）。
2. **`GET /api/skills` 的 400 之谜结案**：不是 bug —— 后端 `SkillController.listSkills` 的 `userId`
   是必填 `@RequestParam`，L3 探针没带参数才 400。顺带修正 `skill.ts` 里
   `createSkill / updateSkill / deleteSkillApi` 的错误返回类型（原写成 `{success,data,message}`，
   实际是 `ApiResponse<Skill>`）。
3. **日志级别解析器重写**（`src/utils/log-line.ts` v3）：真实日志是 logback 简版
   `时间 [线程] LEVEL logger - msg`，而 v2 只检查时间戳后**第一个 token**，
   `[http-nio-8084-exec-3]` 会被当成级别 → 级别恒为空，导致彩色标记、级别筛选、错误统计全部失效。
   v3 改为在时间戳后 100 字符内找「前后均为分隔符」的级别词，Spring Boot / logback 简版两种 pattern 都命中。
4. **新增统一「按需加载」段**（`PAGE_LOADERS`）：当前用 `v-if` 切页，子组件并不真正挂载/卸载，
   `onMounted` 表达不了「进入某页才拉数据」，故集中成一个 `route.path` watcher；
   每个分支带「已加载 / 加载中 / 已失败」三重跳过，避免重复请求与失败刷屏。
   > L5 已改为 `<router-view>` + 各 View 的 `onMounted`，该表已删除，逻辑搬进 `PAGE_BINDINGS` 的 `load` 字段。
5. **卡片展示打磨**：`AgentCard` 新增 `executionLabel`（`auto` → 自动执行）与
   `toolsLabel`（`toolNames` 为空 = 「全部工具」，不再显示误导性的「0 个工具」）；
   转换只放展示层，抽屉回填仍取原始枚举值。

**智能体页补齐：角色性格 + 模型联动**

两块原先缺失的能力，Vue 与 `preview.html` 已同步：

1. **角色性格（`characterProfile`）** —— 抽屉内可折叠子区块，9 个字段严格对齐后端
   `CharacterPromptUtil.buildCharacterSection()`：`name / species / gender / age /
   personality / greeting / background / likes / style`（少写一个字段不报错，但该设定会静默丢失）。
   - 后端存的是 **JSON 字符串**，运行时解析后追加到系统提示词末尾（仅首次会话注入）。
   - **清空必须提交 `"{}"`**：`AgentConfigServiceImpl.update()` 用 `if (getCharacterProfile() != null)`
     判断是否覆盖，传 `undefined` 等于「不修改」，性格永远清不掉。
   - 卡片上仅在配置了内容时显示 `🎭 性格` 标签（`hasCharacter` 由 `hasCharacterProfile()` 推导，非后端字段）。
   - 解析/序列化集中在 `src/utils/character-profile.ts`，容错策略：非法 JSON → 空对象，不阻断抽屉打开。
2. **模型选择联动「模型配置」页** —— 抽屉新增 `Provider` 下拉，模型下拉的选项来自该 Provider 的
   `ProviderConfig.modelList`，不再写死 4 个假模型：
   - Provider 三级回退：表单选中 code → `isDefault` 的 Provider → 列表第一个（与后端运行时选型一致）。
   - 模型四级优先：该 Provider 的 `modelList` → 全部 Provider 模型合集 → 组件兜底列表 → 当前已选值
     （**始终把已选模型插到首位**，否则历史数据里的模型会被静默改写）。
   - 手动切 Provider 时，仅当当前模型不在新列表里才自动换（默认模型优先）；回填不触发该逻辑。
   - 提示文案区分三种来源（Provider 已配置 / 未配置已回退 / 未加载到配置），避免「下拉有值但不知道哪来的」。
3. **后端坑（实测确认）**：`PUT /api/agent-configs/{id}` **不更新 `providerCode`**
   （`update()` 只处理 `providerId`，`updateRuntime()` 才处理 `providerCode`）。
   故编辑分支在 `PUT /{id}` 之后额外调一次 `PUT /{id}/runtime { providerCode }` 补写，
   否则切换 Provider 后刷新会退回旧值。探针已单独验证该结论。
4. **按需加载**：`PAGE_LOADERS['/agents']` 现在同时预加载 `GET /api/llm-providers`（模型联动需要），
   与 Agent 列表并行，仍带「已加载 / 加载中 / 已失败」三重跳过。

新增文件：`src/utils/character-profile.ts`；修改：`AgentEditDrawer.vue`（Provider 联动 + 性格区）、
`AgentCard.vue`（`providerCode` / `hasCharacter` / `modelLabel`）、`AgentListView.vue`（透传 `providers`）、
`ProviderRow.vue`（`ProviderItem` 补 `parsedModelList`）、`App.vue`（`agentProviderOptions` / `toAgentRow` /
`onAgentSave`）、`assets/app.css`（第 34 节 `.cc-char-*`，响应式节此后顺延，当前为 36）、`preview.html`。

**验证**

- `npm run typecheck` → 0 error；`npm run build` → 158 modules / 578ms
- 日志解析器单测 10/10（真实 logback 简版行、Spring Boot 行、栈帧续行、含 `ErrorCodeHandler`
  的 logger 名不误判、正文含 `INFO` 不影响判定）
- 接口探针（临时脚本，含隐形登录）：**日志 5/5、智能体 8/8 全 PASS**
  - 智能体覆盖：列表 → 创建 → 更新（含 `toolNames` 清空）→ 设默认 → 还原默认 → 删除 → 复查列表，
    探针数据已清理干净
- 浏览器实测（dev server HMR）：
  - `/logs` 渲染真实日志流（续行栈帧不重复时间戳/级别、`WARN` 行淡橙底、统计口径正确）
  - `/agents` 渲染真实 Agent「圆圆」（`deepseek-flash · 自动执行`、`全部工具`、
    `E:\zbcode\testDemo\code-craft`、「默认」「内置」标签）

**角色性格 / 模型联动的验证**

- `npm run typecheck` → 0 error；`npm run build` → **159 modules / 592ms**
- 静态自检脚本 41 项全 PASS（内联 JS 语法、CSS 括号 813/813、11 类标签配对、
  17 个新增 id、`getElementById` 349 处引用、图标双库 40 个、`cc-` 类覆盖、
  `.cc-char` 6 条关键规则、Vue 接线 9 项）
- 接口探针 **17/17 PASS**（含隐形登录）：
  - 性格：`PUT /{id}` 写入 9 字段 → 列表回读逐字节一致 → 提交 `"{}"` 可清空
  - `providerCode`：`PUT /{id}/runtime` 落库成功，且**不影响** `characterProfile`
  - 反向确认：`PUT /{id}` 带 `providerCode` 返回 200 但**不生效**（证实需 runtime 补写）
  - 内置 Agent「圆圆」原值已完整还原（`characterProfile` / `providerCode` / `modelName`）
- 浏览器实测：
  - 卡片标题行出现 `🎭 性格` 标签（真实数据里「圆圆」已配性格）
  - 编辑抽屉：`Provider` 显示「跟随默认 Provider」，提示「未指定时跟随默认 Provider：DeepSeek（deepseek）」；
    模型下拉为 `deepseek-flash`，提示「选项来自「DeepSeek」已配置的模型列表」
  - 性格区自动展开并显示「已配置」徽标，回填真实数据（姓名 圆圆 / 物种 人类 / 性别 女 / 年龄 18 /
    性格「调皮捣蛋，活泼可爱」）

**智能体卡片「更多」菜单（设为默认 / 删除）**

三个点原本是死按钮：`AgentCard` 只 emit `more`，父级 handler 固定做「设为默认」，而 `onAgentDelete` 虽有实现却**没有任何 UI 入口**（全链路删不掉 Agent）。

| 项 | 说明 |
| --- | --- |
| 菜单结构 | 后端除 CRUD 外只有 `PUT /{id}/default` 一个独立操作，因此固定两项：设为默认 / 删除 |
| 禁用规则 | 已是默认 → 文案变「已是默认智能体」并置灰；内置 Agent → 「删除」置灰（后端同样会拒绝） |
| 显隐控制 | 走 `is-open` 类而**不是 `v-show`** —— `app.css` 里 `.cc-agent-menu` 基础态是 `display:none`，`v-show` 只清内联 `style.display`，覆盖不了样式表；静态预览与 Vue 共用同一条规则 |
| 关闭时机 | 点「菜单 / 更多按钮」之外、按 ESC 关闭；每张卡各自注册 document 监听，打开 B 的菜单会自动关掉 A 的（天然互斥，父组件无需维护「当前打开哪张卡」） |
| 层级 | 卡片 `position: relative`，菜单 `z-index: 6`；展开时卡片加 `is-menu-open` 抬到 `z-index: 2`，避免被相邻卡片压住 |
| 事件链 | `AgentCard` emit `set-default` / `delete` → `AgentListView` 透传（delete 转成 id）→ `App.vue` 的 `onAgentSetDefault` / `onAgentDelete`（后者从死代码变为可用） |

**验证（CDP 真实浏览器 + 真实后端）**

- 临时创建「菜单验证Agent」（非默认、非内置）→ 其菜单两项均可用；内置「圆圆」两项均置灰
- **设为默认**：确认框「确定将「菜单验证Agent」设为默认？」→ 确定后「默认」标签迁移、Toast「已设为默认智能体」、菜单项变「已是默认智能体」置灰
- **删除**：确认框「确定删除「菜单验证Agent」吗？此操作不可撤销。」→ 确定后卡片消失、Toast「智能体已删除」，后端列表回到 1 条
- 菜单开合：`display` `none → block`、`is-open` / `is-menu-open` / `aria-expanded` 同步；再点按钮、点外部、按 ESC 三种方式均能收起；实测尺寸 172×70、`z-index: 6`
- 静态预览 `preview.html` 同套验证：默认标签可迁移、删除后剩 1 张且默认标记自动落到剩余卡片
- 临时数据已清理，内置 Agent 的 `isDefault` 已还原

**智能体工作目录选择器（`onPickDir` 补齐）**

`App.vue` 里的 `onPickDir()` 原本是个空函数（`// TODO: 打开 DirectoryBrowser`），点「工作目录 → 选择」什么都不会发生。旧项目的 `DirectoryBrowser.vue` 依赖 Electron 原生 `window.electronAPI.selectDirectory()` 与 ant-design-vue，v3 两者都没有，故按 `cc-` 风格重写。

| 项 | 说明 |
| --- | --- |
| 新组件 | `src/components/DirectoryPicker.vue`：居中弹窗（`Teleport` 到 body），盘符列表 / 面包屑 / 手输路径直达 / 单向点击选中 / 双击或箭头下钻 |
| 数据来源 | `GET /api/project/drives`（盘符）+ `GET /api/project/children?path=…&includeFiles=false`（子目录）；返回 `{ name, path, directory }`，`path` 统一正斜杠绝对路径 |
| 联动方式 | **抽屉内自包含**：`AgentEditDrawer` 直接引入，`@pick` 回填 `form.workDir`；`pick-dir` emit 链路与 `App.vue` 的 `onPickDir` 一并删除 |
| 显隐控制 | 走 `is-open` 类而**不是 `v-if` / `v-show`**：静态预览的浮层 HTML 常驻且默认 `display:none`，Vue 侧用同一个类点亮，两边共用一条 CSS 规则（见踩坑经验 216） |
| 层级 | `z-index: 80`，高于抽屉（70）与抽屉遮罩（60），低于 Toast（200）/ Confirm（210） |
| 选中语义 | 默认目标是「当前所在目录」，因此**不进入子目录也能选中它**（旧版必须先下钻一层，这是个体验缺陷） |
| 面包屑 | `C:/Users/61443` → `我的电脑 / C: / Users / 61443`，每级可点回退；Windows 首段自动补 `/`，Unix 从 `/` 累积，两套规则同一处实现 |
| 空态 | 后端对「不存在 / 无权限」目录返回**空数组而非报错**，无法区分，故文案写成「该目录下没有子目录（也可能路径不可访问）」 |
| 初始化 | 传入的 `initialPath` 若是绝对路径就直接定位（否则从盘符开始）；手输框同步回填当前路径 |
| 关闭 | 遮罩点击 / 取消 / 关闭按钮 / ESC；**ESC 只关目录弹窗**，不会连带关掉底下的编辑抽屉 |

**实测踩到的后端差异**：`GET /project/drives` 返回的盘符项 `directory` 是 **`false`** —— 后端用两参构造器 `new DirectoryEntry(name, path)`，`directory` 默认 false。若按 `entry.directory` 一律拦截，盘符永远进不去。故 `enterDir()` 改为「仅在已处于某目录内时按该字段判断」，`drives` 场景一律可进入。

**验证**

- `npm run typecheck` → 0 error；`npm run build` → **162 modules / 611ms**
- 静态自检 38 项全 PASS（内联 JS 4015 行语法、CSS 括号 956/956、7 类标签配对、
  `getElementById` 343 处引用、图标双库 40 个、`cc-dirpick` 类覆盖 17 个、CSS 关键 4 条规则）
- 接口探针 **12/12 PASS**：drives 返回 4 个盘符（`C:/` `D:/` `E:/` `F:/`）、
  children 返回 18 项且全为目录、二级下钻 200、不存在路径返回空数组
- CDP 真实浏览器 + 真实后端（Vue 应用）**24/24 PASS**：初始定位 `E:/zbcode/testDemo/code-craft`
  （面包屑 5 级）→ 回「我的电脑」列 4 盘符 → 箭头下钻 `C:/`（18 项）→ 单击选中 `C:/work` →
  手输 `C:/Windows` 直达 → 确认回填 → ESC / 遮罩只关弹窗不关抽屉
- 静态预览 `preview.html` 同套 CDP 验证 **19/19 PASS**（假目录树；含新建 Agent 时清空工作目录）

**L4 第二批（skills + mcp + lessons）**

三个页面的 DEMO 数据全部换成真实接口，并顺带修掉 4 处会导致「静默数据损坏」的后端问题。

| 页面 | 接口 | 关键点 |
| --- | --- | --- |
| `/skills` 技能 | `GET /api/skills?userId=`、`POST`、`PUT /{id}`、`DELETE /{id}?userId=` | `userId` 必填、按用户隔离；增删改都校验归属，传错抛 SecurityException |
| `/mcp` MCP 服务器 | `GET/POST /api/mcp/servers`、`PUT/DELETE /{id}`、`POST /{id}/{connect,disconnect,refresh}` | 状态由后端按**实时连接**计算（CONNECTED / DISABLED / FAILED），失败原因单独展示 |
| `/lessons` 踩坑经验 | `GET /api/lessons`、`GET /lessons/clusters`、`PUT /{id}`、`POST /{id}/feedback`、`POST /merge`、`DELETE /{id}` | 管理页**不传 `projectKey`** = 查询全部项目（避免 LLM 写入的项目名与页面默认值不一致导致查不到） |

修掉的 4 个后端问题（均属「不改接口契约、只改内部实现」）：

1. **技能创建必然失败**：`skill.agent_type` 是 `NOT NULL` 列，而 `SkillController.createSkill` 直接把前端传来的
   `agentType`（`null`）写库 → `JdbcSQLIntegrityConstraintViolationException`。后端补默认值兜底（`code_assistant`），
   前端也显式传，双保险。
2. **技能作用域改不动**：`SkillMapper.update` 的 UPDATE 语句不含 `agent_config_id`，抽屉里把「全局」改成某个 Agent
   保存后不生效。照抄 `createSkill` 已有的 `jdbcTemplate` 补写模式，约定 `agentConfigId > 0` 绑定、`= 0` 改回全局、`null` 不动。
3. **MCP 编辑会静默清空请求头**：`McpServerMapper.update` 原本是全量 UPDATE，而列表接口只回显 `hasHeaders` 布尔值
   （拿不到明文），抽屉里「请求头留空 = 保持原值」的注释与实际行为**完全相反** —— 改个名字就把 Authorization 清掉。
   Mapper 改为动态 SQL（`<script>` + `<if test="x != null">`），null 列不覆盖；确要清空时传 `"{}"`。
4. **Agent 工具授权失效**：`onAgentSave` 把 `tools` 数组存成逗号串，而后端用
   `objectMapper.readValue(toolNames, List.class)` 解析 → 解析失败后 `log.warn` 并**回退到全部工具**，
   等于「限制 Agent 可用工具」这个安全设置从来没生效过。改为 `JSON.stringify(tools)`（空数组传空串 = 全部工具）。

另有 3 处前端配套改动：

- **技能 / MCP 抽屉保存后自动关闭**：新建不幂等，抽屉一直开着再点「保存」会重复创建同名记录。
- **「更多」死按钮改为「删除」按钮**：`SkillRow` / `McpRow` 的三个点原本只 emit 到 `console.log`，
  而 `onSkillDelete` / `onMcpDelete` 全链路没有 UI 入口；改为 hover 出现的删除按钮，与 `LessonRow` 一致。
- **首屏直达 `/skills` 报「未获取到身份」**：身份由 `main.ts` 的 `void ensureIdentity()` 异步预热，
  `loadSkills` 取 `userId` 时可能还没回来（且失败后 `PAGE_LOADERS` 不再重试）；改为缺身份时先
  `await ensureIdentity()`（内部有并发去重 + 失败冷却）。

`LessonListView` 同时新增 `total` / `loading` / `error` 三个 props：后端默认 `size=10`，管理页一次拉 100 条，
当 `total > 已加载条数` 时统计行会标注「已载入 N 条」，不假装全部已加载。

**验证**

- `npm run typecheck` → 0 error；`npm run build` → **165 modules / 600ms**
- 接口探针（临时后端实例 **8085 + 内存 H2**，不干扰用户的 8084）**20/20 PASS**：
  - 技能 10/10：创建（`agentType` 兜底生效）→ `toolNames` 为 JSON 数组 → `agentConfigId=0` 不落库 →
    作用域切到 Agent 1 **生效** → 删除 → 列表归零
  - MCP 7/7：创建带 headers → **只改名不带 headers，重新 GET 后 `hasHeaders` 仍为 true**（动态 SQL 生效）→ 删除
  - 踩坑经验 3/3（对 8084 真实库）：分页返回 `items/total/page/size`、78 条、时间为 ISO 字符串且能被 `new Date()` 解析
- CDP 真实浏览器 + 真实后端：
  - **三页加载 18/18**：`/skills` 空态、`/mcp` 空态、`/lessons` 78 行 + 统计（已验证 4 / 草稿 74 / 隐藏 0 / 命中 223）
    + 归并按钮禁用（clusters 为空）
  - **技能 + MCP 端到端 22/22**：新建 → 列表出现 → 抽屉自动关闭 → 删除确认 → 列表回到 0；
    MCP 额外验证「连接失败原因展示」与「启用开关双向切换（全量 PUT，后端做必填校验）」
  - **踩坑经验只读 10/10**：详情抽屉回填（3 个文本域 + 只读现象区）、关闭后数据未改动（仍 78 行）
- 写操作（反馈 / 删除 / 归并）未在真实库上做破坏性验证（会改动用户的 78 条经验）；
  接口路径已由 `npm run check:api` 的 A=0 覆盖，代码路径与技能 / MCP 同构。

**L4 第三批（databases + files）**

两个「多接口联动」页面：数据库连接要串起「CRUD + 测试连接 + 执行 SQL」，文件管理要串起「分页列表 + 上传 + 重命名 + 删除 + 预览 + 云端清单」。

| 页面 | 接口 | 关键点 |
| --- | --- | --- |
| `/databases` 数据库连接 | `GET/POST /api/db-connections`、`PUT/DELETE /{id}`、`POST /{id}/test`、`POST /test`、`POST /{id}/execute` | 密码**只写不读**；测试连接就地反馈（行内 / 抽屉内，不弹 toast）；执行 SQL 结果按语句分块渲染 |
| `/files` 文件管理 | `GET /api/files/page`、`POST /api/files`、`PATCH/DELETE /{id}`、`GET /{id}/preview`、`GET /api/files/cloud`、`DELETE /files/cloud/{fileId}` | 分页与筛选**全交后端**；上传按文件循环（后端单文件接口）；预览走 blob（`<img>` 带不了鉴权头）；云端清单按需拉取 |

后端行为带来的 4 个约束（都在代码注释里写明）：

1. **`PUT /db-connections/{id}` 是全量覆盖**：`DbConnectionMapper.update` 的 SQL 把 name / db_type / host / port /
   database_name / username / password_encrypted / extra_params / enabled **一律写一遍**。只提交 `{ enabled }` 会把其余列清空，
   所以「启用开关」也要用当前行数据还原成完整提交体（`dbPayloadOf()`）。CDP 实测确认：停用后连接串与库名保持不变。
2. **抽屉里「测试连接」测的不一定是表单值**：`DbConnectionService.test()` 的逻辑是「带 id 且 `passwordPlain` 为空 → 直接用库里已存配置」。
   因此：新建 → `POST /test`（完整配置）；编辑且填了新密码 → `POST /test`；编辑且没填密码 → `POST /{id}/test`（测的是已保存配置）。
3. **文件搜索要服务端参与**：`/page` 的 `keyword / status / storageType` 与分页是同一套筛选口径，前端本地过滤会与 `total` 统计打架。
   搜索框加了 300ms 防抖，翻页/改筛选统一回第 1 页重查。
4. **时间格式**：后端 `LocalDateTime` 序列化为 ISO（`2026-10-18T22:31:21.002802`），而行组件的 `formatTime` 走
   `new Date(v.replace(/-/g, '/'))` —— 斜杠换完后 `T` 仍在，部分浏览器解析失败会原样显示长串。
   新增 `normalizeTimeText()` 统一压成 `YYYY-MM-DD HH:mm:ss`（正则截取，不依赖 `Date` 的宽松解析）。

前端配套改动：

- `DbConnectionListView` / `FileListView` 新增 `loading` / `error` 两个 props：加载中禁用刷新与分页按钮，
  失败时页面顶部显示红色错误条（与 MCP / 技能页同款），空状态条件改为 `v-else-if="!loading && !error"`（避免「加载中」时先闪一下空状态）。
- `FileListView` 新增 `open` / `open-cloud` 两个 emit：前者让父组件按文件 id 拉预览 blob（api 层已按 id 缓存 objectURL），
  后者让「云端文件」抽屉按需拉 `GET /api/files/cloud`（服务端要翻 Provider 账号全量清单，可能较慢，不随列表预加载）。
- `DbConnectionVO` 补 `userId?: number | null`：后端实体本来就返回该字段，之前前端类型漏了，导致行上的「系统级 / 我的」标签恒为「系统级」。
- 删除末页最后一条时会回退到最后一页重查（只回退一次），避免停在空白页。

**验证**

- `npm run typecheck` → 0 error；`npm run build` → **167 modules / 613ms**
- 接口探针（真实后端 8084）**15/15 PASS**：
  - 数据库：列表 → 创建 h2 + sa 免密连接 → `POST /{id}/test` 返回「连接成功」 → `PUT /{id}` 改名 + 停用
    （`passwordEncrypted` 仍为 null、`hasPassword=false`）→ 列表回读校验 → 删除
  - 文件：`/page` 返回 `total=95 / totalSize=23.6MB`、上传 txt（`storageType=local`）→ `PATCH` 重命名 →
    关键词搜索命中 → `GET /{id}/preview` 返回原文 → 删除 → 搜索归零
  - 云端：`provider=deepseek total=173 linked=95 cloudOnly=78`
- CDP 真实浏览器 + 真实后端 **15/15 PASS**：
  - `/files`：列表 20 行/页、统计「共 95 个文件 占用 22.5 MB」、分页「第 1 / 5 页」、翻到第 2 页、搜索 `png` 命中并回第 1 页、
    **页面文本无 ISO `T` 时间串**、云端抽屉 173 行（95 已登记 / 78 仅云端 31.3 MB）
  - `/databases`：空状态 → 新建 h2 连接（保存按钮由校验驱动）→ 列表新增 + Toast「连接已创建」+ 抽屉自动关闭 →
    行内测试连接「连接成功 · 17 ms」→ 停用开关生效且连接串未被清空 → 删除确认框 → 列表回到 0
- 探针创建的临时连接与上传文件均已删除，后端数据恢复原状。

**第三批已知项**

- `GET /api/files/cloud` 的真实耗时取决于 Provider 账号下的文件数量（当前 173 个，实测约 2~3 秒），故只在打开云端抽屉时拉取。
- 「执行 SQL」抽屉的写操作未在真实业务库上验证（会改动用户数据）；只读查询链路（`SELECT` 结果分块渲染）由接口探针与页面渲染覆盖。
- `file.pageSize` 固定 20（与后端默认一致），页面暂无「每页条数」切换器。

**L4 第四批（tasks/schedule + p2p + data + users + menu-permission）**

一次接通 5 个有状态页面，全部走真实接口（后端 8084）：

- `/tasks/schedule`：列表 / 新建 / 编辑 / 删除 / 启用 / 禁用
- `/p2p`：本机状态与地址、连接二维码（打开抽屉时现算）、节点列表、断开 / 重连 / 删除、备注与 Agent 授权差集
- `/data`：数据统计、导出（真实下载 JSON）、导入（multipart 上传）、重置
- `/users`：分页列表、搜索、新建 / 编辑 / 启停 / 删除
- `/menu-permission`：菜单树、角色列表、角色菜单预取、菜单 / 角色 CRUD、角色菜单分配

**这批踩到的 4 个后端语义坑**

1. **定时任务的 `execute_time` 是「下次执行时间」，不是「一次性任务时间」**：调度器每 30s 扫
   `status='ENABLED' AND (execute_time IS NULL OR execute_time <= NOW())`，而 `PUT /schedule-task/update` 是全量覆盖 8 列。
   若 cron 任务保存时把 `execute_time` 写成 NULL，任务会在 30 秒内被当成「到期」立刻跑一次（还会新建一个会话）。
   → 保存后追加 `POST /enable/{id}`，复用 `ScheduleTaskServiceImpl.enableTask` 里的重算逻辑先算出下次执行时间。
2. **`agent_type` 是 NOT NULL 列且 update 全量覆盖**：必须每次显式提交（前端固定 `code_assistant`），否则被写成 NULL 直接 500。
3. **`executeTime` 不能传空串**：后端是 `LocalDateTime` + `@JsonFormat`，空串反序列化直接 400；切换周期 / 一次性模式时另一侧必须传 `null`。
4. ~~**后端没有「立即执行」接口**~~ → **L9 已补**：新增 `POST /schedule-task/run/{id}`
   （建会话 + 异步跑指令，不改 status / execute_time，仅累加执行统计），行内按钮已去掉 `disabled`。

**验证记录**

- `npm run typecheck` 0 error；`npm run build` **171 modules / 636ms**；`npm run check:api` → **A=0**（B=14 为有意直连）。
- 接口探针 **26/26 PASS**：定时任务 create → update（改名生效且 agentType 未被写空）→ enable
  （`execute_time` 被算成 `2026-10-19 09:00:00`，确认不会立刻触发）→ disable → delete；
  p2p status / address / name / peers / qrcode；data-stats + export-data（真实 982 字节、`app=CodeCraft`）；
  用户 create → update（`password` 空串不改）→ 只传 `id+status` 的安全开关（其余字段未被清空）→ delete → 删自己被拒（400）；
  菜单树 15 条 / 角色 2 个 / 角色菜单 15 项。探针数据全部清理。
- CDP 真实浏览器 **25/25 PASS**：5 个页面标题与真实数据渲染（端口 9527 + IPv6、总记录 6031、admin 1 条、
  菜单 12 组 / 15 勾选项且 admin 已回填 15 项）；「我的二维码」抽屉打开即现算 `data:image`（1442 字符）。
- 端到端 **13/13 PASS**：定时任务 UI 新建 → 行渲染「周期 · 每天 09:00 · `0 0 9 * * ?` · 默认 Agent」→
  删除确认框 → 列表归零（探针数据已清理）；用户 / 菜单新增抽屉的必填校验。

**这批补的 2 个体验缺口**

- `/p2p` 的「我的二维码」原先只在点「刷新」时才请求，现改为每次打开抽屉都现算（二维码含时效信息）。
- `UserEditDrawer` / `MenuEditDrawer` 的保存按钮原先没有 disabled 校验（点得动但内部静默 `return`，用户看不出差什么），
  现与 `submit()` 同源前移为 `:disabled="!canSave"`。

**第四批已知项**

- 用户列表的角色 / 状态筛选仍是对「当前页」本地过滤（后端 `/user/list` 只支持 `search` 参数），页面文案已标注「本页」。
- 「立即执行」~~受后端接口缺失限制，只做了置灰 + 说明~~ → L9 已补接口并接线（见 L9 章节）。
- `/data` 的「重置所有数据」「导入备份」属破坏性操作，未在真实数据上执行；统计与导出已实测。
- 定时任务的新建会真实进入调度队列（保存后即 `ENABLED`），故端到端测试用了临时任务并当场删除。

**L4 第五批（聊天页）**

聊天页是最后一块，涉及 SSE 流式、工具调用、审批回执、会话管理。落地方案：

- **新增 `src/utils/chat-mapper.ts`**：把「后端数据 / SSE 事件」翻译成组件模型，App.vue 只管时序
  - `toChatMessages()`：先 `processMessageGroups()` 把分段消息合并成「一条 AI 消息」，再映射成
    `ChatMessageModel`（思考 → 过程折叠，工具结果 → 可展开工具卡）
  - `parseTaskManagerOutput()`：解析 `task_manager` 输出尾部的 `---TASK_JSON---` 数组 → `TaskItem[]`
  - `toApprovalPayload()`：`ask_user` 事件 → 审批卡片（按 askType 决定标题与按钮语义）
- **`ChatApproval` 支持三种 askType**：`permission`（同意/全部同意/拒绝 + 可选自定义指令）、
  `clarification`（必答文本框 + 跳过）、`iteration_confirm`（继续执行/结束任务）
- **`ChatView` 增强**：概览条的文件改动片段改为可选（快照接口未接时自动隐藏）、
  暴露 `getDraft/setDraft`（AI 优化提示词回填）、新增 `load-more` 事件（滚动到顶部拉更早历史）、
  自动贴底改为「尾部消息变化才贴底」（避免加载历史时把人拽到底部）
- **新增 `ChatModelPicker.vue`**：模型 / 思考模式 / 执行模式三段选择，应用后
  `PUT /agent-configs/{id}/runtime` 持久化到当前 Agent
- **消息正文保留换行**：`.cc-msg-ai__body` 补 `white-space: pre-wrap`（v3 未接 markdown 渲染，
  纯文本渲染下多段回答原会挤成一行）
- **会话管理**：侧栏列表按 `updatedAt` 分「今天/昨天/更早」；切会话按轮次游标分页（`beforeId`）；
  「更多」只提供删除（重命名需要输入弹窗，v3 未内置）
- **后台任务重连**：进入会话先 `GET /deepseek/task/{id}`；运行中则带 `cursor=seq` 订阅增量事件流
  （历史内容由 DB 兜底，避免重复渲染）；有待审批问题则恢复审批卡片
- **附件两条通道**：文档走 `POST /deepseek/upload` → `attachmentIds`（LLM 用 `chat_attachment` 工具读）；
  粘贴的截图走 `POST /api/files` → `fileAssetIds`（vision 内容块）
- **`sse-client` 修正**：401 分支原先 `router.push('/login')`，v3 无该路由 → 改为只清 token 由调用方提示

**第五批已知项**

- 历史消息里的「文件改动统计」需要快照接口（`matchSnapshotsForMessages` / `loadSessionChanges`），
  本轮未接 → 概览条只显示工具调用数与上下文 token。
- 历史消息里的 `task_manager` 任务清单不恢复（历史映射时工具详情会被截断，尾部 JSON 可能被截掉），
  任务面板只反映本轮实时输出。
- 会话重命名未做（`PUT /conversation/{id}?name=` 已可用，缺输入弹窗）。
- 子 Agent 事件（`agent_event`）与委托进度（`delegate_progress`）当前忽略不渲染，未做专属卡片。
- `askType=iteration_confirm` 的「继续执行」固定按默认扩容次数提交（旧项目可选 10/20/50）。
- 实测数据：会话列表 1 条（1228 条消息的超长会话），历史渲染成 11 条消息（6 AI / 5 用户，617 个工具调用），
  发送「只回复两个字：收到」后 SSE 正常返回「收到！」。

**已知项（历史遗留）**

- 智能体真实数据只有 1 条（内置「圆圆」）；卡片「更多 → 设为默认 / 删除」已用临时数据在真实浏览器 + 真实后端跑通（数据已清理），
  工作目录选择器已实测。**技能 / MCP 的新建 → 列表 → 删除已端到端实测（22/22）**；
  智能体的新建 / 编辑保存仍由接口探针覆盖，浏览器侧留待后续页面接线时一起回归。
- `tail` 模式的行号是展示序号（后端不返回行号），只有搜索模式是真实文件行号 —— 已在 `LogRow.vue` 注释说明。
- 当前后端只有 **1 个启用 Provider（deepseek）且 `modelList` 只配了 `deepseek-flash`**，
  因此模型下拉实际只有一个选项 —— 联动逻辑正确，但观感上体现不出「切 Provider 换模型」。
  想看完整效果需先到「模型配置」页给各 Provider 补齐 `modelList`。

### L4 第六批：外观设置（已完成）

`/appearance` 是最后一个有待接接口的页面，本轮把它与「全局外观生效链路」一起补齐。

**做了什么**

| 位置 | 改动 |
|------|------|
| `App.vue` | 外观状态由本地 `ref` 改为 `settingsStore` 的双向 `computed`（`theme` / `bgImageUrl` / `bgOpacity` / `bgBlur` / `windowOpacity`）；`onLookUploadBg` 改调 `uploadBackground(file)`（含图片类型 + 10MB 校验、上传中互斥、成功/失败 toast）；`onLookRemoveBg` / `onLookReset` 走 store |
| `views/AppearanceView.vue` | 新增 `uploading` prop（上传中按钮禁用 + 文案「上传中…」）；类型/大小校验统一交给父级（避免两处口径不一致） |
| `layouts/AppShell.vue` | 主题由本地 `ref` + `localStorage['codecraft-theme']` 改为读 `settingsStore.theme`（`light`/`dark`/`auto`，`auto` 监听 `prefers-color-scheme`）；新增 `.cc-app-bg` 背景层（图片 + 不透明度 + 模糊）；`setup` 阶段即同步 `data-theme`，避免深色用户刷新闪白 |
| `assets/app.css` | 新增第 37 节「窗口背景层」（`.cc-app-bg`、`.cc-shell.has-bg .cc-main { background: transparent }`），响应式顺延为第 38 节 |

**为什么外观要改 `AppShell`**：设置项此前只写进 `App.vue` 的本地 `ref`，改完既不影响 `<html data-theme>` 也不会渲染背景图 ——
「设置页调好了但界面没变」。现在设置页写 `settingsStore`、`AppShell` 读同一个 store，改完立刻生效；刷新后由
`pinia-plugin-persistedstate` 从 `localStorage` 恢复。

**验证（真实后端 8084）**

```
接口探针（_probe_look.cjs，7/7）
  POST /api/appearance/background           → 200，返回 /api/appearance/file/<uuid>.png
  GET  /api/appearance/file/<name>          → 200，image/png，回读字节与上传一致（免鉴权）
  上传 text/plain                           → body.code=400「不支持的图片格式」
  （注意：后端 @ExceptionHandler 返回 HTTP 200 + body.code=400，断言要看 body.code）

浏览器 CDP（28/28）
  16 个路由逐页渲染                         → 标题/关键节点全部命中，无「该页面待实现」兜底
  load 下沉生效                             → /agents 1 张卡（圆圆）、/logs 12 行、/lessons 84 条、/providers 1 行（deepseek）
  点「深色」                                → <html data-theme> light → dark；点「浅色」→ light
  构造 File 触发上传                        → .cc-app-bg 出现，background-image 指向 /api/appearance/file/*，
                                              localStorage.settings.bgImageUrl 同步
  有背景图时主区                            → .cc-main 背景 rgba(0,0,0,0)（不再遮住背景层）
  移除背景图                                → 背景层消失，卡片回到「尚未设置背景图」
  聊天页                                    → 动态组件 ref 正常，输入框在
  运行期异常                                → 0
```

---

### L5 收尾：`<router-view>` + 加载下沉（已完成）

**做了什么**

1. **页面切换改 `<router-view>`**：`App.vue` 模板里 16 个 `v-if="activeKey === '/xxx'"` 分支（约 290 行）替换为一个
   `<router-view v-slot="{ Component }">` + `<component :is="Component" v-bind="pageView.props" v-on="pageView.on">`。
2. **删除 16 个 `views/*` 静态 import**：只保留 `import type`（类型导入编译后擦除，不参与运行时）。
   这是 `INEFFECTIVE_DYNAMIC_IMPORT` 的根因 —— 同一个模块既被 `router` 动态 import、又被 `App.vue` 静态 import 时，
   打包器只能把它留在主 chunk，懒加载失效。**改造后警告从 16 条降到 0 条**，构建产物已按页分包
   （`ChatView-*.js` 16.6 kB、`ProviderListView-*.js` 27.5 kB …）。
3. **新增 `PAGE_BINDINGS`（`pageView` computed）**：按 `route.path` 返回该页的 `props` + `on` + `load`。
   放在 `App.vue` 而不是逐页容器组件，是因为数据仍集中在 `App.vue`；`computed` 里 `switch` 只求值命中的分支，
   未访问的 ref 不建立依赖，切页互不触发重算。
4. **加载下沉**：删除 `PAGE_LOADERS` 表与 `watch(route.path)`，改为 16 个 View 各自
   `onMounted(() => props.load?.())`。页面现在随路由真实挂载/卸载，`onMounted` 才真正表达「进入这一页」；
   每个 `load` 内部保留「已加载 / 加载中 / 已失败」三重跳过。
5. **顺带修掉最后 1 条 `INEFFECTIVE_DYNAMIC_IMPORT`**：`api/chat.ts`（2 处）与 `utils/sse-client.ts`（1 处）
   对 `http-client` 的动态 import 并无循环依赖（`http-client` 只依赖 `md5`），改为静态导入。

**踩到的坑（关键）**

> `<component v-bind="obj">` 的对象形式**不会**自动解包 ref。模板的自动解包只覆盖 `:prop="someRef"` 这种单值写法。
> 直接把 `{ agents: agentList }` 传下去，页面组件收到的是 `Ref` 对象，首屏即报
> `props.agents.filter is not a function`。
> 解法：新增 `unwrapProps()`，在同一个 computed 内逐个读 `.value` 再返回普通对象 ——
> 既解包，又让 computed 正确依赖到每个 ref 的值变化（若把解包放到第二层 computed，第一层只持有 ref 对象、
> 从未读 `.value`，值变化时不会重算，props 会永久停在首屏值）。

**验证**

```
typecheck            → 0 error（含故意探针校验）
build                → 182 modules / ~640ms，**0 条 INEFFECTIVE_DYNAMIC_IMPORT**（改造前 16 条）
check:api            → A=0 / B=14（B 均为有意直连：SSE、下载、预览 blob、静态文件）
浏览器 CDP           → 16 页渲染 + 4 页真实数据 + 外观全链路 28/28 PASS
```

**边界说明（后续可选）**：本轮只下沉「加载时机」，页面 state 与事件处理仍集中在 `App.vue`
（通过 `PAGE_BINDINGS` 注入）。彻底下沉需为每页建独立容器组件，把对应 `ref` + `handler` 一起搬走，
属可选重构 —— 收益是 `App.vue` 瘦身，成本是 16 页逐个回归。

**剩余可选增强**：会话重命名（✅ 本轮已完成）、历史任务清单恢复、快照改动统计、子 Agent 事件卡片。

---

### L6 聊天页渲染增强（已完成）

用户反馈的 6 个问题一次收口，同时补齐了旧项目里被漏掉的聊天体验。

#### 1. 思考与工具调用彻底分离

**问题**：`chat-mapper.ts` 与流式逻辑把同一次工具调用**同时**写进 `process`（思考折叠区）和 `tools`（工具卡片），
一条长会话折叠区里堆着几十上百条工具输出，既重复又不可读。

**改法**：
- `process` 只承载思考文本（且**不再截断到 240 字**，展开即看完整推理；高度由 CSS `max-height: 320px` 控制）；
- 工具调用独立成 `.cc-tools` 区域：**默认折叠**成一行汇总（「94 个工具调用」），
  展开后是紧凑的 tool-card 列表，单条再展开看参数/结果；
- `App.vue` 流式分支同步删除 `processList.push({ tag: name, ... })`。

#### 2. Markdown 渲染 + 代码高亮 + diff 对比

**问题**：`ChatMessage.vue` 一直用 `{{ content }}` 纯文本输出，`contentHtml` 字段从没被填过，
LLM 输出的 `##`、`**`、表格、代码块全部原样裸露。

**改法**：
- 新增 `src/utils/markdown.ts`（`markdown-it` + `highlight.js` 按需注册 28 种语言 + `markdown-it-container`）：
  - `renderMarkdown`：完整版，含代码高亮；
  - `renderMarkdownLight`：流式版，跳过 highlight.js（最贵的一步），收尾再由完整版补齐；
  - 自定义 fence：`filelist`（文件修改清单卡片）、`cmd`/`terminal`（命令执行卡片）、其余走 hljs；
  - 外链统一 `target="_blank" rel="noopener noreferrer"`；`html: false` 防 XSS；
- `ChatMessage.vue` 正文改 `v-html="renderedContent"`（优先 `contentHtml`，缺失时组件内兜底渲染）；
- `App.vue` 流式期间每 80ms 用轻量版渲染一次，`finally` 用完整版渲染收尾；
- 历史消息在 `toChatMessages()` 映射时一次性渲染完整版；
- 新增 `src/utils/markdown.ts` 的 **diff 行级着色**（`.hljs-addition/.hljs-deletion` 整行底色）、
  表格、引用、提示容器、行内代码全套样式（`app.css` 第 38 节）。

> 体积代价：主包 330KB → 396KB（gzip +约 30KB），换来完整的代码高亮与表格渲染。

#### 3. 消息里的文件路径 / 网页地址可点击

- 本地路径（`C:\proj\src\App.vue`、`src/utils/markdown.ts`）在 markdown 里被识别成 `.cc-file-link` 芯片，
  点击走 `ChatView` 的点击委托：`/project/resolve` 按后缀解析 → 拼绝对路径 → `/project/read` 读取 →
  在右侧「文件」抽屉内预览；图片路径（`.png/.jpg/...`）走 `/project/raw` + blob URL 预览；
- 解析不到时明确提示「未找到该文件（当前工作目录：…）」，而不是返回空白；
- 顺带修掉一个静默失败：`/project/read` 对不存在的文件返回 Spring 错误页（无 `code/message` 字段），
  原代码直接取 `res.data` 导致空白预览且无提示 —— 现在显式校验 `code === 200 && typeof data === 'string'`。

#### 4. 会话选中态跟随页面

**问题**：`AppSidebar` 的 `is-active` 只比对 `conv.id === activeConvId`，切到管理页后会话仍高亮，
视觉上像是「还停在对话里」。

**改法**：新增 `inChat` 判定（`activeKey === '/chat'`），非聊天页不高亮；
点击任意会话时若不在聊天页，`onSelectConversation` 先 `router.push('/chat')` 再加载消息；
`onNewChat` 同样先回聊天页。

#### 5. 会话重命名

**问题**：`onConversationMore` 里写着「重命名需要输入弹窗，v3 未内置」，后端 `PUT /api/conversation/{id}?name=`
与 `api/conversation.ts` 的 `updateConversationName()` 其实早已就绪。

**改法**：
- 新增 `src/utils/prompt.ts` + `src/components/PromptHost.vue`（Promise 化输入弹窗，Enter 确认 / ESC 取消 /
  自动聚焦全选 / 空值与 60 字上限校验）；
- 侧栏会话「更多」改为下拉菜单（重命名 / 删除），`position: fixed` 定位避免被 `.cc-conv-list` 的
  `overflow` 裁剪，滚动时自动收起；
- `AppSidebar` → `AppShell` → `App.vue` 补齐 `rename-conv` / `delete-conv` 事件透传。

#### 6. 滚动体验：流式跟随 + 一键到底

- `atBottom`（距底 80px 内视为「在看最新」）与 `follow`（是否跟随最新输出）双状态；
- 流式增量只在「跟随态」贴底；用户上滑离开底部后不再把视图拽回，并出现浮动「回到最新」按钮；
- 详见下方 **L7**：跟随逻辑已升级为「正文 / 思考过程 / 工具调用组任一增长都跟随」，
  并修掉了「一次滚动追不平 markdown 节流渲染」导致稳定差几十像素的问题。

#### 验证

```
typecheck            → 0 error
build                → 293 modules / ~710ms（markdown + hljs 引入）
markdown 单测        → 19/19（标题/列表/表格/代码高亮/diff/filelist/cmd/容器/XSS 转义/light 无高亮）
浏览器 CDP（Vue）    → 23/23（markdown、工具区 94 个调用折叠、一键到底、切页无选中、点会话跳回、重命名端到端）
浏览器 CDP（Vue）    → 8/8 + 文件预览（5719 字符）/ 不存在文件报错 / 流式增量与光标
浏览器 CDP（preview）→ 13/13（markdown 结构、hljs、文件清单、工具折叠、切页取消高亮、重命名）
静态自检             → 23/23（JS 语法、CSS 括号 1094/1094、标签配对、cc- 类覆盖 545 个）
```

> ⚠️ **后端遗留任务提示**：当前会话（id=2）在数据库里有一条 `active=true, status=running` 的历史任务，
> 进入该会话会一直显示「停止生成」并订阅事件流。这是旧项目遗留的僵尸任务，与本次改动无关；
> 新建会话即可正常发送。如需清理可调 `POST /api/deepseek/task/{id}/cancel`。

> ⚠️ **后端改动需要重启才生效**：本轮的 4 处后端修复（`McpServerMapper.update` 动态 SQL、
> `SkillController` 的 `agentType` 兜底与作用域补写）已在临时实例（**8085 + 内存 H2**）验证通过，
> 但正在运行的 8084 仍是旧代码 —— 重启后才能让「编辑 MCP 保留请求头」「技能作用域可改 / 创建成功」生效。
> 启动临时实例的做法：`mvn -DskipFrontend=true spring-boot:run "-Dspring-boot.run.arguments=--server.port=8085 --spring.datasource.url=jdbc:h2:mem:probe;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"`

### L7 聊天页滚动跟随与内容区折叠（已完成）

针对三条反馈的增量修复：① 流式输出要自动往下跟，除非用户手动上滑；② 工具调用组默认折叠；③ 展开思考过程 / 工具调用组后，滚动条也要保持在最底部。

#### 1. 流式跟随：三处缺口一起补

**原实现为什么跟不住**：
1. 尾部消息指纹**只看 `content` 长度** —— 思考阶段正文还是空的，watch 根本不触发；
2. markdown 是 80ms 节流渲染 + 收尾高亮，`nextTick` 之后容器还会继续撑高，**一次 `scrollTo` 稳定差几十~上百像素**，累积后彻底「脱底」；
3. 只有几何判定（距底 80px），用户轻微上滑会被反复拽回。

**改法**（`src/views/ChatView.vue`）：
- 尾部指纹改为 `content 长度 / 思考文本长度 / 工具数量 / 消息数` 四元组，思考与工具阶段同样参与贴底；
- `scrollToBottom()` 增加 **rAF 递归校正**（最多 3 帧），追平节流渲染造成的二次撑高；
- 新增 `ResizeObserver` 观察 `.cc-stream__inner`，覆盖「不经过数据、只由渲染引起」的高度变化（代码高亮、图片、表格）；
- 流式期间叠加 **120ms 低频兜底**（仅在跟随态且确实落后时才写 `scrollTop`）；手指按住滚动时让位（`touching`）；
- `follow`（跟随意图）与 `atBottom`（几何位置）分离；`wheel` / `touchmove` 捕获「手动上滑」并给出 1.2s 豁免；
  滚回真正贴底（<8px）才恢复跟随。

#### 2. 展开思考过程 / 工具调用组，滚动条同样保持最底部

这两个区域承载的正是「最新进展」，展开后内容继续增长时走同一条跟随链路（指纹 + ResizeObserver + 兜底）。
另外：展开动作本身若处于跟随态，会把视口平滑带到最新内容；**若用户此前已手动上滑，则不强行拽回**（尊重浏览位置）。

实现：`ChatMessage.vue` 新增 `expand` 事件（思考区 / 工具组 / 单条 tool-card 展开时触发）→ `ChatView.onMessageExpand()` 按 `follow` 决定是否贴底。

#### 3. 工具调用组默认折叠

原策略「≤3 个铺开、>3 个折叠」改为**一律默认折叠**（工具调用属过程信息，正文才是结果）。
首次出现工具时保持折叠，之后保留用户手动展开的选择，不被流式更新覆盖；`preview.html` 同步移除默认 `is-open`。

#### 4. 顺带修掉的两个真实缺陷

- **新会话在侧栏消失**：`loadConversations()` 原本「加载中直接 return」，首轮结束后触发的那次刷新被丢弃 →
  改为**并发调用复用同一次请求**（`conversationsInFlight`）。实测新会话 `agentConfigId=1` 能稳定出现在侧栏。
- **会话可能建成「无归属」**：`onSend()` 若在 Agent 列表未加载完时发送，`agentConfigId` 会缺失，
  之后按 Agent 过滤的侧栏就看不到它 → 发送前补 `if (!currentAgentId.value) await loadAgents()`。
- 侧栏会话项补 `:data-conv-id`（便于定位 / 自动化，无视觉影响）。

#### 验证

```
typecheck            → 0 error
build                → 293 modules / ~724ms / 0 警告
浏览器 CDP（Vue）    → 29/29
  · 生成中自动贴底（gap < 80，实测贴到 0）；用户上滑后 gap 保持 > 80 不被拽回；「回到最新」回到底部
  · 用户已上滑时展开思考/工具组不拽回；跟随态下展开保持贴底
  · 切页返回续接同一段会话：AI 消息仍 1 条 / 用户 1 条 / 正文 1678 字，未单开新段
  · 清理后仅剩真实会话「帮我去网上找」(id=2)，名称未被改动
浏览器 CDP（preview）→ 5/5（工具组与思考过程默认折叠、点击展开、再次点击收起）
```

> 验证脚本对真实会话做了硬保护：只操作自己创建的临时会话，按 `data-conv-id` 精确定位，
> 绝不点击、绝不删除既有真实会话；结束后核对「仅剩该会话且名称未变」。

### L8 聊天页能力补齐（已完成）

把聊天页从「能对话」补到「能干活」：删掉两个纯装饰按钮，补齐会话搜索 / 余额 / 导出、右侧抽屉三件套（文件树 + Git + 终端）、改动回滚，以及任务看板的会话隔离。

#### 1. 删除两个装饰按钮

`ChatView` 头部的放大镜（搜索会话）与三个点（更多操作）**没有任何 `@click`**，是从 v2 骨架带过来的占位。
它们承诺的能力已经各有归属，留着只会误导：

| 原按钮 | 能力归属 |
| --- | --- |
| 放大镜「搜索会话」 | 侧栏会话搜索框（见第 2 点） |
| 三个点「更多操作」 | 侧栏会话项菜单（重命名 / 导出 / 删除） |

同时删掉只用于分隔这两个按钮的 `.cc-sep-v`，`preview.html` 同步移除。

#### 2. 会话搜索（侧栏本地过滤）

`AppSidebar` 新增搜索框，对已加载的会话列表做**本地过滤**（会话按 Agent 拉取，量级有限，无需后端接口）：

- 关键词命中为空的**分组标题一并剔除**（否则会剩下「今天 / 昨天 / 更早」三个空标题，看起来像列表坏了）；
- 搜索无结果时显示「没有匹配「xxx」的会话」，与「暂无会话」空态区分；
- `ESC` 或点清空按钮复位。

#### 3. 余额显示接真实接口

`ChatComposer` 早就有 `balance` 插槽位，但 `App.vue` 的 `conversation` computed 从未传值，所以那一行永远不出现。

- 新增 `chatProviderCode`（Agent 显式 `providerCode` → `isDefault` 项 → 列表第一项，与后端 `LLMClientManager` 兜底顺序一致）；
- `loadChatBalance()` 走 `GET /api/llm-providers/{code}/balance`，把 `ProviderBalanceVO` 压成一行小字：
  金额类取 `totalBalance`（火山另给 `cashBalance`），额度类取剩余百分比 / 剩余次数，**取不到就返回空串让模板隐藏**（绝不显示「余额 --」这种占位噪音）；
- **静默失败**：不弹 toast、不写错误条 —— 否则没配 AK/SK 的 Provider 每次进聊天页都会蹦错误提示。

实测：`上下文 6.3K · 余额 ¥9.65`（DeepSeek 真实余额）。

#### 4. 会话导出（Markdown）

会话菜单从「重命名 / 删除」扩为「重命名 / 导出 Markdown / 删除」。

- 走 `GET /api/conversation/{id}/messages`（全量，不是 `/page` 的最近 5 轮），再用与聊天页同一套 `processMessageGroups` 聚合轮次；
- 只导出**正文与思考过程**（思考放进 `<details>` 折叠块）：工具调用原始返回体量极大（单个会话可上千条），塞进 Markdown 既不可读也会让浏览器卡住；
- 文件名过滤 Windows 非法字符（否则 `a[download]` 会被浏览器改名成 `download`）。

#### 5. 右侧抽屉三件套（文件树 / Git / 终端）

原先三个 tab 都是「面板（待接入）」占位，现在各接一个真实组件，均以**当前 Agent 工作目录**为根
（文件面板可临时切到任意目录；Git / 终端始终用 Agent 工作目录 + 抽屉内 `cd`）：

| 组件 | 接口 | 说明 |
| --- | --- | --- |
| `ProjectFileTree.vue` | `GET /project/children`、`GET /project/read`、`POST /project/write` | 目录懒加载 + 缓存；树是「扁平渲染 + 展开集合」而非递归组件；点文本文件在下半区打开编辑器，可保存（带「未保存」标记）。**点顶部路径条可切到任意目录**（`DirectoryPicker`），与 Agent 目录不一致时出现「跟随 Agent」按钮 |
| `GitChangePanel.vue` | `GET /git/status`、`/git/diff`、`/git/branches`、`/git/tags`、`/git/stash` + `POST /git/add|unstage|restore|commit|pull|push|fetch|stash*|branch/*|merge|tag*` | **完整 Git 客户端**：远程同步（拉取 / 推送 / 获取）、暂存区（全部暂存 / 全部取消暂存 / 储藏变更 / 逐文件勾选 / 撤销单个文件改动）、折叠区（储藏记录 / 分支 / 标签，各带增删改与推送）、提交区（含「N 个文件已暂存」提示）。diff 按行着色（+ 绿 / - 红 / @@ 蓝），点文件名就地展开 |
| `TerminalPanel.vue` | `POST /project/exec` | **xterm.js 真终端**（`@xterm/xterm` + `addon-fit`，按需异步加载）：整块终端屏 + 目录提示符 + 行内回显，Enter 执行、Backspace 退格、Ctrl+C 中断、Ctrl+L 清屏、↑↓ 翻历史（落 localStorage）、`cd` 本地切换目录（支持 `cd` / `cd ..` / 绝对路径 / 相对路径）、`clear`/`cls` 清屏；主题跟随「外观设置」实时换色 |

> **关于终端形态**：L8 曾判断「后端 `/project/exec` 无 PTY、xterm 能力用不上」而做成「输入框 + 输出块」，
> 但那样的观感与真实终端差距明显。L9 改用 xterm 做**渲染层 + 行编辑器**（命令仍是一次性执行、输出直接打印到同一块屏），
> 既保留真实终端的外观与操作习惯（回显 / 历史 / 清屏 / 框选复制 / 滚轮回看），也不依赖 PTY。
> 依赖约 337KB（gzip 86KB），通过 `defineAsyncComponent` 只在第一次点「终端」时加载，主 chunk 未变大。

> **timeout 单位坑（已踩）**：`ProjectBuildController.exec` 的 `timeout` 参数单位是**毫秒**，不传默认 30s。
> 首次实现传了 `60`，命令 **60ms** 就被判超时（返回 `timedOut=true / exitCode=1 / 空输出`，42ms 返回），
> 排查时极易误判成「命令不支持」。现已改为不传（用后端默认），并在 `api/project.ts` 注释里写明。

#### 6. 改动回滚（快照 + 逐文件 + 全量）

新增 `ChangeReview.vue`（居中浮层，`z-index: 83`，基础态 `display:none`，靠 `.is-open` 显示）。

入口：概览条的「N 个文件改动 +A -D」由纯文字改为可点击按钮（`.cc-overview__link`，hover 变强调色，视觉上仍像一段文字）。

- **数据源**：概览条统计来自 `GET /api/snapshots/session-changes`（`totalFiles / totalLinesAdded / totalLinesDeleted`），
  原来 `changedFiles / added / deleted` 一直是 `undefined` 所以概览条那段从不渲染；
- **按文件**：`session-changes` 的文件列表，逐行「回滚」走 `POST /snapshots/rollback-file`；
- **按快照**：`GET /snapshots/list` 列表 → 点搜索图标 `GET /snapshots/preview` 看这个快照包含哪些文件（还原 / 删除两种动作）→ 回滚走 `POST /snapshots/rollback`；
- 全部回滚走 `POST /snapshots/rollback-session`，破坏性操作统一二次确认 + 红色实心按钮；
- 这些 api 函数内部吞掉了异常（失败返回 `false` / `null`），所以成功与否只看返回值，失败统一 toast，**不做乐观更新**。

实测：会话 id=2 读到 **67 个文件改动 / +6617 / -512**，面板 67 行正常渲染。

#### 7. 任务看板：按会话隔离 + 历史回放 + 中断提示

原实现 `tasks = ref<TaskItem[]>([])` 是**全局单 ref**：切到另一个会话会看到上一个会话的任务进度，刷新页面清单直接丢。

- 改为 `taskMap: Record<会话id, TaskItem[]>` + `tasks = computed(当前会话)`；
- **历史回放**：任务清单没有独立落库，只存在于工具输出的 `---TASK_JSON---` 段落里。
  加载消息后倒序找最近一条能解析出清单的消息（搜索范围含 `content` / 思考过程 / 工具详情三个通道），找到就停
  （更早的清单已被后续调用覆盖，回放它只会显示过时进度）；
- **中断提示**：`checkActiveTask` 的 `lastTask` 原被完全忽略。现在若 `active=false` 且 `lastTask.status !== 'COMPLETED'`，
  用 toast 告知原因 —— 覆盖「关掉浏览器再回来，只看到 AI 没回复完」的场景。

#### 验证

```
typecheck            → 0 error
build                → 303 modules / 751ms / 0 警告（CSS 113.14 kB / JS 424.95 kB）
preview.html 自检    → 17/17（内联 JS 语法、标签配对 div 1113 / svg 370 / section 23、新增 id 与函数、装饰按钮已删）
浏览器 CDP（批次 1） → 13/13
  · header 无装饰图标按钮（titles=[]）、工具组 3 个、无分隔竖线
  · 侧栏搜索框存在；过滤后会话项 1 → 0；清空恢复 1；空态文案「没有匹配「zzz-not-exist-key」的会话」
  · 会话菜单 3 项：重命名 / 导出 Markdown / 删除
  · 文件抽屉渲染文件树 32 行；Git 抽屉分支 master；终端抽屉有输入框
  · 输入区余额真实渲染：上下文 6.3K · 余额 ¥9.65
浏览器 CDP（批次 2） → 9/9
  · 概览条「67 个文件改动 +6617 -512」可点击
  · 改动面板打开 → 67 行 / 统计「67 个文件 +6617 -512」/ 两个视图（按文件、按快照）/ 全量回滚按钮存在
  · 面板可关闭
  · 终端执行 `echo cc-term-ok` → 拿到真实输出 `cc-term-ok`，无超时错误
```

> **TDZ 教训**：`watch(chatProviderCode, ...)` 最初写在 `agentList` 声明之前。
> `watch` 注册时会**立即求值一次 getter**，而 `chatProviderCode` 依赖 `currentAgent → agentList`，
> 于是 setup 直接抛 `ReferenceError: Cannot access 'agentList' before initialization`，**整个应用白屏**。
> `typecheck` 抓不到这类运行时顺序问题，只有真实浏览器能暴露 —— 这也是「每轮必须跑 CDP」的原因。
> 修复方式：把 watch 移到所有被依赖状态之后（现位于 `PAGE_BINDINGS` 之前），并在注释里写明「必须放在依赖之后」。

### L9 深度补齐（定时任务立即执行 / 文件切目录 / Git 完整客户端 / xterm 真终端）

L8 把三件套与定时任务接通后，四处「半成品」被用户点名：立即执行按钮置灰、文件面板锁死 Agent 目录、
Git 只有最简闭环、终端是「输入框 + 输出块」。本轮逐个补齐。

#### 1. 定时任务「立即执行」——补后端接口

原 `ScheduleTaskController` 只有 list/create/update/delete/enable/disable，前端按钮只能置灰。本轮加：

```
POST /api/schedule-task/run/{id}   →  data = 新建的会话 id
```

- `ScheduleTaskScheduler.runNow(taskId)` 复用「建会话 → 异步跑指令」流程（与 `executeTask` 同构）；
- **关键差异：不改 `status` 与 `execute_time`** —— 手动执行属于临时插队，若沿用定时触发那套收尾逻辑，
  cron 任务会被算成「已到期」、一次性任务会被直接禁用；
- 执行统计（`execute_count` / `last_execute_time` / `last_conversation_id`）照常累加，
  所以行上的「已执行 N 次」「查看会话」立刻可用；
- 前端 `onScheduleRun` 用 loading toast 承接，成功后刷新列表；行内按钮按 `runBusyId` 单独转 loading（防重复触发）；
- 顺手把「查看会话」接上：原本只弹 toast 说「聊天页尚未接线」，现在直接 `onSelectConversation` 跳过去。

实测（8085 临时实例 + 内存库，避免与 8084 文件库抢锁）：`run` 返回 `conversationId=1`，
`executeCount 0 → 1`、`status` 仍 `ENABLED`、`executeTime` 仍为空，探针数据已清理。

#### 2. 文件面板可切换浏览目录

`ProjectFileTree` 的根目录原本写死 `props.root`（Agent 工作目录）。现在：

- 顶部路径条从 `<span>` 改成按钮（`.cc-ftree__root.is-btn`），点击打开 `DirectoryPicker`
  （复用智能体「工作目录」那个组件，新增 `title` prop 让标题显示「选择浏览的目录」）；
- 新增本地 `localRoot`：选中即切根目录并清空目录缓存 / 展开集合 / 编辑器；
- 与 Agent 目录不一致时出现「跟随 Agent」虚线小按钮，一键退回；
- `watch(props.root)` 仍会重置回 Agent 目录（换 Agent 时不该停留在上个 Agent 的目录）。

#### 3. Git 面板补全为完整客户端

对照旧项目 `GitSidebar.vue` 的能力清单逐项补齐（接口早已齐备，缺的是 UI）：

| 区域 | 能力 |
| --- | --- |
| 分支行 | 当前分支（点击展开分支面板）+ 刷新（旋转态） |
| 远程同步 | 拉取 / 推送 / 获取（`gitPull` / `gitPush` / `gitFetch`，逐个 loading 文案） |
| 暂存区 | 全部暂存 / 全部取消暂存 / 储藏变更 |
| 变更列表 | 复选框（勾选 = 已暂存）+ 单字母状态徽章（M/A/U，中文 label 挪到 `title`）+ 文件名（点击看 diff）+ 撤销该文件改动（二次确认） |
| 折叠区 | 储藏记录（弹出 / 删除）、分支（新建并切换 / 切换 / 合并 / 删除）、标签（创建 / 推送 / 删除） |
| 提交区 | 提交信息 + 「N 个文件已暂存」提示；无暂存文件时提交按钮禁用 |

工程细节：

- **`anyBusy` 全局互斥**：git 命令并发会抢 `index.lock`，所以任一操作在飞时禁用其余按钮；
- 折叠区**展开时才拉数据**（`toggleSection` → `loadStashes` / `loadBranches` / `loadTags`），不拖慢首屏；
- 储藏序号从 `stash@{n}` 正则提取（拿不到按最新 0 处理）；
- 抽屉宽度 340 → **400px**（三列按钮 + 折叠区在 340px 下会挤），仅聊天页这个抽屉改。

#### 4. 终端改成 xterm 真终端

见前文「关于终端形态」：整块终端屏、目录提示符、行内回显、历史 / 清屏 / `cd` 本地处理，
按主题（light / dark / auto）实时换色。

两个踩过的坑：

- **`.cc-term` 类名撞车**：第 14 节早有 `.cc-term`（静态预览里的终端示意块，`background:#14131B` + `padding` +
  `height:100%`）。新组件复用同名类后，那套深色外壳被一起套上，终端外面出现一圈粗黑边。
  改为 `.cc-xterm` 后正常 —— **新增组件类名前先全局搜一遍**；
- **xterm 的 viewport 背景不走主题**：`@xterm/xterm/css/xterm.css` 把 `.xterm-viewport` 写死 `background:#000`，
  亮色主题下整块屏仍是黑的。用 `.cc-xterm__screen .xterm-viewport` + `html:not([data-theme='dark'])` 双规则接管；
  另外挂载后主动 `applyTheme()` 一次，避免 store 恢复 / 系统偏好求值的时序差导致「外壳浅色、终端深色」。

#### 验证

```
后端 test-compile     → 通过（新增 runNow + /run/{id}）
接口探针（8085 内存库） → 11/11（run 返回会话 id、统计累加、状态与下次执行时间不变、探针数据清理）
typecheck             → 0 error
build                 → 306 modules / 811ms / 0 警告
                       主 chunk 428.20 kB（gzip 152.65 kB）
                       TerminalPanel chunk 337.29 kB（gzip 85.85 kB，按需加载）
浏览器 CDP（抽屉三件套） → 17/18（唯一 fail 是 8084 上定时任务表为空、没有行可点）
  · Git 面板：分支 master、6 个操作按钮、3 个折叠区、20 行变更、20 个复选框
  · 分支面板展开 → 拉到 master、有新建行；点文件名 → diff 41 行
  · 终端：`.xterm` + helper textarea 存在，执行 `echo cc-term-ok` → 输出 cc-term-ok
  · 文件面板：路径条为按钮、点开 DirectoryPicker（标题「选择浏览的目录」）、可关闭
浏览器 CDP（定时任务） → 5/5
  · 「立即执行」按钮 disabled=false；点击后实际发出 POST /api/schedule-task/run/{id}
```

> ⚠️ **8084 后端需重启**：本轮新增了 `POST /api/schedule-task/run/{id}`。
> 未重启前点「立即执行」会 404（前端已正确弹出失败 toast，不会静默）。
> 其余三项（文件切目录 / Git 完整客户端 / xterm 终端）只用到既有接口，无需重启。

### L10 运行状态栏：状态耗时 / 补充需求 / 任务与改动常驻入口（已完成）

对齐旧项目 `CodeAssistantView.vue` 底部工具条的三项能力。改造前 v3 聊天页存在三个「等于没有」的入口：

| 旧位置 | 问题 |
| --- | --- |
| 执行状态 + 耗时 | **完全没有**（旧项目有 `stream-indicator` + `streamStatus` + `formatElapsed`） |
| 「补充需求」 | **完全没有**（旧项目 `supplement-panel` → `POST /api/deepseek/supplement`） |
| 任务看板 | 内联在消息流**末尾**，长会话滚不到 |
| 改动 + 回滚 | 只在消息流**顶部**概览条，贴底时不可见 |

统一收成输入区上方一条常驻状态栏（`.cc-runwrap > .cc-runbar`）：

```
┌────────────────────────────────────────────────────────────┐
│ ● 正在输出…  1m48s  [💡 补充需求]        [任务 3/6] [改动 +6702 -512] │
└────────────────────────────────────────────────────────────┘
        ↑ 仅 isStreaming 时出现              ↑ 常驻，没有数据时不显示徽章
```

#### 1. 执行状态与耗时

- `ChatView.vue` 新增 `elapsedMs` / `runStartAt` / `elapsedTimer`，`watch(isStreaming)` 启停；
- `formatElapsed()` 与旧项目同规则：`Xs` / `Xm Ys` / `Xh Ym Zs`；
- `streamStatusText` 用启发式推断（v3 的 SSE 没有旧项目的 `streamStatus` 状态机）：
  有正文 → `正在输出…`；有工具 → `工具执行中…`；否则 → `思考分析中…`；
- 只在流式期间显示，结束后归零（与旧项目一致，不保留「上次耗时」）。

#### 2. 补充需求（注入运行中的 ToolLoop）

- 入口在状态栏左侧，点击展开 `.cc-supplement` 输入条（**仅在生成中可用**）；
- 提交走 `supplementRequest(conversationId, text)` → `POST /api/deepseek/supplement`，
  后端把它入队 `SupplementStore`，当前 ToolLoop **下一轮迭代**消费；
- 发送状态由父级持有（`chatSupplementState: idle | sending | done | error`），
  组件只负责展示与清空 —— 只有父级知道 `chatSessionId` 与接口结果；
- **不做乐观插入消息**：后端会在下一轮把它落成正式消息，前端先插会重复；
- 切会话 / 新建会话时重置为 `idle`，避免上一个会话的 `error` 态把输入条一直挂在那里。

#### 3. 任务看板与改动入口迁移

- 删除消息流末尾的内联 `ChatTaskPanel`，改为状态栏按钮 + 向上弹出的 `.cc-taskpop` 浮层；
- 空态有明确说明：「AI 还没建立任务清单。任务看板由 task_manager 工具输出生成，复杂任务才会计入。」
- 「改动」按钮点击前先 `loadChatChanges()` 刷新徽章，再打开 `ChangeReview` 抽屉；
- 两个徽章都只在有数据时渲染，没有任务/改动时只显示纯文字按钮。

#### 4. 静态预览同步（`preview.html`）

- 新增 `.cc-runwrap` / `.cc-runbar` / `.cc-taskpop` / `.cc-supplement` 结构 + 11 个 id；
- **显隐不用 `v-show` 语义**：`app.css` 里 `.cc-taskpop` / `.cc-supplement` 的基础态是 `display:flex`
  （Vue 侧靠 `v-if` 决定是否渲染），静态页改用 `.is-open` 类，
  这两条 `:not(.is-open){display:none}` 规则写在 `preview.html` 的**内联样式**里，不污染共用 CSS；
- 「生成中」分支用内联样式加了一层 `.cc-runbar__live`（Vue 用 `<template v-if>` 不产生包裹元素，
  静态页需要一个真实元素才能整块切换）；
- JS 用计时器模拟耗时递增（3 秒后文案从「思考分析中…」推进到「正在输出…」）；
- 任务浮层：点按钮开合、点浮层内部不关闭、点外部 / ESC 收起；
- 补充需求条：打开后隐藏入口按钮并聚焦输入框，发送/取消后收起；
- ESC 关闭链顺序：模型弹层 → 目录弹窗 → **补充需求条** → **任务浮层** → 各抽屉，
  轻量浮层优先收起且 `return`，不连带关掉底层抽屉；
- `switchPage()` 切页时收起两个浮层，避免跨页残留；
- 「改动」按钮在静态页给出说明提示（没有改动面板，不假装能点）。

#### 5. 两个排查记录

- **CDP 真实按键投递失效**：`Input.dispatchKeyEvent` 在 CDP 启动的页面里不触发 `keydown`，
  原因是 `document.hasFocus() === false`（页面从未被激活）。验证 ESC 这类交互时，
  要么先 `Page.bringToFront` 再 `Input.dispatchMouseEvent` 点一下页面，要么直接用
  `document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`
  走同一条冒泡链路（本项目监听器都挂在 `document` 上，两种方式等价）；
- **`v-show` → `:class` 的同类问题再次出现**：任何「CSS 基础态是 `display:none`、靠 `is-open` 显示」
  的静态样式，Vue 侧都不能用 `v-show`（只清内联 `style.display`，盖不住样式表默认值）。
  本轮静态页反向遇到（基础态是 `display:flex`），所以把隐藏规则限定在 `preview.html` 内联样式里。

#### 验证

```
typecheck                → 0 error
build                    → 304 modules / 785ms / 0 警告
preview.html 结构自检     → 49/49（JS 语法 / CSS 括号 / 11 个 id / 类名 / 显隐规则 / 标签配对）
浏览器 CDP（静态预览）     → 33/33
  · 初始「就绪」、浮层与补充条隐藏
  · 切 streaming → 耗时递增（0s → 1s → 3s）、文字推进到「正在输出…」
  · 补充需求：展开 → 聚焦 → 输入 → 发送 → 清空收起 → 入口按钮恢复
  · 任务浮层：打开、6 条任务（3 条已完成）、按钮 is-active、点内部不关闭、ESC 收起
  · 切回 chat → 耗时归零、生成中分支隐藏
  · 切到管理页 → 状态栏隐藏、浮层不残留；切回恢复
  · 「改动」入口给出说明提示
```

> 说明：Vue 侧的状态栏/补充需求/浮层与 8084 真实后端联动（`/deepseek/supplement` 已在旧项目验证可用）；
> 本轮新增的界面部分未在真实生成流里逐一回归，仅验证了组件契约与静态预览交互。

---

### L11 消息操作区三件套：复制 / 重新生成 / 回滚本轮改动（已完成）

#### 0. 需求来源

三件事一起做：

1. 旧项目的「回滚当前对话修改」按钮原来挂在 **user 消息**的头部小图标上；
   v3 的 AI 消息底部有「复制 / 重新生成」两个按钮，把回滚也移到这一排，统一成「消息操作区三件套」。
2. v3 的「重新生成」早期只在前端截断数组就重发 —— DB 里的旧回复仍在，刷新后又回来，
   历史里出现两条并存的答案。本轮把它做成**真删再重发**。
3. （补充需求）输入框下方的「上下文 7.3K」明显偏小 —— 确实算错了，口径一并修正。

#### 1. 回滚改动：按轮次匹配快照

```
快照来源：GET /api/snapshots/list?sessionId=  →  SnapshotSummary[]{ snapshotId, turnId, rolledBack, ... }
匹配键  ：turn_id（后端只给 user 消息落库，assistant/tool 行是空）
挂载点  ：AI 消息操作区（图标 + 文字胶囊，只有本轮有未回滚快照时才出现）
点击    ：GET /api/snapshots/preview?snapshotId=  →  列出文件 + 二次确认  →  POST /api/snapshots/rollback
成功后  ：清掉该消息的 snapshotId（按钮消失），并刷新顶部「N 个文件改动」统计
```

三处实现要点：

- **assistant 也要有 turnId**：`chat-mapper.ts` 顺序遍历分组时把最近一条 user 的 turnId 继承给其后的 assistant
  （`lastUserTurnId`），否则按钮无从匹配。
- **两个不覆盖保护**：`rolledBack` 的快照不点亮；消息上已有 `snapshotId` 就不覆盖 ——
  否则用户刚回滚完，下一次匹配又把按钮点亮。
- **触发时机**：历史消息加载完（`loadConversationMessages`）、每轮生成结束（`onSend` 的 finally）各匹配一次。

视觉上做成「图标 + 文字」胶囊而不是纯图标：破坏性操作要一眼看懂；
hover 变红（`--danger` + `--danger-lt`），与旁边两个中性图标按钮区分。

#### 2. 重新生成：先删该轮消息，再原样重发

早期实现（已废弃）：

```ts
messages.value = messages.value.slice(0, userIdx)   // 只截前端
await onSend(question)                              // 重发 → DB 里多一套
```

现在的前置动作是后端新增的接口：

```
DELETE /api/conversation/{conversationId}/messages?turnId=xxx
  → Controller：校验会话归属；Service：Mapper 找该 turnId 最早一条消息 id，删 id >= fromId
  → 返回实际删除条数（0 = 该轮还没落库，按幂等处理）
```

三种保护：

| 场景 | 行为 |
| --- | --- |
| 正在生成 | 直接拒绝（后端任务还在写库，删了会被写回来） |
| 该轮之后还有对话 | 二次确认，说明会一并丢弃 |
| 找不到 turnId（旧数据） | 提示「本次重发不会清理历史」，仍允许重发但不静默 |

**只删消息、不删快照**：这一轮真实发生过的文件改动仍可从「改动与回滚」面板回退，
删快照等于把用户的撤销能力一并清掉。

> ⚠️ 后端有改动，**必须重启 8084 才生效**。未重启时前端会明确报
> 「重新生成失败 · 无法删除旧回复，请确认后端已更新并重启」，不会静默降级去污染历史。

#### 3. 上下文统计：7.3K 的真相

原实现：

```ts
contextTokens: formatTokens(estimateTokenCount(messages.value.map(m => m.content || '').join('')))
```

只统计**正文 content**，漏掉了真正的大头：思考过程（`reasoning`）与工具返回（`tool_calls`）。
一个 1228 条消息、617 次工具调用的会话，实测从 **7.3K → 558.7K**，差了 76 倍。

修正后口径（对齐旧项目 `totalContextTokens`「累加每条消息 tokenCount」）：

```
上下文 = Σ content + Σ process[].text + Σ tools[].detail
```

同时改成**时机驱动**而非 computed：估算要遍历数百万字符，
流式期间每帧重算会掉帧，改为「消息加载完 / 一轮结束 / 重新生成后」各算一次（`refreshContextTokens()`）。

局限（UI 上以 tooltip 标注）：仍是前端字符估算，不是后端真实 `prompt_tokens`（后端未透传 usage）；
且只覆盖「已加载的消息」（分页每次 5 轮）。

#### 4. 静态预览同步

`preview.html` 的消息操作区补了「回滚改动」胶囊与 `data-msg-*` 标记，点击给出说明性 toast
（静态预览不连后端）；回滚按钮点击后隐藏，演示「回滚成功后按钮消失」。

#### 5. 验证

```
typecheck                      → 0 error
build                          → 304 modules / 777ms / 0 警告
后端 test-compile               → 通过（23s）
preview.html 自检               → 12/12（i-undo 图标 / 按钮结构 / 交互 JS / 内联 JS 语法 / CSS 括号 / 标签配对）
接口探针（8085 内存库 + 预置两轮消息） → 12/12
  · 删 turn-B → 影响 2 条，A 轮完整保留、B 轮清空
  · 删 turn-A → 影响 2 条，会话消息清空
  · 重复删除幂等（code=200 / data=0）、未知 turnId 返回 0 不报错
  · 无 token → 401；会话不存在 → 业务 404；缺 turnId → 400
浏览器 CDP（Vue，8084 真实数据）  → 12/12
  · 上下文 556.1K / 558.7K（原 7.3K）
  · 回滚按钮 2 个（两条 AI 消息各匹配到快照），与复制 / 重新生成同排
  · 点击 → 确认框「回滚这一轮的代码改动」+ 3 个文件清单 → 取消（未执行真实回滚）
  · 8084 未重启时新接口返回 405（预期，前端据此提示重启）
```

> 说明：「重新生成」的端到端回归需要重启 8084 —— 未重启时点下去会停在前端的错误提示，
> 不会产生任何数据变更，属预期降级。回滚按钮的**真实执行**未在测试中点击（保护用户工作区文件）。

### L12 多智能体并行流 + 身份与观感修复（已完成）

把旧项目 `backgroundStreams` 的思路落成 C 档完整实现：**切走不中断、各会话独立状态**，
同时修掉「头像不同步」「背景图不显示」「历史消息 Agent 名退化」三个真实缺陷。

#### 1. 会话分片状态（App.vue）

原先聊天页只有一组单例状态（`messages` / `isStreaming` / `approval` / `chatSessionId` / …），
切会话必须先 `abort` 当前流，能力等价于「单会话单流」。改为按会话 key 分片：

| 状态 | 改造前 | 改造后 |
| --- | --- | --- |
| 消息 | `messages` 单 ref | `messagesMap: Record<key, ChatMessageModel[]>` |
| 流式 | `isStreaming` 单 ref | `streamingMap: Record<key, boolean>` |
| 审批 | `approval` 单 ref | `approvalMap` + `pendingMap`（侧栏黄点） |
| 统计 | `chatContextTokens` / `chatSessionChanges` / `chatSupplementState` | 各自 `*Map` |
| 任务清单 | `taskMap`（L8 已完成） | 不变 |
| 中断句柄 | `chatAbort` 单变量 | `streamAborts: Map<key, AbortController>` |
| 待答 uuid | `pendingApprovalUuid` 单变量 | `pendingUuidMap: Map<key, string>` |

对外仍暴露同名的 computed（`messages` / `isStreaming` / `approval` / `chatSessionId` / …），
所以 ChatView 这一层模板**零改动**。

**会话 key 约定**：已落库会话用 `String(id)`；新会话用 `draft-<时间戳>`。
首个 SSE 事件带回 `sessionId` 时调 `migrateConversationKey(from, to)` 整体迁移
（消息 / 流 / 审批 / 统计 / 任务 / abort / uuid 一并搬家，并同步 `activeConversationId`）。

**关键行为变化**：

- `onSelectConversation`：不再 abort 当前流；目标会话若本地有流，跳过 DB 覆盖（避免把流式内容冲掉）
- `onNewChat`：只切到一个空草稿 key，不 abort 任何会话（空草稿才回收）
- `onStop`：只停当前会话的流
- 新会话在**首个 SSE 事件到达后立即**刷新侧栏（否则流式期间没有入口切回去）
- `ask_user` 落到对应会话；若该会话在后台，弹 toast 提示而不抢当前会话的审批卡片
- 一轮结束按 `finalKey` 清理，并在后台会话完成时提示「后台会话已完成」（主动停止不提示）

**UI 配套**：`ConversationItem` 加 `pending` 字段 → 侧栏会话项黄点（优先于运行圆点）；
ChatView 的 runbar 与空状态都显示「N 个会话后台运行中」，点击切到最近开始的那个后台会话。

#### 2. 用户 / 智能体头像同步

`GET /api/user/current` 只返回 `userId / username / role`，不含头像与昵称，所以侧栏与消息气泡一直显示首字兜底。
新增 `src/utils/avatar.ts`（`isImageAvatar` / `avatarFallback`）与 `session.ts` 的 `fillProfile()` / `refreshProfile()`：
身份就绪后补查 `GET /api/user/profile`，把 `nickname / avatar` 写回 user store，`main.ts` 启动时刷新一次。

`ChatMessage` 同时支持用户头像（气泡右侧）与 AI 头像（消息头），并让 **AI 头像跟着消息走**（`message.agentAvatar`）：
多会话并行后，后台会话可能属于另一个 Agent，统一取「当前 Agent」会把别人的头像挂上来。

#### 3. 背景图不生效（层叠顺序）

`.cc-app-bg` 是 `position: fixed; z-index: 0`，而 `.cc-shell` 没有定位也没有 z-index，
结果背景层盖在整个 UI 之上（表现为「设置了背景图，但什么都看不见」）。
修复：`.cc-shell` 补 `position: relative; z-index: 1`；带背景图时（`.cc-shell.has-bg`）
主区 / 侧栏 / 顶栏改用 `color-mix` 半透明 + `backdrop-filter`，暗色主题同样生效（含 `@supports` 回退）。

#### 4. 历史消息 Agent 身份退化（本轮收尾修复）

**现象**：流式时消息头显示「圆圆」，切回会话或刷新页面后变成兜底的「编码助手」。

**根因**：`toChatMessages()`（DB 历史消息 → 视图模型）从不设置 `agentName` / `agentAvatar`，
只有流式新建的消息（`onSend` / `checkChatActiveTask`）才带这两个字段。

**修复**：消息表里没有「这条回复来自哪个 Agent」，只能按**会话所属的 `agentConfigId`** 反查：

1. `ConversationResponse.agentConfigId` 落到侧栏 `conversationList`（原来被丢弃）
2. 新增 `agentMetaOf(agentConfigId)`：在 Agent 列表里反查名称/头像，查不到才回落到当前 Agent
3. `toChatMessages(messages, model, agent)` 增加第 3 参，为 assistant 消息填 `agentName` / `agentAvatar`

不直接用「当前选中的 Agent」：多会话并行后，后台会话可能属于别的 Agent，用当前值兜底会张冠李戴。

#### 5. 验证

```
typecheck                      → 0 error
build                          → 305 modules
浏览器 CDP（Vue，8084 真实数据，21 项断言） → 21 passed / 0 failed
  · 流式期间新会话即出现在侧栏（132ms 采样命中，首次命中 2ms）
  · .cc-msg-ai__body.is-streaming 与 .cc-caret 均命中
  · 思考区 / 正文区分离：正文「收到」，思考全文独立在 .cc-proc__text
  · 切到新会话后原会话仍在侧栏；空态显示「1 个会话后台运行中」
  · 切回后正文完整未中断
  · 切回后 Agent 名为「圆圆」（修复前是「编码助手」）
  · 刷新页面后 Agent 名仍为「圆圆」
  · 测试会话用完即删；受保护会话 id=2 未被改动
```

> 测试脚本踩坑记录（临时脚本已清理，坑位留档）：
> ① CDP 的 pending 请求原先不会超时，页面 reload / target 分离时会永久挂住（曾把命令拖到 440s 超时），
> 需要给 `send()` 加超时 + `onclose` 批量 reject；
> ② 「流式中」属瞬时状态，固定时刻采样必然漏（短回复 < 1s 就结束），要连续采样；
> ③ 正文选择器不能写 `.cc-msg-ai__body, .cc-md` —— 后者会按文档顺序命中思考区的 `.cc-proc__text`；
> ④ CDP 会话僵死时（`Page.enable` 20s 无回包）只能重启那个调试专用 Edge 实例
> （`--user-data-dir=C:\Temp\edge-cdp`，按 PID 杀，不要 `taskkill /IM msedge.exe`）。

> 已知遗留（L13 已纠正认识）：后端 `GET /deepseek/task/{id}` 只有 `running` 状态、没有心跳时间，
> 「`seq / eventCount` 长时间零增长」既可能是真僵尸，也可能只是**慢任务**——实测会话 2 的
> `eventCount` 十余秒不动，但隔一轮再看 `seq` 从 1394205 涨到 1396130，说明它一直在产出。
> 因此前端**不再尝试 cancel**，只在页面上忽略这条标记（详见 L13 第 7 节）；
> 要根治需后端补活性判定或心跳超时。

### L13 聊天页输入区补齐 + 遗留标记语义纠正（已完成）

本轮补的是「聊天时手边缺的东西」，以及一处**会误伤用户任务**的旧逻辑。

#### 1. 历史文件引用（`FilePickerDialog.vue`）

问题：以前传过的文件，聊天时想再引用只能重新上传（云端多一份对象、本地多一份副本）。

做法：

- 输入区附件按钮拆成两项菜单：**上传新文件** / **用历史文件**；
- 选择器走 `GET /api/files?limit=200&order=desc`，图片用 `GET /api/files/{id}/preview` 取 blob 缩略图；
- 选中后只记 `fileAssetId`，发送时按 id 复用同一份资产（图片注入 vision 内容块，文档注入读取提示），**不重新上传**；
- 已在附件区的文件置灰并标「已在附件」，单次最多 20 个（防止一次挂几十个文件把上下文撑爆）。

#### 2. 粘贴任意文件 + 跨页「引用到聊天」

- `onPaste` 同时处理 `clipboardData.items`（截图）与 `clipboardData.files`（普通文件，过滤图片防重复）；
- 文件管理页行上新增「引用到聊天」：写 `useFileRefStore` → 跳 `/chat` → 聊天页 `tryConsumePendingRefs()` 消费。
  走 store 而不是直接改 attachments，是因为引用发生时聊天页可能还没挂载。

#### 3. 运行中禁止输入

生成中 textarea 与附件 / 优化按钮统一 `disabled`，placeholder 换成说明文案。
用 `running` 单独判定，不复用「无 Agent」的禁用原因，避免文案串味。

#### 4. 任务清单解析修复（真实 bug）

现象：任务浮层经常空着，即使 `task_manager` 明明返回过清单。

两个根因：

1. 工具卡片正文被截断到 4000 字符，`---TASK_JSON---` 恰好在尾部被切掉；
2. 一段文本含多个 marker 时 `JSON.parse` 直接失败。

修复：`parseTaskManagerOutput` 改为**从后往前遍历所有 marker + 括号配平提取 JSON 数组**；
`toChatMessages` 用**未截断原文**解析并挂 `taskSnapshot`（不改 `TOOL_DETAIL_LIMIT`，工具卡片展示不受影响）。

#### 5. 聊天页 Agent 切换器（`ChatAgentPicker.vue`）

此前换 Agent 只能回智能体管理页点卡片（两次跳转）。现在侧栏顶部就有切换器：

- 显示头像 / 名称 / 模型 / 默认标记，以及该 Agent 名下**正在后台跑的会话数**；
- 切换 **不 abort** 任何后台流（多会话并行按会话 key 分片，切走只是不再显示）；
- 切换后按新 Agent 重拉会话列表，并切到一个空草稿。

#### 6. 多任务并行看板

- 侧栏会话项加 `3/6` 任务进度徽章（读各会话的 `taskMap`）；
- 任务浮层新增「其他会话」分组：跨 Agent 展示后台会话的任务摘要，点击直接切过去（`onSwitchBackgroundTask`）。

#### 7. 遗留运行标记：从「清理」改为「只忽略」（重要纠正）

旧实现点「遗留运行标记 · 点击清理」会调 cancel 接口，把后端任务**真取消**。

实测发现判定并不可靠：会话 2 的 task 在十余秒内 `eventCount` 恒定、看着像僵尸，
但 `seq` 隔一轮就涨 —— 它其实还在产出，只是间隔较长。用户的长任务会被这条「清理」误杀。

现在的语义：

- 按钮文案改为「遗留运行标记 · **点击忽略**」；
- 点击只清前端 `staleTaskKeys`，**不请求后端、不中断任务**；
- toast 明确写：「已忽略运行标记 / 只影响本页显示，未中断后端任务（它可能仍在慢速产出）」；
- 真要停止，由用户在会话里点「停止生成」显式决定。

验证方式：点击前后各查一次 `GET /api/deepseek/task/2`，两次结果完全一致
（`active=true / status=running / seq=1396130`），证明前端忽略没有影响后端。

#### 8. 静态预览同步（`preview.html`）

补 6 处结构（样式全部复用 `app.css`，只新增结构 + 交互）：

| 位置 | 内容 |
|------|------|
| 侧栏顶部 | Agent 切换器（2 个 demo Agent，含「1 个会话运行中」角标） |
| 会话项 | 任务进度徽章 `3/6`、待确认黄点 |
| 输入区 | 附件两项菜单（上传新文件 / 用历史文件） |
| 浮层 | 历史文件选择器（8 条假数据，选中 → 确认 → 并入附件区 → 可移除） |
| 运行状态栏 | 遗留运行标记（跟随「工具审批」状态演示，点击只忽略） |
| 任务浮层 | 「其他会话」分组 + 两条只读摘要 |

#### 9. 验证

```
typecheck                                → 0 error
build                                    → 310 modules（主 chunk 448.90 kB / gzip 159.81 kB）
preview.html 自检（24 项）                → 24 passed / 0 failed
浏览器 CDP（preview.html，16 项）         → 16 passed / 0 failed
浏览器 CDP（Vue，8084 真实数据，15 项）   → 15 passed / 0 failed
  · 附件菜单两项 / 历史文件选择器（105 个真实文件）/ 并入附件区 / 可移除
  · 遗留标记点击后：前端消失、输入框可用、后端任务状态零变化
```

#### 10. 踩坑留档

- **`inline-flex` 在 flex 容器里会被 blockify 成 `flex`**：断言显示态时不能写死 `inline-flex`
  （`getComputedStyle` 返回计算值，`.cc-runbar__status` 是 flex 容器，按钮计算值就是 `flex`）。
- **Vue 侧浮层用 `v-if`、静态预览用 `.is-open` 类**：两套断言不能混用，
  拿 `classList.contains('is-open')` 判 Vue 浮层必然 false。
- **跨 URL 导航会让 CDP 连接失效**（`Not attached to an active page`），导航后要重新 `openPage()` 拿新连接。
- **同 URL 的 `Page.navigate` 不会 reload**：Pinia 不会重读 localStorage，验证持久化必须 `Page.reload`。



### L14 「回滚改动」入口可见性修复（已完成）

用户反馈：**聊天页在复制 / 重新生成旁边看不到回滚按钮**。
排查结果是「功能没坏，但入口几乎不可见」，本轮把它搬到台面上。

#### 1. 先定位真相（实测数据，不是猜）

查真实接口确认链路本身完好：

```
GET /api/snapshots/list?sessionId=2   → 12 条快照，turnId 全部非空
GET /api/conversation/2/messages      → 161 条 user 消息，其中 64 条带 turnId
交叉比对                               → 12 条快照的 turnId 与 12 条 user 消息**完全对上**
                                         （交集 12 / 孤儿 0）
```

所以后端映射、`chat-mapper` 的 turnId 继承、`matchSnapshotsForMessages` 的点亮逻辑都是对的。

真正的原因是**分页 + 按钮显隐规则叠加**：

| 位置 | 内容 | 命中快照 |
|------|------|----------|
| 第 1 页（首次进入默认加载最近 5 轮） | 10253 / 9959 / 9442 / 9304 / 9198 | **0 条** |
| 第 2 页（往上滚一次） | 8834 / 8780 / 8488 / 8358 / 8237 | 2 条（★8488、★8237） |
| 第 3 页 | 7889 / 7407 / 7357 / 7165 / 6968 | 1 条（★7889） |

最近 5 轮都在讨论 / 排查，没有产生文件改动 → `snapshotId` 全为空 → 按钮一个都不渲染；
即便翻到有快照的那轮，按钮还藏在 `opacity: 0` 的 `.cc-msg-ai__actions` 里（hover 才显形）。

#### 2. 四处改动

| # | 位置 | 改动 |
|---|------|------|
| 1 | `assets/app.css` | `.cc-msg-ai__actions` **不再整组 `opacity:0`**，改为只让子级 `.cc-icon-btn`（复制 / 重新生成）参与 hover 渐显；`.cc-msg-ai__rollback` 常驻可见 |
| 2 | `src/views/ChatView.vue` | 状态栏入口 `改动` → **`改动/回滚`**（图标 `i-undo`），并新增 `snapshotCount` prop，徽章显示「N 轮」 |
| 3 | `src/components/ChangeReview.vue` | 「按快照」列表新增第二列显示**该轮用户提问摘要**（`turnHints` prop），第一列改为固定宽 `.cc-changes__snaptime` |
| 4 | `src/App.vue` | 新增 `snapshotCountMap` / `snapshotHintMap` 及两个 computed；`matchSnapshotsForMessages` 在点亮按钮**之前**先记录「可回滚轮次数」与「turnId → 提问摘要」；流式结束后补一次 1.5s 延时重匹配 |

#### 3. 为什么第 4 项的延时重试是必要的

后端在会话收尾阶段才写文件快照，而 `consumeChatStream` 的 `finally` 紧跟在最后一个 SSE 事件之后。
实测「刚让 AI 改完文件的这一轮」首次匹配经常捞不到快照，按钮要等刷新才出现 —— 补一次 1500ms
重试兜住这个时序差；`matchSnapshotsForMessages` 对已点亮的消息会跳过（`msg.snapshotId` 已存在不覆盖），
重复调用无副作用。

#### 4. 「可回滚轮次」为什么要单独统计

`matchSnapshotsForMessages` 原本在 `if (!snapshots.length) return` 之后就返回了。
现在把计数写在**所有提前返回之前**：即使当前页一条快照都没匹配上，状态栏也会如实显示
「本会话历史上有 12 轮改动可回滚」，用户不必先翻历史才知道功能存在。

#### 5. 静态预览同步

- `preview.html` 的 `#runChangesBtn`：图标改 `i-undo`、补「3 轮」徽章与 title；
  文案当时写成「改动/回滚」，**L15 已按用户要求改回「改动」**；
- 消息操作区注释更新（回滚按钮改为常驻可见）。
- 回滚按钮本身在 `.cc-msg-ai__actions` 内，与 Vue 共用同一条 CSS 规则，样式自动生效。

#### 6. 验证

```
typecheck                                  → 0 error
build                                      → 310 modules（主 chunk 449.70 kB / gzip 160.13 kB）
浏览器 CDP 第一轮（15 项）                 → 13 passed
  · 状态栏「改动/回滚 +6795/-514 · 12 轮」/ title 含「历史上有 12 轮改动可回滚」
  · 回滚按钮 opacity=1（不依赖 hover）、复制/重新生成仍 opacity=0
  · 翻页后出现 3 个消息级回滚按钮
  · 2 项 FAIL：面板在翻页**之前**就打开了，提问摘要自然是占位
浏览器 CDP 第二轮（调整顺序，5 项）        → 5 passed / 0 failed
  · 翻 2 页后：AI 消息 25 条、回滚按钮 4 个
  · 按快照 12 条，4 条显示提问摘要（已加载轮次），行高 32px 未被撑破
  · 点击快照回滚 → 正确弹出二次确认（已取消，未动真实文件）
```

#### 7. 已知边界

- **更早的轮次摘要显示占位**：「按快照」12 条里只有已加载进内存的轮次能显示提问（实测 4/12），
  其余显示「（更早的一轮，未加载提问）」，hover 有提示「往上滚动聊天记录后即可显示提问」。
  要 100% 覆盖得全量拉一次消息拿 turnId 映射，代价与收益不成比例，暂不做。
- 面板里仍是「回滚到快照」语义（按时间点整体回退），没有「跳过中间轮次只回退第 N 轮」的能力 ——
  那是后端快照机制本身的限制（每个快照记录的是当时的全量文件状态）。
- 回滚按钮的**真实执行**依旧未在自动化测试里点击（保护用户工作区文件），只验证到二次确认弹窗。

---

## 八、L15 回滚全景修复（跨盘快照 + 快照提问回填）

用户反馈两条：**① 改动面板「按快照」里全部显示「更早的一轮，未加载提问」；② 不是本轮，是所有轮次都没有「回滚改动」按钮。**
另外要求把状态栏按钮文案从「改动/回滚」改回「改动」。

### 1. 根因：快照只对「项目根内」的文件生效

后端 `SnapshotService.createSnapshot` 旧实现：

```java
try {
    relativePath = projectRootPath.relativize(absPath).toString();   // ← 这里
} catch (Exception e) {
    log.warn("文件不在项目目录内，跳过快照: {}", absoluteFilePath);
    return null;                                                     // ← 直接放弃
}
```

- 项目根 = Agent 工作目录（`E:\zbcode\testDemo\code-craft`，ProjectRootContext）；
- 10-19 之后所有开发轮次改的是**另一个盘的前端项目**（`C:\Users\61443\Desktop\codecraft`）；
- 不同盘符时 `relativize` 抛 `IllegalArgumentException` → 命中 catch → **快照被静默跳过**；
- 即使同盘，不同目录会得到 `../../x` 逃逸路径，也会被后续逻辑拒绝。

真实数据佐证（`%APPDATA%\codecraft-desktop\runtime\snapshots\.snapshots_index.json`）：

| 快照 | 时间 | 被改文件所在盘 | 是否有快照 |
|---|---|---|---|
| 前 12 条 | 10-17 ~ 10-19 01:53 | `E:\`（后端源码） | ✅ 有 |
| 10-19 01:53 之后的全部轮次 | 10-19 ~ 现在 | `C:\`（前端项目） | ❌ 无 |

所以「所有轮次都没有按钮」= 用户当时能翻到的最近几十轮，恰好全都在改 C 盘。

### 2. 后端修复①：根外文件也能建快照（`SnapshotService`）

- `FileEntry` 新增 `absolutePath`：定位文件以绝对路径为准，`relativePath` 退化为**展示名**
  （根内仍是相对路径，根外就是绝对路径，面板里能直接看懂改的是哪个盘的文件）；
- 新增 `toDisplayPath`：relativize 失败或结果以 `..` 开头 → 一律按根外处理（不再放弃）；
- 新增 `resolveBackupPath`：根内/旧数据仍存 `files/<relativePath>`（**旧快照完全兼容**），
  根外文件存 `files/_ext/<盘符>/<路径>`（冒号等非法字符已替换，杜绝 `..` 逃逸）；
- 新增 `resolveTargetPath`：回滚目标优先用 `absolutePath`，旧数据回退 `projectRoot + relativePath`；
- `computeDiffStats` / `rollback` / `rollbackFile` / `getSnapshotFileContent` 全部切到上述两个解析函数；
- `getProjectRootPath()` 改为可返回 `null`（旧实现 `Path.of(null)` 会 NPE），缺上下文时按「无根」继续建快照。

### 3. 后端修复②：快照列表回填「这一轮问了什么」

- `ConversationMessageMapper` 新增 `selectUserPromptsByTurnIds`（`turn_id IN (...)` 批量查 `role=USER` 的 content），
  返回 `TurnPromptVO{turnId, content}` 而不是 `Map`：`content` 是 LONGTEXT（H2 → CLOB），
  Map 方式拿到的是 `JdbcClob`，`String.valueOf` 会变成 `org.h2.jdbc.JdbcClob@1a2b`；
  声明成 String 字段后由 StringTypeHandler 取值，同时绕开列别名大小写问题（H2 会把别名转大写）；
- `SnapshotSummary` 新增非持久化字段 `turnPrompt`；
- `SnapshotController#listSnapshots` 回填：收集 turnId（含去掉 `_review_N` 后缀的候选）→ 一次 IN 查询 → 写入
  `turnPrompt`（压空白 + 截 200 字）。查询失败只 warn，不影响列表。

### 4. 前端调整

| 位置 | 改动 |
|---|---|
| `src/api/snapshot.ts` | `SnapshotSummary` 新增 `turnPrompt?: string` |
| `src/components/ChangeReview.vue` | `hintOf(snap)` 优先用 `snap.turnPrompt`，其次才回退父级 `turnHints`；占位文案改为「（该轮提问未获取到）」并给出排查提示 |
| `src/views/ChatView.vue` | 状态栏按钮文案 `改动/回滚` → **`改动`**（`N 轮` 徽章与 title 说明保留） |
| `preview.html` | `#runChangesBtn` 文案同步为「改动」 |

### 5. 验证

```
后端 test-compile                                  → 通过
SnapshotCrossRootTest（新增，main 方法式）         → 20 passed / 0 failed
  · 根外文件建快照 / 备份落在快照目录内（未逃逸）
  · diff 统计 +行 -行 均写入
  · preview / rollback / 内容恢复
  · 跨盘（projectRoot=E:\，文件在 C:\）建快照 + 回滚成功
  · 新建文件回滚后被删除
  · getSnapshotFileContent / rollbackFile 正常
SnapshotPromptMapperTest（新增，真 MyBatis + H2）    → 6 passed / 0 failed
  · 注解 <script>/<foreach> 解析、多元素/单元素 IN
  · content 走 StringTypeHandler（不是 JdbcClob.toString）
  · 会话隔离生效
真实数据交叉核对（8084 全量消息 21.9MB / 10574 条）→ 快照 turnId 命中 13/13
  · 证明「快照 turnId ↔ user 消息 turn_id」完全对齐，回填后一定能显示提问
前端 typecheck / build                             → 0 error / 310 modules
```

### 6. 生效方式（重要）

桌面版 CodeCraft 跑的是**安装目录里那份固定 JAR**（`%LOCALAPPDATA%\Programs\CodeCraft\resources\backend\codecraft.jar`，
本次排查时是 10-17 构建的版本）。**只重启客户端不会加载新代码**，必须重打 JAR：

```bat
REM 先完全退出 CodeCraft（含托盘图标）；脚本在**后端项目**里，不在本前端目录
cd /d E:\zbcode\testDemo\code-craft
scripts\relink-backend.bat          :: 构建 + 备份旧 jar + 替换
scripts\relink-backend.bat --dry    :: 只构建，打印路径，不替换
```

脚本内部：`mvn clean package -DskipTests -DskipFrontend=true` → 取 `target` 下最新 jar →
备份为 `codecraft.jar.bak-<时间戳>` → 覆盖 → 提示重启客户端。

### 7. 已知边界（更新 L14 第 7 节）

- 已修复：更早轮次的提问不再依赖「翻页到才显示」，改为后端按 turnId 回填，**13/13 全部可显示**；
  只有后端未升级（旧 jar）或该轮 user 消息没有 `turn_id`（极老数据）时才回退占位文案。
- 未变：面板仍是「回滚到快照」语义（按时间点整体回退），没有「跳过中间轮次只回退第 N 轮」的能力。
- 未变：回滚的**真实执行**不在自动化测试里点击，只验证到二次确认弹窗（保护工作区文件）。
- 新的注意点：本轮（10-19 12:17 之后）产生的快照记录的是 **E 盘后端源码**（SnapshotService / Mapper / Controller / 新增测试类）——
  点「回滚本轮改动」会把后端修复一起回滚掉，操作前确认清楚。

---

## 九、L16 聊天页 Provider 切换与上下文模式

用户问：「旧版聊天页有**上下文模式选择**和 **LLM 提供商切换**，新版实现了吗？」
核查结论：**新版两项都没有真正实现**，本轮补齐。

### 1. 核查：旧版有的、新版缺的

| 能力 | 旧版（`CodeAssistantView.vue`） | 新版（迁移前） | 后端 |
|---|---|---|---|
| Provider 切换 | 「更多 → 📡 Provider」下拉 → `onProviderSwitch` → 写回 Agent 运行时 | ❌ **无 UI**；`chatProviderCode` 只用于查余额，`onSend` 从不传 `providerCode` | ✅ `ChatRequest.providerCode`，优先级高于 `agentConfig.providerId` |
| 上下文模式 | 「更多 → 🧠 上下文」下拉（全量/精简）→ `settingsStore.contextMode` + `setConfig('context_mode')` | ⚠️ **半成品**：store 有 `contextMode` 字段和 persist，但**无任何 UI 入口**、`onSend` 不传、服务端从不写 | ✅ `ChatRequest.contextMode` + `sys_config.context_mode` 兜底 + `ContextBuilder` 精简历史 |
| 精简徽章 | footer「上下文 Token: N」+「⚡ 精简」 | ❌ 只有上下文数字，不区分配置 | — |
| 上下文统计口径 | compact 时只算「本轮」，历史轮次打折 | ❌ 恒按 full 口径累加 | — |

关键事实：**后端两项能力都是完整可用的**，缺的只是前端入口与参数传递。

### 2. 前端改动

| 位置 | 改动 |
|---|---|
| `src/components/ChatModelPicker.vue` | 重写为 5 组设置：**Provider 下拉** + 模型（随 Provider 联动）+ **上下文模式分段** + 思考模式 + 执行模式；`apply` payload 增加 `providerCode` / `contextMode`；新增 `ModelPickerProvider` 类型导出 |
| `src/App.vue` | 新增 `chatProviderOverride`（临时覆盖）/ `chatContextMode`（读 settingsStore）/ `chatProviderOptions`（复用 Agent 页 Provider 选项）；`onSend` 补 `providerCode` + `contextMode`；`onChatModelApply` 扩展为五参数（上下文写 store + `sys_config`，其余写 Agent 运行时）；`refreshContextTokens` 按模式分口径；`syncChatRuntime` 切 Agent 时清覆盖 |
| `src/views/ChatView.vue` | `ConversationDetail` 加 `contextMode`，两处 `ChatComposer` 透传 |
| `src/components/ChatComposer.vue` | 新增 `contextMode` prop，compact 时 footer 出现「⚡ 精简」徽章并切换 tooltip 口径说明 |
| `assets/app.css` | 新增 `.cc-modelpick__hint` / `__seg` / `__seg-btn`、`.cc-ctx-badge` |
| `preview.html` | 模型弹层同步：Provider 下拉（3 个演示 Provider 联动模型列表）+ 上下文分段 + Composer 徽章；顺手修正思考模式取值 |

### 3. 三个值得记的实现细节

#### 3.1 Provider 什么时候才真的下发给后端

`chatProviderCode` 是个 computed，Agent 没绑 Provider 时会回退成「默认 Provider 的 code」。
如果把这个值无条件下发，就把「跟随 Agent 绑定 / 跟随默认」这一语义**固化成某个具体 Provider**，
反而会盖掉后端按 `providerId` 的选型（后端优先级：`ChatRequest.providerCode` > `agentConfig.providerId` > `agentConfig.providerCode` > 默认）。

所以 `onSend` 只传 `chatProviderOverride`（用户本会话手动切换过的值），不传 computed 的兜底结果。

#### 3.2 切 Provider 时纠正模型的时机

`ChatModelPicker` 的 `modelOptions` 会把「当前已选模型」插到首位（避免历史配置被静默替换），
但**只有用户手动切换 Provider 时**（`@change`）才把非法模型纠正成 `defaultModel` / 列表首项。
打开弹层回填不触发纠正 —— 否则编辑一个「模型不属于当前 Provider」的历史配置时会被悄悄改掉。

#### 3.3 compact 模式的统计口径

旧版对非本轮 assistant 粗按 `tokenCount * 0.2` 打折（它的思考与正文混在一个字段里拆不开）。
v3 的 `ChatMessageModel` 把思考（`process[]`）与工具（`tools[]`）外置了，所以改成精确口径：

- **本轮**（最后一条 user 消息之后）：正文 + 思考 + 工具明细全算；
- **更早轮次**：正文照算、思考不计、工具按每条约 30 token 的摘要计 ——
  与后端 `ContextBuilder.compactHistoryMessages` 的实际行为对齐。

#### 3.4 顺手修的 bug

`ChatModelPicker` 的思考模式选项原本写的是 `non_thinking`（下划线），
而 `AgentEditDrawer` / `App.vue` 默认值 / 后端统一用 `non-thinking`（连字符）。
选中「不思考」会把 `thinking_mode` 写成后端不认识的枚举值。已统一为 `non-thinking`。

### 4. 验证

```
前端 typecheck                                  → 0 error
前端 build                                      → 311 modules（主 chunk 452.46 kB / gzip 161.25 kB）
Vue 应用 CDP（18 项）                            → 18 passed / 0 failed
  · 弹层打开（display:grid）、Provider 字段在首位、下拉有选项
  · 上下文为二选一分段，默认「全量」
  · 切「精简」→ 应用 → Composer 出现「⚡ 精简」徽章（display:flex）
  · localStorage settings.contextMode 写入 compact
  · 重新打开弹层仍为「精简」（persist 生效）
  · 切回「全量」→ 徽章隐藏、localStorage 恢复 full
静态预览 CDP（17 项）                            → 17 passed / 0 failed
  · Provider 3 个演示项；deepseek → 2 个模型、volcengine → 2 个、anthropic → 1 个
  · 切到 anthropic 时旧模型 deepseek-flash 被自动纠正为 claude-sonnet-4
  · 上下文分段、徽章点亮/隐藏、模型按钮文案同步
  · 思考模式取值已是 non-thinking
后端契约探针（8 项，真实 8084）                   → 8 passed / 0 failed
  · POST /api/deepseek/chat/stream 带 providerCode + contextMode=compact → HTTP 200
  · SSE 返回真实 sessionId、收到正文「收到」、无 error 事件
  · 临时会话已删除（未触碰受保护的会话 id=2）
```

### 5. 已知边界

- **只有 1 个启用 Provider 时，Provider 下拉只有 1 项**（当前库就是这种情况：只有 `deepseek`），
  切换联动只能靠静态预览的 3 个演示 Provider 验证；要真实体验得先到「模型配置」页再加一个 Provider。
- 上下文模式是**全局设置**（不绑 Agent）：与旧版一致，切换后对所有 Agent 生效。
  若将来要做「每个 Agent 独立的上下文模式」，需要后端在 `agent_config` 加字段。
- `sys_config.context_mode` 只是兜底镜像：请求里始终显式带 `contextMode`，
  后端选择顺序是「请求 > sys_config > 默认 full」，所以前端写失败也不影响实际行为。

### 6. 视觉重构：模型弹层排齐（L16 收尾）

用户反馈「LLM 提供商和上下文模式样式好丑」，根因不是某一条样式写错，而是**三种控件形态混排**：

| 位置 | 原形态 | 问题 |
| --- | --- | --- |
| 提供商 | 原生 `<select>` | 箭头由系统绘制，Windows/macOS/各浏览器都不一样，和 1px 描边圆角不搭 |
| 模型 | `--r-full` 圆角 chips，换行流式布局 | 模型名长短不一，换行后参差不齐 |
| 上下文模式 | 两个 `--r-xs` 分段按钮挤在一起 | 高度 24px 与另外两种控件（34px / 26px）都不一样；说明只能靠 hover tooltip |

改动（`ChatModelPicker.vue` + `assets/app.css` 第 36 节）：

1. **统一骨架 `.cc-mpk`**：每个设置项一律「标签 12.5px/600/`--text-2` → 8px → 控件」，
   组与组之间 20px（`--sp-5`）。靠间距区分「组」与「组内」两层关系，比加分割线更轻。
2. **下拉自绘箭头**：`appearance: none` + 绝对定位的 `i-chev`（旋转 90°、右 10px、垂直居中、`pointer-events: none`），
   原生箭头各系统观感不一正是「乱」的主要来源；控件高度统一 34px、右内边距 30px 防文字压箭头。
3. **模型 chips → 整宽单选行**：容器 1px 描边 + 行分隔线，行高 34px，模型名 `--mono` 单行省略，
   右侧勾选标记常驻占位只切 `opacity`（避免选中时文字被挤压抖动）。
4. **上下文分段按钮 → 等宽双卡片**：`grid 1fr 1fr`，卡片内「标题 + 说明」两行，说明从 tooltip 挪到明面；
   列间距 `--sp-3` 与下方「思考模式 / 执行模式」两列保持一致（否则两组两列布局看着不对齐）。
5. **可读性修正**：11.5px 小字不用 `--text-4`（白底对比度约 2.1:1，太弱），说明文字统一 `--text-3`，
   选中态提到 `--text-2`。
6. 弹窗宽度 440 → 460px，两列控件各 207px 足够放下「手动确认」+ 箭头。

同步与清理：

- `preview.html` 结构同构改造，`renderModelPick()` 改为渲染 `.cc-mpk__model` 行（含 `#i-check` 勾选），
  新增 `modelPickProviderName` 显示当前提供商名。
- 旧类 `cc-modelpick__{item,seg,seg-btn,list,hint,empty}` **全部删除**，全仓 grep 确认零引用。

验证：

```
前端 typecheck                         → 0 error
前端 build                             → 311 modules（主 chunk 452.94 kB / gzip 161.28 kB）
浏览器几何断言（CDP，真实 5173）        → 全部符合预期
  · 弹窗 460×448、圆角 14px、5 个 .cc-mpk 组
  · 下拉 3 个均高 34px、appearance=none、右内边距 30px
  · 自绘箭头右边距 10px、垂直偏差 0px
  · 模型行 34px 宽 424px、选中项勾选 opacity=1
  · 双列卡片 209×50 等宽、列间距 12px
静态预览自检                           → 12 项中 11 项 PASS
  · 内联 JS 语法、CSS 括号 1393/1393、div 1196/1196、button 399/399
  · 新类 15 个都有样式、旧类 5 个已清除、11 个关键 id 齐全
  · 唯一 FAIL 为 logToast（该元素由 JS 动态创建，属检查脚本假阳性）
```

## 十、L17 侧栏折叠修复与「更多」菜单收口

### 1. 折叠后「显示乱了」的根因

`.cc-shell.is-collapsed` 原本只做了一件事：网格第一列从 `--sidebar-w`（268px）收成
`--sidebar-w-collapsed`（64px）。但侧栏内部仍是 268px 的排版，挤进 64px 后：

- 品牌名「CodeCraft」压到折叠按钮上；
- 一级导航文字戳出侧栏右边界；
- 会话搜索框、会话列表缩成一条缝；
- 底部头像与两个图标按钮并排总宽超过 64px。

隐藏文字的那套规则当时只写在 `@media (max-width: 900px)` 里，桌面点折叠按钮完全用不到 —— 这就是「乱了」的直接原因。

修复方式：把「折叠态观感」抽成一组 `.cc-shell.is-collapsed` 规则（`app.css` 第 4 节），
并把 900px 断点改成同一套（窄屏自动折叠与手动折叠共用一个观感）：

| 处理 | 规则 |
| --- | --- |
| 隐藏文字节点 | `.cc-brand` / `.cc-new-chat span` / `.cc-nav__item span` / `.cc-user__name` |
| 隐藏整块交互 | `.cc-agentpick`（头像+名称+模型，64px 放不下）/ `.cc-conv-search` / `.cc-conv-list` |
| 头部改竖排 | logo 在上、折叠按钮在下；箭头 `rotate(180deg)` 暗示「点一下展开」 |
| 图标居中 | `.cc-nav__item` 取消 `padding` 与 `gap`，`justify-content: center` |
| 用户区竖排 | 头像 + 两个图标按钮 `flex-direction: column` |
| 底栏贴底 | `.cc-sidebar__foot` 加 `margin-top: auto`（会话列表隐藏后没有 `flex: 1` 元素压住底栏） |
| 窄屏同源 | 900px 断点补上 `.cc-agentpick` / `.cc-conv-search` / 头部竖排 / 用户区竖排 |

顺带三处体验补全：

- 折叠状态持久化到 `localStorage`（`codecraft.sidebar-collapsed`），刷新后保持；
- 折叠按钮 `title` 在「折叠侧栏 / 展开侧栏」之间切换；
- 一级导航项加 `title`，折叠后悬停仍能读出页面名。

### 2. 「更多」菜单移除

侧栏底部的「更多」弹层与「设置」页读的是**同一份** `MORE_GROUPS`（4 组 12 项），
等于同一份菜单挂了两处入口。现统一收口到「设置」：

- `AppSidebar` 删除「更多」入口、弹出面板，以及 `moreGroups` / `moreOpen` / `moreHint` / `onMoreClick`
  和 document 上的面板开合判定；
- `AppSidebar` 不再 import `MORE_GROUPS`（`menu.config.ts` 保留该导出，`SettingsView` 与路由守卫继续用）；
- 一级导航不变（对话 / 智能体 / 技能 / 任务 / 设置）；非管理员仍可从「设置」页进入其余可见页面。

### 3. 验证

```
前端 typecheck                  → 0 error
前端 build                      → 311 modules
CDP（/settings，1440×900）      → 31/31 PASS
  · 「更多」入口与面板均不存在；设置页 12 个入口齐全
  · 折叠后侧栏 64px；品牌名 / 导航文字 / 会话列表 / 搜索框 / 用户名全隐藏
  · 导航项宽 55px、图标水平偏移 0px（居中）、用户区 flex-direction=column
  · 逐元素比对 getBoundingClientRect：侧栏内无任何元素右边界超出
  · 底栏 gap=0（贴底）；刷新后折叠状态保持；再点恢复展开
  · 折叠态下点导航可正常跳转（#/chat）
CDP（/chat，1440×900 + 800px）  → 17/17 PASS
  · 聊天页折叠：智能体切换器与会话列表隐藏、无溢出
  · 窄屏 800px 自动折叠：侧栏 64px、文字全隐藏、无溢出、底栏贴底
  · 恢复宽屏：268px、无溢出
```

> 本轮按需求只改 Vue 应用，静态预览 `preview.html` 未同步（它仍然保留「更多」面板）。
> `app.css` 是两边共用的，折叠规则对静态预览是兼容的（只影响 `.is-collapsed` 与 900px 断点）。

## 十一、L18 侧栏「新建会话」按钮重做

### 1. 两个问题，一个根因 + 一处观感失衡

| 现象 | 根因 |
| --- | --- |
| 悬停没有手型光标 | 元素写的是 `<div class="cc-new-chat" @click>`；`app.css` 第 1 节的重置只给 `button` 加了 `cursor: pointer`，`div` 拿不到 |
| 观感不协调 | 36px 高、无描边、`--accent-lt` 淡紫底 + 紫字：既和满屏「1px 描边容器」的风格脱节，又和 `.cc-nav__item.is-active` 的选中底同色，容易被误读成「当前页」；上下间距 `4px / 12px` 不对称，块体看起来在飘；hover 只是把透明度 `.10 → .16`，几乎看不出反馈 |

### 2. 改动

- **`AppSidebar.vue`**：模板由 `<div>` 改 `<button type="button">`，顺带拿到手型光标、`Tab` 聚焦与 `Enter/Space` 激活；折叠态补 `title="新建会话"`（此时文字被隐藏）。
- **`app.css` 第 4 节**：重写 `.cc-new-chat` ——
  - 34px 高（与 `.cc-nav__item` 36px / `.cc-conv` 34px 同一档）、`--r-sm` 圆角、`1px solid var(--border-strong)` 描边、`--card` 白底；
  - 文字 `--text-1`、图标 `var(--accent)`（主色只留在图标上，不与导航选中态抢色）；
  - hover 只换底色 `--card → --card-2`，与 nav / 会话项同一种手感；`:active` 才落到 `--accent-lt` + 主色描边 + 主色文字做按下反馈；
  - `margin` 统一 `var(--sp-3)`（上下对称），左右边界与上方 `.cc-agentpick` 完全对齐（实测 left/right 差 0px）；
  - 折叠态与 900px 断点同步为 `margin: var(--sp-2) var(--sp-2) var(--sp-3)`，图标居中、48px 内无溢出。
- **`preview.html`**：同步把该 `<div>` 换成 `<button type="button" title="新建会话">`（样式两边共用，观感一致）。

### 3. 验证

```
前端 typecheck                     → 0 error
前端 build                         → 311 modules / 0 警告
CDP（/chat，1440×900）             → 18/18 PASS
  · tagName=BUTTON、type=button、cursor=pointer
  · 高 34px、1px solid 描边、圆角 8px、底色 rgb(255,255,255)
  · 图标 rgb(124,92,252) 主色、文字 rgb(26,26,46) 主文字色
  · 与 Agent 切换器左右边界差 0px / 0px
  · 鼠标移入后底色 → rgb(247,247,249)（--card-2）
  · 可脚本聚焦、tabIndex=0、focus-visible 轮廓正常
  · 折叠态：高 34px、宽 47px、文字 display:none、图标偏移 0.00px、无横向溢出、光标仍为 pointer
CDP（/agents，非聊天页无 Agent 切换器） → 9/9 PASS
  · 四边描边均 1px；图标与文字都完整落在按钮内、垂直居中偏差 ≤1px
  · 图标与文字间距 6px、无纵向溢出、按钮宽 > 200px
静态预览结构自检                    → 15/15 PASS
  · preview.html 中已是 <button>，无遗留 div；div/button/svg 等标签配对全对
  · app.css 规则含 cursor:pointer / 1px 描边 / hover→--card-2 / :active；旧 rgba hover 已移除
```

> `preview.html` 的按钮既无 JS 绑定也无跳转（静态稿只做观感展示），样式由共用的 `app.css` 提供。

---

## 十二、P0 地基：抽屉拖宽（F16）+ 未配置 Provider 引导页（F15）

> 对应 `PLAN-功能补齐路线图.md` 的阶段 P0。定位是「被后续功能反复复用」的两块地基：
> P3/P4 的 diff 面板、终端、多标签都要宽抽屉；每个页面都要处理「没有可用模型」这个前置条件。

### 1. F16 抽屉拖宽

**为什么要做成两层（composable + 组件）**

v3 有 **18 个 `.cc-drawer`**（聊天工具抽屉 + 17 个管理页编辑抽屉），模板结构完全一致：
`<aside class="cc-drawer" style="--drawer-w: 480px">`。如果每个组件都自己 import composable、
声明 6 个解构变量、把 `:style` 接上，等于把同一段样板抄 18 遍 —— 新增抽屉必然漏掉，宽度语义也会分叉。

所以拆成：

| 层 | 文件 | 职责 |
|---|---|---|
| 逻辑 | `src/utils/drawer-resize.ts` | `useDrawerResize(key, opts)`：宽度状态、视口上限收敛、指针拖拽、持久化、复位 |
| 适配 | `src/components/DrawerResizer.vue` | 从父元素反读 `--drawer-w` 作为默认宽度；拖拽时**直接改父元素的 CSS 变量** |
| 样式 | `assets/app.css` §14 | `.cc-drawer__resizer` 命中区/视觉线；`body.cc-resizing` 全局光标与禁选 |

接入只需一行（模板里放进抽屉第一行）：

```html
<aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
  <DrawerResizer storage-key="agent-edit" />
```

**已接入的 18 个抽屉**：`ChatView`（chat-tools）、Agent / Skill / MCP / 定时任务 / 踩坑经验 / DB 连接 /
DB 执行 / 文件详情 / 云端文件 / P2P 三件套 / Provider / 用户 / 菜单 / 角色 / 数据重置。

**边界语义**

- 只改宽度、不动 `position: fixed` 的 right 锚点 → 左边界严格跟手；
- 上限同时受 `max`(1200) 与视口约束（≤72% 视口、主区至少留 320px），不会把对话区挤成一条缝；
- **窄屏（≤900px）不响应拖拽**，宽度由 CSS `min(var(--drawer-w, 400px), 100vw)` 兜底；
- 宽度按 `codecraft.drawerWidth.<key>` 持久化；双击分隔条复位并清记录；
- 用户设定值与实际宽度**分离**：窗口变窄时实际宽度自动收敛，恢复后自动回弹（见下方第 2 条排查）。

**这一轮真修掉的一个缺陷**

`width` 原本是只依赖 `desiredWidth` 的 computed，而 `upperBound()` 读的 `window.innerWidth`
不是响应式数据 —— 结果是「拖到 600px 后把窗口缩到 800px，抽屉仍然 600px 宽，直接压掉主区」，
窗口恢复后也不会回弹（注释承诺了、代码没做到）。修法是加一个 `viewportTick` ref 建立显式依赖，
并在 `resize` 时自增。CDP 用 600 → 480 → 600 三段断言锁住了这个行为。

### 2. F15 未配置 Provider 引导页

`GET /api/llm-providers` 只返回 `enabled = 1` 的记录，空数组即「一个可用模型都没有」。
此时聊天页照常能输入、能点发送，然后请求一路失败 —— 用户看到的是一句莫名其妙的接口错误。

新增 `src/components/ProviderGuide.vue`（挂在 `ChatView` 空态分支之前），判定三条件缺一不可：

| 条件 | 为什么 |
|---|---|
| `providers.length === 0` | 真的没有可用 Provider |
| `!providerLoading` | 首屏有一段空数组窗口，抢在加载完成前显示会闪一下 |
| `!providerError` | 后端连不上属另一类故障，显示「没有 Provider」会误导用户白改配置 |

角色差异：管理员 → 「去配置」跳 `/providers`；普通用户 → 按钮置灰 + 文案「联系管理员配置」+
黄字提示（Provider 读写接口都要 `checkAdmin`，普通用户去了也改不了）。

`App.vue` 把 `providerList` / `providerLoading` / `providerError` / `isAdmin` 一起注入
`PAGE_BINDINGS['/chat'].props`，新增两个事件 `configure-provider` / `refresh-providers`。

### 3. 静态预览同步

- `preview.html` 内联样式新增 `body[data-state="noprov"]` 分支（隐藏消息流/输入区、显示 `.cc-pguide`）；
- 预览条状态组加「未配置模型」按钮；
- 引导页 HTML 与 Vue 同构，「去配置」走 `switchPage('providers')`；
- 新增 `initDrawerResize()`：给**所有** `.cc-drawer` 插分隔条并绑定拖拽/持久化/双击复位/窄屏禁用，
  与 Vue 侧同一套规则（18 个抽屉全部生效，含继承 `tokens.css --drawer-w: 340px` 的 `#drawer`）。

### 4. 验证

```
前端 typecheck                        → 0 error
前端 build                            → 316 modules / 0 警告（DrawerResizer 独立 chunk 2.73 kB）
CDP（Vue /chat，1440×900）            → 30/30 PASS
  · 分隔条渲染、cursor=col-resize、默认宽 400px
  · 拖到 600px → CSS 变量同步、localStorage 落 600、body 类清理
  · 刷新后仍 600px；窄屏 800px 时压到 480px 且分隔条 display:none；恢复 1440 回弹 600px
  · 双击复位 400px 并清空持久化
  · Provider 空列表（Fetch 拦截）→ 引导页出现、三步说明 3 条、管理员按钮可点、跳 /providers
  · /user/current 伪装 role=user → 按钮置灰 + 文案「联系管理员配置」+ 黄字提示
CDP（preview.html）                   → 16/16 PASS
  · 引导页默认隐藏；切「未配置模型」→ display:flex、原空态 display:none、三步齐全
  · 「去配置」→ body[data-page]=providers
  · agentDrawer 默认 480px → 拖到 630px → 持久化 → 双击复位 480px
  · 18 个抽屉全部装上分隔条
静态预览结构自检                       → 22/22 PASS（内联 JS 4773 行语法、标签配对、CSS 括号 1424/1424）
```

### 5. 三条排查记录

1. **`Page.navigate` 到相同 URL 不会真正 reload**（老坑复现）：上一轮用例留下的内联
   `--drawer-w` 会被带进下一轮，导致「默认宽度 400px」随机变成 320px。修法是先绕 `about:blank` 再回目标页。
2. **headless 视口不稳定**：复用调试实例时上一轮的 `Emulation.setDeviceMetricsOverride(800×900)`
   可能残留（更麻烦的是 `clearDeviceMetricsOverride` 会回到 headless 默认的 800×600），
   而宽度上限是按视口算的。改为每轮显式钉死 1440×900。
3. **`<link ... />` 会被 `<li[^>]*/>` 误匹配**：静态自检脚本的标签配对正则必须带标签名边界
   （`<li(?=[\s/])`），否则 li 会数出 24/24/2 的假不平衡。

---

## 十三、P1 聊天页体验增强（F13 / F12 / F14 / F08）

**目标**：把聊天页从「能用」做到「好用」——工具结果看得懂、任务清单有结构、图片点得开、长会话不卡。

### 1. F13 工具结果语义化渲染

**问题**：所有工具输出都被压进一个 `<pre>`（`detail` 截断 4000 字符），命令输出、搜索结果、
文件改动长得一模一样，用户得自己在一坨等宽文本里找重点；折叠态更是只能看到第一行。

**做法**：

| 新增 | 作用 |
| --- | --- |
| `src/utils/tool-render.ts` | 把「工具名 + 原始输出」归一成 `ToolRenderModel` 联合类型（file-write / file-read / file-tree / search / command / web / db / git / agent / task / error / raw） |
| `src/components/ToolResultCard.vue` | 按 kind 渲染卡片：文件改动带 `+N/-M` 徽章与 diff 着色、命令用终端块 + 退出码色标、搜索给命中列表、网页给标题 + 域名 + 摘要 |
| `toolHeadline()` | 折叠态轻量摘要（只看前几行 + `[TOOL_RESULT]` 徽章），不进全文解析 |
| `clipToolDetail()` | 截断改为**保头也保尾**：尾部常带 `[TOOL_RESULT]`（增删行数）与 `---TASK_JSON---`（任务清单），一刀切会永远显示 `+0 -0` |

**解析规则来源**（实测真实会话，不是猜的）：

```
> **file_writer**
✅ 写入成功：C:\...\drawer-resize.ts
大小：7.0 KB
[TOOL_RESULT]{"additions":185,"deletions":0}[/TOOL_RESULT]

> **command**
执行命令：npm run typecheck
退出码：0
耗时：6.4 秒
<stdout>

> **file_explorer**（read / tree / grep / glob 四种首行特征）
> **task_manager**（尾部 ---TASK_JSON--- + JSON 数组）
```

未知工具（如 MCP `mcp_github_list_issues`）一律降级 `raw`：等宽原文 + 「查看原始输出」，
既不报错也不会丢失信息。

**验证**：`_test_toolrender.cjs` 用 15 组真实样本做单测，**85/85 通过**（含边界：空输出、
纯空行、MCP 未知工具、缓存一致性）。

### 2. F12 任务清单优先级与依赖

**问题**：`TaskItem` 只有 `{id, title, status}`，后端 `task_manager` 输出的 `priority` /
`depends_on` 被直接丢掉 —— 「6 个任务里有 3 个必须等前两个做完」这种信息在界面上完全看不出来。

**做法**：

- 新建 `src/utils/task-list.ts`：`TaskItem`（+ `priority` / `dependsOn` / `group` / `failed`）、
  `normalizeStatus` / `normalizePriority` / `normalizeDepends`、`blockedBy` / `isBlocked` / `taskStats`。
- 阻塞判定口径：自身已完成不算阻塞；依赖任务**在同一清单且未完成**才算；依赖不存在（脏数据）忽略 ——
  否则一条脏依赖会把整行永久标红。
- 渲染：左侧 3px 色条（HIGH 红 / MEDIUM 橙 / LOW 灰）+ 优先级文字徽章 + 「等待 T1、T3」小字；
  状态栏与浮层头部增加「N 阻塞」徽章。
- `TaskItem` 定义从 `ChatTaskPanel.vue` 上移到 utils，三个消费方（ChatMessage / ChatView / App.vue）
  共用一份口径，避免浮层、内嵌面板、历史回放各写一遍 filter。

**验证**：`_test_tasklist.cjs` **32/32 通过**（状态/优先级/依赖归一化、阻塞传递、完成后解除、
脏依赖忽略、空清单）。

### 3. F14 图片灯箱

**做法**：

- 新建 `src/components/ImageLightbox.vue`：全屏遮罩（`z-index: 190`，低于 Toast 200 / Confirm 210）、
  多图左右切换（← → / 两侧箭头）、滚轮缩放 1x–4x、缩放后拖拽平移、双击复位、下载原图、ESC 关闭。
- 新建 `src/utils/lightbox.ts` + `LightboxHost.vue`：**模块级单例**（与 toast / confirm 同一套路），
  调用方只需 `openLightbox(images, index)`，不必层层透传 3 个 prop，也不会出现「同时开两个灯箱」。
- 接入点：聊天输入区图片附件（48×48 缩略图 + hover 出现的移除按钮）、历史文件选择器缩略图
  （hover 放大镜，`stopPropagation` 不触发选中）、文件详情抽屉预览图。
- 下载优先 `fetch → blob`（保留文件名），跨域失败退回新标签页打开。

### 4. F08 消息列表窗口化

**为什么不用 `vue-virtual-scroller`**：消息高度极不稳定（思考折叠、工具展开、代码块、图片、diff），
测量失准就会出现占位块与真实内容高度不一致 —— 滚动条会跳；且要与「流式自动贴底 /
ResizeObserver 校正 / 触顶加载更早」三套既有逻辑重新对齐。

**做法**（`src/utils/virtual-list.ts` 的 `useTailWindow`）：尾部窗口 + 顶部占位块。
默认渲染最近 60 条，每次展开多放 40 条；`size` 只增不减；流式新消息永远落在窗口尾部。

- 触顶时**先展开本地窗口**，本地放完才 `emit('load-more')` —— 反过来会出现
  「内容明明在内存里，却先发一次网络请求」。
- 展开时按「距底部距离」反算 `scrollTop` 做补偿（新内容插在上方，不补偿会视觉跳一下），
  并把 `follow` 置 false，避免 ResizeObserver 的贴底逻辑把正在看历史的用户拽回最新。
- 消息数 ≤ 60 时与改造前完全等价（无占位块），风险可控。

### 5. 静态预览同步（`preview.html`）

- 任务浮层：优先级色条 + `HIGH/MEDIUM/LOW` 徽章 + `等待 T4` 标签 + 头部「1 阻塞」。
- 工具卡片：折叠态加语义标签与徽章，展开态改成 `.cc-tr` 结构化卡片（文件改动 diff / 终端块）。
- 附件：图片附件改 48×48 缩略图，点击开灯箱；灯箱 DOM + 完整 JS（显隐用 `.is-open` 类，
  **不能用 `hidden` 属性** —— `.cc-lb{display:flex}` 会覆盖 UA 的 `[hidden]{display:none}`）。
- ESC 链路把灯箱插到最高优先（灯箱 → 模型弹层 → 目录弹窗 → 补充需求 → 任务浮层 → 抽屉）。
- 消息流顶部加窗口占位块（纯视觉参照，静态预览没有窗口化逻辑）。

### 6. 验证

```
npm run typecheck                        → 0 error
npm run build                            → 326 modules / 873ms（主 chunk 470.80 kB，gzip 167.57 kB）
tool-render 单测（15 组真实样本）          → 85/85 PASS
task-list 单测                            → 32/32 PASS
preview.html 自检                         → 36/37 PASS（唯一 FAIL 是 logToast：动态创建元素，脚本假阳性）
```

### 7. 已知边界

1. **窗口化是「尾部窗口」而非真虚拟列表**：极长会话滚到最上面不是无限回放，需要点几次「展开」
   （每次都有明确按钮与条数）。换来的是零行高测量、不会跳动。
2. **工具卡片的解析是启发式的**：后端换措辞时某些字段抽不到（例如新增一种「已移动」提示），
   卡片会退化成字段缺失但**不会报错**，底部「查看原始输出」始终能看原文。
3. **灯箱下载受跨域限制**：`fetch` 拿不到 CORS 头的地址会退回新标签页打开（无法静默存盘）。

---

## 十四、P2 管理页补全（F07 / F17 / F18）

> 目标：把管理页最后几处「写死」和「无入口」补齐 —— 头像能上传、工具能按真实注册表选。

### 1. 新增文件

| 文件 | 作用 |
|---|---|
| `src/utils/image-compress.ts` | 上传前按需压缩（阈值 1.5MB **或** 长边 2048px 才压 → canvas 等比缩放 → JPEG 0.85）；GIF 不压、压完更大则用原图、任何异常回退原图（**绝不阻断上传**） |
| `src/utils/tool-catalog.ts` | 工具目录：`toToolGroups()` 把注册表响应映射成分组选项、`shortToolLabel()` 精简 chip 文案、`parseToolNames()` 解析 `tool_names`、`FALLBACK_TOOL_GROUPS` 内置真实快照 |
| `src/components/AvatarUploader.vue` | 头像上传器（圆形 / 空态虚线 / hover 遮罩「更换」/ 右上角「移除」/ 上传中转圈并禁用保存） |
| `assets/app.css` §45 | `.cc-avatar-up*`、`.cc-avatar-field*` 样式；原响应式节顺延为 §46 |

### 2. F07 用户头像 / F17 智能体头像

- 两个抽屉都复用 `AvatarUploader`，`v-model` 绑 `form.avatar`，**只拿 URL 不写库** ——
  用户点「取消」即丢弃，不会留下脏数据。
- 上传中 `avatarUploading` 为 true → 保存按钮 disabled（避免把旧 URL 提交上去）。
- 提交语义：
  - 用户 `PUT /user/update` 传空串 = 清除（后端只在 `avatar == null` 时继承原值）；
  - 智能体 `PUT /agent-configs/{id}` 传空串 = 清空 → 卡片回落名字首字（新建时后端仍会给 `🤖` 兜底）。
- **改自己头像**时额外调 `refreshProfile()`，侧栏与聊天气泡立即跟着变（`/user/current` 不返回头像，靠 `/user/profile` 补）。
- `AgentCard.vue` 的头像判定从自写正则改为共用 `utils/avatar.ts` 的 `isImageAvatar`，与侧栏 / 聊天气泡 / Agent 切换器一套规则（emoji 头像不会被塞进 `<img src>` 变裂图）。

### 3. F18 工具选择（真实数据源）

**问题**：`AgentEditDrawer` 的工具选项原本是写死的 `file / command / git / web / sql / agent` 六个假 key。
后端 `tool_names` 是 **工具名 JSON 数组**，`DeepSeekServiceImpl` 解析后逐项与 `ToolRegistry` 比对 ——
写死的假 key 一个都匹配不上，等于把该 Agent 的工具全删了。所以这不是「选项不准」，是「保存即致残」。

**做法**：

- `App.vue` 进 `/agents` 时并行拉 `GET /api/tools/registry`（10 组 27 个），失败静默降级（不弹 toast，抽屉内有兜底）；
- 抽屉里按分类分组渲染，每组带「本组已选 / 总数」与「全选本组」按钮；
- 顶部搜索框（命中短名 / 工具名 / 完整描述），带「清空搜索」；
- 「全部工具」开启时整块 `opacity:.55` + `pointer-events:none`（表意 = `tool_names` 留空）；
- 高危工具（`risk=high`，如 `command` / `execute_sql` / `desktop_control`）chip 前缀一个小暖色圆点，完整风险信息放 `title`；
- 历史数据里已下线的工具名**保留不显示但原样提交**（否则等于静默删授权），底部提示「保留 N 个未在注册表中的历史工具」；
- 注册表拉不到时回退 `FALLBACK_TOOL_GROUPS`（**真实工具名快照**，不是假 key），底部黄字提示。

**兜底表为什么必须用真名**：假 key 存进库，Agent 会一个工具都调不出来 —— 比「看不到选项」严重得多。

### 4. 顺带修掉的两个真实缺陷

1. **`toAgentRow` 解析 `toolNames` 错**：原来 `.split(',')` 直接切 JSON 字符串，
   `["file_writer","command"]` 会被切成 `["file_writer"` 这类碎片，卡片「N 个工具」永远显示错数字。
   改走 `parseToolNames()`（JSON 优先、逗号串兼容）。
2. **后端 `"[]"` 会让 Agent 无工具**：`AgentConfigServiceImpl.update` 原逻辑只有当数据库原值为 NULL 时才保留 NULL，
   否则会把 `"[]"` 原样写库 → `objectMapper.readValue` 解析出空列表 → 该 Agent 无任何工具。
   改为 **`"[]"` 与空串一律归一为 NULL**（NULL 才命中「回退默认工具集」分支），并给 `AgentConfigMapper.update`
   的 `tool_names` 补 `jdbcType=VARCHAR`（保证 null 能正确写库）。**该修复需重启 8084 后端才生效**。

### 5. 静态预览同步（`preview.html`）

- 智能体抽屉顶部加头像区（`objectURL` 演示，注释写明真实链路是 `compressImageIfNeeded → POST /api/avatar`）；
- 工具集改为 `.cc-toolsel` 分组结构：10 组 27 个真实工具名 + 搜索 + 组内全选 + 已选计数 + 高危点；
- `fillAgentEditState()` / `fillAgentCreateState()` 一并重置工具选择与头像（编辑 = 🌙 全部工具，新建 = 🤖 全部工具）；
- 用户抽屉顶部加头像区（无头像时显示昵称首字，`移除` 按钮仅在有图时出现）；
- `saveUser()` 的 payload 补 `avatar`。

### 6. 验证

```
npm run typecheck                         → 0 error
npm run build                             → 332 modules / 841ms（AvatarUploader 独立 chunk 4.15 kB）
mvn test-compile（后端）                   → 通过
preview.html 自检                          → 19/19 PASS
Vue 浏览器 CDP                             → 19/19 PASS
静态预览 CDP                               → 14/14 PASS
接口探针（8084 真实后端）                    → 11/11 PASS
```

接口探针细节（**用临时 Agent，未碰真实数据**）：

- `POST /api/avatar` → `/api/avatar/file/b9ac….png`，`GET` 该地址 HTTP 200（免鉴权）；
- 创建临时 Agent（`toolNames=["file_writer","command","git_query"]` + 上传的头像）→ 列表读回**逐字一致**；
- 更新为 `toolNames: ''` → 落库为空串（语义 = 全部工具，后端解析时空值回退默认工具集）；
- 删除临时 Agent → 复查列表只剩 1 个（内置「圆圆」），头像文件保留但无引用。

浏览器实测要点：抽屉 10 组 27 个工具与注册表一致；「全部工具」默认开启灰显、关闭后出现 10 个「全选本组」；
本组全选后计数 `已选 4 / 27`；搜索 `git` 只剩 1 组 6 个；清空恢复 10 组且计数保留；
头像位 64×64、`border-radius: 999px`、`accept` 含 6 种图片格式；用户抽屉读到 admin 的真实头像（`<img>`）与「移除」按钮同步出现。

### 7. 已知边界

1. **注册表只在首次进 `/agents` 拉一次**：后端新增工具后需刷新页面才看得到（不做轮询，避免无谓请求）。
2. **头像文件不做「旧文件回收」**：`POST /api/avatar` 没有对应的删除接口，换头像会留下孤儿文件（本机磁盘，量级很小）。
3. **`FALLBACK_TOOL_GROUPS` 是 2026-10 的快照**：后端新增/改名工具后要同步这张表，否则断网兜底时会缺项（不影响真实注册表路径）。
4. **后端 toolNames 归一未生效前**：旧 jar 下前端提交空串仍是安全的（旧代码会写空串 → 后端解析时空值回退默认工具集），
   但旧前端若提交过 `"[]"` 的 Agent 会处于「无工具」状态，重启后端并重新保存一次即可恢复。

---

## 十五、P3 代码工作区底座（F11 / F09 / F10）

> 目标：把右侧「文件面板」从只读文本框升级为**可看、可改、可精确撤销**的代码工作区。
> 这一阶段是 P4（多标签 + 分屏）的前置——编辑器与 diff 共用同一套行模型。

### 1. 新增 / 改动文件

| 文件 | 作用 |
|---|---|
| `src/utils/language-detect.ts` | 扩展名 + 文件名（`Dockerfile`、`.gitignore` 这类无扩展名也能认）→ hljs 语言、语言展示名、大文件阈值（2 MB / 2 万行）、体积与行数格式化 |
| `src/components/CodeEditor.vue` | 伪富文本编辑器：高亮层 + 透明 textarea + 行号栏 + 当前行高亮 + 查找条 + Tab/自动缩进 + `Ctrl+S`/`Ctrl+F` + 大文件只读 |
| `src/utils/diff-hunk.ts` | unified diff 解析、前端 LCS 计算 hunks、hunk / 单行 patch 生成（喂给 `git apply --reverse`）、双栏拆分 |
| `src/components/DiffViewer.vue` | 单栏 / 双栏、hunk 折叠、`+A/-D` 统计、撤销块 / 撤销行 |
| `assets/app.css` §46 / §47 | `.cc-code-ed*` 与 `.cc-dv*` 样式；响应式节顺延为 §48，并在 900px 断点补 6 条规则 |
| `src/components/ProjectFileTree.vue` | 用 `CodeEditor` 替换原 `<textarea>`；`dirty` 从布尔 ref 改为内容比较；切文件 / 切目录 / 关面板都走未保存确认 |
| `src/components/GitChangePanel.vue` | 纯文本 `<pre>` diff 换成 `DiffViewer`；新增 `onRestorePatch()` 把 patch 交给后端并就地重算 |
| `src/components/ChangeReview.vue` | 每个文件行加「查看差异」（只读）；新增 `projectRoot` prop |
| `src/App.vue` | 给 `ChangeReview` 透传 `:project-root="chatProjectRoot"` |

### 2. F11 代码编辑器

**为什么是伪富文本**：CodeMirror / Monaco 主包都在 1MB 量级，而这里的需求只有「着色 + 行号 + 查找 + 保存」，
300 行伪富文本能覆盖，体积代价为 0（hls 语言已为 Markdown 渲染注册过，直接复用同一个单例）。

**三层结构与三条硬约束（改样式时必须同时满足，否则文字与光标会错位）**：

1. 高亮层 `<pre>` 与 `<textarea>` 的 `font-family` / `font-size(13px)` / `line-height(20px)` / `padding(8px 12px)` / `tab-size` / `letter-spacing` 必须完全一致；
2. 行高固定 **20px** —— 行号栏、当前行高亮条、查找跳转全部按 `(行号-1)*20` 算位置；
3. 滚动由 **textarea 驱动**，高亮层与行号栏用 `translate` 跟随（两者都 `overflow:hidden`，改 `scrollTop` 没有效果）。

**几个细节**：

- textarea 文字 `color: transparent` 只留 `caret-color`；`::selection` 用半透明紫，高亮层的 `::selection` 强制透明 —— 否则选中的是一片纯色块、看不到底层文字；
- hljs 主题不复制：把 `.cc-code` 类挂到高亮层上复用第 12 节的规则，再用 `.cc-code-ed__pre.cc-code` 把它的「代码块容器」外观（边框/圆角/背景/margin）抹掉。这样以后调主题色只改一处；
- **未保存判定改成内容比较**（`fileContent !== fileOriginal`）：旧实现用一个 `dirty` 布尔，切文件时忘记重置就会误判，比较内容则天然自洽；
- 大文件（> 2 MB 或 > 2 万行）自动降级为只读并在头部/顶部给出原因，不假装能编辑；
- 关闭面板、切文件、切换浏览目录都会弹「放弃未保存的修改？」确认；切换 Agent 属被动变化，改为 toast 提示后直接丢弃（弹窗会打断切换流程）。

### 3. F09 改动明细 diff 查看

两条数据通道，共用同一套 `DiffHunk` 行模型：

| 场景 | 数据来源 | 能否 hunk 撤销 |
|---|---|---|
| Git 变更（`GitChangePanel`） | `GET /api/git/diff?projectRoot=&file=` 的 unified 原文 | ✅ 可（需 git 仓库） |
| 快照轮次（`ChangeReview`） | `GET /api/snapshots/file-content`（基线）+ `GET /api/project/read`（当前） | ❌ 只读，撤销走整文件回滚 |

**为什么 Git 用后端 diff、快照用前端算**：Git 场景下 `git diff` 的行号天然准确；快照场景后端只给两份文本，
必须在浏览器里算 LCS。前端 LCS 先裁掉公共前后缀，剩余矩阵 > 400 万格时降级为逐行对齐（大文件不卡死）。

**渲染**：单栏按 hunk 分组，hunk 头 `position: sticky` 常驻；双栏用 `splitHunk()` 把同一 hunk 的新增/删除行配成左右两列
（不足补空占位，保证两栏同高）；hunk 总数 > 4000 行时默认全部折叠，先给概览再逐个展开。

### 4. F10 逐块 / 逐行撤销

后端 `POST /api/git/restore-hunks` 会补 `--- a/f` / `+++ b/f` 头后执行 `git apply --reverse --unidiff-zero`。
撤销整块时把 hunk 原文原样传回即可；**撤销单行必须在浏览器里重写 patch**，否则会把整块一起撤掉：

| 原行类型 | 非目标行怎么处理 | 原因 |
|---|---|---|
| `+`（新增） | 改写成**上下文行**（前缀空格） | 该行在工作区确实存在，apply 时「不动」即可 |
| `-`（删除） | **从 patch 中剔除** | 工作区里本来就没有它，留在 patch 里会校验失败 |

重写后 **new 侧行号与行数保持不变** —— 而 `--reverse` 正是用 new 侧定位与校验，所以能精确命中目标行。

其它保护：

- 只有 `GET /api/git/status` 返回 `isRepo=true` 才显示撤销按钮（非 git 目录按钮不出现，而不是点了才报错）；
- 撤销失败时把后端 `error` / `output` 原文透出，不吞成「操作失败」；
- 成功后局部重算该文件 diff（仍有余量就刷新，干净了就收起面板），并 `refresh()` 同步变更列表；
- 快照视图一律不显示 hunk 撤销按钮（`restorable=false`），避免「以为能撤一块、其实回滚了整个文件」。

### 5. 修掉的两个真实缺陷

1. **`parseUnifiedDiff` 丢掉裸 diff**：`if (!file) continue` 挡在 `--- ` 分支之前，导致「只有 `--- / +++ / @@` 没有
   `diff --git` 头」的输入整段被静默丢弃（单元测试发现）。改为遇到 `--- ` 时先补一个匿名文件对象再解析。
2. **新建文件的快照差异永远打不开**：后端 `SnapshotService.getSnapshotFileContent` 的注释写明
   「如果是新建文件则返回 null」，前端却把 null 一律当失败。改为先看 `SessionFileChange.wasNewFile` —— 新建文件
   基线取空串，diff 会把整个文件显示为新增（符合直觉）。

### 6. 验证

```
npm run typecheck                    → 0 error
npm run build                        → 338 modules / 859ms（主 chunk 491.96 kB / gzip 173.82 kB）
diff-hunk 单元测试                    → 16/16 PASS（解析 / hunk 还原 / 单行 patch / LCS / 双栏拆分 / 大文件降级）
真实 git 端到端（临时仓库 + 8084）      → 15/15 PASS
Vue 浏览器 CDP（编辑器 + Git 差异）     → 23/23 PASS
静态预览 CDP                          → 17/17 PASS
改动面板「查看差异」CDP                → 12/12 PASS
```

**端到端验证做了什么**（独立临时仓库 `C:\Temp\cc-p3-e2e`，不碰用户工作区）：

- 造 2 个 hunk（第 3 行改写 + 第 20 行后插两行）→ 撤销整块 → 第 3 行还原、另一处改动保留；
- 撤销 hunk 里的第一个新增行 → 该行消失、**同块另一个新增行保留**、另一处改动不受影响；
- 纯删除 hunk → 恢复删除行后文件与原始内容**逐字节一致**；
- 非法 patch → 后端返回 `success:false`（不会静默成功）。

CDP 断言要点：行号栏与高亮层 `padding-top` 一致（都是 8px）、高亮层 `line-height` 为 20px、输入后「未保存」出现且
行号数跟着涨、Git 差异显示 `+A/-D` 与「撤销此块」、快照视图**没有**撤销按钮。

### 7. 踩坑留档

1. **CDP 定位文件树节点**：一行里有两个 `<use>`（展开箭头 + 文件/文件夹图标），`querySelector('use')` 拿到的是箭头，
   必须 `querySelectorAll('use').some(...)` 才能判对类型。
2. **Windows 上 `git apply` 会按 `core.autocrlf` 改换行**：端到端断言必须先把 `\r\n` 归一化，否则失败的是断言而不是逻辑。
3. **`patchForLine` 的不变量是 new 侧**：old 侧行数会因为「非目标删除行被剔除」而变少，属预期；断言只能盯 new 侧。

### 8. 已知边界

1. **快照视图只读**：hunk 撤销依赖 git 索引，快照没有索引可依，撤销粒度只能是整文件回滚。
2. **非 git 仓库 / 未跟踪文件**：不显示撤销入口（未跟踪文件在 HEAD 里没有对应版本）。
3. **伪富文本不走原生撤销栈**：`Tab` 缩进与回车自动缩进是用脚本改文本，这两类操作的 `Ctrl+Z` 行为与手打不同；
   普通键入仍是浏览器原生行为。
4. **双栏模式只读**：两栏的价值在「看」，撤销入口只在单栏（每行右侧 hover 出现）。
5. **2 MB / 2 万行以上只读**：逐行高亮与行号栏在更大量级下会明显掉帧，阈值集中在 `FILE_LIMITS` 一处，可调。
6. **静态预览只同步外观**：`preview.html` 的编辑器与差异视图是手写 hljs span 的静态复现，不连后端、不能真的编辑与撤销。

---

## 十六、P4 多标签工作台（F02 / F03 / F04）

路线图里工程量最大的一阶段：把聊天页右侧的「单抽屉」升级为可切换、可并排、可看网页的工作台。
三块能力分别是 F02 多标签、F03 分屏、F04 内嵌网页。

| 新增文件 | 作用 |
|---|---|
| `src/store/workspace.ts` | 标签 / 两栏 / 布局 / 比例 / 宽度 + 全部动作（pinia + persist） |
| `src/components/WorkspacePanel.vue` | 工作区外壳：头部（布局切换 / 收起 / 切回抽屉）+ 快捷键 + 拖宽 |
| `src/components/WorkspacePane.vue` | 单栏容器：标签栏 + 新建菜单 + 内容区 + 关标签二次确认 |
| `src/components/WorkspaceTabs.vue` | 标签栏：图标 / 标题 / dirty 圆点 / 中键关闭 / 拖拽排序 / 跨栏移动 |
| `src/components/WorkspaceTabView.vue` | 按 kind 分发内容（tree / file / diff / git / term / web / image） |
| `src/components/WorkspaceFileView.vue` | 文件标签：读 / 存 + 未保存上报（`/project/read`、`/project/write`） |
| `src/components/WorkspaceDiffView.vue` | 差异标签：只读，复用 P3 的 DiffViewer（`/api/git/diff`） |
| `src/components/SplitPane.vue` | 分屏容器：横 / 纵、`fr` 比例、最小宽保护、双击复位 |
| `src/components/WebFrame.vue` | 内嵌网页：地址栏 + 前进后退刷新 + `iframe`/`webview` 双通道 + 禁嵌降级 |
| `src/utils/web-url.ts` | 网址规范化与协议白名单（http/https only） |

修改：`src/views/ChatView.vue`（两列骨架 + 工具按钮分流 + 路径点击）、`src/App.vue`（外观页接入 workspaceMode）、
`src/store/settings.ts`（`workspaceMode`）、`src/views/AppearanceView.vue`（右侧面板下拉）、
`src/components/ProjectFileTree.vue`（`externalOpen`）、`assets/app.css`（§48 工作区 / §49 内嵌网页 / 响应式顺延 §51）、
`preview.html`（同构演示）。

### 1. 布局骨架：从单列到两列

```
┌──────────────┬──────────────────────────┐
│              │ 工作区头部（布局 / 收起）  │
│  聊天主列     ├──────────────────────────┤
│  header      │ 标签栏  [文件][终端][+]    │
│  消息流       ├──────────────────────────┤
│  运行状态栏   │                          │
│  输入区       │      当前标签内容          │
└──────────────┴──────────────────────────┘
```

`ChatView.vue` 的外层加了 `.cc-chat`（`display:flex`），左列 `.cc-chat__main` 是原来的整条聊天流程，
右列是 `WorkspacePanel`。右列不渲染时（未展开 / 抽屉模式）左列 `flex:1` 吃满，**与改造前完全等价** ——
这是选择「包一层容器」而不是「重写模板」的原因：400 行模板的缩进一处没动，diff 只看得到首尾两行。

### 2. F02 多标签

`src/store/workspace.ts`（pinia + persist）持有全部状态：

| 字段 | 含义 |
|---|---|
| `tabs[]` | 标签数组，每个标签带 `kind / title / payload / dirty / pane` |
| `activeMain` / `activeSide` | 两栏各自的激活标签（分屏时两栏独立） |
| `layout` | `single` / `split-h` / `split-v` |
| `ratio` | 分屏比例（0.2~0.8） |
| `width` | 工作区宽度（320~1200，可拖拽） |

七个标签类型：`tree`（文件树）、`file`（编辑器）、`diff`（差异）、`git`（Git 面板）、`term`（终端）、
`web`（内嵌网页）、`image`（图片）。

几个刻意的设计：

- **同资源只开一个标签**：去重键按语义区分 —— 文件用路径、网页用 URL、文件树/Git/终端天然单例。
  重复点同一个文件不会堆出十个标签，而是激活已有的那个（`openTab` 返回已存在标签的 id）。
- **状态隔离靠「实例隔离」**：每个标签渲染一个 `WorkspaceTabView`，所有标签都创建、只显示激活的那个（`v-show`）。
  切回来时滚动位置、未保存的编辑内容都还在 —— 这正是多标签相对抽屉的核心收益。
- **终端是唯一例外**：xterm 在 `display:none` 容器里初始化会拿到 0 尺寸，切回来变成一条缝，
  所以 `term` 标签只在激活时挂载（`v-if`），切走即销毁。命令历史已落 localStorage，丢的只是输出。
- **未保存标记走组件上报**：`WorkspaceFileView` 用 `content !== original` 判定 dirty，`watch` 上报给 store，
  标签栏据此亮黄点；关标签时 `WorkspacePane` 弹二次确认（确认后才 `markDirty(false)` 再关闭）。
- **标签上限 16**：persist 里堆几百个标签会拖慢启动，超限时 `openTab` 返回 `null`，调用方 toast 提示。

### 3. F03 分屏

`SplitPane.vue` 用 CSS Grid 的 `fr` 表达比例：

```css
grid-template-columns: minmax(0, 0.55fr) 6px minmax(0, 0.45fr)
```

- **比例用 fr 而不是像素**：窗口 resize 时两栏自动等比缩放，不需要监听 resize 重算；
- **最小宽保护**：`minSize=280px` 换算成比例下限，容器放不下两倍 280 时退化为固定 50%（避免比例来回弹）；
- **拖拽用 Pointer Events**：监听挂在 `window` 上，指针拖出容器外也能正确收尾；拖拽期给 `body` 加
  `cc-split-drag-h/v` 统一光标（否则指针离开分隔条后光标会变回默认，观感很差）；
- **双击复位 50%**；
- **进/出分屏不丢标签**：切到分屏时把主栏「非当前」的最后一个标签挪到副栏（否则右半屏是空的）；
  切回单栏时副栏标签并回主栏。副栏被关空时自动回单栏。

### 4. F04 内嵌网页

`WebFrame.vue` 双通道：Electron 用 `<webview>`（能力完整），浏览器用 `<iframe sandbox>`。

**关于「禁嵌检测」的诚实做法**：跨域 iframe 被 `X-Frame-Options` 拒绝时，浏览器**不会**向前端抛任何事件，
`load` 依然会触发（加载的是错误页）。所以「精确检测」在 Web 端做不到。这里的折中是：

1. 4 秒内没有 `load` → 判定「大概率被拒」，显示居中提示卡片 + 外部打开按钮；
2. 顶部常驻一条可关闭的提示条，说明「部分站点禁止嵌入」，随时能一键转系统浏览器。

比假装能检测、白屏后一声不吭要实用。

**安全**（`src/utils/web-url.ts`）：

| 输入 | 结果 |
|---|---|
| `javascript:alert(1)` | 拒绝，提示「只支持 http / https，javascript: 不允许在内嵌页打开」 |
| `file:///C:/Windows/win.ini` | 拒绝 |
| `data:text/html,...` | 拒绝 |
| `example.com` | 补全为 `https://example.com/` |
| `localhost:5173` | 识别为 host:port（不是协议），补全为 `https://localhost:5173/` |

sandbox 放开 `allow-scripts allow-same-origin allow-forms allow-popups allow-modals`，
但**不放开 `allow-top-navigation`** —— 内嵌页不能把整个应用导航走；并带 `referrerpolicy="no-referrer"`。

> 协议判定踩过一个坑：最初用 `/^[a-z][a-z0-9+.-]*:\/\//` 判断「是否已带协议」，`javascript:alert(1)` 没有 `//`
> 被当成裸域名补成 `https://javascript:alert(1)` 再解析失败 —— 虽然仍拒绝了，但提示变成「格式不正确」，
> 没告诉用户真正原因。现改为「识别 scheme + 区分 host:port」两段判定。

### 5. 与旧抽屉的关系（可回退）

`settings.workspaceMode`（默认 `true`）控制形态，两套实现并存、互不干扰：

| workspaceMode | 点「文件 / Git / 终端」 |
|---|---|
| `true` | 打开工作区对应标签（再点同一按钮 = 收起工作区） |
| `false` | 保持原来的右侧抽屉（`#drawer` 槽内容仍在 App.vue 里挂着） |

切换入口有两处：工作区头部「切回抽屉模式」按钮、外观设置「右侧面板」下拉。
新形态万一有兼容问题，用户不必等修版。

### 6. 顺带打通的两个入口

- **消息里的文件路径**：工作区模式下点路径直接开「文件 / 图片」标签（带编辑器、可保存、可点开大图），
  比抽屉里的只读预览更进一步；抽屉模式下仍是原来的预览。
- **文件树点文件**：`ProjectFileTree` 新增 `externalOpen` prop，为真时点文件不发内部编辑器、改 emit
  `open-file` 交父级开标签 —— 树退化为纯导航，符合多标签的心智模型。

### 7. 静态预览同步

`preview.html` 实现了与 Vue 同构的演示（不是贴图）：

- 两列骨架 + 工作区面板 + 标签栏（文件 / 终端 / 网页 / 差异四套 demo 内容）；
- 布局切换（单栏 / 左右 / 上下）真的改 `grid-template-columns`，分隔条可拖拽且比例收敛；
- 工作区宽度可拖拽、双击复位 600px；
- 「浏览网页…」真的会渲染一个 `iframe`（指向 example.com），带地址栏、刷新、外部打开与禁嵌提示条；
- 预览条新增「工作区 / 抽屉」形态切换，可以直观对比两套交互。

### 8. 验证

```
npm run typecheck                     → 0 error
npm run build                         → 356 modules / 900ms（主 chunk 492.68 kB / gzip 174.16 kB；ChatView 69.44 kB）
                                        TerminalPanel 仍是独立 chunk（337 kB），xterm 没有进主包
Vue 浏览器 CDP（含拖拽与网页）          → 48/48 PASS
preview.html 静态自检                  → 24/24 PASS
preview.html CDP                      → 35/35 PASS
```

CDP 实测覆盖：两列骨架与宽度（600px，主列被挤到 548px）、标签创建/激活/切换/隐藏、
终端 xterm 渲染、左右分屏（两个 pane + 两条标签栏）、拖拽分隔条改比例且收敛在 0.2~0.8、
新建菜单 4 项、网页标签（标题=域名 / iframe src / sandbox 无 top-navigation / 地址栏 / 禁嵌提示）、
URL 安全校验 6 例、切回抽屉（工作区消失 + `settings.workspaceMode=false` + 旧抽屉打开）、运行期零 console 错误。

### 9. 已知边界

1. **浏览器环境的 iframe 一定受站点限制**：github.com、google 等无法内嵌，这是浏览器安全模型决定的，
   不是实现缺陷；Electron 壳下走 `<webview>` 不受此限。
2. **聊天本身不是标签**：`chat` 始终是左侧主列（PLAN 里 F02 列了 `chat` kind，实现上没做）——
   把聊天塞进标签会让「切走再切回」重建整个消息流与 SSE 状态，风险远大于收益。
3. **标签全局共享、不按会话隔离**：工作台服务于「边聊边看代码」这一个场景，切会话把正在看的文件关掉反而碍事。
   代价是切到别的 Agent 后，旧标签里的文件可能已不存在（会显示读取错误，不会静默空白）。
4. **工作区宽度上限受视口约束**：拖到 1200px 或「视口 - 420px」先到者为准，聊天区最小保留 420px。
5. **窄屏（≤900px）改为浮层覆盖**：并排会把聊天压到 200px 以内，两栏都没法用；浮层至少保住主流程，
   同时隐藏布局切换（避免切到分屏后两栏各剩一条缝）。
6. **静态预览的标签内容是 demo**：`preview.html` 里的文件树 / Git / 终端 / 编辑器都是静态复现，
   不连后端；只有 `iframe` 是真的。

---

## 十七、P5 P2P 协作（F01）

**目标**：把 `/p2p` 从「设备管理页」升级为「可聊天、可传文件、可远程调 Agent」的协作面板。
**零后端改动**：`api/p2p.ts` 的 22 个方法在 L1 阶段就已全部搬入，这次只是第一次真正用起来。

### 1. 交付形态

设备行新增常显的「聊天」按钮（`P2pPeerRow`）→ 打开 `P2pChatDrawer`（620px，可拖宽至 1200px）：

```
┌─ 抽屉头：对方头像 · 名称 · 在线状态 ………… [授权] [清空] [×]
├─ 消息区（card-2 底，日期分隔 + 六类气泡，自动贴底）
│   对方文本 / 我方文本 / 图片缩略图 / 文件卡 / Agent 调用（转圈）/ Agent 回复（Markdown）/ 系统通知
└─ 输入区：[聊天 | Agent N]  →（Agent 模式）目标 Agent chips
               [📎] [textarea                            ] [发送]
```

### 2. 三个必须记住的后端语义

| 语义 | 说明 | 代码位置 |
|---|---|---|
| 轮询队列只放「对方发来的」帧 | `P2pConnectionManager.messageQueues` 由 `ChatMessageHandler` / `FileTransferHandler` / `P2pAgentService` 写入；自己发的文本直接出网，不会回环到自己的队列 → **本地乐观插入不会重复** | `P2pChatDrawer.poll()` + `dedupeIncoming()` 兜底 |
| 历史接口是 `ORDER BY created_at ASC LIMIT ? OFFSET ?` | `offset=0` 拿到的是**最早**的 N 条，且没有总数接口 → 一次拉 1000 条（`HISTORY_LIMIT`），达到上限时顶部提示「仅显示最早的 1000 条」 | `utils/p2p-chat.ts` 文件头注释 |
| 己方发出的文件 `local_path = null` | 后端注释「发送方本地无文件」，`GET /file/{peer}/{transferId}` 对己方发出的文件返回 404 → 图片气泡对 `from === 'me'` 直接显示「图片已发送」占位，不硬加载 | `P2pChatBubble.imgSrc` |

### 3. 关键设计

- **抽屉自包含**：历史 / 轮询 / 发送 / 传文件 / 授权全部在组件内部直调 `api/p2p`，`P2pListView` 只负责「哪台设备 + 开合」。
  这样「设备互联页」与「未来工作区的 P2P 标签」可以复用同一个组件，不必把 8 个状态再往 `App.vue` 里塞一遍。
- **轮询 2 秒 + 页面不可见即跳过**：`document.visibilityState === 'hidden'` 时不发请求；`pollBusy` 防止慢请求叠罗汉。
- **历史 pending 恢复**：历史里若存在 `agent_invoke` 而其后没有同 `agentName` 的 `agent_response`，
  气泡保持「AI 正在思考中…」——刷新/重开抽屉不会把「正在跑」显示成「已结束」。
- **失败可见可重试**：文本发送失败时气泡加红 + 「重发」按钮（后端 `Peer not connected` 之类错误不再静默）。
- **离线只读**：对方离线仍可打开抽屉看历史，输入区禁用并给出「恢复连接后可继续发送」的说明 —— 比「点了没反应」有用。
- **授权抽屉叠在聊天之上**（`z-index: 78 > 70`），且**必须是 `<aside>` 的兄弟节点**：
  `.cc-drawer` 靠 `transform: translateX(100%)` 收起，transform 会为后代的 `position: fixed` 创建包含块，
  嵌在里面的抽屉会相对父抽屉定位而错位。
- **图片三级降级**：缩略图 → 原图 → 占位（`imgStage` 0/1/2），任一环节 404 都不会留白框。
- **灯箱复用全局单例**：`openLightbox([{ src, name }])`，与聊天页附件、文件详情共用 `LightboxHost`，不新增浮层。

### 4. 与计划的差异

| 计划 | 实现 | 原因 |
|---|---|---|
| 「页内左右分栏（设备列表 + 会话区）」 | 抽屉形态 | 1120px 页宽装两栏后设备行字段（名称/状态/地址/peerId/备注/授权数）会被挤成多行；抽屉可拖到 1200px，宽度上限更高且零布局风险 |
| 「轮询 `getTransferProgress` 更新进度条」 | 未实现进度条，改为「发送中…」状态 + 发送完成 toast | 后端 `send-file` 是**同步**接口（分块发完才返回），前端拿到 `transferId` 时传输已结束，进度查询恒为 100%；做进度条等于假数据 |
| 「未读/已读」 | 未做 | 后端无已读回执字段，不虚构 |

### 5. 验证

```
npm run typecheck                → 0 error
npm run build                    → 363 modules / 925ms（P2pListView chunk 46.37 kB / gzip 13.97 kB）
接口探针（真实 8084，9 项）        → 9/9 PASS
  · /p2p/status running=true、/peers 0 个节点、history 新参数 offset/limit 被接受（code=200）
  · 发文本给不存在的节点 → code=400「Peer not connected」（不是静默成功）
  · DELETE /messages 幂等、GET /file/... 不存在时 404（不是 500）
自连探针（尝试单机端到端，6/10）    → 后端允许建记录但 TLS 通道未打通，send 报 400，节点已清理
preview.html 结构自检（15 项）     → 15/15 PASS
preview.html CDP（26 项）         → 26/26 PASS
Vue 浏览器 CDP（15 项）            → 15/15 PASS
```

Vue 侧 CDP 的做法值得留档：真实环境 0 台配对设备，无法点开聊天抽屉。脚本通过
`document.querySelector('#app').__vue_app__._instance.setupState.p2pPeers` 注入一台「假在线设备」，
完整走通「列表渲染 → 聊天按钮 → 抽屉打开 → 空历史 → 发送失败标红 + 重发按钮 → 授权抽屉 → ESC 关闭」，
测试结束把注入项 `splice` 掉，不污染环境。

### 6. 已知边界

1. **单机无法完成真实互发验证**：P2P 需要两台设备证书互通。自连本机时后端能建节点记录，
   但 TLS 通道打不通（`send` 报 `Peer not connected`），因此「文本互发 / 图片传输 / Agent 远程响应」
   的**真实链路**需双机人工验证；本轮验证覆盖到「接口契约 + 失败分支 + 全部 UI 交互」。
2. **历史只能看到最早的 1000 条**：没有倒序参数与总数接口（改这两个需要动后端）。
   真实 P2P 会话量级远小于 1000，暂不构成问题。
3. **Agent 响应不是流式**：`agent_response` 一次性落库并推入轮询队列，气泡一次性出现（旧版同样如此）。
4. **静态预览不连后端**：`preview.html` 的发送只做本地追加，Agent 模式的「pending → response」是
   `setTimeout` 演示（2.5 秒），文案里已注明这是静态预览演示。

---

## 十八、P6 子Agent面板（F05）

> 目标：让「一个任务拆给多个 Agent 并行」这件事**看得见**。
> 计划原定消费 SSE `agent_event`，核查后发现**这个事件根本不存在**，实现改为「读库 + 轮询」。

### 1. 数据源真相（这条必须先讲清楚）

| 结论 | 依据 |
|---|---|
| `AgentForkManager`（fork 子Agent）**不发任何 SSE 事件** | fork 时只 INSERT `sub_agent_log`（`status=running`），结束时 UPDATE 结果；产出以纯文本写进主 Agent 思考流（「📋 主Agent已完成，正在等待 N 个子Agent结果…」） |
| `AgentEventBus` 只发三类 | `ask_user`、`tool_call_start`、`task_error`（**没有** `agent_event`） |
| `delegate_progress` 是另一条链路 | `AgentInvokeServiceImpl` 发（跨智能体委托 A→B，字段 `callId` / `targetSessionId` / `targetAgent` / `status` / `elapsedSec`），**不落库** |
| 旧版 `AgentPanel.vue` 一直是空面板 | 它等的是 `agent_event`；`sse-client.ts` 里那条 `agent_*` 解析分支永远收不到数据 |

结论：唯一可靠数据源是 `sub_agent_log` 表（schema 早有，字段完整），前端只能「拉库 + 轮询」。

### 2. 后端（只新增，不动既有契约）

| 文件 | 内容 |
|---|---|
| `mapper/SubAgentLogMapper.java` | 新增 `selectMetaByConversationId`：轻量查询，**不拉 `full_messages`**（子Agent全量对话可达数MB，列表用不上） |
| `model/vo/SubAgentVO.java` | 只读视图：`id / agentId / name / status / instructions / contextMode / tools / skills / summary / compileResult / iterationsUsed / maxIterations / errorMessage / parentTurnId / createdFiles / modifiedFiles / deletedFiles / createdAt / completedAt`；`tools`/`skills` 已解析成数组，文件列表限 80 条，时间由服务端格式化成 `yyyy-MM-dd HH:mm:ss` |
| `controller/SubAgentController.java` | `GET /api/sub-agents?conversationId=&turnId=` → `ApiResponse<List<SubAgentVO>>`；`turnId` 可选（不传返回整个会话） |

### 3. 前端落点

| 文件 | 职责 |
|---|---|
| `src/api/sub-agent.ts` | `listSubAgents(conversationId)` + `SubAgentRecord` 类型 |
| `src/utils/agent-event.ts` | `toSubAgentRuns()`（状态归一 / 耗时 / 文件统计）、`subAgentStats()`、`formatDuration()`、`mayForkSubAgent()`（工具调用是否可能产生子Agent）、`toDelegateCall()` / `mergeDelegateCall()` |
| `src/components/SubAgentPanel.vue` | 纯展示浮层：头部统计（N 运行中 / N 已完成 / N 失败 / 共 N）+ 子Agent卡片（头像 / 名称 / 状态点 / 耗时 / 迭代次数 / 文件徽章 / 主题任务 / 工具与技能 chips / 摘要 / 失败原因）+ 委托调用区块 |
| `src/App.vue` | `subAgentMap` / `delegateMap` 按**会话 key** 分片；`loadSubAgents()` + 3s 轮询；`consumeChatStream` 里 `tool_call_start` 命中 `mayForkSubAgent` 立即拉一次、`delegate_progress` 写委托心跳、流结束 `finally` 再刷一次 |
| `src/views/ChatView.vue` | runbar 新增「🤖 子Agent」按钮（`showSubAgentEntry = subAgentStat.total > 0 \|\| delegateActive > 0`）+ 浮层；与任务浮层**互斥**；ESC 关闭 |

### 4. 关键设计

1. **轮询由「是否有 running」驱动，而不是「是否在流式」**——主 Agent 结束后子 Agent 可能还在跑
   （后端 `collectPendingAgents` 最多等 300s），只看流式状态会提前停掉轮询，面板永远停在 running。
2. **上限 200 次（约 10 分钟）**：后端若残留 `running` 记录（进程被杀等），轮询会一直空转，封顶自停。
3. **切回会话时先拉一次再轮询**：`loadConversationMessages()` 末尾拉一次，历史会话的子Agent因此也能回放，
   不需要从消息正文里反解析（计划里的做法作废，库里就是全量）。
4. **委托调用与 fork 分开两块**：`delegate_progress` 是「A 委托 B 智能体」，fork 是「派出子Agent」，
   两者语义不同，混在一起会让「3 个子Agent」的计数失真。
5. **不做乐观占位**：面板只显示库里真实存在的记录。点了 fork 但后端还没 INSERT 完的那几百毫秒里不显示，
   比显示一个永远不更新的假卡片好。

### 5. 验证

```
npm run typecheck                              → 0 error
npm run build                                  → 367 modules
后端 mvn test-compile                          → 通过
静态自检 _p6_check.cjs（31 项）                 → 30/31（唯一 FAIL 为 logToast 动态创建元素的已知假阳性）
接口探针（8085 内存库 + 预置数据，35 项）        → 35/35 PASS
Vue CDP 端到端（26 项）                         → 26/26 PASS
```

关键验证是**会话隔离**：预置数据里 9001 有 3 条（running / completed / failed），9002 只有 1 条。
切到 9002 后 `activeConversationId=9002`、`subAgentMap` keys 变成 `[9001, 9002]`、
面板只剩 9002 那 1 条（状态探针实测：`names=["别的会话的子Agent"]`），未串到 9001 的三张卡片。

CDP 断言覆盖：入口徽章与 title、浮层出现、头部三态统计、三类卡片、running 卡片默认展开 + 再次点击收起、
失败卡片展开可见错误原因、关闭按钮、与任务浮层互斥、ESC 关闭浮层、切会话后入口恢复、无 JS 未捕获异常。
其中 ESC 一项断言的是任务浮层（同一处理器 `onPopoverKeydown` 里子Agent浮层优先匹配），
子Agent浮层的 ESC 走同一分支，未单独造第二条断言。

### 6. 已知边界

1. **本机没有真实 fork 数据**：后端从未真正 fork 过子 Agent（日志零命中「子Agent已创建」），
   `sub_agent_log` 为空，因此验证用 8085 临时实例（内存库 `p6probe` + `_p6_data.sql` 预置）完成，
   真实链路要等用户用 `agent` 工具的 `fork` 动作跑一次。
2. **`delegate_progress` 未被 CDP 覆盖**：需要真实跨智能体委托（A 调 B）才会发出，本轮只做代码审阅。
3. **委托调用不持久化**：刷新页面后历史委托不再回显（后端没有查询接口），只能靠新的心跳重建。
4. **静态预览的浮层是写死的演示数据**：`preview.html` 同步了入口按钮 / 浮层结构 / 开合与互斥逻辑，
   但内容不连后端（与其它页面的静态预览口径一致）。

## 十九、L19 登录页与个人信息

> 背景：v3 早期为了「打开就能用」，把登录做成了隐形登录（启动时用 `admin/123456` 静默换 token，
> 用户看不到登录界面），个人信息页也没落地。这一轮把两件事补回来：
> **显式登录页**（新风格重画）+ **个人信息页**（资料 + 头像 + 改密码），并顺手把侧栏用户区改成账号菜单。

### 1. 隐形登录 → 显式登录（改了什么）

| 位置 | 改法 | 原因 |
| --- | --- | --- |
| `utils/http-client.ts` | 删掉「请求前自动静默登录 / 401 自动重登重试」，改为 `performLogin(username, plain)` 供登录页调用；`hasToken()` 同步判登录态 | 自动登录会把「登录过期」变成「静默换号」，多用户场景下会把别人的 token 用在自己头上 |
| 401 处理 | 不再自己重登，改为清 token + 调 `setUnauthorizedHandler()` 注册的回调 | http-client 引 router 会成环（router → session → http-client → router），跳转动作交给上层注入 |
| `utils/session.ts` | `ensureIdentity()` 里先查 `hasToken()`，未登录直接返回 null 不发请求；新增 `clearSession()`（token + 身份快照一起清） | 否则未登录时 `/user/current` 必然 401，还会触发全局「登录已过期」跳转 |
| `main.ts` | 去掉启动静默登录；改为「本地有 token 才预热身份」 | 未登录时任何请求都只会得到 401 并把用户吓一跳；错误改为就地显示在登录卡片里 |
| `router/index.ts` | 新增 `/login` 路由；守卫改为**两层**：①`hasToken()` 否则跳 `/login?redirect=<原地址>` ②`meta.adminOnly` 判角色；已登录访问 `/login` 直接弹回首页 | 恢复 v2 的 `requiresAuth` / `requiresGuest` 语义，但保持「后端不可用时放行」的容错 |
| `App.vue` | 模板按 `isLoginPage` 分两套布局：登录页只渲染 `<router-view>`，不套 `AppShell`；`ToastHost` 等浮层提到分支之外 | 登录页没有「当前用户 / 会话列表」，套外壳会漏出不该出现的东西 |

### 2. 登录页（`views/LoginView.vue`）

- **版式**：全屏居中 396px 卡片（`--card` 白底 + 1px `--border` 描边 + `--r-lg` + `--shadow-lg`），
  与全站「纯色背景 + 描边卡片」风格一致；品牌区是主色方块 `C` + CodeCraft + 「AI 编程助手」。
- **表单**：用户名 / 密码两栏（左侧线性图标，聚焦时主色描边 + 3px 光圈）、密码眼睛切换、
  自绘复选框「记住用户名」、主按钮全宽。
- **错误就地展示**：登录失败不用 Toast，直接显示在表单内的红条里 —— 登录失败是要「停在原地改」的场景。
- **两步加密**：`POST /user/random-code` 取 4 位随机码 → `MD5(MD5(明文) + 随机码)` → `POST /user/login`，
  全在 `performLogin()` 内部完成，页面只提交明文。
- **只记用户名**：「记住用户名」存 `userStore.rememberedUsername`（已持久化），密码永不落盘。
- **主题自己同步**：登录页在 `AppShell` 之外，没人替它设 `data-theme`，所以在这里读 `settingsStore` 自己写一次，
  否则上次选了深色的用户会在登录页先看到一片白。
- **开放重定向防护**：`?redirect=` 只接受以 `/` 开头的站内路径（拒绝 `//evil.com`），其余一律回 `/chat`。

### 3. 个人信息页（`views/ProfileView.vue`）

| 区块 | 能力 | 接口 |
| --- | --- | --- |
| 账号资料 | 头像上传（上传即写库）、昵称 / 邮箱 / 手机号（保存 / 还原，含格式校验） | `GET/PUT /api/user/profile`、`POST /api/avatar` |
| 修改密码 | 旧密码 + 新密码 + 确认密码，眼睛切换、一致性提示、按钮可用性 | `PUT /api/user/password` |

**两个容易踩的后端语义（都已按后端实现对齐）：**

1. **改密码必须传 MD5**：`UserServiceImpl.changePassword` 直接拿库里存的值（`MD5(明文)`）与入参比较，
   **不做任何加密** —— 传明文永远报「旧密码错误」。旧前端（v2 `ProfileView`）就是传明文，所以那个功能其实一直是坏的。
   本页传 `md5(旧) / md5(新)`，探针已实测通过。
2. **改密后当前 token 立刻失效**：后端成功后会 `invalidateUserTokens(userId)`。所以本页紧接着用新密码
   `performLogin()` 自动重新登录，用户不会在保存资料时突然被踢；自动重登失败才提示并跳 `/login?redirect=/profile`。

**其他细节**：头像「上传即保存」而其余字段走「保存信息」按钮 —— 头像是最容易「以为改了其实没保存」的字段；
`dirty` 比较的是昵称/邮箱/手机号/头像四项，未改动时保存按钮禁用，避免无意义 PUT。

### 4. 侧栏账号菜单

用户区原来是「头像 + 名字 + 外观按钮 + 主题按钮」，加上「个人信息」「退出登录」会变成四个图标（折叠态更挤）。
改为**点头像/名字弹出账号菜单**：个人信息 / 外观设置 / 切换主题 / 退出登录。

- **向上弹出 + fixed 定位**：位置在点击时按按钮矩形算，折叠态（64px 宽）也不会被裁；
- **点外部 / ESC 收起**：与会话操作菜单共用同一套 document 监听；
- **折叠态**只留头像，箭头与名字隐藏；
- **退出登录**先调 `POST /user/logout` 作废服务端 token，再清本地会话 —— 顺序反了后端收到的是「无 Authorization 的注销请求」，
  对该分支直接返回成功，等于 token 没被作废。后端调不通也继续本地清理（用户点了退出就必须退出去）。

### 5. 静态预览同步（`preview.html`）

- 预览条新增「个人信息」「登录页」两个按钮（共 18 个页面）；
- 登录页整屏独立：`body[data-page="login"]` 时隐藏 `.cc-shell`，`.cc-login` 撑满剩余高度（`.pv-bar` 仍保留以便切回）；
- 账号菜单、个人信息页（含保存/还原/密码校验/显隐）都是纯前端演示，不连后端；
- 图标双库同步新增 `i-eye` / `i-eye-off` / `i-logout`（共 49 个）。

### 6. 验证

```text
npm run typecheck                                   → 0 error
npm run build                                       → 371 modules（LoginView 4.99 kB / ProfileView 11.00 kB 各自独立 chunk）
接口探针 _probe_login.cjs（17 项，含改密回滚闭环）  → 17/17 PASS
Vue CDP 端到端 _cdp_login.cjs（38 项）              → 38/38 PASS
静态预览自检 _check_preview.cjs（30 项）            → 29/30（唯一 FAIL 为 logToast 动态创建元素的已知假阳性）
静态预览 CDP _cdp_preview_login.cjs（21 项）        → 21/21 PASS
```

**接口探针覆盖的改密闭环**（关键，避免把 admin 密码改坏）：

```text
登录 admin/123456 → 单次 MD5 登录被拒（证明二次加密生效）→ 无 token 401
→ profile 读取（不返回密码）→ 资料更新 → 头像可写可还原
→ 明文旧密码被拒（证明必须传 MD5）→ md5 旧密码改密成功
→ 旧 token 立即失效 → 新密码可登录 → 用新 token 改回 123456 → 原密码仍可登录
→ logout 成功且 token 失效
```

**CDP 覆盖**：未登录跳登录页 + `redirect` 参数、登录页版式（396px / 1px 描边 / 无侧栏）、密码显隐、
空用户名与错误密码就地提示（文案统一为「用户名或密码不正确」，不泄露账号是否存在）、登录成功落 token、
账号菜单四项 + fixed 定位 + 点外部收起、个人信息页资料回填、dirty 逻辑、密码一致性提示与按钮可用性、
菜单跳外观页、退出登录二次确认 + token 清除 + 回登录页、已登录访问 `/login` 弹回首页。

### 7. 已知边界

1. **后端登录失败统一返回 HTTP 500**：项目没有全局异常处理器，`RuntimeException("密码错误")` 被 Spring 渲染成默认错误页，
   前端只能按「凭证错误」提示。想给出精确原因需要在后端加 `@RestControllerAdvice`（本轮未动后端）。
2. **登录页没有「找回密码」**：后端没有对应接口，忘记密码只能由管理员在「用户管理」里重置。
3. **未做「自动登录」开关**：`http-client.loginSilently()` 仍保留（读 `codecraft.auto-login` 凭据），但登录页不提供入口。
4. **退出登录不清空会话缓存**：只清 token 与身份快照、补充需求状态、聊天错误；`messagesMap` 等分片状态留在内存里，
   下一个登录用户刷新页面后才会彻底重置。

## 二十、L20 两处交互修复（思考区滚动跟随 / 工具自由选择）

> 用户反馈两个「用起来别扭」的点，最后都定位到**具体实现缺失**而非设计取舍。

### 1. 思考过程应始终在底部（除非用户主动上滑）

**现象**：展开「思考 1234 字」后，思考区停在它自己那一格的顶部，模型继续思考时看不到最新内容。

**根因**：`.cc-proc__body` 是 `max-height: 320px; overflow: auto` 的独立滚动容器，但 v3 移植时只搬了容器样式，
**没搬旧项目的滚动跟随逻辑**（旧版 `CodeAssistantView.vue` 的 `thinkAutoScroll` + `handleThinkScroll` + `think-scroll-btn`），
于是它跟一个普通 `div` 一样停在 `scrollTop = 0`。

**关键区分**：这是**第二套滚动**，与 ChatView 的整消息流贴底互不相干。外层 `.cc-stream` 早就有完整的
`follow` / `atBottom` / ResizeObserver / 120ms 兜底，但思考区在 320px 里自己滚，外层贴底对它无效 ——
这正是「看起来滚动逻辑都做了，却还是看不到最新思考」的原因。

**修复**（`src/components/ChatMessage.vue` + `assets/app.css`）：

| 能力 | 实现 |
| --- | --- |
| 内容增长时贴底 | `watch` 思考文本总长度 → `procScrollToBottom()`（`nextTick` + 一帧 rAF 二次校正，追平 80ms 节流渲染的二次撑高） |
| 用户上滑即停止跟随 | `@scroll.passive` 距底 `> 30px` → `procAutoScroll = false`（阈值与旧项目对齐） |
| 展开时定位 | 流式消息展开直接跳到最新；**历史消息保持从头读**（与旧项目 `if (!msg.isStreaming) return` 一致） |
| 找回最新 | 上滑后浮出 `.cc-proc__jump`（悬浮在思考区右下），点击贴底并收起 |
| 不裁掉按钮 | `.cc-proc__jump` 放在 `.cc-proc__wrap`（`position: relative`）里、滚动容器**外面** —— `overflow: auto` 会裁掉绝对定位的子孙节点 |

### 2. 工具选择应该能自由全选 / 组选 / 单选

**现象**：新建或编辑 Agent 时，工具 chips 点不动，必须先点一下「全部工具」把它取消才能选。

**根因**（两处叠加，缺一不可）：

```js
function toggleTool(key) {
  if (form.allTools) return        // ← ① 全部态下直接 return，点了像没反应
  ...
}
```
```css
.cc-toolsel.is-off .cc-toolsel__groups { pointer-events: none; }  /* ← ② 连点击事件都收不到 */
```

当初的理由是：后端 `tool_names` 留空 = 全部工具（跟随系统新增），非空 = 只授权这些，两个状态「语义互斥」，
所以干脆把工具区锁死避免矛盾。代价是**多一步无意义操作 + 点了没反馈**。

**修复思路**：保留两个状态（后端语义不能改），但让它们在**视觉与操作上连续** ——
全部态下所有工具就显示为已选中，点掉任意一个自动变成「全部 - 1」的显式列表。

| 操作 | 全部态下的行为 |
| --- | --- |
| 点单个工具 | 从全部集合里减掉它（`baseToolKeys()` 以全部为基准），转为精细态 |
| 点「全选本组」 | 该组本来就全选 → 实际执行「取消本组」，其余组保持 |
| 点「全部工具」 | 关：把全部工具写进 `form.tools`（不会突然变成一个都没选）；开：清空列表回到全部态 |
| 组头按钮 | 不再 `v-if="!form.allTools"` 隐藏，全部态下显示「取消本组」 |
| 计数 | 全部态显示「全部 27 个」，精细态显示「26 / 27」 |
| 保存 | 全部态 **或** 手选满全部已知工具（且无历史遗留工具）→ 都提交 `[]`，等价于「跟随新增」 |

`fullSelection` 这个归一条件很重要：用户手点满 27 个，若照实提交 27 个名字，
以后端新增工具时这个 Agent 就会漏掉 —— 与直接点「全部工具」的行为不一致。

### 3. 验证

```text
npm run typecheck        → 0 error
npm run build            → 371 modules / 860ms（主 chunk 493.98 kB / gzip 174.54 kB）
静态自检 _check2.cjs     → 14/14 PASS（内联 JS 语法 / CSS 括号 / div 配对 / id 引用 / 图标双库 / 两处逻辑符号）
Vue CDP 端到端 _cdp.cjs  → 25/25 PASS
```

**CDP 实测数据（关键几项）**：

```text
注入 3050 字思考（替换整个 process 数组，避免重渲染卡顿）
思考区 scrollHeight=2397 clientHeight=320       → 确实可滚动
展开流式消息后 gap=0 scrollTop=2077             → 自动贴底
继续追加内容 gap=0 scrollTop=3127               → 仍贴底
手动 scrollTop=0 + dispatch scroll              → .cc-proc__jump 出现（visibility: visible）
上滑后继续追加 gap=4177 scrollTop=0             → 不拽回
点「回到最新思考」gap=0 jumpExists=false         → 贴底且按钮收起

工具区 27 个工具 / 全部态 onTotal=27 / pointer-events: auto / 无 is-off
全部态点「取消本组」→ onTotal=23 groupOn=0       → 组选可用（旧版此按钮在全部态是隐藏的）
再点「全选本组」→ onTotal=27 groupOn=4           → 组选可逆
全部态点单个工具 → onTotal=26 allOn=false        → 单选可用（旧版点了没反应）
点「全部工具」→ onTotal=27 allOn=true            → 全选可用
退出全部态 → onTotal=27 count="27 / 27"         → 视觉连续，提示「已选满全部…按全部工具提交」
```

### 4. 排查记录

1. **首轮 CDP 6 项 FAIL 全是脚本缺陷**：① 注入的流式消息与「展开的思考区」不是同一条
   （`messagesMap` 从后往前找 vs DOM 里第一个按钮），展开的是历史消息所以不触发贴底；
   ② 组选测试先取消了单个工具，该组已非全选，按钮语义变成「全选本组」—— 产品逻辑正确，断言顺序错。
2. **静态自检误报三连**：`.cc-toolsel.is-off` / `if (form.allTools) return` 在**注释里**出现被正则命中，
   需先剥离注释；`logToast` 是运行时动态创建的元素，`getId` 检查需白名单；
   `if (form.allTools) return group.options.length > 0`（有返回值的取值）不能被当成「早退守卫」，
   正则要加 `(?=\s*(?:;|\r?\n|$))` 边界。
3. **静态预览同步**：思考区加了 `.cc-proc__wrap` + `#procJump`，展开即滚到底、上滑浮按钮；
   工具选择同步「全部态 = 全部选中」的连续语义（`agentBaseToolKeys()` + `agentSetTools()`）。

---

## 二十一、P7 版本与更新（F06）

**目标**：让用户看清「自己在用哪个版本」并能自助升级；浏览器环境必须安全降级、不报错、不假装能更新。

### 1. 落点与组成

| 文件 | 职责 |
|---|---|
| `views/AboutView.vue` | 页面壳：页面头 + 统计行 + `VersionCard` + 更新设置 + 更新策略说明；浏览器环境把设置区降级为只读文案 |
| `components/VersionCard.vue` | 版本矩阵（客户端 / 后端 / 构建时间 / 运行环境 / 最近检查 / 后端 PID）+ 检查更新 + Release notes（markdown）+ 下载进度条 + 安装按钮 |
| `utils/update.ts` | `electronAPI.update` 的类型化封装：`getUpdateApi` / `isUpdateSupported` / `describeInstallAction` / `formatBytes` / `formatReleaseTime` / `clearLocalAppData` |
| `api/system.ts` | 新增 `fetchAppVersion()` → `GET /api/system/version` |
| `menu.config.ts` / `router/index.ts` | 新增 `/about`（系统配置组，图标 `i-refresh`）+ 组件登记 |
| 后端 `SystemController` + `application.yml` | 新增 `GET /api/system/version`；`app.version` / `app.build-time` 由 Maven 资源过滤注入 |

样式：`assets/app.css` 第 54 节「页面：版本与更新」（`.cc-about-*` / `.cc-vercard__*`），响应式节顺延为第 55 节并补 900px 窄屏规则（版本矩阵降单列、操作区换行、notes 限高）。

### 2. 三个关键决策

1. **客户端版本与后端版本分列，不合并**
   桌面端 `app.getVersion()` 读的是安装包版本，浏览器端只能读 jar 里的版本；「只换 jar 不重装客户端」时两者会不一致。
   合并成一个数字会让这种偏差消失，反而妨碍排查，所以矩阵里两行都给，并在不一致时打 `与客户端不一致` 标签。
2. **复用 Electron 主进程，不重写更新逻辑**
   `electron/update-manager.js`（检测 / 缓存 / 镜像回退 / 备份 / 安装）+ `preload.js` 的 `electronAPI.update` 已完整可用，v3 **零改动复用**；
   前端只做「取状态 → 渲染 → 调命令」，不重复实现限流、镜像、备份这些容易踩坑的部分。
3. **浏览器环境不渲染假开关**
   没有 `electronAPI` 时，更新设置区渲染为只读说明（而不是可点但无效的开关），「检查更新」按钮给 toast 说明去 GitHub Releases 手动下载；
   只读文案比「点了没反应」清晰。

### 3. 后端版本接口

```
GET /api/system/version   （登录即可，刻意不做管理员校验）
{
  "name": "CodeCraft",
  "version": "3.0.0",
  "buildTime": "2026-10-19T09:23:34Z",
  "javaVersion": "17.0.0.1",
  "osName": "Windows 10",
  "osArch": "amd64",
  "pid": 45800
}
```

- 版本号来源：`application.yml` 的 `app.version: '@project.version@'`（spring-boot-starter-parent 默认对 `application*.yml` 开启资源过滤，分隔符 `@…@`）。
- **占位符兜底**：IDE 直接运行或资源未过滤时会拿到字面量 `@project.version@`，`SystemController.resolveAppVersion()` 检测到以 `@` 开头（或含 `${}`）即回退内置常量 `3.0.0`。
- 构建时间实测是 Maven 内置默认格式（ISO 8601 UTC，如 `2026-10-19T09:23:34Z`），前端按本地时区格式化成 `YYYY-MM-DD HH:mm` 展示，因此没有自定义 `maven.build.timestamp.format`。

### 4. 「重置所有数据」联动清理

重置成功后调用 `clearLocalAppData()`（`electronAPI.clearLocalData`）清掉：升级前备份 `userData/backups`、旧版升级备份 `userData/legacy-backup`、已下载安装包 `temp/codecraft-updates`、检测缓存 `update-cache.json`。
浏览器环境该函数返回 `null`，无副作用；toast 会带上「同时清理本机：…」说明。

### 5. 验证

| 项 | 结果 |
|---|---|
| `npm run typecheck` | 0 error |
| `npm run build` | 377 modules，`AboutView` 独立 chunk 16.46 kB |
| 后端接口探针（8085 临时实例 + 内存库） | **12/12**：未登录 401、`version=3.0.0`（资源过滤生效）、`buildTime` 已注入、name/javaVersion/osName/osArch/pid 齐全、不含 apiKey/password、非 403 |
| 浏览器 CDP（真实 dev server） | **30/30**：浏览器降级 13 项（版本矩阵 6 项、降级提示、只读设置区、策略说明 5 条、点检查更新给 toast）+ mock Electron 17 项（Windows 环境、可编辑设置区、镜像前缀回填、开关调 `setSettings`、检查更新出 v3.1.0、notes markdown 渲染含 `target=_blank`、下载进度条、下载完成出「立即安装并重启」、点安装调 `update.install`、卡片不溢出视口、无运行时报错） |

> 说明：真实 Electron 主进程链路（真下载 200MB 安装包、真启动安装器）无法在 CI/无头环境跑，本轮用 mock `electronAPI.update` 覆盖了全部 UI 分支与命令调用；主进程逻辑本身沿用 v2 已上线版本。

### 6. 已知边界

- **静态预览未同步**：`preview.html` 不在本仓库（见 MIGRATION），且自动更新在纯静态页里没有可演示的真实行为。
- **浏览器无法自动更新**：网页版只能看版本 + 跳 GitHub Releases；这是平台能力限制，不是缺陷。
- **安装动作不可回滚**：Windows 会退出应用并拉起安装器；macOS 只能打开 dmg 由用户拖拽（无 Apple 开发者证书）。
- **`clearLocalData` 不清数据库**：删的是备份/安装包/缓存，业务数据仍由后端 `/api/system/reset-data` 负责。
