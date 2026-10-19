> 🌐 English Version：[🇬🇧 FAQ_EN](./FAQ_EN.md)
# 常见问题 FAQ

> 开发、运行、打包过程中已踩过的坑和解决方案。

---

## 启动与运行

### Q1：启动报 "Port 8084 already in use"

```bash
# Windows：查找占用进程
netstat -ano | findstr 8084
taskkill /PID <PID> /F

# Mac/Linux
lsof -i :8084
kill -9 <PID>
```

或者改端口：`SERVER_PORT=8084 mvn spring-boot:run`

---

### Q2：启动后页面能打开但 AI 不回复

**原因**：API Key 未配置或无效。

**检查**：
1. 访问 `http://localhost:8084` → 左侧「模型配置」→ 检查 API Key
2. 确认该 Provider 已启用且模型列表配置正确
3. 查看日志 `logs/app.log` 中是否有 `401 Unauthorized` 错误

---

### Q3：H2 数据库锁表 / 无法启动

**现象**：`Database may be already in use` 或 `File lock exception`

**原因**：上一个进程未正常退出，或同时运行了两个实例。

**解决**：
```bash
# ① 确保没有其他 CodeCraft 进程在运行
jps -l | grep codecraft
# ② 删除锁文件
rm data/codecraft.lock.db    # Mac/Linux
del data\codecraft.lock.db   # Windows
# ③ 重新启动
```

---

### Q4：Token 过期后操作失败

**现象**：API 返回 `401` 或 `Token invalid`

**解决**：重新登录即可获取新 Token。Token 有效期为 7200 秒（2 小时）。

---

## 工具与 AI 行为

### Q5：AI 一直循环调用同一个工具不停止

**原因**：触发了重复调用检测（连续 3 次相同工具 + 相同关键参数），并经过评委评估。

**解决**：
- 等待系统自动终止（会显示终止原因）
- 或手动点击停止按钮
- 如果是误判，尝试换一种方式描述需求

---

### Q6：工具执行被拦截 / 弹窗频繁

**说明**：这是三层安全防护的正常行为。

| 场景 | 为什么弹窗 |
|------|-----------|
| manual 模式 + 写文件 | 层面一：数据影响防护 |
| manual 模式 + 跨目录操作 | 层面二：路径穿透防护 |
| auto 模式 + delete/execute_sql/git_push | 层面三：高危操作防护 |

**减少弹窗**：
- 使用「本轮对话全部同意」选项
- 或将 Agent 切换为 auto 模式（高危操作仍会弹窗）

---

### Q7：文件编码乱码

**原因**：文件编码不是 UTF-8。

CodeCraft 内置了 `FileEncodingDetector`（基于 juniversalchardet，支持 30+ 编码自动识别），读取文件时会自动检测并转换编码。写入时始终使用 UTF-8。

如果仍有问题，手动将源文件转为 UTF-8 编码。

---

## P2P 连接

### Q8：P2P 连不上 / 扫码没反应

**排查步骤**：
1. 检查双方 P2P 端口（9527）是否被防火墙拦截
2. 检查是否在同一局域网（IPv6 地址是否可达）
3. 查看日志中 `[P2P]` 开头的记录
4. 尝试手动输入连接字符串：`codecraft-p2p://{对方IP}:9527?fp={证书指纹}`

---

### Q9：P2P 调用对方 Agent 被拒绝

**原因**：对方取消了授权，或授权记录状态不是 active。

**解决**：联系对方重新授权。每次取消授权在本地立即生效，不依赖网络通知。

---

## 构建与打包

### Q10：`mvn clean package` 失败

**常见原因**：
1. `JAVA_HOME` 未设置或指向 JRE（需要 JDK 17+）
2. Node.js 未安装（`frontend-maven-plugin` 会自动下载，但网络问题可能导致失败）
3. 前端编译失败 — 先单独 `cd frontend && npm install && npm run build` 排查

```bash
# 跳过前端编译的构建
mvn clean package -DskipTests -DskipFrontend=true
```

