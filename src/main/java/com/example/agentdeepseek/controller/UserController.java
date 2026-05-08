package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.dto.GetRandomCodeRequest;
import com.example.agentdeepseek.model.dto.LoginRequest;
import com.example.agentdeepseek.model.dto.LoginResponse;
import com.example.agentdeepseek.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@Tag(name = "用户管理", description = "用户登录、注销等接口")
public class UserController {

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 获取随机码
     * 用于登录时的二次加密
     */
    @Operation(summary = "获取随机码", description = "获取用于登录密码二次加密的随机码，有效期1分钟")
    @PostMapping("/random-code")
    public ApiResponse<String> getRandomCode(
            @Parameter(description = "获取随机码请求，需要用户名")
            @RequestBody GetRandomCodeRequest request) {
        log.info("获取随机码请求: username={}", request.getUsername());
        String randomCode = userService.generateRandomCode(request.getUsername());
        return ApiResponse.success(randomCode);
    }

    /**
     * 用户登录
     */
    @Operation(summary = "用户登录", description = "用户登录接口，需要用户名、前端MD5加密后的密码和随机码")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Parameter(description = "登录请求")
            @RequestBody LoginRequest request) {
        log.info("用户登录请求: username={}", request.getUsername());
        LoginResponse response = userService.login(request);
        return ApiResponse.success(response);
    }

    /**
     * 用户注销
     */
    @Operation(summary = "用户注销", description = "用户注销接口，使token失效")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("用户注销请求: authHeader={}", authHeader);

        if (authHeader == null || authHeader.trim().isEmpty()) {
            log.warn("注销请求缺少Authorization头");
            return ApiResponse.success(null); // 没有token可注销，返回成功
        }

        String token = authHeader;
        // 去除Bearer前缀（如果有）
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        userService.logout(token);
        return ApiResponse.success(null);
    }
}