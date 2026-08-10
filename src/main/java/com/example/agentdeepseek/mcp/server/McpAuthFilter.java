package com.example.agentdeepseek.mcp.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * MCP Server 认证过滤器（可选）
 * 当配置了 codecraft.mcp.server.auth-token 时，所有访问 MCP 端点的请求
 * 必须携带 X-API-Key 请求头且值匹配，否则返回 401。
 * 未配置 token 时全部放行（本地内网场景）。
 */
@Slf4j
@Component
public class McpAuthFilter implements Filter {

    /** 认证请求头名称 */
    private static final String AUTH_HEADER = "X-API-Key";

    private final McpServerProperties props;

    public McpAuthFilter(McpServerProperties props) {
        this.props = props;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // 未配置 token → 不认证，直接放行
        String expectedToken = props.getAuthToken();
        if (expectedToken == null || expectedToken.isEmpty()) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        // 校验 X-API-Key
        String providedToken = request.getHeader(AUTH_HEADER);
        // 恒定时间比较（MessageDigest.isEqual），避免时序侧信道逐字符猜测 token
        byte[] expectedBytes = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedToken == null ? new byte[0] : providedToken.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expectedBytes, providedBytes)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        // 认证失败
        log.warn("MCP 认证失败: 来自 {} 的请求未通过 X-API-Key 校验", request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Unauthorized: invalid or missing X-API-Key header\"}");
    }

    /**
     * 注册过滤器，仅拦截 MCP 端点路径
     * ⚠️ 必须开启 asyncSupported：MCP Streamable HTTP 使用 SSE 异步流，链上过滤器不支持异步会报错
     */
    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration() {
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(this);
        registration.setName("mcpAuthFilter");
        registration.setUrlPatterns(Collections.singletonList(props.getPath()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        return registration;
    }
}
