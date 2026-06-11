> 🌐 English Version：[🇬🇧 SNAPSHOT_SYSTEM_EN](./SNAPSHOT_SYSTEM_EN.md)
# 快照系统深描：代码备份、回滚与 Diff 统计

> 版本：v1.0.5 | 更新：2026-05-28 | 受众：开发者 / AI 协作伙伴
> 本文档解剖 SnapshotService 的存储布局、核心流程、配额管理和数据结构。

---

## 一、一句话定义

快照系统在 AI 每次修改文件之前自动备份原始内容，支持三种粒度的回滚（消息级 / 文件级 / 会话级），并提供 LCS 算法的 diff 统计和 500MB 配额自动清理。

---

## 二、存储布局

```
{工作目录}/
└── snapshots/                              ← 快照根目录
    ├── .snapshots_index.json               ← 全局索引文件
    ├── 20260516-191933-17789303/            ← 单个快照目录（时间戳-UUID）
    │   ├── snapshot.json                   ← 快照元数据
    │   └── files/                          ← 备份文件目录
    │       └── src/main/java/.../App.java   ← 与项目目录结构一致
    ├── 20260516-192141-17789304/
    │   ├── snapshot.json
    │   └── files/
    └── ...（50+ 个快照目录）
```

### 2.1 全局索引：`.snapshots_index.json`

```json
{
  "totalSize": 157286400,
  "snapshots": [
    {
      "snapshotId": "20260516-191933-17789303",
      "turnId": "turn-abc123",
      "sessionId": 42,
      "timestamp": "2026-05-16T19:19:33",
      "totalSize": 2048000,
      "fileCount": 3,
      "rolledBack": false
    }
  ]
}
```

### 2.2 快照元数据：`snapshot.json`

```json
{
  "snapshotId": "20260516-191933-17789303",
  "turnId": "turn-abc123",
  "sessionId": 42,
  "timestamp": "2026-05-16T19:19:33",
  "projectRoot": "E:\\my-project",
  "files": [
    {
      "relativePath": "src/main/java/com/example/App.java",
      "originalSize": 4096,
      "linesAdded": 15,
      "linesDeleted": 3,
      "wasNewFile": false,
      "rolledBack": false
    }
  ],
  "rolledBack": false
}
```

---

## 三、核心数据结构

```
SnapshotService 内部类层次：

SnapshotIndex           ← 全局索引（从 .snapshots_index.json 加载）
  ├─ totalSize: long
  └─ snapshots: List<SnapshotEntry>
       ├─ snapshotId, turnId, sessionId
       ├─ timestamp, totalSize, fileCount
       └─ rolledBack: boolean

SnapshotMetadata        ← 单个快照完整信息（从 snapshot.json 加载）
  ├─ snapshotId, turnId, sessionId
  ├─ timestamp, projectRoot
  ├─ files: List<FileEntry>
  └─ rolledBack: boolean

FileEntry               ← 单个文件的快照记录
  ├─ relativePath       ← 相对于项目根目录的路径
  ├─ originalSize       ← 备份时的文件大小
  ├─ linesAdded / linesDeleted  ← LCS diff 计算结果
  ├─ wasNewFile         ← AI 即将创建的新文件（备份时尚不存在）
  └─ rolledBack         ← 该文件是否已单独回滚

SnapshotSummary         ← 对外返回的轻量摘要
RollbackPreview         ← 回滚前的预览信息
SessionChanges          ← 会话级别的改动统计聚合
SessionFileChange       ← 单个文件的改动统计
```

---

## 四、核心流程

### 4.1 快照创建（createSnapshot）

