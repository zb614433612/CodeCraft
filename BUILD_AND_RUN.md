# CodeCraft 构建与运行指南

> 🎯 **只是想使用 CodeCraft？** 前往 [GitHub Releases](https://github.com/zb614433612/CodeCraft/releases) 下载打包好的安装包：
> - **Windows**：`CodeCraft-Setup-x.x.x.exe`
> - **macOS**：`CodeCraft-x.x.x.dmg`
>
> 双击安装即可使用，无需阅读本文档。
>
> 本文档仅供**开发者**或**需要自行构建**的用户参考。
> 
> 🚀 **懒人通道**：如果只是想快速打包，直接使用一键脚本：
> - Windows：`scripts\build.bat [版本号]`
> - Mac/Linux：`./scripts/build.sh [版本号]`
> 
> 脚本会自动完成版本同步 → Maven构建 → JRE裁剪 → Electron打包。详见 [scripts/](scripts/) 目录。

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

> ⚠️ **首次运行前**：项目启动时会自动加载 `application-local.yml`（优先级高于 `application.yml`）。
> 请复制模板文件 `application-local.yml.example` 为 `application-local.yml`，并填入你的 API Key：
> ```bash
> cp application-local.yml.example application-local.yml
> # 然后编辑 application-local.yml，填入 deepseek.api-key
> ```
> 该文件已在 `.gitignore` 中排除，不会提交到 Git。

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
# target/codecraft-1.0.5.jar  （约 67MB）
```

---

---

# 🪟 Windows 打包

## Windows：打包为 EXE 安装程序（NSIS）

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

# 安装依赖（首次）
npm install

# 打包为安装程序（NSIS 安装包）
npm run dist:win
```

### 打包输出

```
electron/release/
├── CodeCraft-Setup-1.0.5.exe       # NSIS 安装程序（发行用）
└── CodeCraft-1.0.5-win.zip         # 绿色版压缩包（可选）
```

### 安装运行

双击 `CodeCraft-Setup-1.0.5.exe` 安装，安装后桌面会生成快捷方式。

> **运行注意事项：**
> - ✅ **无需安装 Java** — JRE 已内置在 EXE 中
> - ✅ 后端 JAR 已内置，无需额外放置
> - ✅ 启动时自动使用内置 JRE 启动后端（等待约 10-30 秒），然后加载前端页面
> - ⚠️ H2 数据库文件默认存储在 `./data/codecraft`，无需额外配置；如需连接远程 Milvus，请修改 application.yml

---

---

# 🍎 macOS 打包

## macOS：打包为 DMG 磁盘映像

打包后的 DMG **自带 JRE 运行环境**，目标机器无需安装 Java。

> ⚠️ **重要提示**：DMG 打包**只能在 macOS 上执行**，无法在 Windows 或 Linux 上交叉打包。因为 DMG 创建依赖 macOS 的 `hdiutil` 系统工具。

### 前置条件

1. **macOS 系统**（Intel 或 Apple Silicon 均可）

2. **JDK 17+**（用于 `jlink` 裁剪 JRE）
   ```bash
   # 验证 JDK 已安装
   /usr/libexec/java_home -V
   jlink --version
   ```

3. **后端 JAR** 已构建：
   ```bash
   mvn clean package -DskipTests
   ```

4. **裁剪 macOS 版内置 JRE**（首次或 JDK 版本变更时需要）：

   > ⚠️ 必须使用 **macOS 版 JDK** 来裁剪，裁剪出的 JRE 仅能在 macOS 上运行。

   ```bash
   cd electron

   # 先删除旧的 Windows 版 JRE（如果有）
   rm -rf jre

   # 用 jlink 裁剪最小化 JRE（约 43MB）
   # 注意：macOS 上 java 路径不含 .exe 后缀
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)

   "$JAVA_HOME/bin/jlink" \
     --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi \
     --strip-debug --compress 2 --no-header-files --no-man-pages \
     --output jre
   ```

   > 验证裁剪结果：`ls jre/bin/java` 应该存在（不是 `java.exe`！）

5. **准备 macOS 图标**（将 `icon.png` 转为 `icon.icns`）：

   ```bash
   cd electron

   # 创建临时 iconset 目录
   mkdir icon.iconset

   # 生成各尺寸 PNG（macOS 要求）
   sips -z 16 16     icon.png --out icon.iconset/icon_16x16.png
   sips -z 32 32     icon.png --out icon.iconset/icon_16x16@2x.png
   sips -z 32 32     icon.png --out icon.iconset/icon_32x32.png
   sips -z 64 64     icon.png --out icon.iconset/icon_32x32@2x.png
   sips -z 128 128   icon.png --out icon.iconset/icon_128x128.png
   sips -z 256 256   icon.png --out icon.iconset/icon_128x128@2x.png
   sips -z 256 256   icon.png --out icon.iconset/icon_256x256.png
   sips -z 512 512   icon.png --out icon.iconset/icon_256x256@2x.png
   sips -z 512 512   icon.png --out icon.iconset/icon_512x512.png
   sips -z 1024 1024 icon.png --out icon.iconset/icon_512x512@2x.png

   # 生成 icns 文件
   iconutil -c icns icon.iconset

   # 清理临时目录
   rm -rf icon.iconset
   ```

   > 最终得到 `electron/icon.icns`，`electron-builder` 会自动使用它。

6. **（可选）DMG 背景图**：在 `electron/` 目录下放一张 `dmg-background.png`（540×380），作为 DMG 打开后的背景。

### 打包命令

```bash
cd electron

# 安装依赖（首次）
npm install

# 打包为 DMG
npm run dist:mac
```

### 打包输出

```
electron/release/
├── CodeCraft-1.0.5.dmg              # DMG 磁盘映像（发行用）
├── CodeCraft-1.0.5-mac.zip          # 绿色版压缩包（可选）
└── mac/                             # 未打包的 .app 目录
    └── CodeCraft.app
```

### 安装运行

1. 双击 `CodeCraft-1.0.5.dmg` 挂载磁盘映像
2. 将 `CodeCraft.app` 拖到 `Applications` 文件夹
3. 首次打开时，由于未签名，需要**右键 → 打开**（或到「系统偏好设置 → 安全性与隐私」中允许）

> **运行注意事项：**
> - ✅ **无需安装 Java** — JRE 已内置在 .app 中
> - ✅ 后端 JAR 已内置，无需额外放置
> - ✅ 启动时自动使用内置 JRE 启动后端（等待约 10-30 秒），然后加载前端页面
> - ⚠️ H2 数据库文件默认存储在 `~/Library/Application Support/CodeCraft/data`，无需额外配置

### macOS 代码签名（可选）

如果需要分发到未开启"任何来源"的 Mac，建议进行代码签名和公证：

```bash
# 设置签名环境变量
export APPLE_ID="your@email.com"
export APPLE_APP_SPECIFIC_PASSWORD="xxxx-xxxx-xxxx-xxxx"
export APPLE_TEAM_ID="YOUR_TEAM_ID"

# 签名 + 公证
cd electron
npm run dist:mac
```

在 `electron/package.json` 的 `build.mac` 中补充：
```json
"mac": {
  "hardenedRuntime": true,
  "gatekeeperAssess": false,
  "entitlements": "entitlements.mac.plist",
  "entitlementsInherit": "entitlements.mac.plist"
}
```

> 以上配置已在 `package.json` 中预设好了。

---

---

# 🐧 Linux 打包

## Linux：打包为 AppImage / deb

```bash
cd electron

# 裁剪 Linux 版 JRE（与 macOS 类似，使用 Linux 版 JDK）
rm -rf jre
export JAVA_HOME=/path/to/linux-jdk-17
"$JAVA_HOME/bin/jlink" \
  --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi \
  --strip-debug --compress 2 --no-header-files --no-man-pages \
  --output jre

# 打包
npm install
npm run dist:linux
```

输出：
```
electron/release/
├── CodeCraft-1.0.5.AppImage    # AppImage（免安装，双击运行）
└── CodeCraft-1.0.5.deb         # deb 包（Debian/Ubuntu）
```

---

---

## 完整流程速查

### Windows 全流程

```bash
# ===== 从零开始 =====

# 1. 构建后端 JAR（含前端）
mvn clean package -DskipTests

# 2. 裁剪 Windows 内置 JRE（仅首次或 JDK 变更时）
cd electron
"%JAVA_HOME%\bin\jlink" --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi --strip-debug --compress 2 --no-header-files --no-man-pages --output jre

# 3. 打包
npm install
npm run dist:win

# 4. 安装
# 双击 electron/release/CodeCraft-Setup-1.0.5.exe
```

### macOS 全流程

```bash
# ===== 从零开始 =====

# 1. 构建后端 JAR（含前端）
mvn clean package -DskipTests

# 2. 裁剪 macOS 内置 JRE
cd electron
rm -rf jre
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
"$JAVA_HOME/bin/jlink" \
  --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi \
  --strip-debug --compress 2 --no-header-files --no-man-pages \
  --output jre

# 3. 生成 macOS 图标
mkdir icon.iconset
sips -z 16 16 icon.png --out icon.iconset/icon_16x16.png
sips -z 32 32 icon.png --out icon.iconset/icon_16x16@2x.png
sips -z 32 32 icon.png --out icon.iconset/icon_32x32.png
sips -z 64 64 icon.png --out icon.iconset/icon_32x32@2x.png
sips -z 128 128 icon.png --out icon.iconset/icon_128x128.png
sips -z 256 256 icon.png --out icon.iconset/icon_128x128@2x.png
sips -z 256 256 icon.png --out icon.iconset/icon_256x256.png
sips -z 512 512 icon.png --out icon.iconset/icon_256x256@2x.png
sips -z 512 512 icon.png --out icon.iconset/icon_512x512.png
sips -z 1024 1024 icon.png --out icon.iconset/icon_512x512@2x.png
iconutil -c icns icon.iconset
rm -rf icon.iconset

# 4. 打包
npm install
npm run dist:mac

# 5. 安装
# 双击 electron/release/CodeCraft-1.0.5.dmg 挂载后拖入 Applications
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
│   ├── preload.js              # Electron 预加载脚本
│   ├── package.json            # Electron 构建配置（含 win/mac/linux 打包设置）
│   ├── entitlements.mac.plist  # macOS 代码签名权限声明
│   ├── icon.ico                # Windows 图标
│   ├── icon.png                # 通用图标（Linux + macOS 源素材）
│   ├── icon.icns               # macOS 图标（需手动生成）
│   ├── dmg-background.png      # DMG 背景图（可选）
│   ├── start.bat               # Windows 启动脚本
│   └── start.sh                # Linux/Mac 启动脚本
├── pom.xml                     # Maven 构建配置
├── README.md                   # 项目主页
├── CHANGELOG.md                # 更新日志
├── BUILD_AND_RUN.md            # 构建与运行指南（本文档）
├── LICENSE                     # MIT 开源许可证
├── .gitignore                  # Git 忽略规则
├── data/                       # 运行时数据（H2 数据库文件，gitignore）
├── logs/                       # 运行时日志（gitignore）
└── snapshots/                  # 自动生成的快照（gitignore）
```
