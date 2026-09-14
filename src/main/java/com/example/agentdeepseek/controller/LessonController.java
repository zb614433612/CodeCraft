package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.Lesson;
import com.example.agentdeepseek.service.lesson.LessonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 踩坑经验管理 REST 控制器（成长体系）
 * 供前端管理页面：分页浏览、详情、人工编辑、反馈验证、删除、统计看板。
 * 与 lesson 工具（LLM 使用）互补：本控制器是「人」的管理通道。
 */
@Slf4j
@RestController
@RequestMapping("/api/lessons")
@Tag(name = "踩坑经验管理", description = "成长体系：踩坑经验的查看、编辑、反馈、删除、统计")
public class LessonController {

    private final LessonService lessonService;

    public LessonController(LessonService lessonService) {
        this.lessonService = lessonService;
    }

    /**
     * 分页列表（管理页面）
     * 参数：projectKey 必填；status/toolName/errorCode 可选过滤；page 从 1 开始，size 默认 10
     */
    @GetMapping
    @Operation(summary = "分页查询踩坑经验")
    public ApiResponse<Map<String, Object>> pageLessons(
            // 注意：projectKey 不设 defaultValue（Spring 会强制注入 "default" 字符串，绕过 Service 的 blank 兜底），
            // 留空时由 Service 用 extractProjectKey() 解析为当前项目根目录名（与 LLM 写入端一致）
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean zeroHit,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        return ApiResponse.success(lessonService.pageQuery(projectKey, status, toolName, errorCode, type, zeroHit, page, size));
    }

    /**
     * 单条详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询踩坑经验详情")
    public ApiResponse<Lesson> getLesson(@PathVariable Long id) {
        Optional<Lesson> lesson = lessonService.getLesson(id);
        return lesson.map(l -> ApiResponse.success(l))
                .orElseGet(() -> ApiResponse.error(404, "经验 ID=" + id + " 不存在"));
    }

    /**
     * 统计看板（成长体系效果）
     * 参数：projectKey 可选（默认 default）
     */
    @GetMapping("/stats")
    @Operation(summary = "踩坑经验库统计（成长看板）")
    public ApiResponse<Map<String, Object>> getStats(
            @RequestParam(required = false) String projectKey) {
        return ApiResponse.success(lessonService.getStats(projectKey));
    }

    /**
     * P2：聚类查询——重复组列表（管理页归并入口）
     */
    @GetMapping("/clusters")
    @Operation(summary = "查询重复经验组（聚类归并）")
    public ApiResponse<List<Map<String, Object>>> listClusters(
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return ApiResponse.success(lessonService.listClusters(projectKey, limit));
    }

    /**
     * P2：归并执行——把同组多条合并为一条（计数汇聚 + canonical 码 + 新指纹）
     */
    @PostMapping("/merge")
    @Operation(summary = "归并重复经验组")
    public ApiResponse<String> mergeGroup(@RequestBody Map<String, String> body) {
        String result = lessonService.mergeGroup(body.get("projectKey"), body.get("toolName"),
                body.get("errorCategory"), body.get("errorCode"));
        if (result.startsWith("参数不足") || result.startsWith("无需合并")) {
            return ApiResponse.error(400, result);
        }
        return ApiResponse.success(result, "归并完成");
    }

    /**
     * 人工编辑/补全经验（与 lesson action=complete 同逻辑，前端管理用）
     * 只更新非空字段，status 不变（转正仍由反馈驱动）
     */
    @PutMapping("/{id}")
    @Operation(summary = "编辑/补全踩坑经验")
    public ApiResponse<String> updateLesson(@PathVariable Long id, @RequestBody Lesson lesson) {
        String result = lessonService.completeLesson(id,
                lesson.getRootCause(), lesson.getSolution(),
                lesson.getParamsJson(), lesson.getApplicableCond(), lesson.getKeywords());
        if (result.startsWith("错误") || result.startsWith("参数不足")) {
            return ApiResponse.error(400, result);
        }
        return ApiResponse.success(result, "经验更新成功");
    }

    /**
     * 反馈验证结果（与 lesson action=feedback 同逻辑）
     * 参数：effective=true 有效 / false 无效
     */
    @PostMapping("/{id}/feedback")
    @Operation(summary = "反馈经验验证结果")
    public ApiResponse<String> feedback(@PathVariable Long id, @RequestParam boolean effective) {
        String result = lessonService.feedbackLesson(id, effective);
        if (result.startsWith("错误")) {
            return ApiResponse.error(404, result);
        }
        return ApiResponse.success(result, "反馈已记录");
    }

    /**
     * 删除经验（物理删除，管理操作：确认误录/废弃）
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除踩坑经验")
    public ApiResponse<Void> deleteLesson(@PathVariable Long id) {
        if (lessonService.deleteLesson(id)) {
            return ApiResponse.success(null, "经验 ID=" + id + " 已删除");
        }
        return ApiResponse.error(404, "经验 ID=" + id + " 不存在");
    }
}
