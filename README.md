<div align="center">
  <img src="frontend/src/assets/logo.svg" alt="CodeCraft Logo" width="120" height="120">
  <h1>CodeCraft 🛠️</h1>
  <p><strong>AI 驱动的智能代码编程助手</strong></p>
  <p>让 AI Agent 帮你写代码、改代码、管理项目、自动完成任务</p>

  <p>
    <img src="https://img.shields.io/badge/Java-17%2B-blue?logo=openjdk" alt="Java 17+">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen?logo=springboot" alt="Spring Boot 3.4">
    <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs" alt="Vue 3.5">
    <img src="https://img.shields.io/badge/Electron-33-47848F?logo=electron" alt="Electron 33">
    <img src="https://img.shields.io/badge/license-MIT-green" alt="MIT License">
    <img src="https://img.shields.io/badge/download-exe-brightgreen?logo=github" alt="Download">
  </p>
</div>

---

## 📖 项目简介

**CodeCraft** 是一个基于 **AI Agent** 的智能代码编程助手桌面应用。它能够理解你的编程需求，自动帮你完成代码编写、文件操作、项目构建、Git 提交等一系列开发任务。

与普通的 AI 聊天助手不同，CodeCraft 拥有**完整的工具调用能力**——它能读取你的项目文件、搜索代码、执行命令、操作 Git，甚至创建子 Agent 并行处理复杂任务，真正像一个「AI 程序员」一样工作。

---

## ✨ 核心功能亮点

### 🧠 多 Agent 并行协作系统
> **🚀 与市面同类产品（Cursor / GitHub Copilot / Windsurf）的核心差异化优势**

CodeCraft 支持主 Agent **创建多个子 Agent 在后台并行执行子任务**，这是目前市面上同类 AI 编程助手完全不具备的能力。

- **fork_agent** — 主 Agent 可将复杂任务拆解为多个独立子任务，创建子 Agent 后台并行执行
- **collect_agent** — 阻塞等待子 Agent 完成，自动收集结构化结果摘要
- **inspect_agent** — 深入查看子 Agent 的完整执行日志、thinking 思考过程、工具调用历史
- **评委机制（Judge）** — 子 Agent 超迭代次数时自动触发评委评估，智能判断是否继续扩展
- **死循环检测** — 自动检测重复工具调用模式（`hasRepeatedCalls`），及时终止防止无限循环
- **并发控制** — 最多 5 个子 Agent 同时运行，独立线程池互不影响
- **子 Agent 实时流式面板** — 前端实时展示每个子 Agent 的状态（运行中/已完成/失败）、思考过程、工具调用详情

### 🛡️ 三层安全防护管道
> **🔒 AI 操作全程可控，比 Cursor 更安全**

工具执行采用三层防护设计，高风险操作必须经过用户授权：

| 防护层面 | 触发条件 | 保护内容 |
|---------|---------|---------|
| **层面一** | manual 模式 + 影响数据 | 读写文件等数据操作 → 弹窗授权 |
| **层面二** | manual 模式 + 路径敏感 | 文件路径越界穿透 → 弹窗拦截 |
| **层面三** | auto 模式 + 高危操作 | delete_file / edit_file / execute_sql 等高危工具 → 弹窗确认 |

- **「本轮对话全部同意」** — 一次授权，后续自动放行，兼顾效率与安全
- **@ToolPermission 注解** — 每个工具通过注解声明自身风险等级，自动注册到权限中心
- **会话级批准机制** — 用户批准后子 Agent 自动跳过权限检查

### 📸 代码快照回滚系统
> **⏪ 改错了也不怕，一键回到修改前**

每次 AI 修改文件前，自动创建完整的代码快照备份：

- **自动备份** — 文件修改前自动快照，同一轮对话中同一文件只备份首次修改前的状态
- **三种回滚粒度**：
  - 按消息回滚 — 恢复某条消息对应的所有文件改动
  - 按文件回滚 — 只恢复某个文件到快照版本
  - 按会话回滚 — 一键恢复整个对话的所有修改
