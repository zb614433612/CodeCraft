package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.UserMapper;
import com.example.agentdeepseek.model.dto.LoginRequest;
import com.example.agentdeepseek.model.dto.LoginResponse;
import com.example.agentdeepseek.model.entity.User;
import com.example.agentdeepseek.service.UserService;
import com.example.agentdeepseek.util.Md5Util;
import com.example.agentdeepseek.util.RandomCodeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现
 */
@Service
@Slf4j
public class UserServiceImpl implements UserService {

    // Redis key前缀
    private static final String REDIS_KEY_RANDOM_CODE = "user:randomcode:";
    private static final String REDIS_KEY_TOKEN = "user:token:";

    // token过期时间（秒）
    private static final long TOKEN_EXPIRE_SECONDS = 7200; // 2小时

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public String generateRandomCode(String username) {
        // 生成4位随机码（字母数字）
        String randomCode = RandomCodeUtil.generate(4);
        String redisKey = REDIS_KEY_RANDOM_CODE + username;
        // 存储到Redis，1分钟过期
        redisTemplate.opsForValue().set(redisKey, randomCode, 1, TimeUnit.MINUTES);
        log.info("生成随机码: username={}, randomCode={}", username, randomCode);
        return randomCode;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();
        String password = request.getPassword(); // 前端二次加密后的密码：MD5(前端MD5(明文密码) + 随机码)

        // 1. 从Redis获取随机码
        String redisKey = REDIS_KEY_RANDOM_CODE + username;
        String storedRandomCode = redisTemplate.opsForValue().get(redisKey);
        if (storedRandomCode == null) {
            throw new RuntimeException("随机码无效或已过期");
        }
        // 使用后删除随机码
        redisTemplate.delete(redisKey);

        // 2. 查询用户
        User user = userMapper.selectByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 3. 验证密码
        // 数据库存储的是 MD5(明文密码)
        // 前端传来的password是 MD5(前端MD5(明文密码) + 随机码)
        // 验证：将数据库密码与随机码拼接后MD5加密，与前端传来的password比较
        String expectedPassword = Md5Util.md5(user.getPassword() + storedRandomCode);
        if (!expectedPassword.equals(password)) {
            throw new RuntimeException("密码错误");
        }

        // 4. 生成token
        String token = UUID.randomUUID().toString().replace("-", "");
        String tokenKey = REDIS_KEY_TOKEN + token;

        // 存储用户信息到Redis Hash，设置过期时间
        Map<String, String> userInfo = new HashMap<>();
        userInfo.put("id", user.getId().toString());
        userInfo.put("username", user.getUsername());
        userInfo.put("nickname", user.getNickname() != null ? user.getNickname() : "");
        redisTemplate.opsForHash().putAll(tokenKey, userInfo);
        redisTemplate.expire(tokenKey, TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);

        log.info("用户登录成功: username={}, userId={}", username, user.getId());

        // 5. 返回响应
        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setToken(token);
        response.setExpire(TOKEN_EXPIRE_SECONDS);
        return response;
    }

    @Override
    public Long validateToken(String token) {
        String tokenKey = REDIS_KEY_TOKEN + token;
        // 检查key是否存在
        Boolean exists = redisTemplate.hasKey(tokenKey);
        if (exists == null || !exists) {
            return null;
        }
        // 从Hash中获取用户ID
        Object userIdObj = redisTemplate.opsForHash().get(tokenKey, "id");
        if (userIdObj == null) {
            return null;
        }
        String userIdStr = userIdObj.toString();
        // 刷新token过期时间
        redisTemplate.expire(tokenKey, TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return Long.parseLong(userIdStr);
    }

    @Override
    public void logout(String token) {
        String tokenKey = REDIS_KEY_TOKEN + token;
        redisTemplate.delete(tokenKey);
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
            admin.setCreateTime(LocalDateTime.now());
            admin.setUpdateTime(LocalDateTime.now());
            userMapper.insert(admin);
            log.info("创建默认管理员用户: admin");
        }
    }
}