```
调用时机：ToolExecutor 在执行 write_file / edit_file / delete_file 之前

输入参数：
  @param turnId       用户消息 turnId（前端生成，同一轮消息共享）
  @param sessionId    会话 ID
  @param absoluteFilePath  要修改文件的绝对路径

流程：
  ① 验证参数（turnId / sessionId / absoluteFilePath 非空）
  
  ② 计算相对路径
     └─ projectRoot.relativize(absolutePath)
     └─ 文件不在项目目录下 → 跳过，返回 null
  
  ③ 获取或创建 snapshotId
     └─ turnIdToSnapshotId 映射（ConcurrentHashMap）
     └─ 同一 turnId 返回同一个 snapshotId
     └─ 不存在则生成新 ID：{时间戳}-{UUID前8位}
  
  ④ 加载/创建 SnapshotMetadata
     └─ 首次创建 → 写入 timestamp + projectRoot
     └─ 已存在 → 追加文件记录
  
  ⑤ 同一文件只备份一次
     └─ metadata.files 中已存在该 relativePath → 跳过
  
  ⑥ 备份文件内容
     ├─ 文件已存在 → Files.copy() 到 files/ 目录（保持相对路径结构）
     ├─ 文件不存在 → 标记 wasNewFile=true（AI 即将创建）
     └─ 记录 originalSize
  
  ⑦ 持久化
     ├─ 写入 snapshot.json（元数据）
     ├─ 更新 .snapshots_index.json（索引）
     └─ 检查配额 → 超限则触发清理
  
  ⑧ 返回 SnapshotSummary
```

### 4.2 回滚流程（rollback）

```
三种回滚粒度（通过 SnapshotController 暴露）：

┌──────────────┬──────────────────────┬─────────────────────────┐
│ 粒度         │ API                  │ 行为                    │
├──────────────┼──────────────────────┼─────────────────────────┤
│ 按消息回滚   │ rollbackByTurnId     │ 恢复该 turn 所有文件    │
│ 按文件回滚   │ rollbackFile         │ 只恢复单个文件          │
│ 按会话回滚   │ rollbackBySession    │ 恢复整个会话所有快照    │
└──────────────┴──────────────────────┴─────────────────────────┘

rollback(String snapshotId) 流程：
  ① 读取 SnapshotMetadata
  
  ② 遍历 files[]：
     ├─ wasNewFile=true → 删除当前文件（AI 创建的新文件）
     └─ wasNewFile=false → files/ 中备份 → 覆盖回项目目录
  
  ③ 标记 rolledBack=true
     ├─ SnapshotMetadata.rolledBack = true
     └─ 索引中对应 SnapshotEntry.rolledBack = true
  
  ④ 持久化更新后的元数据和索引

关键设计：
  - 快照创建时记录了 projectRoot，回滚时使用当时的根路径
  - 支持跨盘符回滚（项目可能位于不同盘符）
```

### 4.3 Diff 统计（computeDiffStats）

```
调用时机：ToolExecutor 在 write_file / edit_file 执行完成后

computeDiffStats(turnId, absoluteFilePath)：
  ① 找到对应的快照（通过 turnIdToSnapshotId）
  ② 读取备份文件内容（原始版本）
  ③ 读取当前文件内容（修改后版本）
  ④ LCS 算法计算增删行数
     ├─ 文件 ≤ 10000 行 → 精确 LCS 算法
     └─ 文件 > 10000 行 → 近似算法（分块比较）
  ⑤ 更新 FileEntry.linesAdded / linesDeleted
  ⑥ 持久化 snapshot.json
```

### 4.4 会话变更聚合（getSessionChanges）

```
getSessionChanges(sessionId)：
  ① 从索引中筛选该会话的所有快照
  ② 读取每个快照的元数据
  ③ 按文件聚合：
     ├─ 同一文件多次修改 → 合并 linesAdded/linesDeleted
     └─ wasNewFile → 标记为新增文件
  ④ 返回 SessionChanges：
     ├─ totalFiles, totalLinesAdded, totalLinesDeleted
     └─ files: List<SessionFileChange>（每个文件的改动详情）
```

---

## 五、配额管理

### 5.1 触发机制

```
每次创建快照后 → 检查总大小
  └─ totalSize > MAX_SNAPSHOT_SIZE (500 MB)
      └─ 触发清理 → cleanupOldestSnapshots()
```

### 5.2 清理策略

```
cleanupOldestSnapshots()：
  ① 从索引中获取所有快照，按时间戳排序（最旧的在前）
  ② 逐个删除最旧的快照
     ├─ 删除 files/ 目录（递归）
     ├─ 删除 snapshot.json
     └─ 从索引中移除
  ③ 直到 totalSize ≤ TARGET_SNAPSHOT_SIZE (300 MB)
  ④ 持久化更新后的索引

特点：
  - 只清理未回滚的快照（rolledBack=false）
  - 保留已回滚的快照（用户可能需要检查）
  - 先入先出（FIFO），最旧的先删
```

