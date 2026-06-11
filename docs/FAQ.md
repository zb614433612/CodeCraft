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

或者改端口：`SERVER_PORT=8085 mvn spring-boot:run`

---

### Q2：启动后页面能打开但 AI 不回复

**原因**：API Key 未配置或无效。

**检查**：
1. 访问 `http://localhost:8084` → 左侧「配置」→ 检查 API Key
2. 或检查 `application-local.yml` 中 `deepseek.api-key` 是否正确
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

**原因**：触发了死循环检测阈值（连续 4 轮相同工具调用）。

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

> 💡 找不到答案？查看日志 `logs/app.log` 中的错误信息，或在 GitHub Issues 中搜索/提问。
