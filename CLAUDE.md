# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

[强制] 请用中文思考所有问题。在输出任何代码或回答之前，先用中文在内心进行推理和分析。这适用于所有任务，无论用户使用什么语言提问。

---

## 项目概述

Spring Boot 3.4 (Java 17) 项目，AI 智能体引擎 + A股数据平台。核心能力：

1. **DeepSeek API 集成** — 流式聊天、工具调用（Function Calling）、多会话管理
2. **A股数据模块** — 实时行情、K线（日/分钟）、复权因子、自选股（数据源：东方财富 + 腾讯财经）
3. **用户系统** — Token 认证、用户画像

---

## 构建与运行

```bash
# 编译
mvn clean compile

# 打包
mvn clean package

# 运行（需本地 MySQL + Redis + Milvus）
mvn spring-boot:run

# 跳过测试
mvn clean package -DskipTests
```

**启动要求：** MySQL（port 3306）、Redis、Milvus 需先启动。数据库表由 `schema.sql` 在启动时自动初始化。

**API 文档：** 启动后访问 `http://localhost:8080/swagger-ui.html`

---

## Python ETL 脚本

Python 脚本位于项目根目录，用于从东方财富/腾讯财经拉取数据并写入 MySQL。

```bash
pip install akshare pandas pymysql sqlalchemy
python etl_stock_info.py      # 股票基础信息
python etl_realtime.py        # 实时行情（循环执行，交易时段每 3s 拉取一次）
python etl_kline_daily.py     # 日K线
python etl_kline_min.py       # 分钟K线
python etl_kline_min_akshare.py  # 分钟K线（akshare 备用方案）
python etl_adj_factor.py      # 复权因子
python fix_cumulative_vol.py  # 修复累积成交量数据
```

---

## 项目架构

```
agent-deepseek/
├── src/main/java/com/example/agentdeepseek/
│   ├── AgentDeepseekApplication.java    # 启动类
│   ├── config/
│   │   ├── DeepSeekConfig.java          # DeepSeek API 配置（baseUrl, apiKey, model, thinkingMode）
│   │   └── OpenApiConfig.java           # OpenAPI/Swagger 配置
│   ├── controller/
│   │   ├── DeepSeekController.java      # POST /api/deepseek/chat/stream (SSE)
│   │   ├── UserController.java          # 登录/注册/随机码
│   │   ├── ConversationController.java  # 会话 CRUD
│   │   ├── UserProfileController.java   # 用户画像
│   │   ├── StockInfoController.java     # 股票信息查询
│   │   ├── StockRealtimeController.java # 实时行情查询
│   │   ├── StockKlineMinController.java # 分钟K线查询
│   │   └── WatchlistController.java     # 自选股管理
│   ├── service/
│   │   ├── DeepSeekService.java         # DeepSeek 聊天服务接口
│   │   ├── impl/DeepSeekServiceImpl.java # 核心：流式调用、工具调用循环、消息持久化
│   │   ├── UserService.java / impl/     # 用户认证（MD5 + Token）
│   │   ├── ConversationService.java / impl/
│   │   ├── WatchlistService.java / impl/
│   │   ├── StockInfoService.java / impl/
│   │   ├── StockRealtimeService.java / impl/
│   │   └── StockKlineMinService.java / impl/
│   ├── tool/                            # 工具调用系统（Function Calling）
│   │   ├── Tool.java                    # 接口：getName, getDescription, getParameters(JSON Schema), execute
│   │   ├── ToolRegistry.java            # 注册表（ConcurrentHashMap）
│   │   ├── ToolExecutor.java            # 解析并执行工具调用
│   │   ├── ToolInitializer.java         # 启动时自动注册工具
│   │   └── impl/
│   │       ├── WeatherTool.java         # 天气工具（get_weather，假数据）
│   │       └── UserProfileTool.java     # 用户画像工具
│   ├── mapper/                          # MyBatis Mapper（注解方式，无 XML）
│   ├── model/
│   │   ├── entity/                      # 实体类（MySQL 表映射）
│   │   ├── dto/                         # 请求/响应 DTO
│   │   └── vo/                          # 视图对象
│   ├── filter/
│   │   └── TokenAuthenticationFilter.java # Token 认证过滤器
│   ├── scheduler/
│   │   └── MarketTimeUtil.java          # A股交易时段工具
│   ├── util/
│   │   ├── Md5Util.java
│   │   ├── RandomCodeUtil.java
│   │   └── PromptUtil.java
│   └── common/
│       ├── response/ApiResponse.java
│       └── enums/ResponseEnum.java
├── src/main/resources/
│   ├── application.yml                  # 全部配置（MySQL, Redis, Milvus, DeepSeek）
│   ├── schema.sql                       # DDL（启动时自动建表，含分区）
│   └── prompts/
│       ├── system_prompt.txt            # 默认系统提示词
│       └── romantic_chat_agent_prompt.txt # 恋爱聊天助手提示词
├── etl_*.py                             # Python ETL 数据采集脚本
├── frontend/                            # Vue3 前端源码
│   ├── src/                             # 组件、视图、API 层
│   ├── vite.config.ts                   # Vite 构建配置（输出到 static/）
│   └── package.json
├── electron/                            # Electron 桌面壳
│   ├── main.js                          # 主进程（启动 JAR + 打开窗口）
│   ├── package.json
│   ├── start.bat                        # Windows 启动脚本
│   └── start.sh                         # Linux/Mac 启动脚本
└── pom.xml
```

