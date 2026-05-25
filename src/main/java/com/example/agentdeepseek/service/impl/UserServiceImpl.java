package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.common.response.PageResult;
import com.example.agentdeepseek.mapper.RoleMapper;
import com.example.agentdeepseek.mapper.UserMapper;
import com.example.agentdeepseek.model.dto.LoginRequest;
import com.example.agentdeepseek.model.dto.LoginResponse;
import com.example.agentdeepseek.model.entity.Role;
import com.example.agentdeepseek.model.entity.User;
import com.example.agentdeepseek.service.UserService;
import com.example.agentdeepseek.util.Md5Util;
import com.example.agentdeepseek.util.RandomCodeUtil;
import com.example.agentdeepseek.util.TokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户服务实现
 */
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    // token过期时间（秒）
    private static final long TOKEN_EXPIRE_SECONDS = 7200; // 2小时

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private TokenStore tokenStore;

    /**
     * 从 sys_role 表获取角色编码，找不到时返回默认值
     */
    private String getRoleCode(String preferCode, String fallback) {
        if (preferCode != null) {
            Role role = roleMapper.selectByCode(preferCode);
            if (role != null) return role.getCode();
        }
        // 取第一个启用的角色作为默认
        List<Role> roles = roleMapper.selectAll();
        if (!roles.isEmpty()) return roles.get(0).getCode();
        return fallback;
    }

    @Override
    public String generateRandomCode(String username) {
        // 生成4位随机码（字母数字）
        String randomCode = RandomCodeUtil.generate(4);
        // 存储到 Redis，1分钟过期
        tokenStore.saveRandomCode(username, randomCode);
        log.info("生成随机码: username={}, randomCode={}", username, randomCode);
        return randomCode;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();
        String password = request.getPassword(); // 前端二次加密后的密码：MD5(前端MD5(明文密码) + 随机码)

        // 1. 从 Redis 获取随机码
        String storedRandomCode = tokenStore.getRandomCode(username);
        if (storedRandomCode == null) {
            throw new RuntimeException("随机码无效或已过期");
        }
        // 使用后删除随机码
        tokenStore.deleteRandomCode(username);

        // 2. 查询用户
        User user = userMapper.selectByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 3. 检查用户状态
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new RuntimeException("该账号已被禁用，请联系管理员");
        }

        // 4. 验证密码
        // 数据库存储的是 MD5(明文密码)
        // 前端传来的password是 MD5(前端MD5(明文密码) + 随机码)
        // 验证：将数据库密码与随机码拼接后MD5加密，与前端传来的password比较
        String expectedPassword = Md5Util.md5(user.getPassword() + storedRandomCode);
        if (!expectedPassword.equals(password)) {
            throw new RuntimeException("密码错误");
        }

        // 5. 生成token
        String token = UUID.randomUUID().toString().replace("-", "");

        // 存储用户信息到 Redis
        Map<String, String> userInfo = new HashMap<>();
        userInfo.put("id", user.getId().toString());
        userInfo.put("username", user.getUsername());
        userInfo.put("nickname", user.getNickname() != null ? user.getNickname() : "");
        userInfo.put("role", user.getRole() != null ? user.getRole() : "user");
        tokenStore.saveToken(token, userInfo);

        log.info("用户登录成功: username={}, userId={}, role={}", username, user.getId(), user.getRole());

        // 6. 返回响应
        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setRole(user.getRole() != null ? user.getRole() : "user");
        response.setToken(token);
        response.setExpire(TOKEN_EXPIRE_SECONDS);
        return response;
    }

    @Override
    public Long validateToken(String token) {
        // 检查token是否存在
        if (!tokenStore.hasToken(token)) {
            return null;
        }
        // 从缓存中获取用户ID
        String userIdStr = tokenStore.getTokenField(token, "id");
        if (userIdStr == null) {
            return null;
        }
        // 刷新token过期时间
        tokenStore.refreshToken(token);
        return Long.parseLong(userIdStr);
    }

    @Override
    public String getUsernameByToken(String token) {
        return tokenStore.getTokenField(token, "username");
    }

    @Override
    public String getRoleByToken(String token) {
        return tokenStore.getTokenField(token, "role");
    }

    @Override
    public void logout(String token) {
        tokenStore.deleteToken(token);
        log.info("用户注销: token={}", token);
    }

    @Override
    public void createDefaultAdminIfNotExists() {
        if (userMapper.selectByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            // 默认密码：123456，MD5加密存储
            // MD5("123456") = e10adc3949ba59abbe56e057f20f883e
            admin.setPassword("e10adc3949ba59abbe56e057f20f883e");
            admin.setNickname("管理员");
            admin.setRole(getRoleCode("admin", "admin"));
            admin.setStatus(1);
            admin.setCreateTime(LocalDateTime.now());
            admin.setUpdateTime(LocalDateTime.now());
            userMapper.insert(admin);
            log.info("创建默认管理员用户: admin, role={}", admin.getRole());
        }
    }

    // ========== 用户管理接口实现 ==========

    @Override
    public PageResult<User> listUsers(int page, int pageSize, String search) {
        int offset = (page - 1) * pageSize;
        List<User> users;
        long total;
        if (search != null && !search.trim().isEmpty()) {
            users = userMapper.searchByUsername(search.trim(), offset, pageSize);
            total = userMapper.countByUsername(search.trim());
        } else {
            users = userMapper.selectAll(offset, pageSize);
            total = userMapper.selectCount();
        }
        return new PageResult<>(users, total, page, pageSize);
    }

    @Override
    public User createUser(User user) {
        // 检查用户名是否已存在
        if (userMapper.selectByUsername(user.getUsername()).isPresent()) {
            throw new RuntimeException("用户名已存在");
        }

        // 设置默认值
        if (user.getRole() == null || user.getRole().trim().isEmpty()) {
            user.setRole(getRoleCode(null, "user"));
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        // 密码MD5加密存储（与登录校验逻辑一致）
        if (user.getPassword() != null && !user.getPassword().trim().isEmpty()) {
            user.setPassword(Md5Util.md5(user.getPassword()));
        }
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        userMapper.insert(user);
        log.info("创建用户: username={}, role={}, id={}", user.getUsername(), user.getRole(), user.getId());
        return user;
    }

    @Override
    public void updateUser(User user) {
        User existing = userMapper.selectById(user.getId())
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 继承现有值（防止前端未传字段被覆盖为null）
        if (user.getUsername() == null) user.setUsername(existing.getUsername());
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            user.setPassword(existing.getPassword());
        } else {
            // 新密码需要MD5加密存储
            user.setPassword(Md5Util.md5(user.getPassword()));
        }
        if (user.getNickname() == null) user.setNickname(existing.getNickname());
        if (user.getRole() == null) user.setRole(existing.getRole());
        if (user.getEmail() == null) user.setEmail(existing.getEmail());
        if (user.getPhone() == null) user.setPhone(existing.getPhone());
        if (user.getAvatar() == null) user.setAvatar(existing.getAvatar());
        if (user.getStatus() == null) user.setStatus(existing.getStatus());

        // 保留原有创建时间
        user.setCreateTime(existing.getCreateTime());
        user.setUpdateTime(LocalDateTime.now());

        userMapper.update(user);
        log.info("更新用户: id={}, username={}, role={}", user.getId(), user.getUsername(), user.getRole());
    }

    @Override
    public void deleteUser(Long id) {
        // 检查用户是否存在
        User user = userMapper.selectById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 保护最后一个管理员不被删除
        if ("admin".equals(user.getRole())) {
            long totalUsers = userMapper.selectCount();
            List<User> allUsers = userMapper.selectAll(0, (int) totalUsers);
            boolean hasOtherAdmin = allUsers.stream()
                    .anyMatch(u -> "admin".equals(u.getRole()) && !u.getId().equals(id));
            if (!hasOtherAdmin) {
                throw new RuntimeException("无法删除最后一个管理员");
            }
        }

        userMapper.delete(id);
        log.info("删除用户: id={}, username={}", id, user.getUsername());
    }

    @Override
    public User getUserById(Long id) {
        return userMapper.selectById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }

    @Override
    public User getCurrentUserProfile(Long userId) {
        User user = userMapper.selectById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        // 脱敏密码，不返回给前端
        user.setPassword(null);
        return user;
    }

    @Override
    public void updateProfile(Long userId, User user) {
        User existing = userMapper.selectById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 只允许更新特定字段
        if (user.getNickname() != null) {
            existing.setNickname(user.getNickname());
        }
        if (user.getEmail() != null) {
            existing.setEmail(user.getEmail());
        }
        if (user.getPhone() != null) {
            existing.setPhone(user.getPhone());
        }
        if (user.getAvatar() != null) {
            existing.setAvatar(user.getAvatar());
        }
        existing.setUpdateTime(LocalDateTime.now());

        userMapper.update(existing);
        log.info("更新个人信息: userId={}", userId);
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 验证旧密码
        if (!user.getPassword().equals(oldPassword)) {
            throw new RuntimeException("旧密码错误");
        }

        // 更新新密码
        user.setPassword(newPassword);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.update(user);

        // 清除该用户的所有登录 token，强制重新登录
        tokenStore.invalidateUserTokens(userId);
        log.info("用户修改密码并清除所有 token: userId={}", userId);
    }
}
