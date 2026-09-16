package com.example.agentdeepseek;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot应用程序主类
 * 启动整个应用程序
 */
@SpringBootApplication
@MapperScan("com.example.agentdeepseek.mapper")
@EnableScheduling
public class AgentDeepseekApplication {

    /**
     * 应用程序主入口方法
     *
     * <p><b>关闭 JVM headless 模式（桌面自动化前置条件）</b>：screen_capture / desktop_control
     * 依赖 AWT Robot，Spring Boot 默认 {@code spring.main.headless=true} 会把系统属性
     * {@code java.awt.headless} 强制设为 true，导致真实桌面环境下工具误报"无图形界面"。</p>
     *
     * <p><b>为什么不用 application.yml 配 {@code spring.main.headless: false}？</b>
     * Spring Boot 的 {@code SpringApplication#configureHeadlessProperty()} 在 run() 最开始执行
     * （早于配置文件加载与 spring.main 绑定），系统属性一旦写入不会再更新——yml 配置对系统属性无效。
     * 必须在此处编程式设置（{@code headless(false)} 使 configureHeadlessProperty 写入 false）。</p>
     *
     * <p>JVM 参数显式传 {@code -Djava.awt.headless=true} 时仍以外部参数为准（不覆盖已有系统属性）；
     * 无图形界面的服务器环境由工具内 {@code GraphicsEnvironment.isHeadless()} 运行时检测兜底。</p>
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        new SpringApplicationBuilder(AgentDeepseekApplication.class)
                .headless(false)
                .run(args);
    }

}
