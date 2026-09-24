package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.vo.DirectoryEntry;
import com.example.agentdeepseek.model.vo.ProjectTreeNode;
import com.example.agentdeepseek.service.ProjectService;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    @Operation(summary = "获取指定目录下的子目录列表", description = "返回指定路径下的子目录和文件列表，用于目录浏览器逐层导航和终端补全")
    public ApiResponse<List<DirectoryEntry>> listChildren(
            @Parameter(description = "父目录路径")
            @RequestParam String path,
            @Parameter(description = "是否包含文件，默认 false")
            @RequestParam(defaultValue = "false") boolean includeFiles) {
        List<DirectoryEntry> children = projectService.listChildren(path, includeFiles);
        return ApiResponse.success(children);
    }

    @GetMapping("/read")
    @Operation(summary = "读取文件内容", description = "读取指定文件的内容，返回文件文本")
    public ApiResponse<String> readFile(
            @Parameter(description = "文件路径（相对或绝对路径）")
            @RequestParam String path) {
        log.debug("读取文件: {}", path);
        String content = projectService.readFile(path);
        return ApiResponse.success(content);
    }

    @GetMapping("/resolve")
    @Operation(summary = "解析不完整文件路径", description = "将省略前缀的短路径（如 api/x.ts）在项目内按路径后缀匹配，返回候选文件的相对路径列表（精确匹配在前，最多50个）")
    public ApiResponse<List<String>> resolveFilePath(
            @Parameter(description = "项目根目录路径，为空则使用当前项目目录")
            @RequestParam(defaultValue = "") String root,
            @Parameter(description = "待解析的不完整路径（相对路径）")
            @RequestParam String path) {
        log.debug("解析文件路径: root={}, path={}", root.isEmpty() ? "当前项目" : root, path);
        return ApiResponse.success(projectService.resolveFilePath(root, path));
    }

    @GetMapping("/raw")
    @Operation(summary = "读取文件原始字节", description = "返回文件原始字节流（图片/PDF 等二进制预览用），Content-Type 按扩展名推断")
    public ResponseEntity<byte[]> rawFile(
            @Parameter(description = "文件路径（相对或绝对路径）")
            @RequestParam String path) {
        log.debug("读取文件原始字节: {}", path);
        byte[] bytes = projectService.readFileBytes(path);
        MediaType mediaType = parseMediaType(path);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .body(bytes);
    }

    /** 按文件扩展名推断 Content-Type（图片优先，其余按常见文档类型，未知回退 octet-stream） */
    private MediaType parseMediaType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".bmp")) return MediaType.parseMediaType("image/bmp");
        if (lower.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        if (lower.endsWith(".ico")) return MediaType.parseMediaType("image/x-icon");
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @PostMapping("/write")
    @Operation(summary = "写入文件内容", description = "将内容写入指定文件")
    public ApiResponse<Void> writeFile(@RequestBody Map<String, String> body) {
        String path = body.get("path");
        String content = body.get("content");
        log.debug("写入文件: {}", path);
        projectService.writeFile(path, content);
        return ApiResponse.success(null);
    }
}
