package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.AgentConfig;

import java.util.List;

/**
 * Agent配置服务接口
 */
public interface AgentConfigService {

    /**
     * 获取用户可见的Agent配置列表（包含用户自己的 + 系统级的）
     */
    List<AgentConfig> listByUser(Long userId);

    /**
     * 创建新的Agent配置
     */
    AgentConfig create(AgentConfig agentConfig);

    /**
     * 更新Agent配置
     */
    AgentConfig update(Long id, AgentConfig agentConfig);

    /**
     * 删除Agent配置
     */
    void delete(Long id);

    /**
     * 设置为默认Agent（取消其他Agent的isDefault）
     */
    AgentConfig setDefault(Long id, Long userId);

    /**
     * 更新 Agent 运行时配置（模型、思考模式、执行模式、工作目录）
     */
    AgentConfig updateRuntime(Long id, AgentConfig runtimeConfig);
}
