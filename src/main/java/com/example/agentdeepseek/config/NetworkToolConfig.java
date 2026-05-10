package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络工具统一配置
 * 涵盖 web_search / web_fetch / http_request / check_network 四个网络工具
 */
@Configuration
@ConfigurationProperties(prefix = "network-tool")
@Data
public class NetworkToolConfig {

    /** SearXNG 搜索引擎地址 */
    private String searxngUrl = "http://localhost:8888/search";

    /** 搜索结果最大条数 */
    private int searchMaxResults = 8;

    /** web_fetch 连接超时（毫秒） */
    private int fetchConnectTimeout = 5000;

    /** web_fetch 读取超时（毫秒） */
    private int fetchReadTimeout = 15000;

    /** web_fetch 内容最大长度（字符） */
    private int fetchMaxContentLength = 100_000;

    /** web_fetch 使用的 User-Agent */
    private String fetchUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** http_request 连接超时（毫秒） */
    private int httpRequestConnectTimeout = 10_000;

    /** http_request 读取超时（毫秒） */
    private int httpRequestReadTimeout = 30_000;

    /** http_request 响应体上限（字节） */
    private int httpRequestMaxResponseBytes = 50 * 1024;

    /** http_request 允许的 HTTP 方法白名单 */
    private List<String> httpRequestAllowedMethods = new ArrayList<>(
            List.of("GET", "POST", "PUT", "DELETE")
    );

    /** check_network 默认检测站点列表 */
    private List<String> networkCheckSites = new ArrayList<>(
            List.of(
                    "https://www.google.com",
                    "https://github.com",
                    "https://huggingface.co",
                    "https://api.deepseek.com"
            )
    );

    /** 连通性检测超时（毫秒） */
    private int connectivityCheckTimeout = 5000;

    /** HTTP 代理配置（可选，用于访问被墙网站） */
    private ProxyConfig proxy = new ProxyConfig();

    @Data
    public static class ProxyConfig {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
    }
}
