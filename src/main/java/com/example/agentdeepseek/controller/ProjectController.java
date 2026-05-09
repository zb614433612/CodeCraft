package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.vo.DirectoryEntry;
import com.example.agentdeepseek.model.vo.ProjectTreeNode;
import com.example.agentdeepseek.service.ProjectService;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目文件树控制器
 * 提供项目目录结构查询接口，用于编码助手的文件树面板
 */
@Slf4j
@RestController
@RequestMapping("/api/project")
@Tag(name = "项目文件树", description = "项目目录结构查询接口")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/tree")
    @Operation(summary = "获取项目文件树", description = "返回项目目录结构树，支持指定根目录和递归深度")
    public ApiResponse<ProjectTreeNode> getProjectTree(
            @Parameter(description = "项目根目录路径，为空则使用当前项目目录")
            @RequestParam(defaultValue = "") String root,
            @Parameter(description = "递归深度，默认10")
            @RequestParam(defaultValue = "10") int depth) {
        log.debug("获取项目文件树，根目录: {}, 深度: {}", root.isEmpty() ? "当前项目" : root, depth);
        ProjectTreeNode tree = projectService.getProjectTree(root, depth);
        return ApiResponse.success(tree);
    }

    @GetMapping("/drives")
    @Operation(summary = "获取系统盘符/根目录列表", description = "Windows 返回 C:/, D:/ 等；Linux/Mac 返回 /")
    public ApiResponse<List<DirectoryEntry>> listDrives() {
        List<DirectoryEntry> drives = projectService.listDrives();
        return ApiResponse.success(drives);
    }

    @GetMapping("/children")
    @Operation(summary = "获取指定目录下的子目录列表", description = "返回指定路径下的目录列表（不含文件），用于目录浏览器逐层导航")
    public ApiResponse<List<DirectoryEntry>> listChildren(
            @Parameter(description = "父目录路径")
            @RequestParam String path) {
        List<DirectoryEntry> children = projectService.listChildren(path);
        return ApiResponse.success(children);
    }
}