- **LCS diff 统计** — 自动计算每处改动的增删行数（+N/-N），超大文件（>10000行）用近似算法优化
- **预览回滚** — 回滚前清晰展示会恢复/删除哪些文件
- **配额管理** — 500MB 上限自动清理最旧快照（清理到 300MB 以下）

### 🧩 智能技能系统（自进化 AI）
> **📈 越用越聪明，AI 学会你的工作流**

- **自定义技能** — 用户创建技能，配置名称、描述、触发词、关联工具集、执行指令
- **BM25 + Jaccard 相似度匹配** — 自动识别用户意图，匹配最合适的技能
- **贝叶斯置信度计算** — `confidence = (successCount + 1) / (usageCount + 3)`，每次成功 +10%~20%
- **自动淘汰机制** — 置信度低于 0.1 的技能自动停用，保留最有效的技能
- **技能隔离** — 技能与 Agent 类型/用户绑定，按需管理

### ✨ 编辑后处理流水线
> **🔧 写完代码自动格式化 + 编译检查**

每次 `write_file` / `edit_file` 执行后自动触发：

1. **Formatter** — 自动格式化代码风格（支持 Java / Python / JS / TS 等常见语言）
2. **Diagnostic** — 自动诊断语法错误和编译问题
3. **结果合并** — 格式化和诊断结果统一返回给 AI，形成「修改 → 检查 → 修正」闭环

### ⏰ 定时任务系统
> **🤖 让 AI 定时帮你干活**

- **一次性任务** — 指定时间自动执行
- **周期任务** — 支持 Cron 表达式配置周期调度
- **Agent 绑定** — 每个定时任务绑定指定 Agent，不同任务用不同角色
- **执行次数限制** — 可设最大执行次数（0=不限）
- **执行追溯** — 每次执行生成独立会话，支持一键查看执行详情
- **启用/禁用** — 随时开关，无需删除

### 🖥️ 内置代码编辑器
> **📝 媲美 VS Code 的编辑体验**

基于 `highlight.js` 的自研编辑器组件，无需依赖外部 IDE：

- **语法高亮** — 支持 30+ 编程语言智能着色
- **叠加层渲染** — textarea（透明输入）+ pre（高亮展示）双绝对定位同步滚动，性能丝滑
- **行号显示** — 左侧行号栏，与编辑区滚动同步
- **光标定位** — 底部状态栏实时显示当前行/列
- **暗色主题** — VS Code 风格深色主题，完整覆盖 30+ 种 token 类型颜色
- **已修改标记** — 自动检测内容变化，显示 dirty 标记
- **一键保存** — 支持直接保存回项目磁盘

### 🎨 智能 Markdown 渲染引擎
> **📊 AI 对话展示的天花板**

- **KaTeX 数学公式** — 支持 LaTeX 公式渲染
- **命令终端卡片** — `cmd` / `terminal` 代码块渲染为交互式终端风格，显示命令 + 输出 + 成功/失败状态
- **文件清单卡片** — `filelist` 代码块渲染为文件修改清单卡片，带「新增/修改/删除」色彩标签
- **自定义提示容器** — `::: warning` / `::: info` 渲染为彩色提示块
- **表格美化** — 自动添加 CSS 类增强表格样式
- **XSS 防护** — 禁用了 HTML 标签渲染

### 👥 用户管理与权限体系
> **🔐 不止是个人工具，支持团队协作**

- **用户注册登录** — 完整的账户系统
- **Token 鉴权** — 请求拦截统一认证，安全可靠
- **角色权限** — 细粒度的角色管理，按角色控制菜单可见性
- **用户管理** — 管理员界面管理用户
- **初始管理员自动初始化** — 首次启动自动创建

### 🧰 30+ 工具生态（Tool System）
> **🔌 所有能力都是"工具"，AI 几乎可以操作一切**

