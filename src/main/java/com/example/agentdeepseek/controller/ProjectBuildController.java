package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.service.ProjectBuildService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 项目编译/运行控制台 REST 控制器
 * 提供一键编译、运行、停止项目及查看控制台输出的功能
 */
@Slf4j
@RestController
@RequestMapping("/api/project")
@Tag(name = "项目编译运行", description = "项目编译、运行、停止及控制台输出接口")
public class ProjectBuildController {

    private final ProjectBuildService projectBuildService;

    public ProjectBuildController(ProjectBuildService projectBuildService) {
        this.projectBuildService = projectBuildService;
    }

    @PostMapping("/build")
    @Operation(summary = "编译项目", description = "根据项目类型自动选择编译命令（mvn compile / npm run build / gradle build）")
    public Map<String, Object> build(@RequestBody Map<String, String> body) {
        String projectRoot = body.getOrDefault("projectRoot", "");
        log.info("编译项目: projectRoot={}", projectRoot.isEmpty() ? "default" : projectRoot);

        ProjectBuildService.BuildResult result = projectBuildService.build(projectRoot);

        return Map.of(
                "success", result.success(),
                "output", result.output(),
                "exitCode", result.exitCode(),
                "duration", result.duration()
        );
    }

    @PostMapping("/run")
    @Operation(summary = "运行项目", description = "在后台启动项目（mvn spring-boot:run / npm run dev / gradle bootRun）")
    public Map<String, Object> run(@RequestBody Map<String, String> body) {
        String projectRoot = body.getOrDefault("projectRoot", "");
        log.info("运行项目: projectRoot={}", projectRoot.isEmpty() ? "default" : projectRoot);

        ProjectBuildService.RunResult result = projectBuildService.run(projectRoot);

        Map<String, Object> response = Map.of(
                "success", result.success(),
                "message", result.message()
        );
        if (result.pid() != null) {
            return Map.of(
                    "success", result.success(),
                    "message", result.message(),
                    "pid", result.pid()
            );
        }
        return response;
    }

    @PostMapping("/stop")
    @Operation(summary = "停止项目", description = "停止正在运行的后台项目进程")
    public Map<String, Object> stop() {
        log.info("停止项目");
        ProjectBuildService.StopResult result = projectBuildService.stop();
        return Map.of("success", result.success(), "message", result.message());
    }

    @GetMapping("/run/status")
    @Operation(summary = "获取运行状态", description = "查询项目是否在运行中")
    public Map<String, Object> getStatus() {
        ProjectBuildService.StatusResult result = projectBuildService.getStatus();
        return Map.of(
                "running", result.running(),
                "pid", result.pid() != null ? result.pid() : java.util.Optional.empty(),
                "elapsed", result.elapsed()
        );
    }

    @GetMapping("/run/output")
    @Operation(summary = "获取控制台输出", description = "获取运行中项目的控制台输出，支持 tail 参数获取最近 N 行")
    public Map<String, Object> getOutput(@RequestParam(required = false) Integer tail) {
        ProjectBuildService.OutputResult result = projectBuildService.getOutput(tail);
        return Map.of(
                "success", result.success(),
                "lines", result.lines(),
                "running", result.running()
        );
    }
}
