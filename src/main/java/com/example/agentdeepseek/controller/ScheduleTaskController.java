package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.ScheduleTask;
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
