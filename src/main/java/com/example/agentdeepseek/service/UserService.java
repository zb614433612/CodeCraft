package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.dto.LoginRequest;
import com.example.agentdeepseek.model.dto.LoginResponse;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 生成随机码并存储到Redis
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
     * 用户注销
     * @param token 令牌
     */
    void logout(String token);

    /**
     * 创建默认管理员用户（如果不存在）
     */
    void createDefaultAdminIfNotExists();
}