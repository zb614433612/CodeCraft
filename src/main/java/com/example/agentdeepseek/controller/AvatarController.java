package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.service.AvatarStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 头像控制器
 * <p>
 * 本地头像上传与读取（不上传云端 Files API）：
 * <ul>
 *   <li>{@code POST /api/avatar} —— 上传头像（multipart，需登录），返回 {@code {"url": "/api/avatar/file/<uuid>.<ext>"}}</li>
 *   <li>{@code GET /api/avatar/file/{name}} —— 读取头像文件（已由 TokenAuthenticationFilter 放行，供 img 标签直接引用）</li>
 * </ul>
 * 前端将返回的 URL 存入 user.avatar / agent_config.avatar；非本前缀的 avatar 值（如默认 emoji）按文字渲染。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/avatar")
@Tag(name = "头像管理", description = "本地头像上传（POST，需登录）与读取（GET，免鉴权）")
public class AvatarController {

    private final AvatarStore avatarStore;

    public AvatarController(AvatarStore avatarStore) {
        this.avatarStore = avatarStore;
    }

    /**
     * 上传头像（multipart），返回可访问的相对 URL。
     */
    @Operation(summary = "上传头像", description = "multipart 上传本地头像（≤5MB，png/jpg/jpeg/gif/webp/bmp），返回图片相对 URL")
    @PostMapping
    public ApiResponse<Map<String, String>> upload(
            @Parameter(description = "头像图片文件", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute(value = "userId", required = false) Long userId) {
        log.info("收到头像上传: fileName={}, size={}, contentType={}, userId={}",
                file.getOriginalFilename(), file.getSize(), file.getContentType(), userId);
        String url = avatarStore.save(file);
        return ApiResponse.success(Map.of("url", url));
    }

    /**
     * 读取头像文件（免鉴权，供前端 img 标签直接引用）。
     */
    @Operation(summary = "读取头像", description = "按存储文件名流式返回头像图片（免登录，供 img 引用）")
    @GetMapping("/file/{name:.+}")
    public ResponseEntity<Resource> file(
            @Parameter(description = "存储文件名（<uuid>.<ext>）", required = true)
            @PathVariable String name) {
        Path path = avatarStore.resolve(name);
        if (path == null || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(parseMediaType(name))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(new FileSystemResource(path));
    }

    private MediaType parseMediaType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".bmp")) return MediaType.parseMediaType("image/bmp");
        return MediaType.IMAGE_JPEG;
    }

    // ==================== 类内异常处理 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleBadRequest(IllegalArgumentException e) {
        log.warn("头像参数错误: {}", e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Void> handleServiceError(IllegalStateException e) {
        log.error("头像服务错误: {}", e.getMessage());
        return ApiResponse.error(500, e.getMessage());
    }
}
