package com.example.agentdeepseek;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
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
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(AgentDeepseekApplication.class)
                        .headless(false)
                        .run(args);

        /*
         * ── AppCDS 归档预热模式（--app.cds-warmup=true）────────────────────────────
         * 桌面端首次启动后，Electron 会用该参数再拉起一个「一次性」进程：
         * 它照常跑完整的启动流程（把 Spring / MyBatis / 工具链该加载的类都真实加载一遍），
         * 然后立即正常退出 —— JVM 在正常退出时会把「本次加载过的类」写成动态 CDS 归档
         * （由启动参数 -XX:ArchiveClassesAtExit 指定路径）。
         *
         * 下一次正式启动带上 -XX:SharedArchiveFile 就能直接内存映射复用它，
         * 跳过类解析与字节码校验。慢磁盘机器上这一段（类加载）占了启动时间的近一半。
         *
         * 安全前提：预热进程由调用方用**独立端口 + 内存库**启动（见 electron/main.js），
         * 不会碰用户的 H2 数据文件，也不会占用正式端口。
         */
        if (context.getEnvironment().getProperty("app.cds-warmup", Boolean.class, Boolean.FALSE)) {
            System.out.println("[cds-warmup] 预热启动完成，正常退出以生成类归档");
            /*
             * 用 System.exit(0) 而不是 System.exit(SpringApplication.exit(context, ...))：
             * 后者内部会先 context.close() 再返回退出码，等 JVM 再执行 Spring 注册的
             * shutdown hook 时会二次 close 已关闭的容器，抛 IllegalStateException
             * 并把退出码变成 1 —— 调用方（Electron）就会误判「归档生成失败」而每次重跑预热。
             * 直接 System.exit(0) 即可：JVM 会照常执行 shutdown hook（容器正常关闭、H2 正常
             * close），随后在 VM 退出阶段写出 CDS 归档。
             */
            System.exit(0);
        }
    }

}
