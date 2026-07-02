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
                    "temperature DOUBLE DEFAULT 0.3, " +
                    "work_dir VARCHAR(500), " +
                    "sort_order INT DEFAULT 0, " +
                    "enabled TINYINT DEFAULT 1, " +
                    "is_default TINYINT DEFAULT 0, " +
                    "is_builtin TINYINT DEFAULT 0, " +
                    "provider_id BIGINT DEFAULT 1, " +
                    "provider_code VARCHAR(30), " +
                    "character_profile TEXT, " +
                    "user_id BIGINT, " +
                    "created_at DATETIME NOT NULL, " +
                    "updated_at DATETIME NOT NULL" +
                    ")");
            log.info("AgentConfigService: agent_config 表初始化完成");

            // 兼容旧表：为已存在的 agent_config 表添加 temperature 列（如缺失则忽略错误）
            try {
                jdbcTemplate.execute("ALTER TABLE agent_config ADD COLUMN temperature DOUBLE DEFAULT 0.3");
                log.info("AgentConfigService: temperature 列已添加（或已存在）");
            } catch (Exception e) {
                log.debug("AgentConfigService: temperature 列可能已存在，跳过: {}", e.getMessage());
            }

            // 兼容旧表：为已存在的 agent_config 表添加 provider_id 列
            try {
                jdbcTemplate.execute("ALTER TABLE agent_config ADD COLUMN provider_id BIGINT DEFAULT 1 COMMENT 'LLM Provider ID'");
                log.info("AgentConfigService: provider_id 列已添加（或已存在）");
            } catch (Exception e) {
                log.debug("AgentConfigService: provider_id 列可能已存在，跳过: {}", e.getMessage());
            }

            // 兼容旧表：为已存在的 agent_config 表添加 provider_code 列
            try {
                jdbcTemplate.execute("ALTER TABLE agent_config ADD COLUMN provider_code VARCHAR(30)");
                log.info("AgentConfigService: provider_code 列已添加（或已存在）");
            } catch (Exception e) {
                log.debug("AgentConfigService: provider_code 列可能已存在，跳过: {}", e.getMessage());
            }

            // 兼容旧表：为已存在的 agent_config 表添加 character_profile 列
            try {
                jdbcTemplate.execute("ALTER TABLE agent_config ADD COLUMN character_profile TEXT");
                log.info("AgentConfigService: character_profile 列已添加（或已存在）");
            } catch (Exception e) {
                log.debug("AgentConfigService: character_profile 列可能已存在，跳过: {}", e.getMessage());
            }

            // ===== 初始化 llm_provider 表（确保在 agent_config 之后创建，因为 agent_config 默认依赖它） =====
            initLLMProviderTable();

            // 初始化默认编码助手 Agent
            jdbcTemplate.update(
                    "INSERT IGNORE INTO agent_config (id, name, description, avatar, system_prompt, tool_names, model_name, thinking_mode, execution_mode, temperature, work_dir, sort_order, enabled, is_default, is_builtin, provider_id, provider_code, character_profile, created_at, updated_at) " +
                    "VALUES (1, 'AI 助手', '默认的AI编程助手，拥有全部工具', '🤖', NULL, NULL, 'deepseek-v4-flash', 'non-thinking', 'manual', 0.3, NULL, 1, 1, 1, 1, 1, NULL, NULL, NOW(), NOW())");
            log.info("AgentConfigService: 默认 Agent 初始化完成");
        } catch (Exception e) {
            log.warn("AgentConfigService: 初始化 agent_config 表失败: {}", e.getMessage());
        }
    }

    /**
     * 初始化 llm_provider 表（兼容 H2 和 MySQL）
     * <p>
     * 放在 AgentConfigService 而非 LLMClientManager 中，确保依赖链加载顺序正确。
     * LLMClientManager 中的 initTable() 仅做 fallback 兜底。
     * </p>
     */
    private void initLLMProviderTable() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS llm_provider (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "code VARCHAR(30) NOT NULL UNIQUE, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "base_url VARCHAR(300) NOT NULL, " +
                    "api_key VARCHAR(200), " +
                    "default_model VARCHAR(100), " +
                    "model_list TEXT, " +
                    "request_template VARCHAR(30) DEFAULT 'deepseek', " +
                    "is_default TINYINT DEFAULT 0, " +
                    "enabled TINYINT DEFAULT 1, " +
                    "sort_order INT DEFAULT 0, " +
                    "created_at DATETIME NOT NULL, " +
                    "updated_at DATETIME NOT NULL" +
                    ")");
            log.info("AgentConfigService: llm_provider 表初始化完成");
        } catch (Exception e) {
            log.warn("AgentConfigService: 初始化 llm_provider 表失败: {}", e.getMessage());
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
        if (agentConfig.getProviderId() == null) {
            agentConfig.setProviderId(1L);  // 默认 DeepSeek Provider
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
        // ★ 防御性修复：systemPrompt 为空字符串且数据库原值为 null 时不覆盖（保留 NULL = 使用内置默认提示词）
        if (updated.getSystemPrompt() != null) {
            if (updated.getSystemPrompt().trim().isEmpty() && existing.getSystemPrompt() == null) {
                // 保持数据库 NULL，不覆盖
            } else {
                existing.setSystemPrompt(updated.getSystemPrompt());
            }
        }
        // ★ 防御性修复：toolNames 为 "[]" 或空字符串且数据库原值为 null 时不覆盖（保留 NULL = 使用内置默认工具集）
        if (updated.getToolNames() != null) {
            if (("[]".equals(updated.getToolNames()) || updated.getToolNames().trim().isEmpty()) && existing.getToolNames() == null) {
                // 保持数据库 NULL，不覆盖
            } else {
                existing.setToolNames(updated.getToolNames());
            }
        }
        if (updated.getModelName() != null) existing.setModelName(updated.getModelName());
        if (updated.getThinkingMode() != null) existing.setThinkingMode(updated.getThinkingMode());
        if (updated.getExecutionMode() != null) existing.setExecutionMode(updated.getExecutionMode());
        if (updated.getTemperature() != null) existing.setTemperature(updated.getTemperature());
        if (updated.getWorkDir() != null) existing.setWorkDir(updated.getWorkDir());
        if (updated.getSortOrder() != null) existing.setSortOrder(updated.getSortOrder());
        if (updated.getEnabled() != null) existing.setEnabled(updated.getEnabled());
        if (updated.getIsDefault() != null) existing.setIsDefault(updated.getIsDefault());
        if (updated.getProviderId() != null) existing.setProviderId(updated.getProviderId());
        // ★ 修复：添加 characterProfile 更新（之前遗漏导致编辑性格后无法保存）
        if (updated.getCharacterProfile() != null) {
            existing.setCharacterProfile(updated.getCharacterProfile());
        }

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
        if (runtimeConfig.getTemperature() != null) existing.setTemperature(runtimeConfig.getTemperature());
        if (runtimeConfig.getWorkDir() != null) existing.setWorkDir(runtimeConfig.getWorkDir());
        if (runtimeConfig.getProviderId() != null) existing.setProviderId(runtimeConfig.getProviderId());
        if (runtimeConfig.getProviderCode() != null) existing.setProviderCode(runtimeConfig.getProviderCode());
        existing.setUpdatedAt(LocalDateTime.now());
        agentConfigMapper.update(existing);
        log.info("更新Agent运行时配置: {} (ID={})", existing.getName(), id);
        return existing;
    }
}
