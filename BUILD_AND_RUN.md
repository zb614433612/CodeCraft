# CodeCraft 构建与运行指南

> 🎯 **只是想使用 CodeCraft？** 前往 [GitHub Releases](https://github.com/zb614433612/CodeCraft/releases) 下载已打包好的 `CodeCraft-Setup-x.x.x.exe`，双击安装即可使用，无需阅读本文档。
>
> 本文档仅供**开发者**或**需要自行构建**的用户参考。

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17+（JDK） | 构建时需要 `JAVA_HOME`，运行时无需安装 Java |
| Maven | 3.8+ | 用于后端构建 |
| Node.js | 20+ | 前端和 Electron 构建 |
| npm | 10+ | 随 Node.js 安装 |
| H2 Database | 嵌入式 | 启动时自动创建，无需额外安装 |
| Caffeine | 3.x（内置） | 本地缓存，已替代 Redis，无需额外配置 |
| Milvus | 可选 | 向量数据库（可选依赖，不配置不影响核心功能） |

---

## 配置说明

### API Key 配置（必须）

CodeCraft 需要配置 AI 模型的 API Key 才能正常使用。

**推荐方式：通过页面配置**

启动后端服务后，打开浏览器访问 `http://localhost:8084`，按以下步骤操作：

1. 使用默认账号登录（如需）
2. 点击左侧菜单 **「配置」**
3. 在 **「DeepSeek API 配置」** 中输入你的 API Key
4. 点击 **「保存」** 即可

配置保存后即时生效，所有聊天请求将使用此 Key 调用 DeepSeek API。

> 💡 API Key 保存到数据库中，登录后只有你自己能看到（页面显示脱敏后的 Key）。

**备用方式：环境变量或配置文件**

如不方便通过页面配置，也可通过以下方式：

- **环境变量**：设置 `DEEPSEEK_API_KEY=sk-你的API密钥`
- **配置文件**：在 `src/main/resources/application.yml` 中修改 `deepseek.api-key`

> 注意：页面配置的 Key 优先级最高，会覆盖环境变量和配置文件中的 Key。

### 其他配置（可选）

- **Milvus 向量数据库**：默认连接 `localhost:19530`，可在 `application.yml` 中修改
- **代理配置**：如果需要通过代理访问外部网络，可在 `application.yml` 的 `network-tool.proxy` 中配置
- **服务器端口**：默认 `8084`，可通过环境变量 `SERVER_PORT` 或修改 `application.yml` 中的 `server.port`

---

## 开发模式运行

### 1. 启动后端服务

```bash
# 编译打包（含前端自动构建）
mvn clean package -DskipTests

# 启动 Spring Boot
mvn spring-boot:run
```

后端启动后访问 `http://localhost:8084` 即可打开前端页面。

> **默认登录账号**：`admin` / `123456` 🔑
> ⚠️ 首次登录后请务必在「用户管理」中修改密码，确保安全！

> 第一次构建时 `frontend-maven-plugin` 会自动下载 Node.js 和 npm 并编译前端代码。
> 若不需要编译前端可跳过：`mvn clean package -DskipTests -DskipFrontend=true`

### 2. 单独启动前端（开发模式，热更新）

```bash
cd frontend
npm install
npm run dev          # 开发服务器，端口 5173，代理 API 到 8084
```

前端开发模式下需先启动后端（`mvn spring-boot:run`）。

### 3. 启动 Electron 桌面应用（开发模式）

```bash
# 先构建后端 JAR（含前端静态文件）
mvn clean package -DskipTests

# 安装 Electron 依赖（仅首次）
cd electron
npm install

# 启动桌面应用
npm start            # 生产模式：自动启动 JAR + 打开窗口
npm run dev          # 开发模式：不启动 JAR，需先手动启动后端
```

---

## 后端 JAR 构建

```bash
# 完整构建（含前端编译）
mvn clean package -DskipTests

# 跳过前端编译
mvn clean package -DskipTests -DskipFrontend=true

# 输出文件
# target/codecraft-1.0.1.jar  （约 67MB）
```

---

## Electron 打包为 EXE（免 Java 依赖）

打包后的 EXE **自带 JRE 运行环境**，目标机器无需安装 Java。

### 前置条件

1. **JDK 17+**（仅构建时需要，用于 `jlink` 裁剪 JRE）
   ```cmd
   :: 验证 JDK（需要 JDK，仅 JRE 不够）
   "%JAVA_HOME%\bin\jlink" --version
   ```

2. **后端 JAR** 已构建：
   ```bash
   mvn clean package -DskipTests
   ```

3. **裁剪内置 JRE**（首次或 JDK 版本变更时需要）：
   ```cmd
   cd electron
   
   :: 用 jlink 裁剪最小化 JRE（约 43MB）
   "%JAVA_HOME%\bin\jlink" ^
     --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi ^
     --strip-debug --compress 2 --no-header-files --no-man-pages ^
     --output jre
   ```

4. **网络问题处理**（国内环境常见）：
   ```cmd
   :: nsis-resources（如果提示下载超时）
   mkdir "%LOCALAPPDATA%\electron-builder\Cache\nsis-resources"
   cd /d "%LOCALAPPDATA%\electron-builder\Cache\nsis-resources"
   curl -L -o nsis-resources.7z https://github.com/electron-userland/electron-builder-binaries/releases/download/nsis-resources-3.4.1/nsis-resources-3.4.1.7z
   ```

5. **符号链接权限问题**（如果解压 winCodeSign 时报错）：
   - 打开 **设置 → 隐私和安全性 → 开发者选项**
   - 开启 **开发者模式**
   - 无需重启

### 打包命令

```bash
cd electron

# 打包为安装程序（NSIS 安装包）
npm run dist
```

### 打包输出

```
electron/release/
├── CodeCraft-Setup-1.0.1.exe    # NSIS 安装程序（发行用）
└── CodeCraft-1.0.1-win.zip       # 绿色版压缩包（可选）
```

### 安装运行

双击 `CodeCraft-Setup-1.0.1.exe` 安装，安装后桌面会生成快捷方式。

> **运行注意事项：**
> - ✅ **无需安装 Java** — JRE 已内置在 EXE 中
> - ✅ 后端 JAR 已内置，无需额外放置
> - ✅ 启动时自动使用内置 JRE 启动后端（等待约 10-30 秒），然后加载前端页面
> - ⚠️ H2 数据库文件默认存储在 `./data/codecraft`，无需额外配置；如需连接远程 Milvus，请修改 application.yml

---

## 完整流程速查

```bash
# ===== 从零开始全流程 =====

# 1. 构建后端 JAR（含前端）
mvn clean package -DskipTests

# 2. 裁剪内置 JRE（仅首次或 JDK 变更时）
cd electron
"%JAVA_HOME%\bin\jlink" --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi --strip-debug --compress 2 --no-header-files --no-man-pages --output jre

# 3. 打包 Electron 桌面应用
cd ..
npm run dist

# 4. 安装生成的 EXE
# 双击 electron/release/CodeCraft-Setup-1.0.1.exe
```

---

## 项目结构

```
codecraft/
├── src/                        # Java 后端源码（Spring Boot）
│   ├── main/java/              # 主代码
│   │   └── com/example/agentdeepseek/
│   │       ├── common/         # 通用枚举、响应封装
│   │       ├── config/         # 配置类
│   │       ├── controller/     # REST API 控制器
│   │       ├── mapper/         # 数据访问层
│   │       ├── model/          # DTO、实体、VO
│   │       ├── scheduler/      # 定时任务
│   │       ├── service/        # 业务逻辑层
│   │       ├── tool/           # AI Agent 工具（30+ 工具）
│   │       └── util/           # 工具类
│   └── main/resources/         # 配置文件和静态资源
├── frontend/                   # Vue3 前端源码
│   ├── src/
│   │   ├── api/                # 后端 API 调用
│   │   ├── components/         # 通用组件
│   │   ├── views/              # 页面视图
│   │   └── utils/              # 工具函数
│   └── public/                 # 静态资源
├── electron/                   # Electron 桌面壳
│   ├── main.js                 # Electron 主进程
│   ├── package.json            # Electron 配置
│   ├── start.bat               # Windows 启动脚本
│   └── start.sh                # Linux/Mac 启动脚本
├── pom.xml                     # Maven 构建配置
├── README.md                   # 项目主页
├── BUILD_AND_RUN.md            # 构建与运行指南（本文档）
├── LICENSE                     # MIT 开源许可证
├── .gitignore                  # Git 忽略规则
├── data/                       # 运行时数据（H2 数据库文件，gitignore）
├── logs/                       # 运行时日志（gitignore）
└── snapshots/                  # 自动生成的快照（gitignore）
```
