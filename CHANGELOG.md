> 🌐 English Version：[🇬🇧 CHANGELOG_EN](./CHANGELOG_EN.md)
# 更新日志

本文档记录 CodeCraft 项目的所有重要版本变更。

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
