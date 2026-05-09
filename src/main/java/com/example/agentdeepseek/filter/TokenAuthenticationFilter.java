package com.example.agentdeepseek.filter;

import com.example.agentdeepseek.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Token认证过滤器
 * 验证用户token，放行登录和获取随机码接口
 */
@Component
@Order(1)
@Slf4j
public class TokenAuthenticationFilter implements Filter {

    private static final List<String> EXCLUDE_PATHS = Arrays.asList(
            "/api/user/random-code",
            "/api/user/login",
            "/swagger-ui",
            "/api-docs",
            "/v3/api-docs",
            "/api/kline-daily",
            "/api/skills"
    );


    @Autowired
    private UserService userService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestURI = httpRequest.getRequestURI();

        // 只拦截 /api/ 路径，其他请求（静态资源、SPA路由）直接放行
        if (!requestURI.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // 检查是否在排除路径中
        if (isExcludedPath(requestURI)) {
            chain.doFilter(request, response);
            return;
        }

        // 从请求头获取token
        String token = extractToken(httpRequest);
        if (token == null) {
            sendUnauthorizedError(httpResponse, "缺少访问令牌");
            return;
        }

        // 验证token
        Long userId = userService.validateToken(token);
        if (userId == null) {
            sendUnauthorizedError(httpResponse, "访问令牌无效或已过期");
            return;
        }

        // 将userId存储到请求属性中，供后续使用
        request.setAttribute("userId", userId);
        log.debug("Token验证通过: userId={}, uri={}", userId, requestURI);
        chain.doFilter(request, response);
    }

    private boolean isExcludedPath(String requestURI) {
        for (String pattern : EXCLUDE_PATHS) {
            if (requestURI.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private void sendUnauthorizedError(HttpServletResponse response, String message) throws IOException {
        log.warn("Token验证失败: {}", message);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String jsonResponse = String.format(
                "{\"code\":401,\"message\":\"%s\",\"data\":null,\"timestamp\":%d}",
                message, System.currentTimeMillis()
        );
        response.getWriter().write(jsonResponse);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("TokenAuthenticationFilter初始化");
    }

    @Override
    public void destroy() {
        log.info("TokenAuthenticationFilter销毁");
    }
}