---

### Q11：Electron 打包时网络超时

```bash
# 设置国内镜像加速
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
npm install

# 或手动下载 Electron 二进制放到缓存目录
# Windows: %LOCALAPPDATA%\electron\Cache\
# macOS: ~/Library/Caches/electron/
# Linux: ~/.cache/electron/
```

---

## 快照与回滚

### Q12：回滚后发现文件没有完全恢复

**原因**：同一 turn 内同一文件只备份首次修改前的状态。如果 AI 在同一个 turn 内对同一文件做了多次修改，回滚会恢复到第一次修改之前的状态。

如果只需要回滚部分改动，使用「按文件回滚」。

---

### Q13：snapshots 目录占用空间过大

**解决**：
- 配额机制会自动清理（500MB → 清理到 300MB）
- 手动清理：删除 `snapshots/` 下不需要的快照目录，再删除 `.snapshots_index.json`（下次启动自动重建）
- 或在「快照管理」前端页面查看和管理

---

## 技能系统

### Q14：创建的技能没有生效

**排查**：
1. 技能是否绑定了正确的 Agent 配置？（`agent_config_id` 字段）
2. 触发词是否过于宽泛？（太短或太常见的词会被忽略）
3. 查看日志中 `[Skill]` 开头的记录，确认匹配和注入情况
4. 技能置信度是否过低？（低于 0.1 会被自动淘汰）

---

## 其他

### Q15：如何查看完整的 AI 思考过程？

在前端聊天界面中，每条 AI 回复上方有「思考过程」折叠面板，点击展开即可查看 AI 的 reasoning 内容。

子 Agent 的思考过程：在子 Agent 面板中点击「查看详情」。

---

### Q16：数据文件在哪里？如何备份？

| 数据 | 位置 |
|------|------|
| 数据库 | `data/codecraft.mv.db` |
| 日志 | `logs/` |
| 快照 | `snapshots/` |
| P2P 证书 | `data/p2p/certs/` |
| 配置文件 | `application-local.yml` |

备份只需复制以上文件/目录即可。

---

### Q17：macOS 上「关掉窗口后再打开就白屏 / 打不开」

**现象**（Intel 与 Apple Silicon 芯片均会出现，与 CPU 架构无关）：
1. 启动应用 → 正常；点窗口左上角红叉关闭；
2. Dock 图标下的运行小圆点**不消失**（进程仍在，符合 macOS 惯例）；
3. 再点 Dock 图标 → 界面白屏，或再次双击 `.app` 提示「启动失败」，只能强制退出后才能用。

**根因**（`electron/main.js` 生命周期实现）：
- `window-all-closed` 里无条件 `javaProcess.kill()`，但 macOS 分支不 `app.quit()` →
  **进程留着、后端被杀**；`activate` 重建窗口时只 `loadURL`，不会重启后端 →
  `http://localhost:8084` 连接失败 → 白屏。
- 缺 `requestSingleInstanceLock()`：再次双击 `.app` 会拉起第二个实例，
  第二个实例重复 spawn 后端 → 8084 端口与 H2 文件锁冲突 → 后端起不来 → 弹「启动失败」。
- `Menu.setApplicationMenu(null)` 在 macOS 上会连带禁用 `Cmd+Q` / `Cmd+C` / `Cmd+W`，
  用户无法正常退出应用，只能强制退出（又留下孤儿 java 进程）。

**修复后的正确行为**：
| 操作 | 结果 |
|------|------|
| 点红叉关窗 | 窗口关闭，应用与**后端保持后台运行**（Dock 圆点在，属预期） |
| 再点 Dock 图标 | 秒开，无需重启后端、不再白屏 |
| 再次双击 `.app` | 单实例锁生效，只把已有窗口唤到前台，不启动第二个实例 |
| `Cmd+Q` / 菜单「退出」 | 真正退出，同时停止后端 java 进程 |
| 强制退出后再次启动 | 先探测 8084 是否已有残留后端，命中则直接复用，不重复 spawn |