---

## 重点模块说明

### 1. DeepSeek 流式聊天（核心）

`DeepSeekServiceImpl` 是整个项目的核心，关键设计：

- **流式通信：** 使用 Spring WebFlux `WebClient` + `Flux<String>` 实现 SSE 流式响应
- **工具调用半流式架构：**
  1. 阶段一（流式）：模型输出文本 + thinking_content
  2. 阶段二（非流式）：检测到 `tool_calls`，累积参数直至完整 JSON → 执行工具
  3. 阶段三（流式）：发送工具结果 → 模型输出最终回答
  4. 最多 5 次工具调用迭代（`MAX_TOOL_CALL_ITERATIONS = 5`）
- **消息持久化：** 所有 user/assistant/tool 消息均存入 MySQL，每次请求重建上下文
- **会话管理：** 自动建表、支持多用户场景
- **提示词系统：** 请求中指定 `promptFileName` 加载不同 prompt，工具组通过 `application.yml` 的 `deepseek.tool-groups` 配置映射，不再从 prompt 文本中解析

### 2. 工具系统

- `Tool` 接口定义工具契约，`ToolRegistry` 管理注册
- `ToolInitializer` 在 `@PostConstruct` 中自动注册所有 `Tool` Bean
- 工具定义（JSON Schema）动态构建，按 `DeepSeek API` 的 Function Calling 格式发送

### 3. A股数据

- **数据源：** 东方财富（akshare） + 腾讯财经 API
- **实时行情：** `stock_realtime` 表，主键 `ts_code`，交易时段 ETL 脚本 3 秒轮询
- **日K线：** `stock_kline_daily` 按年分区，`stock_kline_min` 按周分区
- **自动分区：** `auto_maintain_partitions` 定时事件，每月 1 号凌晨 3 点自动新增分区
- **Python 脚本** 使用 akshare 库获取数据，pymysql 写入数据库

---

## 编码约定

- 所有注释使用中文
- MyBatis Mapper 使用注解方式（无 XML）
- 流式接口统一返回 `Flux<ServerSentEvent<String>>`（SSE 格式，MediaType: `text/event-stream`）
- Token 认证：请求头 `Authorization: Bearer <token>`，通过 `TokenAuthenticationFilter` 统一验证
- 数据库：下划线命名，MyBatis 开启 `map-underscore-to-camel-case: true`
- 密码加密：MD5（32位）
- **无测试代码**（尚未引入测试框架）

---

## 常见操作

```bash
# 重新加载提示词文件（修改 prompts/*.txt 后无需重启，PromptUtil 实时读取）
# 查看日志
tail -f app.log

# 查看数据库表结构
mysql -u root -p -e "USE agent_deepseek; SHOW TABLES;"

# 查看实时行情数据
mysql -u root -p -e "USE agent_deepseek; SELECT ts_code, name, price, pct_change FROM stock_realtime ORDER BY pct_change DESC LIMIT 10;"
```

---

## 前端开发（Vue3 + Vite）

前端源码位于 `frontend/`，独立开发调试：

```bash
cd frontend
npm install
npm run dev          # 开发服务器（端口 5173，代理 API 到 8080）
npm run build        # 构建到 src/main/resources/static/
```

构建后 Spring Boot 自动托管静态文件（`localhost:8080` 直接访问）。

### 前端目录结构

```
frontend/
├── src/
│   ├── api/          # API 请求层（chat, user, stock, watchlist 等）
│   ├── views/        # 页面：LoginView, HomeView, ChatAssistantView, StockAssistantView
│   ├── layouts/      # 布局组件（侧边菜单栏）
│   ├── store/        # Pinia 状态管理（user 持久化）
│   ├── router/       # Vue Router（路由守卫 + Token 验证）
│   └── utils/        # 工具函数（markdown 渲染、MD5、Token 计算）
├── public/           # 静态资源（katex CSS、favicon）
└── vite.config.ts    # 构建配置（base: ''，outDir: static/）
```

### 使用 Ant Design Vue 的图标

图标使用 `@ant-design/icons-vue`，在组件中按需导入（Tree-shaking 友好）：

```ts
import { RobotOutlined, MessageOutlined } from '@ant-design/icons-vue'
```

---

## 桌面端（Electron）

```bash
# 1. 构建后端 JAR（含前端静态文件）
mvn clean package -DskipTests

# 2. 安装 Electron 依赖（仅首次）
cd electron
npm install

# 3. 启动桌面应用
cd electron
npm start           # 生产模式：自动启动 JAR + 打开窗口
npm run dev         # 开发模式：不启动 JAR，需先手动启动后端
```

Windows 也可双击 `electron/start.bat` 一键启动。

### 打包分发

```bash
cd electron
npm run dist        # 打包为 exe/dmg（需额外安装 electron-builder）
```

### 架构说明

| 层 | 技术 | 说明 |
|---|---|---|
| 桌面壳 | Electron 33 | 启动 JAR + BrowserWindow |
| 后端 | Spring Boot 3.4 | 端口 8080，提供 API + 静态文件 |
| 前端 | Vue 3 + Vite 8 | SPA，客户端路由 |
| 构建 | Maven + frontend-maven-plugin | `mvn package` 自动编译前端 |
