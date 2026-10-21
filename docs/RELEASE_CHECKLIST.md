> 🌐 English Version：[🇬🇧 RELEASE_CHECKLIST_EN](./RELEASE_CHECKLIST_EN.md)
# 打包发布清单

> 从代码到用户手中的完整发布流程。适用版本：v1.0.5+

---

## 发布前检查

> 🚀 **推荐方式**：使用一键构建脚本，自动完成下面 Step 1~3：
> - Windows：`scripts\build.bat v3.0.7`
> - Mac/Linux：`./scripts/build.sh v3.0.7`
>
> 以下为手动步骤，供需要精细控制的场景参考。

- [ ] `pom.xml` 中 `<version>` 已更新为目标版本
- [ ] `electron/package.json` 中 `version` 已同步，且 `build.extraResources` 的 `../target/code-craft-{版本号}.jar` 路径同步
- [ ] `frontend-v3/package.json`（v3 前端）与 `frontend/package.json`（经典版）的 `version` 与后端一致
- [ ] `CodeCraftMcpServer.SERVER_VERSION`（MCP 握手版本）已同步
- [ ] `application.yml` 的 `app.version` 保持占位符 `@project.version@`（**不要写死数字**，否则「版本与更新」页会与 jar 实际版本脱节）
- [ ] `CHANGELOG.md` 已更新
- [ ] 所有代码已提交 `git status` 无未提交变更
- [ ] API Key 等敏感信息未硬编码在代码中
- [ ] `application-local.yml` 未被提交（已在 .gitignore 中）

---

## 发布后验收（版本与更新）

- [ ] 打开 `http://localhost:8084/v3/#/about`（或桌面端「设置 → 版本与更新」），确认**客户端版本**与**后端版本**都是本次发布版本
- [ ] `GET /api/system/version`（带 token）返回的 `version` 与 `pom.xml` 一致，`buildTime` 非 `@maven.build.timestamp@` 字面量
- [ ] 桌面端点击「检查更新」能拿到最新 Release（含 notes 与安装包体积）
- [ ] 浏览器打开 `/about` 时只显示只读版本卡片 + 「请到 GitHub Releases 手动下载」提示，**控制台无报错**

---

## Step 1：构建后端 JAR

```bash
# 完整构建（含前端编译）
mvn clean package -DskipTests

# 产物：target/code-craft-{版本号}.jar（约 67MB）
```

---

## Step 2：裁剪内置 JRE（仅首次或 JDK 版本变更时）

```bash
cd electron

# Windows
"%JAVA_HOME%\bin\jlink" ^
  --add-modules java.base,java.logging,java.sql,java.xml,java.naming,jdk.naming.dns,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi ^
  --strip-debug --compress 2 --no-header-files --no-man-pages ^
  --output jre

# macOS
rm -rf jre
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
"$JAVA_HOME/bin/jlink" \
  --add-modules java.base,java.logging,java.sql,java.xml,java.naming,jdk.naming.dns,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi \
  --strip-debug --compress 2 --no-header-files --no-man-pages \
  --output jre
```

验证：`ls jre/bin/java`（macOS）或 `dir jre\bin\java.exe`（Windows）应存在。

> ⚠️ **跨平台构建前必须清掉旧 JRE**
>
> `electron/jre` 是构建机上一次裁剪的产物，**不跨平台通用**：
> - Windows 的 JRE 里是 `bin/java.exe`，打进 dmg 后 macOS 上找不到 `bin/java` →
>   客户端启动时提示「启动失败：后端服务启动失败」或白屏；
> - 反之先把 mac 的 JRE 留下再打 Windows 包，同样起不来。
>
> 正确做法：切平台打包前先 `rm -rf electron/jre`（Windows 用 `rmdir /s /q electron\jre`）再按上面的命令重新裁剪。
> `scripts/build.sh` 已内置平台校验（用 `file` 检查二进制是 Mach-O 还是 ELF），不匹配会自动重新裁剪；
> `scripts/build.bat` 用 `java.exe` 是否存在判断，Windows 侧天然安全。
>
> 另外 dmg 默认只产出**构建机同架构**（Intel Mac 出 x64、Apple Silicon 出 arm64），
> 要给另一架构发版需在对应架构机器上构建（JRE 也必须与包架构一致，不能混用）。