> 一句话：**macOS 上关窗 ≠ 退出，要彻底退出请用 `Cmd+Q`**（或 Dock 右键 → 退出）。

---

### Q18：怎么确认自己在用哪个版本？「检查更新」为什么在浏览器里点了没反应？

**怎么查版本**：
1. **桌面客户端**：左下角头像 → 个人信息上方菜单，或「设置 → 版本与更新」（路由 `/about`）；
2. **浏览器**：打开 `http://localhost:8084/v3/#/about`（经典版在「系统设置」）；
3. **命令行**：`GET /api/system/version`（登录后）返回
   `{"name":"CodeCraft","version":"3.0.0","buildTime":"...","javaVersion":"17.0.0.1","osName":"Windows 10","osArch":"amd64","pid":45800}`。

**为什么有两个版本号**：
| 显示项 | 来源 | 什么时候会不一致 |
|---|---|---|
| 客户端版本 | 安装包（`electron/package.json` 的 `version`，运行时 `app.getVersion()`） | 只替换了 `codecraft.jar` 但没重装客户端（例如手动换 jar 调试） |
| 后端版本 | jar（`pom.xml` 的 `<version>`，经 `GET /api/system/version` 读取） | 同上 |

界面**故意不合并**这两个数字：合并后一旦出现「客户端 3.0.0 + 后端 3.1.0」这种半升级状态，问题会被掩盖。

**浏览器里点「检查更新」只弹提示，是缺陷吗**：
不是。自动更新依赖 Electron 主进程提供的 `electronAPI.update`（`electron/update-manager.js`），
纯浏览器没有这个桥接，页面会自动降级为「只读版本卡片 + 更新设置只读文案」，
并提示「到 GitHub Releases 手动下载」。要看真实的检查更新 / 下载进度 / 一键安装，请用桌面客户端。

**升级会不会丢数据**：
不会。数据目录在 `userData/runtime`（数据库、日志、接收文件），安装器只替换程序文件、不触碰数据；
安装前还会把数据库备份到 `userData/backups`（保留份数可配，默认 3 份）。
「版本与更新 → 清理升级缓存」只删备份 / 已下载安装包 / 检测缓存，**不动数据库**；「数据管理 → 重置所有数据」才会清业务数据。

---

### Q19：从旧版本覆盖安装 3.0.0 后，启动报「后端服务启动失败：后端服务启动超时」

**典型现场**：旧版（2.0.2 / 2.0.4）一直正常，覆盖安装 3.0.0 后启动直接失败，重装一次仍然一样。

现象其实分两类，排查方向完全不同 —— 先对号入座再动手：

#### 现场 A：安装目录里仍是旧 jar（安装时文件被锁）

判断依据（任一命中即是）：

- `安装目录\resources\backend\codecraft.jar` 的修改时间仍是旧构建时间；
- 解包后包内没有 `BOOT-INF/classes/v3-static/`。

**根因（两个问题叠加）**：

1. **旧后端进程没被结束，锁住了 jar**。安装器只会结束 `CodeCraft.exe`，不会结束它拉起的
   `java.exe -jar ...\resources\backend\codecraft.jar`。这个 java 进程持有 jar 文件句柄，
   导致 NSIS 覆盖写 `codecraft.jar` 失败 —— 而 NSIS 对这种失败是**静默的**：照样弹「安装完成」，
   但装完安装目录里**仍是旧 jar**。
2. **3.0.0 新增的「复用已有后端」探测在旧后端仍占用端口时会误判**。8084 被旧 java 占着时：
   - 探测成功 → 复用旧后端 → 界面缺 `/v3/` 与 3.x 新接口（白屏 / 功能缺失）；
   - 探测超时（旧后端正忙，1.5s 未响应）→ 直接拉起新 java → 撞上端口占用 + H2 文件锁 →
     新进程卡住不退出 → 等满超时 → 弹出「后端服务启动超时」。

#### 现场 B：jar 已是新版，但 java 根本没被启动起来

