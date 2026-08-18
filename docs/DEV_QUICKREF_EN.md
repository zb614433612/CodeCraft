> 🌐 中文版：[🇨🇳 DEV_QUICKREF](./DEV_QUICKREF.md)
# Local Development Quick Reference

> For developers (including AI collaborators). See [BUILD_AND_RUN.md](../BUILD_AND_RUN.md) for detailed steps.

---

## One-Click Start

```bash
# Simplest start (first time requires build)
mvn clean package -DskipTests && mvn spring-boot:run
```

After startup visit: **http://localhost:8084**

---

## One-Click Build

```bash
# Windows
scripts\build.bat v1.1.7

# Mac / Linux
./scripts/build.sh v1.1.7
```

---

## Key Information

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

## Common Commands

```bash
# ===== Backend =====
mvn spring-boot:run                                    # Start backend
mvn clean package -DskipTests                          # Build JAR
mvn clean package -DskipTests -DskipFrontend=true      # Build JAR (skip frontend)

# ===== Frontend =====
cd frontend && npm run dev                             # Frontend hot reload dev

# ===== Electron =====
cd electron && npm start                               # Desktop app (production)
cd electron && npm run dev                             # Desktop app (dev)

# ===== Utils =====
# Find Java processes
jps -l | grep codecraft
# Windows: netstat -ano | findstr 8084
# Mac/Linux: lsof -i :8084
```

---

## Configuration Priority

```
application-local.yml  >  application.yml  >  Code defaults
    (highest)              (base config)       (lowest)
```

For local overrides (proxy/datasource etc.), copy the template:
```bash
cp application-local.yml.example application-local.yml
# Edit overrides as needed (API Key is NOT here, see "LLM Provider" below)
```

This file is in `.gitignore` and will not be committed.

> 💡 **LLM Provider / API Key**: configured via Web UI「Config → Provider Config」(stored in `llm_provider` table), no longer read from env vars or config files.

---

## Database Operations

```bash
# H2 Console (open in browser)
http://localhost:8084/h2-console

# Connection parameters
JDBC URL:  jdbc:h2:file:./data/codecraft
Username:  sa
Password:  (empty)

# Common queries
SELECT * FROM conversation ORDER BY updated_at DESC LIMIT 10;
SELECT * FROM agent_config WHERE enabled = 1;
SELECT * FROM skill WHERE confidence > 0.5;
SELECT * FROM lesson WHERE status = 1 ORDER BY hit_count DESC;
SELECT status, COUNT(*) FROM lesson GROUP BY status;   -- growth dashboard
SELECT status, COUNT(*) FROM sub_agent_log GROUP BY status;
```

---

## Directory Quick Reference

```
project root
├── src/main/java/...        → Backend source (253 Java files)
│   ├── tool/impl/           -> 21 AI tool implementations (incl. lesson growth system)
│   ├── p2p/                 → P2P remote collaboration
│   └── service/lesson/      -> Growth system (lesson retrieval/record/review/normalizer)
│   └── service/impl/        → Core business logic
├── frontend/src/            → Vue 3 frontend source
│   ├── views/               → 14 page views
│   ├── components/          → 16 common components
│   └── api/                 → 21 API call modules
├── electron/                → Electron desktop shell
├── data/                    → H2 database files (runtime)
├── logs/                    → Log files (runtime)
├── snapshots/               → Code snapshots (runtime)
└── docs/                    → Project documentation
```

---

## Token Related

| Item | Value |
|------|-------|
| Token Expiry | 7200 seconds (2 hours) |
| Verification Code Expiry | 60 seconds |
| Token Estimation Coefficients | Chinese ~1.5 / English ~1.0 / Digits ~0.5 |
| Context Compaction Thresholds | WARN → COMPACT → DROP (three-tier progressive) |

---

## Quick Verification

```bash
# Liveness check (opening the home page confirms it)
curl http://localhost:8084/

# Get registered tool list (requires login token)
curl -H "Authorization: Bearer <token>" http://localhost:8084/api/tools

# View last 50 log lines
tail -50 logs/app.log
```

---

> 💡 More details in [BUILD_AND_RUN.md](../BUILD_AND_RUN.md) and [ARCHITECTURE.md](./ARCHITECTURE.md).
