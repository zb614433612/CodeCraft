> 🌐 English Version：[🇬🇧 DEV_QUICKREF_EN](./DEV_QUICKREF_EN.md)
# 本地开发速查表

> 给开发者（包括 AI 协作伙伴）的日常命令速查。详细步骤见 [BUILD_AND_RUN.md](../BUILD_AND_RUN.md)。

---

## 一键启动

```bash
# 最简启动（首次需要先构建）
mvn clean package -DskipTests && mvn spring-boot:run
```

启动后访问：**http://localhost:8084**

---

## 一键打包

```bash
# Windows
scripts\build.bat v1.1.0

# Mac / Linux
./scripts/build.sh v1.1.0
```

---

## 关键信息速查

| 项目 | 值 |
|------|-----|
| **Web 端口** | `8084` |
| **P2P 端口** | `9527` |
| **H2 控制台** | `http://localhost:8084/h2-console` |
| **H2 JDBC URL** | `jdbc:h2:file:./data/codecraft` |
| **默认账号** | `admin` / `123456` |
| **Swagger UI** | `http://localhost:8084/swagger-ui.html` |
| **前端热更新端口** | `5173`（代理 API 到 8084） |
| **日志文件** | `logs/app.log` |

---

## 常用命令

```bash
# ===== 后端 =====
mvn spring-boot:run                                    # 启动后端
mvn clean package -DskipTests                          # 构建 JAR
mvn clean package -DskipTests -DskipFrontend=true      # 跳过前端编译的构建

# ===== 前端 =====
cd frontend && npm run dev                             # 前端热更新开发

# ===== Electron =====
cd electron && npm start                               # 启动桌面应用（生产模式）
cd electron && npm run dev                             # 启动桌面应用（开发模式）

# ===== 工具 =====
# 查看 Java 进程（杀进程用）
jps -l | grep codecraft
# Windows: netstat -ano | findstr 8084
# Mac/Linux: lsof -i :8084
```

---

## 配置文件优先级

```
application-local.yml  >  application.yml  >  代码默认值
    (最高)                  (基础配置)          (最低)
```

本地开发时复制模板：
```bash
cp application-local.yml.example application-local.yml
# 编辑填入 deepseek.api-key
```

此文件已在 `.gitignore` 中，不会提交。

---

## 数据库操作

```bash
# H2 控制台（浏览器打开）
http://localhost:8084/h2-console

# 连接参数
JDBC URL:  jdbc:h2:file:./data/codecraft
用户名:    sa
密码:      (空)

# 常用查询
SELECT * FROM conversation ORDER BY updated_at DESC LIMIT 10;
SELECT * FROM agent_config WHERE enabled = 1;
SELECT * FROM skill WHERE confidence > 0.5;
SELECT status, COUNT(*) FROM sub_agent_log GROUP BY status;
```

---

## 目录速查

```
项目根目录
├── src/main/java/...        → 后端源码（102 个 Java 文件）
│   ├── tool/impl/           → 19 个 AI 工具实现
│   ├── p2p/                 → P2P 远程协作
│   └── service/impl/        → 核心业务逻辑
├── frontend/src/            → Vue3 前端源码
│   ├── views/               → 10 个页面视图
│   ├── components/          → 13 个通用组件
│   └── api/                 → 17 个 API 调用模块
├── electron/                → Electron 桌面壳
├── data/                    → H2 数据库文件（运行时）
├── logs/                    → 日志文件（运行时）
├── snapshots/               → 代码快照（运行时）
└── docs/                    → 项目文档
```

---

## Token 相关

| 项目 | 值 |
|------|-----|
| Token 过期 | 7200 秒（2 小时） |
| 验证码过期 | 60 秒 |
| Token 估算系数 | 中文 ~1.5 / 英文 ~1.0 / 数字 ~0.5 |
| 上下文压缩阈值 | WARN → COMPACT → DROP（三级渐进） |

---

## 快速验证

```bash
# 健康检查
curl http://localhost:8084/actuator/health

# 获取已注册工具列表（需登录后带 Token）
curl -H "Authorization: Bearer <token>" http://localhost:8084/api/tools

# 查看日志最近 50 行
tail -50 logs/app.log
```

---

> 💡 更多细节见 [BUILD_AND_RUN.md](../BUILD_AND_RUN.md) 和 [ARCHITECTURE.md](./ARCHITECTURE.md)。