| 分类 | 工具数 | 能力 |
|------|-------|------|
| 📁 文件操作 | 6 | read_file / write_file / edit_file / delete_file / glob_files / grep_search |
| 💻 命令执行 | 3 | run_command / run_server / service_control |
| 🌐 网络请求 | 4 | web_search / web_fetch / http_request / check_network |
| 🗄️ 数据库 | 1 | execute_sql |
| 🔄 Git 操作 | 7 | git_status / git_diff / git_log / git_add / git_commit / git_push / git_branch |
| 🤖 Agent 协作 | 3 | fork_agent / collect_agent / inspect_agent |
| 🧩 技能管理 | 2 | manage_skill / report_skill_result |
| 📊 项目管理 | 2 | project_info / read_project_tree |
| 📋 任务管理 | 1 | task_manager |
| 💬 交互 | 1 | ask_clarification |

### 🧠 智能上下文压缩
> **💪 超长对话不崩溃，上下文窗口不溢出**

- **三级渐进式决策**：黄色预警（WARN）→ 橙色压缩（COMPACT）→ 红色丢弃（DROP）
- **LLM 摘要压缩** — 用 AI 把最早的历史对话压缩为结构化摘要，保留关键决策、文件路径、待办事项
- **保护带机制** — 最近 N 轮对话保持完整，保证上下文精度
- **异步预压缩** — 每次请求返回后，后台自动提前压缩旧历史，下次请求零延迟
- **精确 Token 估算** — 前后端双 Token 估算器，区分中文、英文、数字、emoji 不同系数

### 🗂️ 完整审计日志
> **📋 一切操作有迹可循**

- **工具调用审计** — `ToolAuditLogger` 记录每次工具调用详情
- **子 Agent 全量日志** — 持久化所有子 Agent 的完整消息、工具调用、thinking 过程
- **操作日志查询** — 按会话、按 Agent、按时间范围追溯
- **日志前端展示** — `CommandLog.vue` 可视化查看

### 💾 开箱即用
> **🎁 下载即用，零配置依赖**

- **Electron 桌面壳** — 跨平台（Windows/macOS/Linux）桌面应用
- **内置 JRE** — 安装包自带 Java 运行环境，用户无需安装 JDK
- **内置 Node.js** — 前端运行时已打包，无需单独安装
- **H2 嵌入式数据库** — 零配置，无需安装 MySQL/PostgreSQL
- **Caffeine 缓存** — 替代 Redis，省去中间件部署
- **一键安装包** — `CodeCraft Setup 1.0.3.exe` 双击即用

### 🎛️ 多 Agent 配置系统
> **🦾 不同角色、不同能力、不同模型**

- 创建多个自定义 AI Agent（编码助手、代码审查、测试生成、架构设计…）
- 每个 Agent 独立配置：系统提示词、可用工具集、AI 模型参数、思考模式
- **自动 / 手动模式切换** — auto 模式 AI 自动执行，manual 模式每步需确认
- **配置热更新** — 运行时修改即时生效，无需重启
- **Agent 切换后台流式** — 切换 Agent 时原 Agent 后台继续流式运行，切回自动恢复

### 🔧 完整 Git 可视化集成
> **🔄 聊天界面内直接完成 Git 操作**

- **Git 侧边栏** — 查看文件变更状态、暂存、撤销、提交
- **Diff 预览** — 行级代码差异对比，绿色新增/红色删除
- **分支管理** — 创建、切换、删除分支
- **全流程工具化** — AI 可直接操作 Git（status / diff / log / add / commit / push / branch）

---

## 🏗️ 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| **后端** | Java + Spring Boot | 17+ / 3.4 |
| **前端** | Vue 3 + TypeScript + Vite | 3.5 / 5.x / 6.x |
| **桌面壳** | Electron | 33 |
| **数据库** | H2 (嵌入式) + Caffeine (缓存) | 2.2+ / 3.1+ |
| **构建** | Maven + npm | 3.8+ / 10+ |
| **AI** | 兼容 OpenAI API 格式的大模型（如 DeepSeek） | - |

