package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.vo.TurnPromptVO;
import com.example.agentdeepseek.service.SnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 代码快照 REST 控制器
 * 提供快照列表、回滚预览、执行回滚功能
 */
@Slf4j
@RestController
@RequestMapping("/api/snapshots")
@Tag(name = "代码快照", description = "代码回滚快照管理接口")
public class SnapshotController {

    /** 子 Agent / 代码审查轮次的 turnId 会带 {@code _review_N} 后缀，查提问时要还原成主轮次 */
    private static final Pattern REVIEW_SUFFIX = Pattern.compile("_review_\\d+$");
    /** 面板一列只放得下几十个字，超长提问在服务端先压一遍，避免把整段代码塞进列表响应 */
    private static final int PROMPT_LIMIT = 200;

    private final SnapshotService snapshotService;
    private final ConversationMessageMapper conversationMessageMapper;

    public SnapshotController(SnapshotService snapshotService,
                              ConversationMessageMapper conversationMessageMapper) {
        this.snapshotService = snapshotService;
        this.conversationMessageMapper = conversationMessageMapper;
    }

    @GetMapping("/list")
    @Operation(summary = "获取会话快照列表", description = "按时间升序返回指定会话的所有代码快照，并回填每轮的用户提问")
    public Map<String, Object> listSnapshots(@RequestParam Long sessionId) {
        List<SnapshotService.SnapshotSummary> snapshots = snapshotService.listSnapshots(sessionId);
        fillTurnPrompts(sessionId, snapshots);
        return Map.of(
                "success", true,
                "data", snapshots
        );
    }

    /**
     * 给每个快照回填「该轮用户问了什么」。
     *
     * <p>背景：聊天页默认只加载最近 5 轮，而快照多半来自更早的轮次，前端手上没有那些提问，
     * 面板只能显示「更早的一轮，未加载提问」。这里按 turnId 精确联查，一次补齐。</p>
     *
     * <p>失败不影响主流程：查不到就保持 null，前端继续走占位文案。</p>
     */
    private void fillTurnPrompts(Long sessionId, List<SnapshotService.SnapshotSummary> snapshots) {
        if (sessionId == null || snapshots == null || snapshots.isEmpty()) return;

        // 原 turnId 与「去掉 _review_N 后缀」的都要查：不同版本的快照落库口径可能不同
        Set<String> candidates = new LinkedHashSet<>();
        for (SnapshotService.SnapshotSummary s : snapshots) {
            String raw = s.getTurnId();
            if (raw == null || raw.isBlank()) continue;
            candidates.add(raw);
            String stripped = REVIEW_SUFFIX.matcher(raw).replaceAll("");
            if (!stripped.isEmpty() && !stripped.equals(raw)) candidates.add(stripped);
        }
        if (candidates.isEmpty()) return;

        Map<String, String> promptByTurn = new HashMap<>();
        try {
            List<TurnPromptVO> rows =
                    conversationMessageMapper.selectUserPromptsByTurnIds(sessionId, new ArrayList<>(candidates));
            for (TurnPromptVO row : rows) {
                String turnId = row.getTurnId();
                String content = row.getContent();
                if (turnId != null && content != null && !promptByTurn.containsKey(turnId)) {
                    promptByTurn.put(turnId, abbreviate(content));
                }
            }
        } catch (Exception e) {
            log.warn("查询快照轮次提问失败（不影响列表）: sessionId={}", sessionId, e);
            return;
        }

        for (SnapshotService.SnapshotSummary s : snapshots) {
            String raw = s.getTurnId();
            if (raw == null || raw.isBlank()) continue;
            String prompt = promptByTurn.get(raw);
            if (prompt == null) {
                prompt = promptByTurn.get(REVIEW_SUFFIX.matcher(raw).replaceAll(""));
            }
            if (prompt != null) s.setTurnPrompt(prompt);
        }
    }

    private String abbreviate(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return s;
        return s.length() > PROMPT_LIMIT ? s.substring(0, PROMPT_LIMIT) + "…" : s;
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
