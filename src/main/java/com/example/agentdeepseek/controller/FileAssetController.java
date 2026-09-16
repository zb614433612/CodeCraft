package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.vo.FileAssetVO;
import com.example.agentdeepseek.service.files.FileAssetService;
import com.example.agentdeepseek.service.files.FilesApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 文件资产控制器
 * <p>
 * 文件资产完整 REST：上传（multipart，图片→云端 Files API / 文档→本地存储）/ 列表（全量+筛选）/
 * 查询 / 重命名 / 删除 / 能力查询 / 本地副本预览。
 * 与旧 /api/deepseek/upload（历史遗留通道）并存兼容，新前端统一走本控制器。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@Tag(name = "文件资产", description = "文件资产管理：上传/列表/查询/重命名/删除/预览（图片走 Files API 云端，文档本地存储）")
public class FileAssetController {

    private final FileAssetService fileAssetService;

    @Autowired
    public FileAssetController(FileAssetService fileAssetService) {
        this.fileAssetService = fileAssetService;
    }

    /**
     * 上传图片文件（本地副本 + 远端 Files API + 记录）
     */
    @Operation(summary = "上传文件", description = "multipart 上传（≤64MB）：图片（JPEG/PNG/GIF/WebP）→ 本地副本 + 远端 Files API（云端）；文档（pdf/word/excel/文本/代码）→ 仅本地存储")
    @PostMapping
    public ApiResponse<FileAssetVO> upload(
            @Parameter(description = "图片文件", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "目标 Provider code（可选，缺省用首个 Provider）")
            @RequestParam(value = "providerCode", required = false) String providerCode,
            @Parameter(description = "来源：upload（按钮）/ paste（粘贴），缺省 upload")
            @RequestParam(value = "source", required = false) String source,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        log.info("收到文件上传: fileName={}, size={}, providerCode={}, source={}, userId={}",
                file.getOriginalFilename(), file.getSize(), providerCode, source, userId);
        return ApiResponse.success(fileAssetService.upload(file, userId, providerCode, source));
    }

    /**
     * 列出当前用户的文件（跨会话、含未引用）
     */
    @Operation(summary = "文件列表", description = "展示当前用户全部文件（跨会话含未引用），支持游标分页与筛选；withRefs=true 附带引用信息")
    @GetMapping
    public ApiResponse<List<FileAssetVO>> list(
            @Parameter(description = "游标：上一页最后一条 id（可空）")
            @RequestParam(value = "after", required = false) Long after,
            @Parameter(description = "条数（默认 50，上限 200）")
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
            @Parameter(description = "排序：asc/desc（缺省 desc=最新在前）")
            @RequestParam(value = "order", required = false, defaultValue = "desc") String order,
            @Parameter(description = "文件名/显示名模糊筛选（可空）")
            @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "状态筛选：active/deleted/orphan（可空）")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "存储类型筛选：cloud=云端（图片）/ local=本地（文档）（可空）")
            @RequestParam(value = "storageType", required = false) String storageType,
            @Parameter(description = "按引用会话筛选（可空）")
            @RequestParam(value = "conversationId", required = false) Long conversationId,
            @Parameter(description = "是否附带引用信息（含 messageId，供历史回显）")
            @RequestParam(value = "withRefs", required = false, defaultValue = "false") boolean withRefs,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        return ApiResponse.success(fileAssetService.list(userId, after, limit, order, keyword, status, storageType, conversationId, withRefs));
    }

    /**
     * 查询单个文件（含引用信息）
     */
    @Operation(summary = "查询文件", description = "查询单个文件资产（含引用信息，供历史回显定位）")
    @GetMapping("/{id}")
    public ApiResponse<FileAssetVO> get(
            @Parameter(description = "本地资产ID", required = true)
            @PathVariable Long id,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        return ApiResponse.success(fileAssetService.get(id, userId));
    }

    /**
     * 重命名（更新本地显示名）
     */
    @Operation(summary = "重命名文件", description = "更新本地显示名（Files API 无远端 update 端点，U 以本地实现）")
    @PatchMapping("/{id}")
    public ApiResponse<FileAssetVO> rename(
            @Parameter(description = "本地资产ID", required = true)
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        String displayName = body == null ? null : body.get("displayName");
        return ApiResponse.success(fileAssetService.rename(id, userId, displayName));
    }

    /**
     * 删除文件（远端 + 引用 + 记录 + 本地副本）
     */
    @Operation(summary = "删除文件", description = "删除文件：远端 Files API（404 视为成功）→ 引用 + 记录 + 本地副本")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "本地资产ID", required = true)
            @PathVariable Long id,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        fileAssetService.delete(id, userId);
        return ApiResponse.success(null, "删除成功");
    }

    /**
     * 能力查询：当前 Provider 是否支持 Files API
     */
    @Operation(summary = "Files API 能力查询", description = "查询指定（或缺省）Provider 是否支持 Files API，供前端能力判断")
    @GetMapping("/supported")
    public ApiResponse<Map<String, Object>> supported(
            @Parameter(description = "Provider code（可选，缺省用首个 Provider）")
            @RequestParam(value = "providerCode", required = false) String providerCode) {
        return ApiResponse.success(fileAssetService.supported(providerCode));
    }

    /**
     * 预览本地副本（流式返回图片；前端可用 fetch+blob 加载以携带鉴权头）
     */
    @Operation(summary = "预览文件", description = "流式返回本地副本（图片）；仅本人可访问")
    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(
            @Parameter(description = "本地资产ID", required = true)
            @PathVariable Long id,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        try {
            FileAssetService.PreviewData data = fileAssetService.loadPreview(id, userId);
            Resource resource = new FileSystemResource(data.path());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(data.mimeType()))
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .body(resource);
        } catch (NoSuchElementException e) {
            log.warn("预览失败: id={}, userId={}, reason={}", id, userId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    // ==================== 类内异常处理（仅作用于本控制器，body.code 与项目惯例一致） ====================

    @ExceptionHandler(NoSuchElementException.class)
    public ApiResponse<Void> handleNotFound(NoSuchElementException e) {
        log.warn("文件资产不存在: {}", e.getMessage());
        return ApiResponse.error(404, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
        log.warn("文件资产参数错误: {}", e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(FilesApiException.class)
    public ApiResponse<Void> handleFilesApiError(FilesApiException e) {
        log.warn("Files API 调用失败: code={}, retryable={}, msg={}", e.getCode(), e.isRetryable(), e.getMessage());
        return ApiResponse.error(502, "远端服务错误: " + e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Void> handleServiceError(IllegalStateException e) {
        log.error("文件资产服务错误: {}", e.getMessage());
        return ApiResponse.error(500, e.getMessage());
    }
}