---

## 🚀 快速开始

### 💾 下载安装（普通用户）

不想搭建开发环境？直接下载已打包好的安装包：

- 前往 **[GitHub Releases 页面](https://github.com/zb614433612/CodeCraft/releases)** 
- 下载最新版本的 `CodeCraft-Setup-1.0.3.exe`
- 双击安装即可使用，**无需安装 Java**（JRE 已内置在安装包中）

> 安装后启动，会先启动后端服务（等待约 10~30 秒），然后自动打开主界面。
> 首次使用请先到「配置」页面设置你的 DeepSeek API Key。

---

### 环境要求（开发者）

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17+ (JDK) | 构建时需要 `JAVA_HOME`，运行时无需安装 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 20+ | 前端和 Electron 构建 |
| npm | 10+ | 随 Node.js 安装 |

### 开发模式运行

```bash
# 1️⃣ 构建后端（自动编译前端）
mvn clean package -DskipTests

# 2️⃣ 启动后端服务
mvn spring-boot:run

# 3️⃣ 访问 http://localhost:8084
```

### 单独启动前端（热更新）

```bash
cd frontend
npm install
npm run dev
```

### 启动 Electron 桌面应用

```bash
cd electron
npm install
npm start
```

> 详细构建和运行指南请参见 [BUILD_AND_RUN.md](BUILD_AND_RUN.md)

---

## 📂 项目结构

```
codecraft/
├── src/                          # Java 后端源码（Spring Boot）
│   ├── main/java/                # 主代码
│   │   └── com/example/agentdeepseek/
│   │       ├── common/           # 通用枚举、响应封装
│   │       ├── config/           # 配置类
│ │       ├── controller/       # REST API 控制器（含工具注册表 API）
│   │       ├── filter/           # 过滤器（Token 鉴权）
│   │       ├── mapper/           # MyBatis 数据访问层
│   │       ├── model/            # DTO、实体、VO
│   │       ├── scheduler/        # 定时任务
│   │       ├── service/          # 业务逻辑层
│   │       ├── tool/             # AI Agent 工具（30+ 工具）
│   │       │   ├── impl/         # 工具实现
│   │       │   ├── permission/   # 工具权限控制
│   │       │   └── postedit/     # 工具后处理（格式化、检查）
│   │       └── util/             # 工具类
│   └── main/resources/           # 配置文件和静态资源
├── frontend/                     # Vue3 前端源码
│   ├── src/
│ │   ├── api/                  # 后端 API 调用（agent-config, chat, conversation, skill, tools 等）
│ │   ├── components/           # 组件（AgentSelector, SkillList, FileTree 等）
│ │   ├── views/                # 页面（CodeAssistantView, AgentConfigView, SkillManageView 等）
│ │   ├── store/                # 状态管理
│ │   └── utils/                # 工具函数
│   └── public/                   # 静态资源
├── electron/                     # Electron 桌面壳
│   ├── main.js                   # Electron 主进程
│   ├── package.json              # Electron 配置
│   └── start.sh / start.bat     # 启动脚本
├── pom.xml                       # Maven 构建配置
├── BUILD_AND_RUN.md              # 构建与运行指南
├── CHANGELOG.md                   # 版本更新日志
├── .gitignore                    # Git 忽略规则
└── application-local.yml.example # 本地配置模板
```

---

## 🤝 贡献指南

欢迎贡献代码、报告问题或提出新功能！请参见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 📄 开源许可

本项目基于 [MIT License](LICENSE) 开源。

---

## 🙏 致谢

- [DeepSeek](https://deepseek.com/) — 提供强大的 AI 模型能力
- [Spring Boot](https://spring.io/projects/spring-boot) — 后端框架
- [Vue.js](https://vuejs.org/) — 前端框架
- [Electron](https://www.electronjs.org/) — 桌面应用框架
