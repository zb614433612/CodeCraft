> 🌐 中文版：[🇨🇳 SNAPSHOT_SYSTEM](./SNAPSHOT_SYSTEM.md)
# Snapshot System Deep Dive: Code Backup, Rollback & Diff Statistics

> Version: v1.0.5 | Updated: 2026-05-28 | Audience: Developers / AI Collaborators
> This document dissects the storage layout, core flows, quota management, and data structures of SnapshotService.

---

## 1. One-Sentence Definition

The snapshot system automatically backs up original file content before each AI modification, supports three granularities of rollback (message-level / file-level / session-level), and provides LCS algorithm-based diff statistics with 500MB quota auto-cleanup.

---

## 2. Storage Layout

```
{workspace}/
└── snapshots/                              ← Snapshot root directory
    ├── .snapshots_index.json               ← Global index file
    ├── 20260516-191933-17789303/            ← Single snapshot directory (timestamp-UUID)
    │   ├── snapshot.json                   ← Snapshot metadata
    │   └── files/                          ← Backup file directory
    │       └── src/main/java/.../App.java   ← Same structure as project directory
    ├── 20260516-192141-17789304/
    │   ├── snapshot.json
    │   └── files/
    └── ... (50+ snapshot directories)
```

### 2.1 Global Index: `.snapshots_index.json`

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

### 2.2 Snapshot Metadata: `snapshot.json`

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

## 3. Core Data Structures

```
SnapshotService internal class hierarchy:

SnapshotIndex           ← Global index (loaded from .snapshots_index.json)
  ├─ totalSize: long
  └─ snapshots: List<SnapshotEntry>
       ├─ snapshotId, turnId, sessionId
       ├─ timestamp, totalSize, fileCount
       └─ rolledBack: boolean

SnapshotMetadata        ← Single snapshot full info (loaded from snapshot.json)
  ├─ snapshotId, turnId, sessionId
  ├─ timestamp, projectRoot
  ├─ files: List<FileEntry>
  └─ rolledBack: boolean

FileEntry               ← Single file snapshot record
  ├─ relativePath       ← Path relative to project root
  ├─ originalSize       ← File size at backup time
  ├─ linesAdded / linesDeleted  ← LCS diff calculation results
  ├─ wasNewFile         ← AI was about to create a new file (didn't exist at backup)
  └─ rolledBack         ← Whether this file has been individually rolled back

SnapshotSummary         ← Lightweight summary for external return
RollbackPreview         ← Preview info before rollback
SessionChanges          ← Session-level change statistics aggregation
SessionFileChange       ← Single file change statistics
```

---

## 4. Core Flows

### 4.1 Snapshot Creation (createSnapshot)

```
Call timing: Before ToolExecutor executes write_file / edit_file / delete_file

Input params:
  @param turnId       User message turnId (frontend-generated, shared by same message round)
  @param sessionId    Session ID
  @param absoluteFilePath  Absolute path of file to modify

Flow:
  ① Validate params (turnId / sessionId / absoluteFilePath non-empty)
  
  ② Calculate relative path
     └─ projectRoot.relativize(absolutePath)
     └─ File not under project directory → skip, return null
  
  ③ Get or create snapshotId
     └─ turnIdToSnapshotId mapping (ConcurrentHashMap)
     └─ Same turnId returns same snapshotId
     └─ Not exists → generate new ID: {timestamp}-{UUID first 8 chars}
  
  ④ Load/create SnapshotMetadata
     └─ First creation → write timestamp + projectRoot
     └─ Already exists → append file record
  
  ⑤ Same file only backed up once
     └─ relativePath already in metadata.files → skip
  
  ⑥ Backup file content
     ├─ File exists → Files.copy() to files/ directory (maintaining relative path structure)
     ├─ File doesn't exist → mark wasNewFile=true (AI is about to create)
     └─ Record originalSize
  
  ⑦ Persist
     ├─ Write snapshot.json (metadata)
     ├─ Update .snapshots_index.json (index)
     └─ Check quota → trigger cleanup if exceeded
  
  ⑧ Return SnapshotSummary
```

### 4.2 Rollback Flow

```
Three rollback granularities (exposed via SnapshotController):

┌──────────────┬──────────────────────┬─────────────────────────┐
│ Granularity  │ API                  │ Behavior                │
├──────────────┼──────────────────────┼─────────────────────────┤
│ By Message   │ rollbackByTurnId     │ Restore all files of    │
│              │                      │ that turn               │
│ By File      │ rollbackFile         │ Restore single file     │
│ By Session   │ rollbackBySession    │ Restore all snapshots   │
│              │                      │ of entire session       │
└──────────────┴──────────────────────┴─────────────────────────┘

rollback(String snapshotId) flow:
  ① Read SnapshotMetadata
  
  ② Iterate files[]:
     ├─ wasNewFile=true → delete current file (AI-created new file)
     └─ wasNewFile=false → backup from files/ → overwrite back to project directory
  
  ③ Mark rolledBack=true
     ├─ SnapshotMetadata.rolledBack = true
     └─ Index SnapshotEntry.rolledBack = true
  
  ④ Persist updated metadata and index

Key design:
  - Snapshot records projectRoot at creation time, used for rollback
  - Supports cross-drive rollback (project may be on different drive)
```

### 4.3 Diff Statistics (computeDiffStats)

