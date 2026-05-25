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

## ✨ 核心功能

### 🤖 AI Agent 能力
- **多工具调用**：读写文件、搜索代码、执行命令、操作 Git、调用 API 等 30+ 工具，工具选择区按类型分组折叠显示，标注高危操作 ⚠️
- **任务拆解与并行**：自动将复杂任务拆解为子任务，创建多个子 Agent 并行执行
- **多 Agent 后台流式切换**：切换 Agent 时后台流式继续运行，切回自动恢复，支持多个 Agent 同时并行流式
- **自动纠错**：执行失败自动重试、切换方案，连续失败自动上报
- **技能系统**：支持创建和管理技能，让 AI 学习你的工作流
- **自定义 Agent**：支持创建多个 AI Agent 配置，每个 Agent 可独立设置提示词、工具集、模型、执行模式

### 👨‍💻 编码辅助
- 智能代码生成与修改
- 项目文件浏览与编辑
- 代码搜索与定位
- 差异对比（Diff View）
- 文件快照管理
- 会话级工作目录隔离

### 🎛️ Agent 配置管理
- 创建多个自定义 AI Agent（编码助手、代码审查、测试生成等）
- 每个 Agent 独立配置：系统提示词、可用工具、AI 模型、思考模式
- 自动/手动执行模式切换
- 技能与 Agent 关联，技能按 Agent 隔离管理
- 定时任务绑定指定 Agent

### 🔧 项目管理
- Maven 项目构建与管理
- Git 集成（状态查看、提交、推送、分支管理）
- 项目结构分析
- 配置文件管理

### 🗄️ 内置数据库
- H2 嵌入式数据库（无需安装，零配置）
- Caffeine 内置缓存（替代 Redis）
- 可选连接 Milvus 向量数据库

### 🖥️ 桌面端（Electron）
- 跨平台桌面应用（Windows/macOS/Linux）
- 内置 JRE 运行环境，无需安装 Java
- 一键打包为安装程序

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
- 下载最新版本的 `CodeCraft-Setup-1.0.2.exe`
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

# 3️⃣ 访问 http://localhost:8085
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
