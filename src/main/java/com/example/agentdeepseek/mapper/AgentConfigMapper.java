package com.example.agentdeepseek.mapper;

import com.example.agentdeepseek.model.entity.AgentConfig;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Agent配置数据访问接口
 */
@Mapper
@Repository
public interface AgentConfigMapper {

    @Select("SELECT id, name, description, avatar, system_prompt, tool_names, model_name, " +
            "thinking_mode, execution_mode, work_dir, sort_order, enabled, is_default, is_builtin, " +
            "user_id, created_at, updated_at FROM agent_config WHERE id = #{id}")
    @Results(id = "agentConfigResultMap", value = {
        @Result(property = "id", column = "id"),
        @Result(property = "name", column = "name"),
        @Result(property = "description", column = "description"),
        @Result(property = "avatar", column = "avatar"),
        @Result(property = "systemPrompt", column = "system_prompt"),
        @Result(property = "toolNames", column = "tool_names"),
        @Result(property = "modelName", column = "model_name"),
        @Result(property = "thinkingMode", column = "thinking_mode"),
        @Result(property = "executionMode", column = "execution_mode"),
        @Result(property = "workDir", column = "work_dir"),
        @Result(property = "sortOrder", column = "sort_order"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "isDefault", column = "is_default"),
        @Result(property = "isBuiltin", column = "is_builtin"),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<AgentConfig> selectById(Long id);

    @Select("SELECT id, name, description, avatar, system_prompt, tool_names, model_name, " +
            "thinking_mode, execution_mode, work_dir, sort_order, enabled, is_default, is_builtin, " +
            "user_id, created_at, updated_at FROM agent_config " +
            "WHERE (user_id = #{userId} OR user_id IS NULL OR #{userId} IS NULL) " +
            "AND enabled = 1 " +
            "ORDER BY is_builtin DESC, sort_order ASC, created_at DESC")
    @ResultMap("agentConfigResultMap")
    List<AgentConfig> selectByUser(@Param("userId") Long userId);

    @Select("SELECT id, name, description, avatar, system_prompt, tool_names, model_name, " +
            "thinking_mode, execution_mode, work_dir, sort_order, enabled, is_default, is_builtin, " +
            "user_id, created_at, updated_at FROM agent_config " +
            "WHERE user_id IS NULL AND enabled = 1 " +
            "ORDER BY sort_order ASC")
    @ResultMap("agentConfigResultMap")
    List<AgentConfig> selectAllSystem();

    @Insert("INSERT INTO agent_config (name, description, avatar, system_prompt, tool_names, model_name, " +
            "thinking_mode, execution_mode, work_dir, sort_order, enabled, is_default, is_builtin, " +
            "user_id, created_at, updated_at) " +
            "VALUES (#{name}, #{description}, #{avatar}, #{systemPrompt}, #{toolNames}, #{modelName}, " +
            "#{thinkingMode}, #{executionMode}, #{workDir}, #{sortOrder}, #{enabled}, #{isDefault}, #{isBuiltin}, " +
            "#{userId}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AgentConfig agentConfig);

    @Update("UPDATE agent_config SET name = #{name}, description = #{description}, avatar = #{avatar}, " +
            "system_prompt = #{systemPrompt}, tool_names = #{toolNames}, model_name = #{modelName}, " +
            "thinking_mode = #{thinkingMode}, execution_mode = #{executionMode}, work_dir = #{workDir}, " +
            "sort_order = #{sortOrder}, enabled = #{enabled}, is_default = #{isDefault}, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int update(AgentConfig agentConfig);

    @Delete("DELETE FROM agent_config WHERE id = #{id}")
    int delete(Long id);

    @Select("SELECT id, name, description, avatar, system_prompt, tool_names, model_name, " +
            "thinking_mode, execution_mode, work_dir, sort_order, enabled, is_default, is_builtin, " +
            "user_id, created_at, updated_at FROM agent_config " +
            "WHERE is_default = 1 AND (user_id = #{userId} OR user_id IS NULL) " +
            "ORDER BY is_builtin DESC, sort_order ASC LIMIT 1")
    @ResultMap("agentConfigResultMap")
    Optional<AgentConfig> selectDefaultByUser(@Param("userId") Long userId);
}