```
Call timing: After ToolExecutor completes write_file / edit_file

computeDiffStats(turnId, absoluteFilePath):
  ① Find corresponding snapshot (via turnIdToSnapshotId)
  ② Read backup file content (original version)
  ③ Read current file content (modified version)
  ④ LCS algorithm computes added/deleted line counts
     ├─ File ≤ 10000 lines → exact LCS algorithm
     └─ File > 10000 lines → approximate algorithm (block comparison)
  ⑤ Update FileEntry.linesAdded / linesDeleted
  ⑥ Persist snapshot.json
```

### 4.4 Session Change Aggregation (getSessionChanges)

```
getSessionChanges(sessionId):
  ① Filter all snapshots for this session from index
  ② Read each snapshot's metadata
  ③ Aggregate by file:
     ├─ Same file modified multiple times → merge linesAdded/linesDeleted
     └─ wasNewFile → mark as newly created file
  ④ Return SessionChanges:
     ├─ totalFiles, totalLinesAdded, totalLinesDeleted
     └─ files: List<SessionFileChange> (per-file change details)
```

---

## 5. Quota Management

### 5.1 Trigger Mechanism

```
After each snapshot creation → check total size
  └─ totalSize > MAX_SNAPSHOT_SIZE (500 MB)
      └─ Trigger cleanup → cleanupOldestSnapshots()
```

### 5.2 Cleanup Strategy

```
cleanupOldestSnapshots():
  ① Get all snapshots from index, sort by timestamp (oldest first)
  ② Delete oldest snapshots one by one
     ├─ Delete files/ directory (recursive)
     ├─ Delete snapshot.json
     └─ Remove from index
  ③ Until totalSize ≤ TARGET_SNAPSHOT_SIZE (300 MB)
  ④ Persist updated index

Characteristics:
  - Only clean non-rolled-back snapshots (rolledBack=false)
  - Preserve rolled-back snapshots (users may need to inspect)
  - FIFO: oldest deleted first
```

---

## 6. Concurrency Safety

```
turnIdToSnapshotId: ConcurrentHashMap<String, String>
  └─ Multiple tools executing concurrently within same turn → mapping guarantees same snapshotId

createSnapshot(): synchronized
  └─ Prevent same file from being concurrently backed up twice

rollback(): synchronized
  └─ Prevent concurrent rollback causing inconsistent file state
```

---

## 7. Lifecycle Overview

```
User sends one message (one turn)
    ↓
    ├─ AI requests write_file("A.java")
    │   ├─ ToolExecutor → createSnapshot(turnId, sessionId, "A.java")
    │   │   └─ Backup A.java original → snapshot.json persisted
    │   ├─ Execute write_file
    │   └─ ToolExecutor → computeDiffStats(turnId, "A.java")
    │       └─ LCS computes added/deleted lines → update FileEntry
    │
    ├─ AI requests edit_file("A.java")  ← Same file modified again
    │   ├─ ToolExecutor → createSnapshot(turnId, sessionId, "A.java")
    │   │   └─ Detected already backed up → skip (same turn, same file, once only)
    │   ├─ Execute edit_file
    │   └─ ToolExecutor → computeDiffStats(turnId, "A.java")
    │       └─ Recalculate diff (original vs latest)
    │
    └─ AI task complete
        └─ Quota check → exceeded → clean oldest snapshots

User unsatisfied → Rollback
    ├─ By turn → restore A.java to initial state
    ├─ By file → restore only A.java
    └─ By session → restore all changes of entire session
```

---

## 8. Collaboration Interface with ToolExecutor

```
ToolExecutor.executeSingleToolCall()
    ↓
    ├─ [Before execution] If file modification tool (write/edit/delete)
    │   └─ snapshotService.createSnapshot(turnId, sessionId, filePath)
    │       └─ Same turn same file: only backup state before first modification
    │
    ├─ [Permission pipeline] ToolExecutionPipeline.execute()
    │
    ├─ [Execute] tool.execute(arguments)
    │
    └─ [After execution] If file modification tool
        └─ snapshotService.computeDiffStats(turnId, filePath)
            └─ LCS compare original vs current
```

---

## 9. Known Issues & Recommendations

| Issue | Description | Recommendation |
|-------|-------------|---------------|
| snapshots/ directory bloat | 150+ snapshot directories, 2-3 files each | Periodic auto-clean or manual cleanup button |
| Large file LCS performance | 10000+ line files use approximate, may be inaccurate | Consider Myers Diff algorithm optimization |
| Index file may corrupt | Manual editing of .snapshots_index.json causes inconsistencies | Validate index vs actual directory on startup |
| wasNewFile rollback risk | If user manually edits after AI creates file, rollback deletes user changes | Double-confirm before rollback |
| Silent quota cleanup | No notification, user unaware of deleted snapshots | Log on cleanup, frontend notification |

---

## 10. Key Constants

| Constant | Value | Description |
|----------|-------|-------------|
| MAX_SNAPSHOT_SIZE | 500 MB | Cleanup trigger threshold |
| TARGET_SNAPSHOT_SIZE | 300 MB | Post-cleanup target size |
| SNAPSHOTS_DIR | `snapshots` | Snapshot root directory name |
| INDEX_FILE | `.snapshots_index.json` | Global index filename |
| METADATA_FILE | `snapshot.json` | Snapshot metadata filename |
| FILES_DIR | `files` | Backup files subdirectory name |
| LCS Exact Algorithm Limit | 10000 lines | Switch to approximate above this |

---

> 📌 **Doc Maintenance Convention**: When snapshot storage format changes or new rollback granularities are added, please sync this document.
