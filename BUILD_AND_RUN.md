# zb-agent 构建与运行指南

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17+（JDK） | 构建时需要 `JAVA_HOME`，运行时无需安装 Java |
| Maven | 3.8+ | 用于后端构建 |
| Node.js | 20+ | 前端和 Electron 构建 |
| npm | 10+ | 随 Node.js 安装 |
| MySQL | 8.0+ | 端口 3306 |
| Redis | 任意 | 端口 6379 |
| Milvus | 任意 | 端口 19530 |

---

## 开发模式运行

### 1. 启动后端服务

```bash
# 编译打包（含前端自动构建）
mvn clean package -DskipTests

# 启动 Spring Boot
mvn spring-boot:run
```

后端启动后访问 `http://localhost:8080` 即可打开前端页面。

> 第一次构建时 `frontend-maven-plugin` 会自动下载 Node.js 和 npm 并编译前端代码。
> 若不需要编译前端可跳过：`mvn clean package -DskipTests -DskipFrontend=true`

### 2. 单独启动前端（开发模式，热更新）

```bash
cd frontend
npm install
npm run dev          # 开发服务器，端口 5173，代理 API 到 8080
```

前端开发模式下需先启动后端（`mvn spring-boot:run`）。

### 3. 启动 Electron 桌面应用（开发模式）

```bash
# 先构建后端 JAR（含前端静态文件）
cd E:\zbcode\agent-deepseek
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
cd E:\zbcode\agent-deepseek

# 完整构建（含前端编译）
mvn clean package -DskipTests

# 跳过前端编译
mvn clean package -DskipTests -DskipFrontend=true

# 输出文件
# target/agent-deepseek-0.0.1-SNAPSHOT.jar  （约 67MB）
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
   ```cmd
   cd E:\zbcode\agent-deepseek
   mvn clean package -DskipTests
   ```

3. **裁剪内置 JRE**（首次或 JDK 版本变更时需要）：
   ```cmd
   cd E:\zbcode\agent-deepseek\electron
   
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
cd E:\zbcode\agent-deepseek\electron

:: 打包为安装程序（NSIS 安装包）
npm run dist
```

### 打包输出

```
electron/release/
├── zb-agent-Setup-1.0.0.exe    # NSIS 安装程序（发行用）
└── zb-agent-1.0.0-win.zip       # 绿色版压缩包（可选）
```

### 安装运行

双击 `zb-agent-Setup-1.0.0.exe` 安装，安装后桌面会生成快捷方式。

> **运行注意事项：**
> - ✅ **无需安装 Java** — JRE 已内置在 EXE 中
> - ✅ 后端 JAR 已内置，无需额外放置
> - ✅ 启动时自动使用内置 JRE 启动后端（等待约 10-30 秒），然后加载前端页面
> - ⚠️ 需要配置远程 MySQL、Redis、Milvus 连接（默认配置在 JAR 中）

---

## 完整流程速查

```bash
:: ===== 从零开始全流程 =====

:: 1. 构建后端 JAR（含前端）
cd E:\zbcode\agent-deepseek
mvn clean package -DskipTests

:: 2. 裁剪内置 JRE（仅首次或 JDK 变更时）
cd electron
"%JAVA_HOME%\bin\jlink" --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi --strip-debug --compress 2 --no-header-files --no-man-pages --output jre

:: 3. 打包 Electron 桌面应用
npm run dist

:: 4. 安装生成的 EXE
:: 双击 electron/release/zb-agent-Setup-1.0.0.exe
```

---

## 项目结构

```
agent-deepseek/
├── src/                        # Java 后端源码（Spring Boot）
├── frontend/                   # Vue3 前端源码
├── electron/                   # Electron 桌面壳
│   ├── main.js                 # Electron 主进程
│   ├── package.json            # Electron 配置（含 electron-builder 配置）
│   ├── jre/                    # 内置 JRE（jlink 裁剪，gitignore）
│   ├── start.bat               # Windows 启动脚本
│   └── start.sh                # Linux/Mac 启动脚本
├── pom.xml                     # Maven 构建配置（含 frontend-maven-plugin）
└── target/                     # 构建输出
    └── agent-deepseek-0.0.1-SNAPSHOT.jar
```
