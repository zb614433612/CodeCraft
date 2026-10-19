package com.example.agentdeepseek.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 路由回退：前端路由（/ai-assistant、/chat-assistant 等）直接访问时返回 index.html，
 * 交由 Vue Router 处理客户端路由。
 * API 路径如 /api/** 由对应 Controller 处理，不受影响。
 *
 * <h3>两套前端产物并存</h3>
 * <ul>
 *   <li><b>经典版（v2）</b>：挂在根路径 {@code /}，产物在 {@code classpath:/static/}
 *       （由 Spring Boot 默认静态资源映射处理）；</li>
 *   <li><b>新版（v3）</b>：挂在子路径 {@code /v3/}，产物在 {@code classpath:/v3-static/}
 *       （见下方 ResourceHandler）。</li>
 * </ul>
 * 两者共用同一个后端与同一份数据，用户通过 localStorage 的 {@code cc.uiVersion} 偏好选择进哪个。
 *
 * <h3>为什么 v3 不放在 static/v3/ 下</h3>
 * 经典版的构建（{@code frontend/vite.config}）把 outDir 指向 {@code src/main/resources/static}
 * 并开启了 emptyOutDir —— 每次构建都会清空整个 static 目录。曾经把 v3 产物放在
 * {@code static/v3/}，结果「刚构建完 v3，一打包又没了」。改到独立目录后彻底解耦。
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    /** v3 产物的 classpath 位置（与 static 分离，避免被经典版构建清空） */
    private static final String V3_RESOURCE_LOCATION = "classpath:/v3-static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        /*
         * /v3/** -> classpath:/v3-static/
         * 只负责真正的静态文件（index.html、assets/*.js、assets/*.css）。
         * 路由类请求（/v3、/v3/）由下面的 ViewController 处理 —— Spring 的
         * RequestMappingHandlerMapping（order 0）优先于 ResourceHandler
         * （order Integer.MAX_VALUE-1），不会被这条抢走。
         */
        registry.addResourceHandler("/v3/**").addResourceLocations(V3_RESOURCE_LOCATION);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        /*
         * 新版（v3）必须拆成两条规则，缺一不可：
         *
         *   /v3  -> 302 /v3/
         *     v3 的 Vite 配置是 `base: './'`（产物里写的是 ./assets/xxx.js）。
         *     若浏览器停在 /v3（无尾斜杠），相对路径会解析成 /assets/xxx.js —— 那是
         *     经典版的目录，结果是一堆 404 + 白屏。所以先 302 补上尾斜杠。
         *
         *   /v3/ -> forward:/v3/index.html
         *     v3 用 hash 路由（#/chat），服务器只需在任何 /v3/ 请求上返回入口 HTML。
         *
         * 注意：字面量 "/v3" 比下面的 "/{path}" 模板更精确，Spring 的
         * RequestMappingHandlerMapping 会优先匹配它，不会被兜底规则抢走。
         */
        registry.addRedirectViewController("/v3", "/v3/");
        registry.addViewController("/v3/").setViewName("forward:/v3/index.html");

        // 经典版（v2）：匹配前端 SPA 路由（不含 . 的路径，排除 api/、swagger- 等系统路径）
        registry.addViewController("/{path:[a-zA-Z0-9\\-]+}")
                .setViewName("forward:/index.html");
    }
}
