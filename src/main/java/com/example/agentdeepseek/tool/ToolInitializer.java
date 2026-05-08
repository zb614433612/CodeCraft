package com.example.agentdeepseek.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具初始化器
 * 在应用启动时自动注册所有工具
 */
@Slf4j
@Component
public class ToolInitializer implements ApplicationRunner {

    private final ToolRegistry toolRegistry;
    private final List<Tool> tools;

    public ToolInitializer(ToolRegistry toolRegistry, List<Tool> tools) {
        this.toolRegistry = toolRegistry;
        this.tools = tools;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始注册工具，共发现 {} 个工具", tools.size());

        for (Tool tool : tools) {
            toolRegistry.register(tool);
            log.info("注册工具: {} - {}", tool.getName(), tool.getDescription());
        }

        log.info("工具注册完成，当前已注册 {} 个工具", toolRegistry.size());
    }
}