package com.example.agentdeepseek;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentDeepseekApplication.class, args);
    }

}