---

## Step 3：各平台打包

```bash
cd electron
npm install

# Windows → electron/release/CodeCraft-Setup-{版本号}.exe
npm run dist:win

# macOS → electron/release/CodeCraft-{版本号}.dmg（需在 macOS 上执行）
npm run dist:mac

# Linux → electron/release/CodeCraft-{版本号}.AppImage + .deb
npm run dist:linux
```

---

## Step 4：产物验证

```bash
# 检查产物是否存在且大小合理
ls -lh electron/release/

# 预期产物：
# Windows: CodeCraft-Setup-{版本号}.exe  (~120MB+)
# macOS:   CodeCraft-{版本号}.dmg      (~130MB+)
# Linux:   CodeCraft-{版本号}.AppImage  (~130MB+)
```

- [ ] EXE/DMG/AppImage 文件存在且 > 100MB
- [ ] Windows：在干净虚拟机中安装测试
- [ ] macOS：DMG 挂载正常，拖到 Applications 后运行
- [ ] 首次启动 API Key 配置流程正常
- [ ] 发送一条简单消息验证 AI 功能正常

---

## Step 5：创建 Git Tag

```bash
git tag -a v{版本号} -m "Release v{版本号}"
git push origin v{版本号}
```

---

## Step 6：创建 GitHub Release

使用 [Release发布标准化流程] 技能，或手动操作：

1. 前往 `https://github.com/zb614433612/CodeCraft/releases/new`
2. Tag：选择刚推送的 `v{版本号}`
3. Title：`CodeCraft v{版本号}`
4. 描述：粘贴 CHANGELOG.md 中对应版本的内容
5. 上传产物：
   - `CodeCraft-Setup-{版本号}.exe`
   - `CodeCraft-{版本号}.dmg`（如有）
   - `CodeCraft-{版本号}.AppImage`（如有）
6. **自动更新要求（v2.0.3+，务必满足）**：
   - Release 必须是**正式发布**（不能是 draft / pre-release）——客户端只读 `/releases/latest`；
   - 资产必须上传到该 Release（客户端按 `assets[].browser_download_url` 下载，仅放仓库文件不生效）；
   - 资产文件名需能被 `electron/update-utils.js` 匹配：Windows 含 `Setup` 的 `.exe`、macOS `.dmg`、Linux `.AppImage` / `.deb`，并带版本号；
   - `tag_name` 用 `vX.Y.Z` 形式（客户端会自动归一化 `v` 前缀，支持预发布版本比较）；
   - 旧版本客户端需手动升级一次到 v2.0.3+，之后才能享受自动更新。
7. 点击 Publish Release

---

## Step 7：发布后验证

- [ ] Release 页面可正常访问
- [ ] 各平台产物可正常下载
- [ ] 下载的 EXE 可安装运行
- [ ] `README.md` 中的下载链接指向最新 Release
- [ ] 「设置 → 版本与更新」（`/about`）→「检查更新」能识别到新版本（客户端版本与后端版本都应显示本次版本号）
- [ ] 下载进度与安装流程可走通（Windows 启动安装器；macOS 打开 dmg 引导拖拽）

---

## 版本号规范

采用 `主版本.次版本.修订号`（SemVer）：

| 变更类型 | 版本号 | 示例 |
|---------|--------|------|
| Bug 修复 | 修订号 +1 | 1.0.5 → 1.0.6 |
| 新功能（向后兼容） | 次版本 +1 | 1.0.5 → 1.1.0 |
| 破坏性变更 | 主版本 +1 | 1.0.5 → 2.0.0 |

---

## 常见打包问题

| 问题 | 解决 |
|------|------|
| `jlink` 报 module not found | 检查 `JAVA_HOME` 指向 JDK（非 JRE），版本 ≥ 17 |
| Electron 下载超时 | 设置镜像：`set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/` |
| nsis-resources 下载失败 | 手动下载放到 `%LOCALAPPDATA%\electron-builder\Cache\nsis-resources\` |
| macOS 打包提示无权限 | 开启开发者模式：`设置 → 隐私与安全 → 开发者选项` |
| Windows 打包缺少图标 | 确保 `electron/icon.ico` 存在（256x256 多尺寸 ico） |