---

## 六、并发安全

```
turnIdToSnapshotId: ConcurrentHashMap<String, String>
  └─ 同一 turn 内多个工具并发执行 → 映射保证同一 snapshotId

createSnapshot(): synchronized
  └─ 防止同一文件被并发备份两次

rollback(): synchronized
  └─ 防止并发回滚导致文件状态不一致
```

---

## 七、生命周期总览

```
用户发送一次消息（一个 turn）
    ↓
    ├─ AI 请求 write_file("A.java")
    │   ├─ ToolExecutor → createSnapshot(turnId, sessionId, "A.java")
    │   │   └─ 备份 A.java 原始内容 → snapshot.json 持久化
    │   ├─ 执行 write_file
    │   └─ ToolExecutor → computeDiffStats(turnId, "A.java")
    │       └─ LCS 计算增删行数 → 更新 FileEntry
    │
    ├─ AI 请求 edit_file("A.java")  ← 同一文件再次修改
    │   ├─ ToolExecutor → createSnapshot(turnId, sessionId, "A.java")
    │   │   └─ 检测到已备份过 → 跳过（同一 turn 同一文件只备份一次）
    │   ├─ 执行 edit_file
    │   └─ ToolExecutor → computeDiffStats(turnId, "A.java")
    │       └─ 重新计算 diff（对比原始版本 vs 最新版本）
    │
    └─ AI 任务完成
        └─ 配额检查 → 超限则清理最旧快照

用户不满意 → 回滚
    ├─ 按 turn 回滚 → 恢复 A.java 到初始状态
    ├─ 按文件回滚 → 只恢复 A.java
    └─ 按会话回滚 → 恢复整个会话的所有改动
```

---

## 八、与 ToolExecutor 的协作接口

```
ToolExecutor.executeSingleToolCall()
    ↓
    ├─ [执行前] 如果是文件修改工具（write/edit/delete）
    │   └─ snapshotService.createSnapshot(turnId, sessionId, filePath)
    │       └─ 同一 turn 同一文件只备份首次修改前的状态
    │
    ├─ [权限管道] ToolExecutionPipeline.execute()
    │
    ├─ [执行] tool.execute(arguments)
    │
    └─ [执行后] 如果是文件修改工具
        └─ snapshotService.computeDiffStats(turnId, filePath)
            └─ LCS 对比原始版本 vs 当前版本
```

---

## 九、已知问题与建议

| 问题 | 说明 | 建议 |
|------|------|------|
| snapshots/ 目录膨胀 | 150+ 快照目录，每个目录 2~3 个文件 | 定期自动清理或提供手动清理按钮 |
| 超大文件 LCS 性能 | 10000+ 行文件用近似算法，可能不精确 | 考虑使用 Myers Diff 算法优化 |
| 索引文件可能损坏 | .snapshots_index.json 手动编辑会导致不一致 | 启动时校验索引与实际目录的一致性 |
| wasNewFile 回滚风险 | 如果用户在 AI 创建文件后又手动编辑，回滚会删除用户的修改 | 回滚前做二次确认，提示文件已被 AI 独占 |
| 配额清理无通知 | 静默清理，用户不知道哪些快照被删 | 清理时记录日志，前端展示清理通知 |

---

## 十、关键常量

| 常量 | 值 | 说明 |
|------|----|------|
| MAX_SNAPSHOT_SIZE | 500 MB | 触发清理的阈值 |
| TARGET_SNAPSHOT_SIZE | 300 MB | 清理后的目标大小 |
| SNAPSHOTS_DIR | `snapshots` | 快照根目录名 |
| INDEX_FILE | `.snapshots_index.json` | 全局索引文件名 |
| METADATA_FILE | `snapshot.json` | 快照元数据文件名 |
| FILES_DIR | `files` | 备份文件子目录名 |
| LCS 精确算法上限 | 10000 行 | 超过此行数切换到近似算法 |

---

> 📌 **文档维护约定**: 快照存储格式变更或新增回滚粒度时，请同步更新本文档。
