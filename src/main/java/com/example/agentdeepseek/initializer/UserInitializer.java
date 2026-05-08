package com.example.agentdeepseek.initializer;

import com.example.agentdeepseek.mapper.UserMapper;
import com.example.agentdeepseek.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 用户数据初始化
 */
@Component
@Slf4j
public class UserInitializer implements CommandLineRunner {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserService userService;

    @Override
    public void run(String... args) {
        try {
            // 创建用户表（如果不存在）
            userMapper.createTable();
            log.info("用户表初始化完成");

            // 创建默认管理员用户（如果不存在）
            userService.createDefaultAdminIfNotExists();
            log.info("默认用户初始化完成");
        } catch (Exception e) {
            log.error("用户初始化失败", e);
        }
    }
}