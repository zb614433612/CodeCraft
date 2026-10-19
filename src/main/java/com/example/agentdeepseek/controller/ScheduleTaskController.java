package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.ScheduleTask;
import com.example.agentdeepseek.scheduler.ScheduleTaskScheduler;
import com.example.agentdeepseek.service.ScheduleTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/schedule-task")
@Tag(name = "定时任务管理", description = "定时任务的增删改查和执行管理")
public class ScheduleTaskController {

    @Autowired
    private ScheduleTaskService scheduleTaskService;

    @Autowired
    private ScheduleTaskScheduler scheduleTaskScheduler;

    @Operation(summary = "获取定时任务列表")
    @GetMapping("/list")
    public ApiResponse<List<ScheduleTask>> listTasks(HttpServletRequest request) {
        checkAdmin(request);
        return ApiResponse.success(scheduleTaskService.getAllTasks());
    }

    @Operation(summary = "创建定时任务")
    @PostMapping("/create")
    public ApiResponse<ScheduleTask> createTask(@RequestBody ScheduleTask task, HttpServletRequest request) {
        checkAdmin(request);
        Long userId = (Long) request.getAttribute("userId");
        task.setUserId(userId);
        ScheduleTask created = scheduleTaskService.createTask(task);
        return ApiResponse.success(created);
    }

    @Operation(summary = "更新定时任务")
    @PutMapping("/update")
    public ApiResponse<Void> updateTask(@RequestBody ScheduleTask task, HttpServletRequest request) {
        checkAdmin(request);
        scheduleTaskService.updateTask(task);
        return ApiResponse.success(null);
    }

    @Operation(summary = "删除定时任务")
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> deleteTask(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        scheduleTaskService.deleteTask(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "启用定时任务")
    @PostMapping("/enable/{id}")
    public ApiResponse<Void> enableTask(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        scheduleTaskService.enableTask(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "立即执行一次", description = "手动触发一次执行：新建会话并异步跑任务指令；不改任务状态与下次执行时间，仅累加执行统计")
    @PostMapping("/run/{id}")
    public ApiResponse<Long> runTask(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        Long conversationId = scheduleTaskScheduler.runNow(id);
        if (conversationId == null) {
            throw new RuntimeException("任务不存在或已被删除");
        }
        log.info("手动触发定时任务: taskId={}, conversationId={}", id, conversationId);
        return ApiResponse.success(conversationId);
    }

    @Operation(summary = "禁用定时任务")
    @PostMapping("/disable/{id}")
    public ApiResponse<Void> disableTask(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        scheduleTaskService.disableTask(id);
        return ApiResponse.success(null);
    }

    private void checkAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("userRole");
        if (!"admin".equals(role)) {
            throw new RuntimeException("权限不足，需要管理员权限");
        }
    }
}
