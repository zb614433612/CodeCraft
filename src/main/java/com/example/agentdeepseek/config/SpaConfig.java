package com.example.agentdeepseek.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 路由回退：前端路由（/ai-assistant、/chat-assistant 等）直接访问时返回 index.html，
 * 交由 Vue Router 处理客户端路由。
 * API 路径如 /api/** 由对应 Controller 处理，不受影响。
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 匹配前端 SPA 路由（不含 . 的路径，排除 api/、swagger- 等系统路径）
        registry.addViewController("/{path:[a-zA-Z0-9\\-]+}")
                .setViewName("forward:/index.html");
    }
}
