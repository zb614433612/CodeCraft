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

### 🧠 多 Agent 体系
可创建不同角色的 AI Agent（编码助手、代码审查员、架构师等），每个 Agent 独立配置系统提示词、工具集和模型参数。主 Agent 执行复杂任务时，可自动拆分为子任务、召唤多个子 Agent 后台并行执行，最多 5 个并发协作。

### 🛡️ 三层安全防护管道
工具执行采用三层防护：manual 模式下数据操作和路径敏感操作弹窗授权，auto 模式下高危工具（delete_file / execute_sql 等）弹窗确认。支持「本轮对话全部同意」一键放行。

### 📸 代码快照回滚系统
AI 修改文件前自动创建快照备份，支持按消息/按文件/按会话三种粒度回滚，500MB 配额自动清理。

### 🧩 智能技能系统
将常用操作流程写成技能（名称+触发词+执行步骤），AI 通过 BM25 + 触发词双路匹配自动调用，贝叶斯置信度动态调整确保越用越聪明。技能信息采用「动态注入」不占上下文，长对话开销恒定。

### ⏰ 定时任务系统
支持一次性任务和 Cron 周期任务，可绑定指定 Agent 自动执行，每次执行生成独立会话追溯。

### 🖥️ 内置代码编辑器
基于 highlight.js 的自研编辑器，支持 30+ 语言语法高亮、行号、暗色主题、光标定位、dirty 标记和快捷键保存。

### 🎨 智能 Markdown 渲染
支持 KaTeX 公式、命令终端卡片、文件清单卡片、彩色提示容器、XSS 防护。

### 👥 用户管理与权限
完整的账户体系：注册登录、Token 鉴权、角色权限、菜单可见性控制、初始管理员自动创建。

### 🧰 30+ 工具生态
文件操作、命令执行、网络请求、数据库、Git、Agent 协作、技能管理等 7 大类 30+ 个工具全部对 AI 开放。

### 🧠 智能上下文压缩
三级渐进式压缩策略（预警→压缩→丢弃），LLM 摘要 + 保护带机制 + 异步预压缩，超长对话不崩溃。

### 🔗 P2P 远程协作
基于 Netty + TLS 的点对点安全通道，可将 Agent 授权给远程伙伴调用。支持二维码配对、IPv6 直连、端到端加密、图片/文件传输。

### 💾 开箱即用
Electron 桌面应用，内置 JRE + Node.js + H2 数据库，下载安装即用，零配置。

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
- 下载最新版本的 `CodeCraft-Setup-1.0.5.exe`
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
├── scripts/                      # 自动化脚本（版本同步 / 一键构建）
├── .github/workflows/            # GitHub Actions CI（自动发布）
├── BUILD_AND_RUN.md              # 构建与运行指南
├── CHANGELOG.md                   # 版本更新日志
├── .gitignore                    # Git 忽略规则
└── application-local.yml.example # 本地配置模板
```

---

## 📚 文档导航

想深入了解 CodeCraft 的架构和开发细节？查看 [docs/](docs/) 目录：

| 文档 | 适合谁 | 说明 |
|------|--------|------|
| [架构全景图](docs/ARCHITECTURE.md) | 新人 / AI 协作 | 5 分钟建立全局认知 |
| [工具系统](docs/TOOL_SYSTEM.md) | 开发者 | 如何新增一个 AI Tool |
| [核心引擎](docs/DEEPSEEK_SERVICE_IMPL.md) | 资深开发者 | Tool Loop 状态机 + 方法调用拓扑 |
| [P2P 协作](docs/P2P_SYSTEM.md) | 开发者 | 远程 Agent 调用全链路 |
| [快照系统](docs/SNAPSHOT_SYSTEM.md) | 开发者 | 代码备份与回滚机制 |
| [开发速查](docs/DEV_QUICKREF.md) | 日常开发 | 常用命令 / 端口 / 账号 |
| [发布清单](docs/RELEASE_CHECKLIST.md) | 维护者 | 打包发布 step-by-step |
| [常见问题](docs/FAQ.md) | 所有人 | 16 条已踩坑 FAQ |

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