判断依据：`CodeCraft.exe` 版本已经是 3.0.0、**8084 端口没有任何占用**、
`runtime\logs\app.log` 里看不到本次启动的任何记录。

这种情况说明 Spring Boot 进程根本没跑起来（还没走到写 logback 的阶段），常见原因：

- 内置 JRE 缺失或与系统不匹配：`安装目录\resources\jre\bin\java.exe` 不存在（被杀毒软件隔离、
  安装残留、跨平台打包）；
- 内置 JRE 的 jlink 裁剪漏了模块，`java` 启动即报 `Module ... not found`；
- 机器上既没有内置 JRE、系统也没装 Java 17+，回退到 PATH 的 `java` 时 spawn 直接 `ENOENT`；
- jar 文件损坏（安装不完整）。

排查方式（重发包起）：打开 `%APPDATA%\codecraft-desktop\runtime\logs\backend-console.log`，
这是 java 子进程 stdout / stderr 的**原样记录**，开头几行就写明 Java 路径、JAR 路径、工作目录与平台，
后面是 JVM / Spring Boot 的原始输出；启动失败的原生弹窗也会直接附带该日志的最后 30 行。
若该文件只有启动头、后面一片空白，基本可判定为「java 没能启动」，请对照上面四条逐项核对。

**手动解决（现场 A 适用，用当前 3.0.0 安装包即可，不必等新版）**：

1. 托盘图标右键退出 CodeCraft（任务管理器里不应再有 `CodeCraft.exe`）；
2. 结束残留后端：任务管理器 → 详细信息 → 找到**命令行含 `codecraft.jar`** 的 `java.exe` 结束。
   ⚠️ 不要「结束所有 java.exe」，IDEA 等开发工具也用它；
3. 重新运行 `CodeCraft.Setup.3.0.0.exe` 完成安装（这一步 jar 才会真正被替换）；
4. 启动。

仓库 `scripts/fix-startup.bat` 可一键完成第 1、2 步，并检查 8084 是否已释放。

**现场 B 的处理**：先按判断依据确认 `resources\jre\bin\java.exe` 是否存在；若被安全软件隔离，
从隔离区恢复后重装；若文件本就不存在，说明安装包不完整，重新下载安装包。

**已经改掉的代码**（进重发的 3.0.0 安装包起生效）：

- 安装器 `customInit` 改为三步清理：`CodeCraft.exe` 优雅退出（触发 `before-quit` → 停后端）→
  强制结束 `CodeCraft.exe` → 兜底清理「命令行含 `codecraft.jar`」的 `java.exe`，全部做完才覆盖文件；
- 启动时区分 `none / current / legacy / foreign` 四种端口状态：
  - 旧版后端或缺响应 → 先结束旧进程、等端口释放，再启动新后端（自愈）；
  - 被其它程序占用 → 直接报错并给出「进程名 + PID」，不再盲等 90 秒；
- 页面特征匹配从裸 `CodeCraft` 子串收紧为 `<div id="app">` / `<title>CodeCraft`，
  避免把任意含 codecraft 字样的第三方服务误判成自家后端；
- **新增 `runtime/logs/backend-console.log`**：java 子进程的 stdout / stderr 原样落盘，
  并在启动失败时把最后 30 行直接拼进错误弹窗 —— 专门解决「现场 B 什么报错都看不到」的问题；
- `findJava()` 的每一步探测（内置 JRE → `JAVA_HOME` → 系统 `PATH`）都会写入该日志，
  版本不对或回退路径一目了然。

**数据会不会丢**：不会。2.0.2 及更早的数据在安装目录内，安装器会先备份到
`%APPDATA%\codecraft-desktop\legacy-backup`，首次启动自动恢复到 `userData/runtime`。
旧安装器曾误备份到 `%APPDATA%\CodeCraft\legacy-backup`（productName 目录），
新版本已兼容读取该路径 —— 历史备份仍可在上述两个目录之一找回。

---

> 💡 找不到答案？查看日志 `logs/app.log` 中的错误信息，或在 GitHub Issues 中搜索/提问。
