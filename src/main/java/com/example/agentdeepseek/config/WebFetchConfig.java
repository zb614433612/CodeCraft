package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 网页读取工具代理配置
 */
@Configuration
@ConfigurationProperties(prefix = "web-fetch")
@Data
public class WebFetchConfig {

    private ProxyConfig proxy = new ProxyConfig();

    @Data
    public static class ProxyConfig {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
    }
}
