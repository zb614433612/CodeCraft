> 🌐 中文版：[🇨🇳 BUILD_AND_RUN](./BUILD_AND_RUN.md)
# Build & Run Guide (Lazy Channel)

> Applicable version: v1.1.2+

## TL;DR

### One-click Build

```bash
# Windows
scripts\build.bat v1.1.2

# Mac / Linux
./scripts/build.sh v1.1.2
```

### One-click Run

```bash
mvn spring-boot:run
# Visit: http://localhost:8084
```

---

## 1. Environment Requirements

| Item | Requirement | Check Command |
|------|------------|---------------|
| **Java** | JDK 17+ | `java -version` |
| **Maven** | 3.6+ | `mvn -version` |
| **Node.js** | 18+ | `node -v` |
| **npm** | 9+ | `npm -v` |
| **Git** | 2.30+ (optional) | `git --version` |

---

## 2. Local Development (IDE + Hot Reload)

### 2.1 Configuration

```bash
# 1. Copy local config template
cp application-local.yml.example application-local.yml

# 2. Edit application-local.yml and fill in your API Key
# deepseek:
#   api-key: sk-your-key-here
```

This file is in `.gitignore` and will not be committed.

### 2.2 Start Backend

```bash
# Hot start (profile=local)
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=local"

# Or use IDE to run main class:
# com.example.agentdeepseek.AgentDeepseekApplication
```

### 2.3 Start Frontend

```bash
cd frontend
npm install     # First time only
npm run dev     # Hot reload at http://localhost:5173
```

Frontend dev server proxies API requests to `http://localhost:8084`.

### 2.4 Start Electron (Optional)

```bash
cd electron
npm install     # First time only
npm run dev     # Electron + frontend hot reload
```

---

## 3. Build & Package

### 3.1 Backend JAR

```bash
# Build (skip front-end compilation)
mvn clean package -DskipTests -DskipFrontend=true

# Build (full, includes front-end compilation)
mvn clean package -DskipTests

# Output: target/codecraft-{version}.jar (~67MB)
```

### 3.2 Electron Desktop App

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

## 4. Key Information Quick Reference

| Item | Value |
|------|-------|
| **Web Port** | `8084` |
| **P2P Port** | `9527` |
| **H2 Console** | `http://localhost:8084/h2-console` |
| **H2 JDBC URL** | `jdbc:h2:file:./data/codecraft` |
| **Default Account** | `admin` / `123456` |
| **Swagger UI** | `http://localhost:8084/swagger-ui.html` |
| **Frontend Hot Reload Port** | `5173` (proxies API to 8084) |
| **Log File** | `logs/app.log` |

---

## 5. Project Structure

```
project root
├── src/main/java/...        → Backend source (102 Java files)
│   ├── tool/impl/           → 19 AI tool implementations
│   ├── p2p/                 → P2P remote collaboration
│   └── service/impl/        → Core business logic
├── src/main/resources/
│   ├── application.yml      → Base configuration
│   ├── application-local.yml.example → Local config template
│   └── schema.sql           → Database schema (H2 DDL)
├── frontend/src/            → Vue 3 frontend source
│   ├── views/               → 10 page views
│   ├── components/          → 13 common components
│   └── api/                 → 17 API call modules
├── electron/                → Electron desktop shell
├── data/                    → H2 database files (runtime)
├── logs/                    → Log files (runtime)
├── snapshots/               → Code snapshots (runtime)
├── docs/                    → Project documentation
└── scripts/                 → Build scripts
```

---

## 6. Common Commands

```bash
# ===== Backend =====
mvn spring-boot:run                                    # Start backend
mvn clean package -DskipTests                          # Build JAR
mvn clean package -DskipTests -DskipFrontend=true      # Build JAR (skip frontend)
mvn test                                               # Run tests

# ===== Frontend =====
cd frontend && npm run dev                             # Hot reload dev
cd frontend && npm run build                           # Production build

# ===== Electron =====
cd electron && npm start                               # Desktop app (production)
cd electron && npm run dev                             # Desktop app (dev)

# ===== Tools =====
# Check port
netstat -ano | findstr 8084           # Windows
lsof -i :8084                         # macOS/Linux

# Check Java processes
jps -l | grep codecraft
```

---

## 7. Configuration Hierarchy

```
application-local.yml  >  application.yml  >  Code defaults
    (highest)              (base config)       (lowest)
```

---

## 8. Database

### H2 Console

Browser open: `http://localhost:8084/h2-console`

Connection parameters:
```
JDBC URL:  jdbc:h2:file:./data/codecraft
Username:  sa
Password:  (empty)
```

### Common Queries

```sql
SELECT * FROM conversation ORDER BY updated_at DESC LIMIT 10;
SELECT * FROM agent_config WHERE enabled = 1;
SELECT * FROM skill WHERE confidence > 0.5;
SELECT status, COUNT(*) FROM sub_agent_log GROUP BY status;
```

---

## 9. Build Script Details

### `scripts/build.bat` / `scripts/build.sh`

Automated build scripts handle the following steps:

1. **Sync version numbers** — Update version in `pom.xml`, `electron/package.json`, and frontend `package.json`
2. **Build backend JAR** — `mvn clean package -DskipTests`
3. **Trim JRE** — Use `jlink` to trim a minimal JRE (only first time or after JDK version change)
4. **Package Electron app** — `npm run dist:win` / `dist:mac` / `dist:linux`
5. **Output artifacts** to the `electron/release/` directory

---

## 10. Troubleshooting

| Symptom | Possible Cause | Solution |
|---------|---------------|----------|
| `mvn` not found | Maven not installed or not in PATH | Install Maven 3.6+, add to PATH |
| Port 8084 already in use | Another instance running | `netstat -ano | findstr 8084` then kill the process |
| H2 "Database not found" | First run needs init | Start the app once to auto-create DB |
| Frontend can't connect to API | CORS or proxy misconfig | Check `frontend/vite.config.ts` proxy settings |
| `jlink` fails module not found | JAVA_HOME points to JRE not JDK | Point JAVA_HOME to JDK 17+ |
| Electron download timeout | Network issue in China | Set mirror: `set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/` |
| nsis-resources download failed | Network issue | Manually download to `%LOCALAPPDATA%\electron-builder\Cache\nsis-resources\`|
| macOS build permission denied | Code signing / Gatekeeper | System Settings → Privacy & Security → Developer Options |
| Frontend blank page after update | Old HTTP cache | Clear Electron cache, delete `%APPDATA%\CodeCraft\Cache` |
