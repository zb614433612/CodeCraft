# 更新日志

本文档记录 CodeCraft 项目的所有重要版本变更。

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
