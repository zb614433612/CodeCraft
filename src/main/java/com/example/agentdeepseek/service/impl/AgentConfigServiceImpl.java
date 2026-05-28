package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.AgentConfigMapper;
import com.example.agentdeepseek.model.entity.AgentConfig;
import com.example.agentdeepseek.service.AgentConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent配置服务实现
 */
@Slf4j
@Service
public class AgentConfigServiceImpl implements AgentConfigService {

    private final AgentConfigMapper agentConfigMapper;
    private final JdbcTemplate jdbcTemplate;

    public AgentConfigServiceImpl(AgentConfigMapper agentConfigMapper, JdbcTemplate jdbcTemplate) {
        this.agentConfigMapper = agentConfigMapper;
        this.jdbcTemplate = jdbcTemplate;
        // 确保 agent_config 表存在并初始化默认 Agent
        initTable();
    }

    private void initTable() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS agent_config (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "description VARCHAR(500), " +
                    "avatar VARCHAR(20) DEFAULT '🤖', " +
                    "system_prompt TEXT, " +
                    "tool_names TEXT, " +
                    "model_name VARCHAR(100) DEFAULT 'deepseek-v4-flash', " +
                    "thinking_mode VARCHAR(20) DEFAULT 'non-thinking', " +
                    "execution_mode VARCHAR(10) DEFAULT 'manual', " +
                    "work_dir VARCHAR(500), " +
                    "sort_order INT DEFAULT 0, " +
                    "enabled TINYINT DEFAULT 1, " +
                    "is_default TINYINT DEFAULT 0, " +
                    "is_builtin TINYINT DEFAULT 0, " +
                    "user_id BIGINT, " +
                    "created_at DATETIME NOT NULL, " +
                    "updated_at DATETIME NOT NULL" +
                    ")");
            log.info("AgentConfigService: agent_config 表初始化完成");

