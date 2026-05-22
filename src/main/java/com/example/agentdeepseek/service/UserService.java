package com.example.agentdeepseek.service;

import com.example.agentdeepseek.common.response.PageResult;
import com.example.agentdeepseek.model.dto.LoginRequest;
import com.example.agentdeepseek.model.dto.LoginResponse;
import com.example.agentdeepseek.model.entity.User;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 生成随机码并存储到 Redis
     * @param username 用户名
     * @return 随机码
     */
    String generateRandomCode(String username);

    /**
     * 用户登录
     * @param request 登录请求
     * @return 登录响应
     */
    LoginResponse login(LoginRequest request);

    /**
     * 验证token是否有效
     * @param token 令牌
     * @return 用户ID，如果无效返回null
     */
    Long validateToken(String token);

    /**
     * 根据token获取用户名
     * @param token 令牌
     * @return 用户名，如果无效返回null
     */
    String getUsernameByToken(String token);

    /**
     * 根据token获取用户角色
     * @param token 令牌
     * @return 角色编码，如果无效返回null
     */
    String getRoleByToken(String token);

    /**
     * 用户注销
     * @param token 令牌
     */
    void logout(String token);

    /**
     * 创建默认管理员用户（如果不存在）
     */
    void createDefaultAdminIfNotExists();

    // ========== 用户管理接口 ==========

    /**
     * 分页查询用户列表
     * @param page 页码（从1开始）
     * @param pageSize 每页条数
     * @param search 搜索关键词（用户名，可选）
     * @return 分页结果
     */
    PageResult<User> listUsers(int page, int pageSize, String search);

    /**
     * 创建用户
     * @param user 用户实体
     * @return 创建后的用户（含ID）
     */
    User createUser(User user);

    /**
     * 更新用户
     * @param user 用户实体
     */
    void updateUser(User user);

    /**
     * 删除用户
     * @param id 用户ID
     */
    void deleteUser(Long id);

    /**
     * 根据ID获取用户
     * @param id 用户ID
     * @return 用户实体
     */
    User getUserById(Long id);

    /**
     * 获取当前登录用户的个人信息
     * @param userId 用户ID
     * @return 用户实体（不含密码）
     */
    User getCurrentUserProfile(Long userId);

    /**
     * 更新个人信息
     * @param userId 用户ID
     * @param user 包含更新信息的用户实体
     */
    void updateProfile(Long userId, User user);

    /**
     * 修改密码
     * @param userId 用户ID
     * @param oldPassword 旧密码（MD5加密后的）
     * @param newPassword 新密码（MD5加密后的）
     */
    void changePassword(Long userId, String oldPassword, String newPassword);
}
