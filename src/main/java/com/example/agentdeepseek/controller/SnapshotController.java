package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.service.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 代码快照 REST 控制器
 * 提供快照列表、回滚预览、执行回滚功能
 */
@Slf4j
@RestController
@RequestMapping("/api/snapshots")
@Tag(name = "代码快照", description = "代码回滚快照管理接口")
public class SnapshotController {

    private final SnapshotService snapshotService;

    public SnapshotController(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping("/list")
    @Operation(summary = "获取会话快照列表", description = "按时间升序返回指定会话的所有代码快照")
    public Map<String, Object> listSnapshots(@RequestParam Long sessionId) {
        List<SnapshotService.SnapshotSummary> snapshots = snapshotService.listSnapshots(sessionId);
        return Map.of(
                "success", true,
                "data", snapshots
        );
    }

    @GetMapping("/preview")
    @Operation(summary = "预览回滚", description = "查看快照中包含了哪些文件，以及回滚时各文件的操作（restore/delete）")
    public Map<String, Object> previewRollback(@RequestParam String snapshotId) {
        SnapshotService.RollbackPreview preview = snapshotService.previewRollback(snapshotId);
        if (preview == null) {
            return Map.of("success", false, "message", "快照不存在");
        }
        return Map.of(
                "success", true,
                "data", preview
        );
    }

    @PostMapping("/rollback")
    @Operation(summary = "执行回滚", description = "恢复快照中所有文件的原始内容")
    public Map<String, Object> rollback(@RequestBody Map<String, String> body) {
        String snapshotId = body.get("snapshotId");
        if (snapshotId == null || snapshotId.isEmpty()) {
            return Map.of("success", false, "message", "snapshotId 不能为空");
        }

        log.info("执行回滚: snapshotId={}", snapshotId);
        boolean success = snapshotService.rollback(snapshotId);
        return Map.of("success", success);
    }

    @GetMapping("/session-changes")
    @Operation(summary = "获取会话文件改动汇总", description = "返回指定会话中所有文件的增删行数统计")
    public Map<String, Object> getSessionChanges(@RequestParam Long sessionId) {
        SnapshotService.SessionChanges changes = snapshotService.getSessionChanges(sessionId);
        return Map.of(
                "success", true,
                "data", changes
        );
    }

    @PostMapping("/rollback-file")
    @Operation(summary = "回滚单个文件", description = "将会话中的指定文件回滚到最早快照版本")
    public Map<String, Object> rollbackFile(@RequestBody Map<String, Object> body) {
        Long sessionId = body.get("sessionId") instanceof Number
                ? ((Number) body.get("sessionId")).longValue() : null;
        String filePath = (String) body.get("filePath");
        if (sessionId == null || filePath == null || filePath.isEmpty()) {
            return Map.of("success", false, "message", "sessionId 和 filePath 不能为空");
        }

        boolean success = snapshotService.rollbackFile(sessionId, filePath);
        return Map.of("success", success);
    }

    @PostMapping("/rollback-session")
    @Operation(summary = "回滚会话全部文件", description = "将指定会话中所有被修改过的文件回滚到最早快照版本")
    public Map<String, Object> rollbackSession(@RequestBody Map<String, Object> body) {
        Long sessionId = body.get("sessionId") instanceof Number
                ? ((Number) body.get("sessionId")).longValue() : null;
        if (sessionId == null) {
            return Map.of("success", false, "message", "sessionId 不能为空");
        }

        boolean success = snapshotService.rollbackSession(sessionId);
        return Map.of("success", success);
    }

    @GetMapping("/file-content")
    @Operation(summary = "获取快照中的文件原始内容", description = "返回指定文件在快照中的原始内容，用于diff对比")
    public Map<String, Object> getFileContent(@RequestParam Long sessionId, @RequestParam String filePath) {
        if (sessionId == null || filePath == null || filePath.isEmpty()) {
            return Map.of("success", false, "message", "sessionId 和 filePath 不能为空");
        }
        String content = snapshotService.getSnapshotFileContent(sessionId, filePath);
        return Map.of(
                "success", true,
                "data", content
        );
    }
}