            // 初始化默认编码助手 Agent
            jdbcTemplate.update(
                    "INSERT IGNORE INTO agent_config (id, name, description, avatar, system_prompt, tool_names, model_name, thinking_mode, execution_mode, work_dir, sort_order, enabled, is_default, is_builtin, created_at, updated_at) " +
                    "VALUES (1, 'AI 助手', '默认的AI编程助手，拥有全部工具', '🤖', NULL, NULL, 'deepseek-v4-flash', 'non-thinking', 'manual', NULL, 1, 1, 1, 1, NOW(), NOW())");
            log.info("AgentConfigService: 默认 Agent 初始化完成");
        } catch (Exception e) {
            log.warn("AgentConfigService: 初始化 agent_config 表失败: {}", e.getMessage());
        }
    }

    @Override
    public List<AgentConfig> listByUser(Long userId) {
        List<AgentConfig> list = agentConfigMapper.selectByUser(userId);
        log.info("AgentConfigService.listByUser: userId={}, total={}", userId, list.size());
        for (AgentConfig a : list) {
            log.info("  Agent id={}, name={}, enabled={}, userId={}, isBuiltin={}",
                    a.getId(), a.getName(), a.getEnabled(), a.getUserId(), a.getIsBuiltin());
        }
        return list;
    }

    @Override
    @Transactional
    public AgentConfig create(AgentConfig agentConfig) {
        agentConfig.setCreatedAt(LocalDateTime.now());
        agentConfig.setUpdatedAt(LocalDateTime.now());
        if (agentConfig.getEnabled() == null) {
            agentConfig.setEnabled(1);
        }
        if (agentConfig.getIsDefault() == null) {
            agentConfig.setIsDefault(0);
        }
        if (agentConfig.getIsBuiltin() == null) {
            agentConfig.setIsBuiltin(0);
        }
        if (agentConfig.getSortOrder() == null) {
            agentConfig.setSortOrder(0);
        }
        if (agentConfig.getAvatar() == null || agentConfig.getAvatar().isEmpty()) {
            agentConfig.setAvatar("🤖");
        }
        agentConfigMapper.insert(agentConfig);
        log.info("用户 {} 创建Agent配置: {} (ID={})", agentConfig.getUserId(), agentConfig.getName(), agentConfig.getId());
        return agentConfig;
    }

    @Override
    @Transactional
    public AgentConfig update(Long id, AgentConfig updated) {
        AgentConfig existing = agentConfigMapper.selectById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent配置不存在: " + id));

        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getAvatar() != null) existing.setAvatar(updated.getAvatar());
        if (updated.getSystemPrompt() != null) existing.setSystemPrompt(updated.getSystemPrompt());
        if (updated.getToolNames() != null) existing.setToolNames(updated.getToolNames());
        if (updated.getModelName() != null) existing.setModelName(updated.getModelName());
        if (updated.getThinkingMode() != null) existing.setThinkingMode(updated.getThinkingMode());
        if (updated.getExecutionMode() != null) existing.setExecutionMode(updated.getExecutionMode());
        if (updated.getWorkDir() != null) existing.setWorkDir(updated.getWorkDir());
        if (updated.getSortOrder() != null) existing.setSortOrder(updated.getSortOrder());
        if (updated.getEnabled() != null) existing.setEnabled(updated.getEnabled());
        if (updated.getIsDefault() != null) existing.setIsDefault(updated.getIsDefault());

        existing.setUpdatedAt(LocalDateTime.now());
        agentConfigMapper.update(existing);
        log.info("更新Agent配置: {} (ID={})", existing.getName(), id);
        return existing;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        AgentConfig existing = agentConfigMapper.selectById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent配置不存在: " + id));
        if (existing.getIsBuiltin() != null && existing.getIsBuiltin() == 1) {
            throw new IllegalArgumentException("内置Agent不允许删除");
        }
        // 级联删除：子Agent日志 ← 会话消息 ← 会话
        jdbcTemplate.update("DELETE FROM sub_agent_log WHERE parent_conversation_id IN (SELECT id FROM conversation WHERE agent_config_id = ?)", id);
        jdbcTemplate.update("DELETE FROM conversation_message WHERE conversation_id IN (SELECT id FROM conversation WHERE agent_config_id = ?)", id);
        jdbcTemplate.update("DELETE FROM conversation WHERE agent_config_id = ?", id);
        // 级联删除关联的技能
        jdbcTemplate.update("DELETE FROM skill WHERE agent_config_id = ?", id);
        // 级联删除定时任务
        jdbcTemplate.update("DELETE FROM schedule_task WHERE agent_config_id = ?", id);
        // 最后删除 Agent 配置
        agentConfigMapper.delete(id);
        log.info("删除Agent配置及所有关联数据: {} (ID={})", existing.getName(), id);
    }

    @Override
    @Transactional
    public AgentConfig setDefault(Long id, Long userId) {
        AgentConfig target = agentConfigMapper.selectById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent配置不存在: " + id));

        // 取消该用户其他Agent的isDefault标记（同时取消系统级默认Agent）
        if (userId != null) {
            jdbcTemplate.update(
                    "UPDATE agent_config SET is_default = 0, updated_at = NOW() WHERE is_default = 1 AND (user_id = ? OR user_id IS NULL)",
                    userId);
        } else {
            jdbcTemplate.update(
                    "UPDATE agent_config SET is_default = 0, updated_at = NOW() WHERE is_default = 1");
        }

        // 设置目标Agent为默认
        target.setIsDefault(1);
        target.setUpdatedAt(LocalDateTime.now());
        agentConfigMapper.update(target);
        log.info("设置默认Agent: {} (ID={})", target.getName(), id);
        return target;
    }

    @Override
    @Transactional
    public AgentConfig updateRuntime(Long id, AgentConfig runtimeConfig) {
        AgentConfig existing = agentConfigMapper.selectById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent配置不存在: " + id));
        if (runtimeConfig.getModelName() != null) existing.setModelName(runtimeConfig.getModelName());
        if (runtimeConfig.getThinkingMode() != null) existing.setThinkingMode(runtimeConfig.getThinkingMode());
        if (runtimeConfig.getExecutionMode() != null) existing.setExecutionMode(runtimeConfig.getExecutionMode());
        if (runtimeConfig.getWorkDir() != null) existing.setWorkDir(runtimeConfig.getWorkDir());
        existing.setUpdatedAt(LocalDateTime.now());
        agentConfigMapper.update(existing);
        log.info("更新Agent运行时配置: {} (ID={})", existing.getName(), id);
        return existing;
    }
}
