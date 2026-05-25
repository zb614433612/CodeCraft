# 更新日志

本文档记录 CodeCraft 项目的所有重要版本变更。

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
