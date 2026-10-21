> 🌐 中文版：[🇨🇳 RELEASE_CHECKLIST](./RELEASE_CHECKLIST.md)
# Build & Release Checklist

> Complete release workflow from code to users. Applicable version: v1.0.5+

---

## Pre-Release Checks

> 🚀 **Recommended**: Use one-click build scripts to automatically complete Steps 1-3:
> - Windows: `scripts\build.bat v3.0.7`
> - Mac/Linux: `./scripts/build.sh v3.0.7`
>
> Below are manual steps for scenarios requiring fine-grained control.

- [ ] `<version>` in `pom.xml` updated to target version
- [ ] `version` in `electron/package.json` synced, and `build.extraResources` points to `../target/code-craft-{version}.jar`
- [ ] `frontend-v3/package.json` (v3 frontend) and `frontend/package.json` (classic) versions match the backend
- [ ] `CodeCraftMcpServer.SERVER_VERSION` (MCP handshake version) synced
- [ ] `app.version` in `application.yml` stays as the placeholder `@project.version@` (**never hardcode a number**, otherwise the Version & Updates page drifts from the actual jar version)
- [ ] `CHANGELOG.md` updated
- [ ] All code committed, `git status` shows no uncommitted changes
- [ ] API Key and other sensitive info not hardcoded
- [ ] `application-local.yml` not committed (already in .gitignore)

---

## Post-Release Verification (Version & Updates)

- [ ] Open `http://localhost:8084/v3/#/about` (or the desktop app's Settings → Version & Updates) and confirm **both** client version and backend version equal this release
- [ ] `GET /api/system/version` (with token) returns the same `version` as `pom.xml`, and `buildTime` is not the literal `@maven.build.timestamp@`
- [ ] Desktop app: "Check for updates" resolves the latest GitHub release (with notes and installer size)
- [ ] In a browser, `/about` shows the read-only version card plus the "download manually from GitHub Releases" hint, **with no console errors**

---

## Step 1: Build Backend JAR

```bash
# Full build (includes frontend compilation)
mvn clean package -DskipTests

# Output: target/code-craft-{version}.jar (~67MB)
```

---

## Step 2: Trim Bundled JRE (first time or JDK version change only)

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

Verify: `ls jre/bin/java` (macOS) or `dir jre\bin\java.exe` (Windows) should exist.

---

## Step 3: Platform Packaging

```bash
cd electron
npm install

# Windows → electron/release/CodeCraft-Setup-{version}.exe
npm run dist:win

# macOS → electron/release/CodeCraft-{version}.dmg (must run on macOS)
npm run dist:mac

# Linux → electron/release/CodeCraft-{version}.AppImage + .deb
npm run dist:linux
```

---

## Step 4: Artifact Verification

```bash
ls -lh electron/release/

# Expected artifacts:
# Windows: CodeCraft-Setup-{version}.exe  (~120MB+)
# macOS:   CodeCraft-{version}.dmg        (~130MB+)
# Linux:   CodeCraft-{version}.AppImage   (~130MB+)
```

- [ ] EXE/DMG/AppImage file exists and > 100MB
- [ ] Windows: Install and test in clean VM
- [ ] macOS: DMG mounts correctly, runs after dragging to Applications
- [ ] First-launch API Key configuration flow works
- [ ] Send a simple message to verify AI functionality

---

## Step 5: Create Git Tag

```bash
git tag -a v{version} -m "Release v{version}"
git push origin v{version}
```

---

## Step 6: Create GitHub Release

1. Visit `https://github.com/zb614433612/CodeCraft/releases/new`
2. Tag: Select the just-pushed `v{version}`
3. Title: `CodeCraft v{version}`
4. Description: Paste the corresponding version content from CHANGELOG.md
5. Upload artifacts:
   - `CodeCraft-Setup-{version}.exe`
   - `CodeCraft-{version}.dmg` (if available)
   - `CodeCraft-{version}.AppImage` (if available)
6. **Auto-update requirements (v2.0.3+, mandatory)**:
   - The Release must be a **published release** (not a draft and not a pre-release) — clients only read `/releases/latest`;
   - Assets must be attached to that Release (clients download via `assets[].browser_download_url`; files merely committed to the repo do not work);
   - Asset names must be matched by `electron/update-utils.js`: a `Setup` `.exe` on Windows, `.dmg` on macOS, `.AppImage` / `.deb` on Linux, all containing the version;
   - `tag_name` should be `vX.Y.Z` (the client normalizes the `v` prefix and supports prerelease comparison);
   - Clients older than v2.0.3 must upgrade manually once before auto-update takes effect.
7. Click Publish Release

---

## Step 7: Post-Release Verification

- [ ] Release page accessible
- [ ] Platform artifacts downloadable
- [ ] Downloaded EXE installs and runs
- [ ] Download links in `README.md` point to latest Release
- [ ] "Settings → Version & Updates" (`/about`) → "Check for updates" detects the new version (both client and backend versions should show this release)
- [ ] Download progress and installation flow work (Windows launches the installer; macOS opens the dmg for drag-and-drop)

---

## Version Number Convention

SemVer: `major.minor.patch`

| Change Type | Version | Example |
|------------|---------|---------|
| Bug fix | Patch +1 | 1.0.5 → 1.0.6 |
| New feature (backward compatible) | Minor +1 | 1.0.5 → 1.1.0 |
| Breaking change | Major +1 | 1.0.5 → 2.0.0 |

---

## Common Packaging Issues

| Issue | Solution |
|-------|----------|
| `jlink` reports module not found | Check JAVA_HOME points to JDK (not JRE), version ≥ 17 |
| Electron download timeout | Set mirror: `set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/` |
| nsis-resources download failed | Manually download to `%LOCALAPPDATA%\electron-builder\Cache\nsis-resources\` |
| macOS packaging permission denied | Enable developer mode: Settings → Privacy & Security → Developer Options |
| Windows packaging missing icon | Ensure `electron/icon.ico` exists (256x256 multi-size ico) |
