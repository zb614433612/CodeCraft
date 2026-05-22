package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.common.response.PageResult;
import com.example.agentdeepseek.model.dto.GetRandomCodeRequest;
import com.example.agentdeepseek.model.dto.LoginRequest;
import com.example.agentdeepseek.model.dto.LoginResponse;
import com.example.agentdeepseek.model.entity.User;
import com.example.agentdeepseek.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@Tag(name = "用户管理", description = "用户登录、注销、用户增删改查等接口")
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
        log.info("用户注销请求");

        if (authHeader == null || authHeader.trim().isEmpty()) {
            log.warn("注销请求缺少Authorization头");
            return ApiResponse.success(null);
        }

        String token = authHeader;
        // 去除Bearer前缀（如果有）
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        userService.logout(token);
        return ApiResponse.success(null);
    }

    // ========== 用户管理接口 ==========

    /**
     * 分页查询用户列表
     */
    @Operation(summary = "用户列表", description = "分页查询用户列表（需要管理员权限），支持按用户名搜索")
    @GetMapping("/list")
    public ApiResponse<PageResult<User>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            HttpServletRequest request) {
        checkAdmin(request);
        log.info("查询用户列表: page={}, pageSize={}, search={}", page, pageSize, search);
        PageResult<User> result = userService.listUsers(page, pageSize, search);
        // 脱敏密码
        if (result.getRecords() != null) {
            result.getRecords().forEach(user -> user.setPassword(null));
        }
        return ApiResponse.success(result);
    }

    /**
     * 创建用户
     */
    @Operation(summary = "创建用户", description = "新增用户（需要管理员权限）")
    @PostMapping("/create")
    public ApiResponse<User> createUser(@RequestBody User user, HttpServletRequest request) {
        checkAdmin(request);
        log.info("创建用户: username={}, role={}", user.getUsername(), user.getRole());
        User created = userService.createUser(user);
        created.setPassword(null);
        return ApiResponse.success(created);
    }

    /**
     * 更新用户
     */
    @Operation(summary = "更新用户", description = "修改用户信息（需要管理员权限）")
    @PutMapping("/update")
    public ApiResponse<Void> updateUser(@RequestBody User user, HttpServletRequest request) {
        checkAdmin(request);
        log.info("更新用户: id={}", user.getId());
        userService.updateUser(user);
        return ApiResponse.success(null);
    }

    /**
     * 删除用户
     */
    @Operation(summary = "删除用户", description = "删除用户（需要管理员权限，不能删除自己）")
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> deleteUser(
            @Parameter(description = "用户ID") @PathVariable Long id,
            HttpServletRequest request) {
        checkAdmin(request);
        // 不能删除自己
        Long currentUserId = (Long) request.getAttribute("userId");
        if (currentUserId != null && currentUserId.equals(id)) {
            return ApiResponse.error(400, "不能删除自己");
        }
        log.info("删除用户: id={}", id);
        userService.deleteUser(id);
        return ApiResponse.success(null);
    }

    /**
     * 获取个人信息
     */
    @Operation(summary = "个人信息", description = "获取当前登录用户的个人信息")
    @GetMapping("/profile")
    public ApiResponse<User> getProfile(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "未登录");
        }
        log.info("获取个人信息: userId={}", userId);
        User user = userService.getCurrentUserProfile(userId);
        return ApiResponse.success(user);
    }

    /**
     * 更新个人信息
     */
    @Operation(summary = "更新个人信息", description = "更新当前登录用户的昵称、邮箱、手机号等信息")
    @PutMapping("/profile")
    public ApiResponse<Void> updateProfile(@RequestBody User user, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "未登录");
        }
        log.info("更新个人信息: userId={}", userId);
        userService.updateProfile(userId, user);
        return ApiResponse.success(null);
    }

    /**
     * 修改密码
     */
    @Operation(summary = "修改密码", description = "修改当前登录用户的密码，需要验证旧密码")
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody Map<String, String> params, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return ApiResponse.error(401, "未登录");
        }

        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");

        if (oldPassword == null || oldPassword.trim().isEmpty()) {
            return ApiResponse.error(400, "旧密码不能为空");
        }
        if (newPassword == null || newPassword.trim().isEmpty()) {
            return ApiResponse.error(400, "新密码不能为空");
        }

        log.info("修改密码: userId={}", userId);
        userService.changePassword(userId, oldPassword, newPassword);
        return ApiResponse.success(null);
    }

    /**
     * 当前登录用户信息（含角色）
     */
    @Operation(summary = "当前用户信息", description = "获取当前登录用户的基本信息（含角色），供前端判断权限")
    @GetMapping("/current")
    public ApiResponse<Map<String, Object>> getCurrentUser(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String userRole = (String) request.getAttribute("userRole");
        String username = (String) request.getAttribute("username");

        if (userId == null) {
            return ApiResponse.error(401, "未登录");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("username", username);
        result.put("role", userRole);
        return ApiResponse.success(result);
    }

    // ========== 权限检查辅助方法 ==========

    /**
     * 检查当前用户是否为管理员
     */
    private void checkAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("userRole");
        if (!"admin".equals(role)) {
            throw new RuntimeException("权限不足，需要管理员权限");
        }
    }
}
